package net.ptcrys.fpsmatch.editor.core.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException.Code.*;

/**
 * Blocking local I/O service: invoke from an application's I/O executor, never its UI thread.
 * One instance owns the project's OS writer lock until closed. No world or runtime side effects.
 */
public final class LocalProjectStore implements AutoCloseable {

    public enum RequestStatus {
        PUBLISHED,
        UNKNOWN
    }

    /** UNKNOWN includes expired receipts and requests that were never published. */
    public record RequestResult(RequestStatus status, SavedProject saved, UUID currentCommitId) {}

    public record CommitResult(SavedProject saved, UUID currentCommitId, boolean replayed) {}

    enum Stage {
        MANIFEST_READY,
        BEFORE_PUBLISH,
        AFTER_PUBLISH,
        BEFORE_EXPORT_PUBLISH
    }

    @FunctionalInterface
    interface Checkpoint {

        void reached(Stage stage) throws IOException;
    }

    private static final Checkpoint NO_CHECKPOINT = stage -> {};
    private static final LinkOption[] NO_LINKS = { LinkOption.NOFOLLOW_LINKS };
    private final Path root;
    private final StoreLimits limits;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Checkpoint checkpoint;
    /** Identity token of the snapshot capabilities this instance issues; never exposed or serialized. */
    private final Object issuer = new Object();
    private String observedHeadHash;
    private boolean closed;

    /** Creates only a new directory; an existing directory is never overwritten or adopted. */
    public static LocalProjectStore create(Path directory) throws IOException {
        return create(directory, StoreLimits.DEFAULT);
    }

    public static LocalProjectStore create(Path directory, StoreLimits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        Path path = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        Files.createDirectory(path);
        return new LocalProjectStore(path, limits, true, NO_CHECKPOINT);
    }

    public static LocalProjectStore open(Path directory) throws IOException {
        return open(directory, StoreLimits.DEFAULT);
    }

    public static LocalProjectStore open(Path directory, StoreLimits limits) throws IOException {
        return new LocalProjectStore(directory, limits, false, NO_CHECKPOINT);
    }

