package net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.resources;

import net.ptcrys.fpsmatch.editor.common.domain.weapon.assets.BuiltinWeaponPackFormat.Source;
import net.ptcrys.fpsmatch.editor.core.packio.PackIndex;
import net.ptcrys.fpsmatch.editor.core.packio.PackSnapshots;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceValidationCancelledException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipFile;

/** Bounded I/O for explicit original mod containers; no live index, reload, world or background thread. */
public final class BuiltinWeaponPacks {

    private static final long MAX_CONTAINER_BYTES = 128L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 16384;

    private BuiltinWeaponPacks() {}

    /** Caller owns both paths. Existing targets are never overwritten, including late arrivals. */
    public static PackIndex capture(Source source, Path originalContainer, Path target, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(source);
        Objects.requireNonNull(originalContainer);
        Objects.requireNonNull(target);
        Objects.requireNonNull(cancelled);
        check(cancelled);
        Set<String> selected = select(source, originalContainer, cancelled);
        // Re-authenticates the whole raw original, selected paths and actual bytes; detects changes
        // between the initial fingerprint, selection and the final deterministic capture.
        return PackSnapshots.captureZipEntries(originalContainer, target, source.containerSha256(), selected, cancelled);
    }

    private static Set<String> select(Source source, Path originalContainer, BooleanSupplier cancelled) throws IOException {
        if (Files.size(originalContainer) > MAX_CONTAINER_BYTES) throw new IOException("Builtin container exceeds capture budget");
        Path fixed = Files.createTempFile("editor-builtin-container-", ".zip");
        Throwable failure = null;
        try {
            MessageDigest hash = digest();
            try (InputStream input = Files.newInputStream(originalContainer); OutputStream output = Files.newOutputStream(fixed)) {
                byte[] buffer = new byte[65536];
                long total = 0;
                for (int read; (read = input.read(buffer)) != -1;) {
                    check(cancelled);
                    if (read > MAX_CONTAINER_BYTES - total) throw new IOException("Builtin container exceeds capture budget");
                    total += read;
                    hash.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            check(cancelled);
            if (!HexFormat.of().formatHex(hash.digest()).equals(source.containerSha256()))
                throw new IOException("Unsupported builtin container bytes: " + source.key());
            Set<String> selected = new HashSet<>();
            try (ZipFile archive = new ZipFile(fixed.toFile())) {
                var entries = archive.entries();
                int count = 0;
                while (entries.hasMoreElements()) {
                    check(cancelled);
                    if (++count > MAX_ZIP_ENTRIES) throw new IOException("Builtin container entry budget exceeded");
                    var entry = entries.nextElement();
                    if (!entry.isDirectory() && source.selects(entry.getName()) && !selected.add(entry.getName()))
                        throw new IOException("Duplicate builtin source path");
                }
            }
            check(cancelled);
            if (selected.size() != source.entryCount()) throw new IOException("Builtin source selection changed");
            return Set.copyOf(selected);
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                Files.deleteIfExists(fixed);
            } catch (IOException cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
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
}
