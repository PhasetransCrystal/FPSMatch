package net.ptcrys.fpsmatch.editor.core.packio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Owns only its read channel; closing never deletes the archive. */
public final class PackSnapshot implements AutoCloseable {

    private static final int STREAM_LIMIT = 64;

    record Location(PackEntry entry, long offset) {}

    private final PackIndex index;
    private final FileChannel channel;
    private final Map<String, Location> locations;
    private boolean closed;
    private int openStreams;
    private boolean checkingCancellation;

    PackSnapshot(PackIndex index, FileChannel channel, Map<String, Location> locations) {
        this.index = index;
        this.channel = channel;
        this.locations = Map.copyOf(locations);
    }

    public PackIndex index() {
        return index;
    }

    /**
     * Opens an independently positioned stream on this snapshot's fixed channel.
     * Complete consumption verifies the entry digest again; earlier reads may
     * already have exposed bytes if the archive was modified externally. Publish
     * derived results only after successful complete consumption. Closing early
     * does not authenticate the unread tail. The caller must close even an EOF or
     * failed stream to release its slot; closing this snapshot invalidates it.
     */
    public synchronized InputStream openEntry(String path, BooleanSupplier cancelled) throws IOException {
        requireOutsideCancellation();
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(cancelled, "cancelled");
        if (closed) throw new IOException("Snapshot is closed");
        Location location = locations.get(path);
        if (location == null) throw new IllegalArgumentException("Unknown entry");
        checkCancellation(cancelled);
        if (closed) throw new IOException("Snapshot is closed");
        if (openStreams == STREAM_LIMIT) {
            throw PackIo.fail(PackSnapshotException.Code.BUDGET, "Too many open entry streams");
        }
        EntryStream stream = new EntryStream(location, cancelled);
        openStreams++;
        return stream;
    }

    private void requireOutsideCancellation() throws IOException {
        if (checkingCancellation) throw new IOException("Cannot read or open during a cancellation callback");
    }

    /** Called only while holding the parent monitor. Close remains reentrant. */
    private void checkCancellation(BooleanSupplier cancelled) throws IOException {
        checkingCancellation = true;
        try {
            PackIo.cancel(cancelled);
        } finally {
            checkingCancellation = false;
        }
    }

    /** All mutable stream state is guarded solely by the parent monitor. */
    private final class EntryStream extends InputStream {

        private final Location location;
        private final BooleanSupplier cancelled;
        private final MessageDigest digest = PackIo.digest();
        private final byte[] singleByte = new byte[1];
        private long position;
        private boolean streamClosed;
        private boolean verified;
        private Throwable failure;

        private EntryStream(Location location, BooleanSupplier cancelled) {
            this.location = location;
            this.cancelled = cancelled;
        }

        private void requireOpen() throws IOException {
            if (closed) throw new IOException("Snapshot is closed");
            if (streamClosed) throw new IOException("Entry stream is closed");
        }

        private void pollCancellation() throws IOException {
            checkCancellation(cancelled);
            requireOpen();
        }

        @Override
        public int read() throws IOException {
            synchronized (PackSnapshot.this) {
                int count = read(singleByte, 0, 1);
                return count < 0 ? -1 : singleByte[0] & 0xff;
            }
        }

        @Override
        public int read(byte[] bytes) throws IOException {
            synchronized (PackSnapshot.this) {
                requireOutsideCancellation();
                return read(bytes, 0, bytes.length);
            }
        }

        @Override
        public int read(byte[] bytes, int off, int len) throws IOException {
            synchronized (PackSnapshot.this) {
                requireOutsideCancellation();
                Objects.checkFromIndexSize(off, len, bytes.length);
                requireOpen();
                if (len == 0) return 0;
                if (failure != null) throw new IOException("Entry stream has failed", failure);
                try {
                    pollCancellation();
                    if (verified) return -1;
                    long remaining = location.entry().size() - position;
                    if (remaining == 0) {
                        verifyDigest();
                        return -1;
                    }
                    int limit = (int) Math.min(Math.min((long) len, remaining), PackIo.BUFFER);
                    int count;
                    do {
                        pollCancellation();
                        count = channel.read(ByteBuffer.wrap(bytes, off, limit), location.offset() + position);
                        if (count < 0) {
                            throw PackIo.fail(PackSnapshotException.Code.CONTENT_MISMATCH, "Entry was truncated");
                        }
                    } while (count == 0);
                    digest.update(bytes, off, count);
                    position += count;
                    pollCancellation();
                    if (position == location.entry().size()) verifyDigest();
                    return count;
                } catch (IOException | RuntimeException | Error e) {
                    failure = e;
                    throw e;
                }
            }
        }

        private void verifyDigest() throws IOException {
            if (!PackIo.hex(digest.digest()).equals(location.entry().sha256())) {
                throw PackIo.fail(PackSnapshotException.Code.CONTENT_MISMATCH, "Entry digest changed");
            }
            verified = true;
        }

        @Override
        public int available() throws IOException {
            synchronized (PackSnapshot.this) {
                requireOpen();
                if (failure != null) throw new IOException("Entry stream has failed", failure);
                return (int) Math.min(Integer.MAX_VALUE, location.entry().size() - position);
            }
        }

        // InputStream.skip consumes through read(), so skipped bytes are hashed.
        // The inherited mark/reset contract remains unsupported.
        @Override
        public void close() {
            synchronized (PackSnapshot.this) {
                if (!streamClosed) {
                    streamClosed = true;
                    openStreams--;
                }
            }
        }
    }

    /**
     * Copies without closing the caller's sink. Any failure, including cancellation
     * or a digest mismatch, may leave partial bytes in the sink. Use staged output
     * and publish it only after this method returns successfully.
     */
    public synchronized void copyEntry(String path, OutputStream target, BooleanSupplier cancelled) throws IOException {
        requireOutsideCancellation();
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(cancelled, "cancelled");
        if (closed) throw new IOException("Snapshot is closed");
        Location location = locations.get(path);
        if (location == null) throw new IllegalArgumentException("Unknown entry");
        PackIo.cancel(cancelled);
        MessageDigest digest = PackIo.digest();
        byte[] bytes = new byte[PackIo.BUFFER];
        long remaining = location.entry().size(), offset = location.offset();
        while (remaining > 0) {
            PackIo.cancel(cancelled);
            ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, (int) Math.min(bytes.length, remaining));
            int read = channel.read(buffer, offset);
            if (read < 0) throw PackIo.fail(PackSnapshotException.Code.CONTENT_MISMATCH, "Entry was truncated");
            if (read == 0) continue;
            digest.update(bytes, 0, read);
            target.write(bytes, 0, read);
            remaining -= read;
            offset += read;
        }
        PackIo.cancel(cancelled);
        if (!PackIo.hex(digest.digest()).equals(location.entry().sha256())) {
            throw PackIo.fail(PackSnapshotException.Code.CONTENT_MISMATCH, "Entry digest changed");
        }
    }

    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            channel.close();
        }
    }
}
