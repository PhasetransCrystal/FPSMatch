package net.ptcrys.fpsmatch.editor.core.packio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

final class PackIo {

    static final long LIMIT = 512L * 1024 * 1024;
    static final long META_LIMIT = 4L * 1024 * 1024;
    static final int COUNT_LIMIT = 8192;
    static final int NODE_LIMIT = 16384;
    static final int PATH_LIMIT = 1024;
    static final int BUFFER = 64 * 1024;
    static final byte[] MAGIC = "EDPACK01".getBytes(StandardCharsets.US_ASCII);
    static final LinkOption[] NOFOLLOW = { LinkOption.NOFOLLOW_LINKS };

    private PackIo() {}

    static PackSnapshotException fail(PackSnapshotException.Code code, String text) {
        return new PackSnapshotException(code, text);
    }

    static void cancel(BooleanSupplier cancelled) throws IOException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw fail(PackSnapshotException.Code.CANCELLED, "Operation cancelled");
        }
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    static void validateHash(String hash) {
        if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid SHA-256");
    }

    static byte[] pathBytes(String path) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(path));
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8 path", e);
        }
    }

    static String decodePath(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new PackSnapshotException(PackSnapshotException.Code.INVALID_PATH, "Invalid UTF-8 path", e);
        }
    }

    static void validatePath(String path) {
        if (path.isEmpty() || pathBytes(path).length > PATH_LIMIT) throw new IllegalArgumentException("Invalid path length");
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (Character.isISOControl(c) || "\\<>:\"|?*".indexOf(c) >= 0) {
                throw new IllegalArgumentException("Invalid path character");
            }
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.endsWith(" ") || segment.endsWith(".")) {
                throw new IllegalArgumentException("Invalid path segment");
            }
            String base = segment.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (base.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]|CONIN\\$|CONOUT\\$")) {
                throw new IllegalArgumentException("Reserved path segment");
            }
        }
    }

    static void inputPath(String path) throws IOException {
        try {
            validatePath(path);
        } catch (IllegalArgumentException e) {
            throw new PackSnapshotException(PackSnapshotException.Code.INVALID_PATH, "Invalid entry path", e);
        }
    }

    static void addPath(Set<String> names, String path) throws IOException {
        String folded = path.toLowerCase(Locale.ROOT);
        if (!names.add(folded)) throw fail(PackSnapshotException.Code.DUPLICATE_PATH, "Duplicate entry path");
    }

    static void prefixes(Set<String> names) throws IOException {
        for (String name : names) {
            for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1)) {
                if (names.contains(name.substring(0, slash))) {
                    throw fail(PackSnapshotException.Code.DUPLICATE_PATH, "File path prefix conflict");
                }
            }
        }
    }

    static void validateEntries(List<PackEntry> entries, long contentBytes) {
        if (entries.isEmpty() || entries.size() > COUNT_LIMIT || contentBytes < 0 || contentBytes > LIMIT) {
            throw new IllegalArgumentException("Invalid index budget");
        }
        Set<String> names = new HashSet<>();
        long total = 0, metadata = 24;
        String previous = null;
        for (PackEntry entry : entries) {
            Objects.requireNonNull(entry, "entry");
            if (previous != null && previous.compareTo(entry.path()) >= 0) throw new IllegalArgumentException("Unsorted index");
            previous = entry.path();
            total += entry.size();
            metadata += 44L + pathBytes(entry.path()).length;
            try {
                addPath(names, entry.path());
            } catch (IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }
        try {
            prefixes(names);
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        if (total != contentBytes || metadata > META_LIMIT || metadata + total > LIMIT) {
            throw new IllegalArgumentException("Invalid aggregate size");
        }
    }

    static BasicFileAttributes attributes(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
        if (attrs.isSymbolicLink() || attrs.isOther()) throw fail(PackSnapshotException.Code.INVALID_SOURCE, "Unsupported filesystem node");
        // Providers may expose a reparse marker in their DOS attribute map.
        if (Files.getFileAttributeView(path, java.nio.file.attribute.DosFileAttributeView.class, NOFOLLOW) != null) {
            Object marker = Files.readAttributes(path, "dos:*", NOFOLLOW).get("reparsePoint");
            if (Boolean.TRUE.equals(marker)) throw fail(PackSnapshotException.Code.INVALID_SOURCE, "Reparse node");
        }
        return attrs;
    }

    static void ancestors(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path part = absolute.getRoot();
        if (part != null && !attributes(part).isDirectory()) throw fail(PackSnapshotException.Code.INVALID_SOURCE, "Invalid root");
        for (Path segment : absolute) {
            part = part == null ? segment : part.resolve(segment);
            if (!attributes(part).isDirectory()) throw fail(PackSnapshotException.Code.INVALID_SOURCE, "Invalid ancestor");
        }
    }

    static boolean same(BasicFileAttributes a, BasicFileAttributes b) {
        return Objects.equals(a.fileKey(), b.fileKey()) && a.size() == b.size() && a.lastModifiedTime().equals(b.lastModifiedTime()) && a.isRegularFile() == b.isRegularFile() && a.isDirectory() == b.isDirectory();
    }
}
