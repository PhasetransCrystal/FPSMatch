package net.ptcrys.fpsmatch.editor.common.ui.persistence;

import net.ptcrys.fpsmatch.editor.common.ui.model.WorkspaceState;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;

/**
 * Synchronous, bounded storage of one caller-selected environment scope. The caller owns project
 * identity, background scheduling and serialization of writes. No corrupt or unsupported original
 * is repaired or overwritten. Atomic replacement is mandatory, without a copy fallback.
 *
 * Content/identity checks detect observed external changes, including missing-to-created files.
 * Ordinary filesystem APIs cannot make the final compare and rename one conditional operation:
 * an uncooperative process racing precisely between them is outside this optimistic contract.
 */
public final class WorkspaceLayoutStore {

    public enum Status {
        MISSING,
        LOADED,
        PROTECTED
    }

    public enum Reason {
        INVALID_FORMAT,
        UNSUPPORTED_VERSION,
        LIMIT_EXCEEDED,
        INVALID_PATH,
        CHANGED,
        PROTECTED,
        TICKET_MISMATCH,
        IO,
        ATOMIC_UNSUPPORTED
    }

    public record Issue(Reason reason, String detail) {

        public Issue {
            Objects.requireNonNull(reason);
            Objects.requireNonNull(detail);
        }
    }

    public static final class StorageException extends IOException {

        private static final long serialVersionUID = 1L;
        private final Reason reason;

