package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.core.editing.ResourceImporter;
import net.ptcrys.fpsmatch.editor.core.resources.*;
import net.ptcrys.fpsmatch.editor.core.storage.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One private, bounded import preview. All filesystem work and cleanup belong to the I/O executor. */
final class ResourceImportCapture implements AutoCloseable {

    static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;

    enum InputReason {
        ABSOLUTE_REQUIRED,
        MISSING,
        NOT_REGULAR,
        LIMIT,
        CHANGED,
        ACCESS,
        IO
    }

    /** Typed capture failure: only the registered input label is exposed to the view. */
    static final class InputException extends IOException {

        private static final long serialVersionUID = 1L;
        final InputReason reason;
        final TextContent label;

        InputException(ResourceImporter.Input input, InputReason reason, Throwable cause) {
            super("Resource input " + input.id() + ": " + reason, cause);
            this.reason = reason;
            label = input.label();
        }

        InputException(ResourceImporter.Input input, InputReason reason) {
            this(input, reason, null);
        }
    }

    final ResourceCatalog candidate;
    final List<TextContent> summary;
    private final Map<UUID, ResourceImporter.Source> sources;
    private final Map<UUID, BlobRef> expectedSources;
    private final ResourceImporter.Prepared prepared;
    private final Path directory;

    private ResourceImportCapture(Path directory, ResourceImporter.Prepared prepared, ResourceCatalog baseline) {
        this.directory = directory;
        this.prepared = prepared;
        candidate = Objects.requireNonNull(prepared.candidate());
        requirePreserved(baseline.resources(), candidate.resources());
        requirePreserved(baseline.versions(), candidate.versions());
        requirePreserved(baseline.byteEntries(), candidate.byteEntries());
        ResourceStorageBridge.planAll(candidate);
        Map<UUID, ResourceImporter.Source> declared = Objects.requireNonNull(prepared.sources());
        if (declared.size() > 8192) throw new IllegalArgumentException("import source budget");
        sources = Map.copyOf(declared);
        Set<UUID> added = new HashSet<>(candidate.byteEntries().keySet());
        added.removeAll(baseline.byteEntries().keySet());
        if (!added.equals(sources.keySet()) || candidate.resources().size() == baseline.resources().size())
            throw new IllegalArgumentException("import must add resources with exact new byte sources");
        long total = 0;
        Map<UUID, BlobRef> frozen = new HashMap<>();
        for (var entry : sources.entrySet()) {
            BlobRef ref = Objects.requireNonNull(entry.getValue().content());
            if (!ref.equals(candidate.byteEntries().get(entry.getKey()).content()) || ref.size() > MAX_TOTAL_BYTES - total)
                throw new IllegalArgumentException("import output reference or budget");
            total += ref.size();
            frozen.put(entry.getKey(), ref);
        }
        expectedSources = Map.copyOf(frozen);
        var lines = Objects.requireNonNull(prepared.summary());
        if (lines.size() > 256) throw new IllegalArgumentException("import summary budget");
        summary = List.copyOf(lines);
    }

    static ResourceImportCapture inspect(ExtensionRegistry registry, ResourceImporter importer,
                                         List<ResourceImporter.Input> specs, Map<String, Path> selected, ResourceCatalog baseline,
                                         UUID seed, BooleanSupplier cancelled) throws IOException {
        Path directory = Files.createTempDirectory("editor-resource-import-");
        ResourceImporter.Prepared prepared = null;
        try {
            Map<String, ResourceImporter.Source> captured = new HashMap<>();
            long total = 0;
            for (var spec : specs) {
                check(cancelled);
                Path original = Objects.requireNonNull(selected.get(spec.id()));
                try {
                    if (!original.isAbsolute()) throw new InputException(spec, InputReason.ABSOLUTE_REQUIRED);
                    var before = Files.readAttributes(original, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!before.isRegularFile()) throw new InputException(spec, InputReason.NOT_REGULAR);
                    if (before.size() > spec.maxBytes() || before.size() > MAX_TOTAL_BYTES - total)
                        throw new InputException(spec, InputReason.LIMIT);
                    Path target = directory.resolve(spec.id());
                    MessageDigest digest = digest();
                    long count = 0;
                    try (var input = Files.newInputStream(original, LinkOption.NOFOLLOW_LINKS); var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer = new byte[65536];
                        int size;
                        while ((size = input.read(buffer)) != -1) {
                            check(cancelled);
                            if (size > spec.maxBytes() - count || size > MAX_TOTAL_BYTES - total - count) throw new InputException(spec, InputReason.LIMIT);
                            output.write(buffer, 0, size);
                            digest.update(buffer, 0, size);
                            count += size;
                        }
                    }
                    var after = Files.readAttributes(original, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (count != before.size() || !after.isRegularFile() || after.size() != before.size() || !after.lastModifiedTime().equals(before.lastModifiedTime()) || !Objects.equals(after.fileKey(), before.fileKey()))
                        throw new InputException(spec, InputReason.CHANGED);
                    total += count;
                    BlobRef ref = new BlobRef(BlobRef.Kind.ASSET, HexFormat.of().formatHex(digest.digest()), count);
                    captured.put(spec.id(), new ResourceImporter.Source() {

                        public BlobRef content() {
                            return ref;
                        }

                        public InputStream open() throws IOException {
                            return Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS);
                        }
                    });
                } catch (InputException | ResourceValidationCancelledException failure) {
                    throw failure;
                } catch (NoSuchFileException failure) {
                    throw new InputException(spec, InputReason.MISSING, failure);
                } catch (AccessDeniedException | SecurityException failure) {
                    throw new InputException(spec, InputReason.ACCESS, failure);
                } catch (IOException failure) {
                    throw new InputException(spec, InputReason.IO, failure);
                }
            }
            check(cancelled);
            prepared = importer.inspect(registry, Map.copyOf(captured), baseline, seed, cancelled);
            check(cancelled);
            return new ResourceImportCapture(directory, Objects.requireNonNull(prepared), baseline);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                if (prepared != null) prepared.close();
            } catch (Throwable cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            try {
                delete(directory);
            } catch (Throwable cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    void stage(LocalProjectStore store, BooleanSupplier cancelled) throws IOException {
        for (var entry : sources.entrySet()) {
            check(cancelled);
            BlobRef expected = expectedSources.get(entry.getKey());
            try (var input = new FilterInputStream(entry.getValue().open()) {

                long count;

                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    int n = read(one, 0, 1);
                    return n < 0 ? -1 : one[0] & 255;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    check(cancelled);
                    int n = in.read(bytes, offset, (int) Math.min(length, Math.min(65536, expected.size() - count + 1)));
                    if (n > 0) {
                        count += n;
                        if (count > expected.size()) throw new IOException("Resource source grew");
                    }
                    return n;
                }
            }) {
                if (!store.stage(BlobRef.Kind.ASSET, input).equals(expected)) throw new IOException("Resource source content changed");
            }
        }
        check(cancelled);
    }

    private static <T> void requirePreserved(Map<UUID, T> before, Map<UUID, T> after) {
        for (var entry : before.entrySet()) if (!entry.getValue().equals(after.get(entry.getKey())))
            throw new IllegalArgumentException("Resource import may only add identities");
    }

    private static void check(BooleanSupplier cancelled) throws ResourceValidationCancelledException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new ResourceValidationCancelledException();
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            prepared.close();
        } catch (IOException | RuntimeException | Error failure) {
            try {
                delete(directory);
            } catch (Throwable cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        delete(directory);
    }

    private static void delete(Path directory) throws IOException {
        // This is a freshly allocated private capture directory, never an original input or project.
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
