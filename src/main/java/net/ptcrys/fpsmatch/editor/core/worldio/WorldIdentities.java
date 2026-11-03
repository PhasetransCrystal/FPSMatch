package net.ptcrys.fpsmatch.editor.core.worldio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentityException.Code;

/** Explicit, bounded local world identity I/O. This grants no world permissions. */
public final class WorldIdentities {

    private static final byte[] MAGIC = "EDWORLD1".getBytes(StandardCharsets.US_ASCII);
    private static final LinkOption[] NOFOLLOW = { LinkOption.NOFOLLOW_LINKS };
    private static final BooleanSupplier NEVER_CANCELLED = () -> false;

    private WorldIdentities() {}

    public static WorldIdentitySnapshot read(Path root, BooleanSupplier cancelled)
                                                                                   throws WorldIdentityException {
        return read(root, FileIdentityProviders.jdk(), cancelled);
    }

    public static WorldIdentitySnapshot read(Path root, FileIdentityProvider identities, BooleanSupplier cancelled)
                                                                                                                    throws WorldIdentityException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(identities, "identities");
        Objects.requireNonNull(cancelled, "cancelled");
        try {
            return capture(root.toAbsolutePath().normalize(), identities, cancelled);
        } catch (SupplierFailure e) {
            throw e.unwrap();
        } catch (WorldIdentityException e) {
            throw e;
        } catch (UnsupportedOperationException e) {
            throw failure(Code.UNSUPPORTED, "Filesystem capability unavailable", e);
        } catch (IOException | SecurityException e) {
            throw failure(Code.IO_FAILURE, "Cannot read world identity", e);
        }
    }

    public static void verify(WorldIdentitySnapshot captured, BooleanSupplier cancelled)
                                                                                         throws WorldIdentityException {
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(cancelled, "cancelled");
        final WorldIdentitySnapshot current;
        try {
            current = read(captured.root(), captured.identities, cancelled);
        } catch (WorldIdentityException e) {
            switch (e.code()) {
                case MISSING, CORRUPT, INVALID_ROOT, CHANGED -> throw failure(Code.CHANGED, "Captured world identity changed", e);
                default -> throw e;
            }
        }
        if (!sameCapture(captured, current)) {
            throw failure(Code.CHANGED, "Captured world identity changed");
        }
    }

    public static WorldIdentitySnapshot initialize(Path root, BooleanSupplier cancelled)
                                                                                         throws WorldIdentityException {
        return initialize(root, FileIdentityProviders.jdk(), cancelled);
    }

    public static WorldIdentitySnapshot initialize(Path root, FileIdentityProvider identities, BooleanSupplier cancelled)
                                                                                                                          throws WorldIdentityException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(identities, "identities");
        Objects.requireNonNull(cancelled, "cancelled");
        Path temporary = null;
        Stamp temporaryStamp = null;
        boolean published = false;
        try {
            Path absolute = root.toAbsolutePath().normalize();
            Stamp rootStamp = root(absolute, identities, cancelled);
            Path directory = absolute.resolve(".editor-platform");
            cancel(cancelled);
            try {
                Files.createDirectory(directory);
            } catch (FileAlreadyExistsException e) { /* Authenticate the concurrent directory below. */ }
            Stamp directoryStamp = directory(directory, Code.CORRUPT, identities, cancelled);
            recheckRoot(absolute, rootStamp, identities, cancelled);
            Path identity = directory.resolve("world.identity");
            cancel(cancelled);
            try {
                Files.readAttributes(identity, BasicFileAttributes.class, NOFOLLOW);
                throw failure(Code.ALREADY_EXISTS, "World identity already exists");
            } catch (NoSuchFileException e) { /* Explicit first registration only. */ }
            UUID id;
            do {
                id = UUID.randomUUID();
            } while (id.getMostSignificantBits() == 0 && id.getLeastSignificantBits() == 0);
            byte[] bytes = ByteBuffer.allocate(24).put(MAGIC)
                    .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
            cancel(cancelled);
            temporary = directory.resolve(".world.identity-" + UUID.randomUUID() + ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                // Capture ownership before invoking caller code, so even a throwing
                // cancellation supplier cannot strand the newly created link.
                temporaryStamp = regular(temporary, identities, NEVER_CANCELLED);
                regular(temporary, identities, cancelled);
                ByteBuffer data = ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) {
                    cancel(cancelled);
                    channel.write(data);
                }
                cancel(cancelled);
                channel.force(true);
            }
            cancel(cancelled);
            recheckRoot(absolute, rootStamp, identities, cancelled);
            recheckDirectory(directory, directoryStamp, identities, cancelled);
            Record written = record(temporary, identities, cancelled);
            sameDirectory(temporaryStamp, written.stamp());
            if (!Arrays.equals(bytes, written.bytes())) throw failure(Code.CHANGED, "Temporary identity changed");
            cancel(cancelled);
            // The supplier is arbitrary caller code. Authenticate again after its
            // final invocation and do not invoke it between this check and publish.
            recheckRoot(absolute, rootStamp, identities, NEVER_CANCELLED);
            recheckDirectory(directory, directoryStamp, identities, NEVER_CANCELLED);
            Record finalWritten = record(temporary, identities, NEVER_CANCELLED);
            if (!written.stamp().equals(finalWritten.stamp()) || !Arrays.equals(bytes, finalWritten.bytes())) {
                throw failure(Code.CHANGED, "Temporary identity changed before publication");
            }
            Files.createLink(identity, temporary);
            published = true;
            // Cancellation must never be queried after the publication point.
            removeTemporary(temporary, temporaryStamp, identities);
            temporary = null;
            WorldIdentitySnapshot result = capture(absolute, identities, NEVER_CANCELLED);
            sameDirectory(rootStamp, result.rootStamp);
            sameDirectory(directoryStamp, result.directoryStamp);
            if (!id.equals(result.worldId()) || !Arrays.equals(bytes, result.bytes()) || !written.stamp().equals(result.fileStamp)) {
                throw failure(Code.CHANGED, "Published identity authentication failed");
            }
            return result;
        } catch (IOException | RuntimeException e) {
            if (temporary != null && temporaryStamp != null) {
                try {
                    removeTemporary(temporary, temporaryStamp, identities);
                } catch (IOException | RuntimeException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            if (published) throw failure(Code.UNKNOWN, "World identity may have been published; read to reconcile", e);
            if (e instanceof SupplierFailure supplierFailure) throw supplierFailure.unwrap();
            if (e instanceof WorldIdentityException identityFailure) throw identityFailure;
            if (e instanceof FileAlreadyExistsException) throw failure(Code.ALREADY_EXISTS, "World identity already exists", e);
            if (e instanceof UnsupportedOperationException) throw failure(Code.UNSUPPORTED, "Filesystem capability unavailable", e);
            if (e instanceof IOException || e instanceof SecurityException) throw failure(Code.IO_FAILURE, "Cannot initialize world identity", e);
            throw (RuntimeException) e;
        }
    }

    private static WorldIdentitySnapshot capture(Path root, FileIdentityProvider identities, BooleanSupplier cancelled) throws IOException {
        Stamp rootStamp = root(root, identities, cancelled);
        Path directory = root.resolve(".editor-platform");
        Stamp directoryStamp;
        Record record;
        try {
            directoryStamp = directory(directory, Code.CORRUPT, identities, cancelled);
            record = record(directory.resolve("world.identity"), identities, cancelled);
        } catch (NoSuchFileException e) {
            throw failure(Code.MISSING, "World identity is missing", e);
        }
        cancel(cancelled);
        recheckRoot(root, rootStamp, identities, NEVER_CANCELLED);
        recheckDirectory(directory, directoryStamp, identities, NEVER_CANCELLED);
        Record finalRecord;
        try {
            finalRecord = record(directory.resolve("world.identity"), identities, NEVER_CANCELLED);
        } catch (NoSuchFileException e) {
            throw failure(Code.CHANGED, "Identity disappeared before capture", e);
        } catch (WorldIdentityException e) {
            if (e.code() == Code.CORRUPT) throw failure(Code.CHANGED, "Identity changed before capture", e);
            throw e;
        }
        if (!record.stamp().equals(finalRecord.stamp()) || !Arrays.equals(record.bytes(), finalRecord.bytes())) {
            throw failure(Code.CHANGED, "Identity changed before capture");
        }
        return new WorldIdentitySnapshot(record.id(), root, identities, rootStamp, directoryStamp,
                record.stamp(), record.bytes());
    }

    private static Record record(Path file, FileIdentityProvider identities, BooleanSupplier cancelled) throws IOException {
        Stamp before = regular(file, identities, cancelled);
        ByteBuffer buffer = ByteBuffer.allocate(25);
        cancel(cancelled);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (buffer.hasRemaining()) {
                cancel(cancelled);
                if (channel.read(buffer) < 0) break;
            }
        }
        Stamp after;
        try {
            after = regular(file, identities, cancelled);
        } catch (NoSuchFileException e) {
            throw failure(Code.CHANGED, "Identity file disappeared while reading", e);
        } catch (WorldIdentityException e) {
            if (e.code() == Code.CORRUPT) throw failure(Code.CHANGED, "Identity file changed while reading", e);
            throw e;
        }
        if (!before.equals(after)) throw failure(Code.CHANGED, "Identity file changed while reading");
        if (buffer.position() != 24 || after.size() != 24) throw failure(Code.CORRUPT, "Invalid world identity length");
        byte[] bytes = Arrays.copyOf(buffer.array(), 24);
        if (!Arrays.equals(MAGIC, Arrays.copyOf(bytes, 8))) throw failure(Code.CORRUPT, "Unknown world identity format");
        ByteBuffer data = ByteBuffer.wrap(bytes);
        UUID id = new UUID(data.getLong(8), data.getLong(16));
        if (id.getMostSignificantBits() == 0 && id.getLeastSignificantBits() == 0) throw failure(Code.CORRUPT, "Zero world identity");
        return new Record(id, after, bytes);
    }

    private static Stamp root(Path root, FileIdentityProvider identities, BooleanSupplier cancelled) throws IOException {
        if (root.getNameCount() > 256) throw failure(Code.INVALID_ROOT, "Root ancestor budget exceeded");
        try {
            Path part = root.getRoot();
            if (part == null) throw failure(Code.INVALID_ROOT, "Root is not absolute");
            Stamp result = directory(part, Code.INVALID_ROOT, identities, cancelled);
            for (Path segment : root) {
                part = part.resolve(segment);
                result = directory(part, Code.INVALID_ROOT, identities, cancelled);
            }
            return result;
        } catch (NoSuchFileException e) {
            throw failure(Code.INVALID_ROOT, "World root must already exist", e);
        }
    }

    private static Stamp directory(Path path, Code invalid, FileIdentityProvider identities, BooleanSupplier cancelled) throws IOException {
        BasicFileAttributes attrs = attributes(path, invalid, cancelled);
        if (!attrs.isDirectory()) throw failure(invalid, "Expected a directory");
        return stamp(path, attrs, identities);
    }

    private static Stamp regular(Path path, FileIdentityProvider identities, BooleanSupplier cancelled) throws IOException {
        BasicFileAttributes attrs = attributes(path, Code.CORRUPT, cancelled);
        if (!attrs.isRegularFile()) throw failure(Code.CORRUPT, "Expected a regular identity file");
        return stamp(path, attrs, identities);
    }

    private static BasicFileAttributes attributes(Path path, Code invalid, BooleanSupplier cancelled) throws IOException {
        cancel(cancelled);
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
        if (attrs.isSymbolicLink() || attrs.isOther()) throw failure(invalid, "Unsupported filesystem node");
        if (Files.getFileAttributeView(path, DosFileAttributeView.class, NOFOLLOW) != null && Boolean.TRUE.equals(Files.readAttributes(path, "dos:*", NOFOLLOW).get("reparsePoint"))) {
            throw failure(invalid, "Reparse node is not allowed");
        }
        return attrs;
    }

    private static Stamp stamp(Path path, BasicFileAttributes attrs, FileIdentityProvider identities) throws IOException {
        FileNodeIdentity key = Objects.requireNonNull(identities.identity(path), "Provider returned null identity");
        return new Stamp(key, attrs.size(), attrs.lastModifiedTime(), attrs.creationTime());
    }

    private static void sameDirectory(Stamp before, Stamp after) throws WorldIdentityException {
        // Creating/removing our temporary link legitimately changes directory metadata.
        if (!before.key().equals(after.key())) throw failure(Code.CHANGED, "Filesystem identity changed");
    }

    private static void recheckRoot(Path path, Stamp expected, FileIdentityProvider identities, BooleanSupplier cancelled) throws IOException {
        try {
            sameDirectory(expected, root(path, identities, cancelled));
        } catch (WorldIdentityException e) {
            if (e.code() == Code.INVALID_ROOT) throw failure(Code.CHANGED, "World root changed", e);
            throw e;
        }
    }

    private static void recheckDirectory(Path path, Stamp expected, FileIdentityProvider identities, BooleanSupplier cancelled) throws IOException {
        try {
            sameDirectory(expected, directory(path, Code.CHANGED, identities, cancelled));
        } catch (NoSuchFileException e) {
            throw failure(Code.CHANGED, "Identity directory disappeared", e);
        }
    }

    private static boolean sameCapture(WorldIdentitySnapshot first, WorldIdentitySnapshot second) {
        return first.worldId().equals(second.worldId()) && first.rootStamp.key().equals(second.rootStamp.key()) && first.directoryStamp.key().equals(second.directoryStamp.key()) && first.fileStamp.equals(second.fileStamp) && Arrays.equals(first.bytes(), second.bytes());
    }

    private static void removeTemporary(Path path, Stamp owned, FileIdentityProvider identities) throws IOException {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
            if (!attrs.isRegularFile() || owned.key() == null || !owned.key().equals(Objects.requireNonNull(identities.identity(path), "Provider returned null identity"))) {
                throw failure(Code.CHANGED, "Temporary link is no longer owned");
            }
            Files.delete(path);
        } catch (NoSuchFileException e) { /* Our temporary link is already gone. */ }
    }

    private static void cancel(BooleanSupplier cancelled) throws WorldIdentityException {
        final boolean value;
        try {
            value = cancelled.getAsBoolean();
        } catch (RuntimeException e) {
            throw new SupplierFailure(e);
        }
        if (value) throw failure(Code.CANCELLED, "Operation cancelled");
    }

    private static WorldIdentityException failure(Code code, String message) {
        return new WorldIdentityException(code, message);
    }

    private static WorldIdentityException failure(Code code, String message, Throwable cause) {
        return new WorldIdentityException(code, message, cause);
    }

    static record Stamp(FileNodeIdentity key, long size, FileTime modified, FileTime created) {}

    private record Record(UUID id, Stamp stamp, byte[] bytes) {}

    private static final class SupplierFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final RuntimeException original;

        RuntimeException unwrap() {
            for (Throwable suppressed : getSuppressed()) {
                if (suppressed != original) original.addSuppressed(suppressed);
            }
            return original;
        }

        SupplierFailure(RuntimeException original) {
            super(original);
            this.original = original;
        }
    }
}