        private StorageException(Reason reason, String detail, Throwable cause) {
            super(detail, cause);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /** Opaque observation; only this store can issue one, and it is bound to the exact target path. */
    public static final class Revision {

        private final Path path;
        private final Fingerprint fingerprint;
        private final boolean writable;

        private Revision(Path path, Fingerprint fingerprint, boolean writable) {
            this.path = path;
            this.fingerprint = fingerprint;
            this.writable = writable;
        }
    }

    /** Private construction prevents callers from relabeling a protected revision as writable. */
    public static final class LoadResult {

        private final Status status;
        private final WorkspaceState state;
        private final Revision revision;
        private final Issue issue;

        private LoadResult(Status status, WorkspaceState state, Revision revision, Issue issue) {
            this.status = status;
            this.state = state;
            this.revision = revision;
            this.issue = issue;
        }

        public Status status() {
            return status;
        }

        public Optional<WorkspaceState> state() {
            return Optional.ofNullable(state);
        }

        public Revision revision() {
            return revision;
        }

        public Optional<Issue> issue() {
            return Optional.ofNullable(issue);
        }
    }

    private record Fingerprint(boolean exists, Object fileKey, long size, FileTime modified, String sha256) {}

    private record Observation(byte[] bytes, Fingerprint fingerprint) {}

    private static final Fingerprint MISSING = new Fingerprint(false, null, 0, null, "");
    private final Path directory, file;
    private final CommitIO io;

    /** Narrow I/O seam for deterministic failure and external-change tests. */
    static class CommitIO {

        void staged(Path temporary, Path target) throws IOException {}

        void replace(Path temporary, Path target) throws IOException {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public WorkspaceLayoutStore(Path directory, String scopeHash) {
        this(directory, scopeHash, new CommitIO());
    }

    WorkspaceLayoutStore(Path directory, String scopeHash, CommitIO io) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (scopeHash == null || !scopeHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("scopeHash must be a lowercase SHA-256 hex value");
        this.file = this.directory.resolve(scopeHash + ".json");
        this.io = Objects.requireNonNull(io, "io");
    }

    public LoadResult load() {
        Fingerprint observed = null;
        try {
            var input = observe();
            observed = input.fingerprint();
            if (!observed.exists()) return result(Status.MISSING, null, observed, null);
            return result(Status.LOADED, WorkspaceStateCodec.decode(input.bytes()), observed, null);
        } catch (WorkspaceStateCodec.CodecException failure) {
            return result(Status.PROTECTED, null, observed, new Issue(codecReason(failure), failure.getMessage()));
        } catch (StorageException failure) {
            return result(Status.PROTECTED, null, observed, new Issue(failure.reason(), failure.getMessage()));
        } catch (IOException | SecurityException failure) {
            return result(Status.PROTECTED, null, observed, new Issue(Reason.IO, "Cannot read workspace layout"));
        }
    }

    /**
     * Encodes before touching disk, checks the prior observation twice, then stages and atomically
     * replaces exactly this target. A failed post-commit read reports failure rather than claiming
     * a confirmed save; retrying must begin with load(), never a fabricated writable baseline.
     */
    public LoadResult save(WorkspaceState candidate, LoadResult prior) throws StorageException {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(prior, "prior");
        var expected = prior.revision();
        if (!file.equals(expected.path)) throw failure(Reason.TICKET_MISMATCH, "Workspace revision belongs to another scope", null);
        if (!expected.writable) throw failure(Reason.PROTECTED, "Workspace layout is protected; reload after resolving the original", null);
        final byte[] bytes;
        try {
            bytes = WorkspaceStateCodec.encode(candidate);
        } catch (WorkspaceStateCodec.CodecException problem) {
            throw failure(codecReason(problem), problem.getMessage(), problem);
        }
        Path temporary = null;
        try {
            verify(expected);
            Files.createDirectories(directory);
            requireDirectory();
            temporary = Files.createTempFile(directory, ".workspace-", ".tmp");
            try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            io.staged(temporary, file);
            verify(expected);
            io.replace(temporary, file);
            var written = observe();
            if (!written.fingerprint().exists() || !Arrays.equals(bytes, written.bytes())) throw failure(Reason.CHANGED, "Workspace layout changed after replacement; reload required", null);
            return result(Status.LOADED, candidate, written.fingerprint(), null);
        } catch (StorageException problem) {
            throw problem;
        } catch (AtomicMoveNotSupportedException problem) {
            throw failure(Reason.ATOMIC_UNSUPPORTED, "Atomic workspace replacement is unavailable", problem);
        } catch (IOException | SecurityException problem) {
            throw failure(Reason.IO, "Cannot save workspace layout", problem);
        } finally {
            if (temporary != null) try {
                Files.deleteIfExists(temporary);
            } catch (IOException | SecurityException ignored) {/*
                                                                * A private staging file may remain; the original is
                                                                * never deleted here.
                                                                */}
        }
    }

    private LoadResult result(Status status, WorkspaceState state, Fingerprint fingerprint, Issue issue) {
        return new LoadResult(status, state, new Revision(file, fingerprint, status != Status.PROTECTED), issue);
    }

    private void verify(Revision expected) throws IOException {
        final Observation current;
        try {
            current = observe();
        } catch (StorageException problem) {
            throw failure(Reason.CHANGED, "Workspace layout no longer matches the read revision", problem);
        }
        if (!Objects.equals(expected.fingerprint, current.fingerprint())) throw failure(Reason.CHANGED, "Workspace layout changed externally; reload required", null);
    }

    private Observation observe() throws IOException {
        if (!requireDirectory()) return new Observation(null, MISSING);
        final BasicFileAttributes before;
        try {
            before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return new Observation(null, MISSING);
        }
        if (!before.isRegularFile() || before.isSymbolicLink()) throw failure(Reason.INVALID_PATH, "Workspace layout must be a regular file", null);
        if (before.size() > WorkspaceStateCodec.MAX_BYTES) throw failure(Reason.LIMIT_EXCEEDED, "Workspace layout exceeds byte budget", null);
        final byte[] bytes;
        try (var input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(WorkspaceStateCodec.MAX_BYTES + 1);
        }
        if (bytes.length > WorkspaceStateCodec.MAX_BYTES) throw failure(Reason.LIMIT_EXCEEDED, "Workspace layout exceeds byte budget", null);
        var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || after.isSymbolicLink() || !same(before, after) || after.size() != bytes.length) throw failure(Reason.CHANGED, "Workspace layout changed while being read", null);
        return new Observation(bytes, new Fingerprint(true, after.fileKey(), after.size(), after.lastModifiedTime(), digest(bytes)));
    }

    /** A missing directory is a missing layout, not a reason to create anything during load. */
    private boolean requireDirectory() throws IOException {
        final BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return false;
        }
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) throw failure(Reason.INVALID_PATH, "Workspace layout directory is not a regular directory", null);
        return true;
    }

    private static boolean same(BasicFileAttributes a, BasicFileAttributes b) {
        return Objects.equals(a.fileKey(), b.fileKey()) && a.size() == b.size() && a.lastModifiedTime().equals(b.lastModifiedTime());
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static Reason codecReason(WorkspaceStateCodec.CodecException problem) {
        return switch (problem.reason()) {
            case INVALID_FORMAT -> Reason.INVALID_FORMAT;
            case UNSUPPORTED_VERSION -> Reason.UNSUPPORTED_VERSION;
            case LIMIT_EXCEEDED -> Reason.LIMIT_EXCEEDED;
        };
    }

    private static StorageException failure(Reason reason, String detail, Throwable cause) {
        return new StorageException(reason, detail, cause);
    }
}