    LocalProjectStore(Path directory, StoreLimits limits, boolean initialize, Checkpoint checkpoint)
                                                                                                     throws IOException {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        Path candidate = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (!Files.isDirectory(candidate, NO_LINKS)) {
            throw failure(INVALID_PATH, "project directory is missing or a symbolic link");
        }
        root = candidate.toRealPath();
        for (String name : List.of("commits", "documents", "assets", ".pending")) {
            Path child = root.resolve(name);
            if (initialize) {
                Files.createDirectory(child);
            }
            safeDirectory(child);
        }
        Path lockPath = root.resolve(".editor.lock");
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired = null;
        try {
            try {
                acquired = channel.tryLock();
            } catch (OverlappingFileLockException failure) {
                throw new ProjectStorageException(BUSY, "project already has a local writer", failure);
            }
            if (acquired == null) {
                throw failure(BUSY, "project already has a local writer");
            }
            lockChannel = channel;
            lock = acquired;
            if (!initialize) {
                byte[] bytes = headBytes();
                if (bytes == null) {
                    throw failure(MISSING_PROJECT, "project has no published CURRENT");
                }
                observedHeadHash = digest(bytes);
                loadHead(bytes, true);
            }
        } catch (IOException | RuntimeException failure) {
            if (acquired != null) {
                try {
                    acquired.release();
                } catch (IOException releaseFailure) {
                    failure.addSuppressed(releaseFailure);
                }
            }
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public synchronized BlobRef stage(BlobRef.Kind kind, byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        return stage(kind, new ByteArrayInputStream(bytes));
    }

    /** Consumes but does not close the caller's stream. Staged content is not a saved project. */
    public synchronized BlobRef stage(BlobRef.Kind kind, InputStream input) throws IOException {
        return stageContent(kind, input, null);
    }

    synchronized BlobRef stageExpected(BlobRef expected, InputStream input) throws IOException {
        return stageContent(expected.kind(), input, expected);
    }

    private BlobRef stageContent(BlobRef.Kind kind, InputStream input, BlobRef expected) throws IOException {
        ensureOpen();
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(input, "input");
        Path temp = temporary();
        try {
            MessageDigest hash = sha256();
            long size;
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                long maximum = expected == null ? limits.maxBlobBytes() : Math.min(expected.size(), limits.maxBlobBytes());
                size = transfer(input, Channels.newOutputStream(channel), maximum, hash);
                channel.force(true);
            }
            BlobRef ref = new BlobRef(kind, HexFormat.of().formatHex(hash.digest()), size);
            if (expected != null && !ref.equals(expected)) {
                throw failure(CORRUPT_CONTENT, "share content does not match its declared reference");
            }
            Path target = blobPath(ref);
            if (Files.exists(target, NO_LINKS)) {
                verifyBlob(ref, OutputStream.nullOutputStream());
            } else {
                publish(temp, target);
            }
            return ref;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** A failed transfer may have written partial bytes to the caller's destination. */
    public synchronized void copyContent(BlobRef reference, OutputStream destination) throws IOException {
        ensureOpen();
        verifyBlob(Objects.requireNonNull(reference), Objects.requireNonNull(destination));
    }

    /** Fails on an external CURRENT change rather than silently accepting a new editing baseline. */
    public synchronized SavedProject load() throws IOException {
        ensureOpen();
        byte[] bytes = checkedHeadBytes();
        if (bytes == null) {
            throw failure(MISSING_PROJECT, "project has no published CURRENT");
        }
        return loadHead(bytes, true);
    }

    /** Rechecks the captured entry without rereading large immutable content; grants no content validation. */
    public synchronized void verifyCurrent() throws IOException {
        ensureOpen();
        checkedHeadBytes();
    }

    /**
     * Reads exactly the saved version this live instance captured, even after it publishes a later
     * version. Re-verifies the certified manifest and every referenced byte; missing or damaged old
     * content is refused without falling back to CURRENT. External entry changes are checked before
     * and after verification. Holding a capability does not retain its stored content.
     */
    public synchronized SavedProject load(ProjectSnapshot captured) throws IOException {
        ensureOpen();
        Objects.requireNonNull(captured, "captured");
        if (!captured.issuedBy(issuer)) {
            throw failure(REQUEST_MISMATCH, "snapshot capability was not issued by this project store");
        }
        SavedProject project = certifiedSnapshot(captured);
        checkedHeadBytes();
        return project;
    }

    /**
     * Publishes one complete state. expectedCommitId is null only for the first commit.
     * Request identity and candidate bytes must remain unchanged across a retry.
     */
    public synchronized CommitResult commit(UUID expectedCommitId, UUID requestId, ProjectState state)
                                                                                                       throws IOException {
        ensureOpen();
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(state, "state");
        if (requestId.equals(expectedCommitId)) {
            throw failure(REQUEST_MISMATCH, "a request cannot use itself as its original saved baseline");
        }
        validateState(state);
        SavedProject candidate = new SavedProject(requestId, expectedCommitId, state);
        byte[] manifest = StoreJson.manifest(candidate, limits.maxManifestBytes());
        // A larger configured byte/count budget must never publish metadata our reader cannot open.
        StoreJson.readManifest(manifest, limits);
        String manifestHash = digest(manifest);
        byte[] oldHeadBytes = checkedHeadBytes();
        StoreJson.Head oldHead = oldHeadBytes == null ? null : StoreJson.readHead(oldHeadBytes, limits);
        if (oldHead != null) {
            if (!oldHead.projectId().equals(state.projectId())) {
                throw failure(CONFLICT, "project identity changed");
            }
            for (StoreJson.Receipt receipt : oldHead.receipts()) {
                if (receipt.requestId().equals(requestId)) {
                    if (!receipt.manifestHash().equals(manifestHash)) {
                        throw failure(REQUEST_MISMATCH, "request identity reused for different content");
                    }
                    SavedProject saved = readManifest(requestId, manifestHash);
                    if (!saved.state().projectId().equals(oldHead.projectId())) {
                        throw failure(CORRUPT_CONTENT, "receipt belongs to a different project");
                    }
                    verifyState(saved.state());
                    return new CommitResult(saved, oldHead.commitId(), true);
                }
            }
        }
        UUID currentId = oldHead == null ? null : oldHead.commitId();
        if (!Objects.equals(currentId, expectedCommitId)) {
            throw failure(CONFLICT, "saved baseline no longer matches expected commit");
        }
        verifyState(state);
        Path manifestPath = manifestPath(requestId);
        if (Files.exists(manifestPath, NO_LINKS)) {
            if (!digest(readBounded(manifestPath, limits.maxManifestBytes())).equals(manifestHash)) {
                throw failure(REQUEST_MISMATCH, "candidate path already belongs to a different request");
            }
        } else {
            writeImmutable(manifestPath, manifest);
        }
        checkpoint.reached(Stage.MANIFEST_READY);
        List<StoreJson.Receipt> receipts = oldHead == null ? new ArrayList<>() : new ArrayList<>(oldHead.receipts());
        receipts.add(new StoreJson.Receipt(requestId, manifestHash));
        while (receipts.size() > limits.retainedReceipts()) {
            receipts.remove(0);
        }
        byte[] nextHead = StoreJson.head(new StoreJson.Head(state.projectId(), requestId, manifestHash, receipts));
        // Re-check after potentially slow content verification; no internal callback can change the baseline.
        checkpoint.reached(Stage.BEFORE_PUBLISH);
        checkedHeadBytes();
        boolean published = false;
        try {
            replaceHead(nextHead);
            published = true;
            checkpoint.reached(Stage.AFTER_PUBLISH);
            observedHeadHash = digest(nextHead);
            return new CommitResult(candidate, requestId, false);
        } catch (IOException failure) {
            if (published || failure instanceof PublicationUncertainException) {
                throw new ProjectStorageException(RESULT_UNKNOWN,
                        "query the original request before retrying: " + requestId, failure);
            }
            throw failure;
        }
    }

    /** Reads durable receipts even after an uncertain publication; never changes the editing baseline. */
    public synchronized RequestResult lookup(UUID requestId) throws IOException {
        ensureOpen();
        Objects.requireNonNull(requestId, "requestId");
        byte[] bytes = headBytes();
        if (bytes == null) {
            return new RequestResult(RequestStatus.UNKNOWN, null, null);
        }
        StoreJson.Head head = StoreJson.readHead(bytes, limits);
        loadHead(bytes, true);
        for (StoreJson.Receipt receipt : head.receipts()) {
            if (receipt.requestId().equals(requestId)) {
                SavedProject saved = readManifest(requestId, receipt.manifestHash());
                if (!saved.state().projectId().equals(head.projectId())) {
                    throw failure(CORRUPT_CONTENT, "receipt belongs to a different project");
                }
                verifyState(saved.state());
                return new RequestResult(RequestStatus.PUBLISHED, saved, head.commitId());
            }
        }
        return new RequestResult(RequestStatus.UNKNOWN, null, head.commitId());
    }

    /**
     * Exports the snapshot published right now. The destination must be outside this project and must
     * not exist. Equivalent to {@code exportSnapshot(archive, captureSnapshot())}: the same fixed
     * verification and publication boundary apply.
     */
    public synchronized void exportSnapshot(Path archive) throws IOException {
        exportSnapshot(archive, captureSnapshot());
    }

    /**
     * Issues a capability for the currently published snapshot. The published entry, its manifest and
     * every referenced byte are verified before this returns, so an unpublished project cannot be
     * captured. The capability stays bound to this live instance; see
     * {@link #exportSnapshot(Path, ProjectSnapshot)}. Blocking and CPU-bound, like {@link #load()}.
     */
    public synchronized ProjectSnapshot captureSnapshot() throws IOException {
        ensureOpen();
        byte[] bytes = checkedHeadBytes();
        if (bytes == null) {
            throw failure(MISSING_PROJECT, "project has no published CURRENT");
        }
        StoreJson.Head head = StoreJson.readHead(bytes, limits);
        return new ProjectSnapshot(issuer, loadHead(head, true), head.manifestHash());
    }

    /**
     * Exports exactly the fixed snapshot a capability certified, never whatever is published later.
     * Only a capability this live instance issued is accepted; a capability whose issuer was closed, a
     * capability from another instance (even one reopened at the same path) and any object this store
     * did not create are refused. The captured manifest is re-read by its certified identity and
     * digest, fully compared with the capability and verified together with all referenced bytes.
     * An entry changed outside this editing session is reported as {@code CONFLICT} both before that
     * read and after the complete temporary archive exists, and the share is then not published.
     */
    public synchronized void exportSnapshot(Path archive, ProjectSnapshot captured) throws IOException {
        ensureOpen();
        Objects.requireNonNull(captured, "captured");
        if (!captured.issuedBy(issuer)) {
            throw failure(REQUEST_MISMATCH, "snapshot capability was not issued by this project store");
        }
        ProjectArchive.exportSnapshot(this, archive, certifiedSnapshot(captured));
    }

    /**
     * Re-reads the manifest version a capability certified and proves it is still that exact version.
     * The current entry may have moved on to a later snapshot of this session; it is never substituted.
     */
    private SavedProject certifiedSnapshot(ProjectSnapshot captured) throws IOException {
        checkedHeadBytes();
        SavedProject project = readManifest(captured.saved().commitId(), captured.manifestHash());
        if (!project.equals(captured.saved())) {
            throw failure(CORRUPT_CONTENT, "certified manifest no longer matches the captured snapshot");
        }
        verifyState(project.state());
        return project;
    }

    /** Deterministic fault-injection seam for the last moment before a share becomes visible. */
    void beforeExportPublish() throws IOException {
        checkpoint.reached(Stage.BEFORE_EXPORT_PUBLISH);
    }

    /** Final publication gate: a share is never published from an entry changed by another process. */
    void recheckBeforeExportPublish() throws IOException {
        checkedHeadBytes();
    }

    /** Imports to a newly created directory, preserving stable content identities and recording origin. */
    public static SavedProject importSnapshot(Path archive, Path newDirectory, StoreLimits limits)
                                                                                                   throws IOException {
        return ProjectArchive.importSnapshot(archive, newDirectory, limits);
    }

    public static SavedProject importSnapshot(Path archive, Path newDirectory) throws IOException {
        return importSnapshot(archive, newDirectory, StoreLimits.DEFAULT);
    }

    private SavedProject loadHead(byte[] bytes, boolean verify) throws IOException {
        return loadHead(StoreJson.readHead(bytes, limits), verify);
    }

    private SavedProject loadHead(StoreJson.Head head, boolean verify) throws IOException {
        SavedProject project = readManifest(head.commitId(), head.manifestHash());
        if (!head.projectId().equals(project.state().projectId())) {
            throw failure(CORRUPT_CONTENT, "CURRENT and manifest project identities differ");
        }
        validateState(project.state());
        if (verify) {
            verifyState(project.state());
        }
        return project;
    }

    private SavedProject readManifest(UUID id, String expectedHash) throws IOException {
        byte[] bytes = readBounded(manifestPath(id), limits.maxManifestBytes());
        if (!digest(bytes).equals(expectedHash)) {
            throw failure(CORRUPT_CONTENT, "manifest content does not match CURRENT or receipt");
        }
        SavedProject project = StoreJson.readManifest(bytes, limits);
        if (!project.commitId().equals(id)) {
            throw failure(CORRUPT_CONTENT, "manifest commit identity differs from its filename");
        }
        return project;
    }

    void validateState(ProjectState state) throws IOException {
        validateState(state, limits);
    }

    static void validateState(ProjectState state, StoreLimits limits) throws IOException {
        if ((long) state.documents().size() + state.assets().size() > limits.maxEntries() || state.modules().size() > limits.maxEntries()) {
            throw failure(LIMIT_EXCEEDED, "project entry count exceeds budget");
        }
        long bytes = 0;
        long dependencies = 0;
        Set<BlobRef> counted = new HashSet<>();
        java.util.Map<String, BlobRef> paths = new java.util.HashMap<>();
        for (ProjectState.Entry entry : allEntries(state)) {
            BlobRef prior = paths.putIfAbsent(entry.content().relativePath(), entry.content());
            if (prior != null && !prior.equals(entry.content())) {
                throw failure(INVALID_FORMAT, "same blob path has inconsistent metadata");
            }
            if (!state.modules().containsKey(entry.ownerModule())) {
                throw failure(INVALID_FORMAT, "entry owner is absent from module requirements");
            }
            if (!state.documents().keySet().containsAll(entry.documentDependencies()) || !state.assets().keySet().containsAll(entry.assetDependencies())) {
                throw failure(INVALID_FORMAT, "project snapshot has unresolved dependencies");
            }
            dependencies += (long) entry.documentDependencies().size() + entry.assetDependencies().size();
            if (dependencies > limits.maxDependencies() || entry.content().size() > limits.maxBlobBytes()) {
                throw failure(LIMIT_EXCEEDED, "project reference or content budget exceeded");
            }
            if (counted.add(entry.content())) {
                if (entry.content().size() > limits.maxProjectBytes() - bytes) {
                    throw failure(LIMIT_EXCEEDED, "project byte budget exceeded");
                }
                bytes += entry.content().size();
            }
        }
    }

    private void verifyState(ProjectState state) throws IOException {
        validateState(state);
        for (BlobRef ref : contentReferences(state)) {
            verifyBlob(ref, OutputStream.nullOutputStream());
        }
    }

    static Set<BlobRef> contentReferences(ProjectState state) {
        Set<BlobRef> refs = new LinkedHashSet<>();
        for (ProjectState.Entry entry : allEntries(state)) {
            refs.add(entry.content());
        }
        return refs;
    }

    private static List<ProjectState.Entry> allEntries(ProjectState state) {
        List<ProjectState.Entry> entries = new ArrayList<>(state.documents().values());
        entries.addAll(state.assets().values());
        return entries;
    }

    private void verifyBlob(BlobRef ref, OutputStream destination) throws IOException {
        if (ref.size() > limits.maxBlobBytes()) {
            throw failure(LIMIT_EXCEEDED, "content exceeds blob budget");
        }
        Path path = blobPath(ref);
        safeFile(path);
        if (Files.size(path) != ref.size()) {
            throw failure(CORRUPT_CONTENT, "blob length does not match its reference");
        }
        MessageDigest hash = sha256();
        long size;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            size = transfer(input, destination, Math.min(ref.size(), limits.maxBlobBytes()), hash);
        }
        if (size != ref.size() || !HexFormat.of().formatHex(hash.digest()).equals(ref.sha256())) {
            throw failure(CORRUPT_CONTENT, "blob content does not match its reference");
        }
    }

    private Path blobPath(BlobRef ref) throws IOException {
        safeDirectory(root.resolve(ref.kind().directory()));
        return root.resolve(ref.relativePath());
    }

    private Path manifestPath(UUID id) throws IOException {
        safeDirectory(root.resolve("commits"));
        return root.resolve("commits").resolve(id + ".json");
    }

    private Path temporary() throws IOException {
        safeDirectory(root.resolve(".pending"));
        return root.resolve(".pending").resolve(UUID.randomUUID() + ".tmp");
    }

    private byte[] checkedHeadBytes() throws IOException {
        byte[] bytes = headBytes();
        String actual = bytes == null ? null : digest(bytes);
        if (!Objects.equals(observedHeadHash, actual)) {
            throw failure(CONFLICT, "CURRENT changed outside this editing session; reopen or preserve a copy");
        }
        return bytes;
    }

    private byte[] headBytes() throws IOException {
        Path path = root.resolve("CURRENT");
        if (!Files.exists(path, NO_LINKS)) {
            return null;
        }
        return readBounded(path, StoreJson.HEAD_LIMIT);
    }

    private void writeImmutable(Path target, byte[] bytes) throws IOException {
        Path temp = temporary();
        try {
            writeForced(temp, bytes);
            publish(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void replaceHead(byte[] bytes) throws IOException {
        // CURRENT's temporary file is in the same directory and file system as its publication target.
        Path temp = root.resolve(".CURRENT-" + UUID.randomUUID() + ".tmp");
        try {
            writeForced(temp, bytes);
        } catch (IOException failure) {
            removeTemporary(temp, failure);
            throw failure;
        }
        try {
            Files.move(temp, root.resolve("CURRENT"), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException failure) {
            removeTemporary(temp, failure);
            throw new ProjectStorageException(ATOMIC_MOVE_UNSUPPORTED, "atomic publication unavailable", failure);
        } catch (IOException failure) {
            removeTemporary(temp, failure);
            throw new PublicationUncertainException(failure);
        }
    }

    private static void removeTemporary(Path path, IOException original) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanup) {
            original.addSuppressed(cleanup);
        }
    }

    static void publish(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException failure) {
            throw new ProjectStorageException(ATOMIC_MOVE_UNSUPPORTED, "atomic publication unavailable", failure);
        }
    }

    static void writeForced(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    static byte[] readBounded(Path path, int maximum) throws IOException {
        safeFile(path);
        if (Files.size(path) > maximum) {
            throw failure(LIMIT_EXCEEDED, "metadata file exceeds byte budget");
        }
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transfer(input, output, maximum, sha256());
            return output.toByteArray();
        }
    }

    static long transfer(InputStream input, OutputStream output, long maximum, MessageDigest digest)
                                                                                                     throws IOException {
        byte[] buffer = new byte[65536];
        long total = 0;
        while (true) {
            int count = input.read(buffer);
            if (count < 0) {
                return total;
            }
            if (count == 0) {
                int single = input.read();
                if (single < 0) {
                    return total;
                }
                buffer[0] = (byte) single;
                count = 1;
            }
            if (count > maximum - total) {
                throw failure(LIMIT_EXCEEDED, "stream exceeds byte budget");
            }
            output.write(buffer, 0, count);
            digest.update(buffer, 0, count);
            total += count;
        }
    }

    private void safeDirectory(Path path) throws IOException {
        BasicFileAttributes attributes;
        Path real;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, NO_LINKS);
        } catch (IOException failure) {
            throw new ProjectStorageException(INVALID_PATH, "Cannot inspect managed directory: root=" + root + ", path=" + path, failure);
        }
        if (!attributes.isDirectory())
            throw failure(INVALID_PATH, "Managed path is not a directory or is a symbolic link: root=" + root + ", path=" + path);
        try {
            real = path.toRealPath();
        } catch (IOException failure) {
            throw new ProjectStorageException(INVALID_PATH, "Cannot resolve managed directory: root=" + root + ", path=" + path, failure);
        }
        if (!real.startsWith(root))
            throw failure(INVALID_PATH, "Managed directory is outside project: root=" + root + ", path=" + path + ", real=" + real);
    }

    private static void safeFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, NO_LINKS)) {
            throw failure(INVALID_PATH, "managed file is missing, non-regular or symbolic");
        }
    }

    static String digest(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    Path root() {
        return root;
    }

    StoreLimits limits() {
        return limits;
    }

    private void ensureOpen() throws IOException {
        if (closed || !lock.isValid()) {
            throw failure(CLOSED, "project writer is closed");
        }
    }

    private static ProjectStorageException failure(ProjectStorageException.Code code, String diagnostic) {
        return new ProjectStorageException(code, diagnostic);
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            try {
                lock.release();
            } finally {
                lockChannel.close();
            }
        }
    }

    private static final class PublicationUncertainException extends IOException {

        private static final long serialVersionUID = 1L;

        private PublicationUncertainException(IOException cause) {
            super(cause);
        }
    }
}
