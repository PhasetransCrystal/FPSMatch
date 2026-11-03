package net.ptcrys.fpsmatch.editor.core.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException.Code.*;

/** Snapshot exchange, separate from the local commit protocol and runtime installation. */
final class ProjectArchive {

    private ProjectArchive() {}

    static void exportSnapshot(LocalProjectStore store, Path destination, SavedProject snapshot)
                                                                                                 throws IOException {
        Path archive = destination.toAbsolutePath().normalize();
        Path parent = archive.getParent().toRealPath();
        archive = parent.resolve(archive.getFileName());
        if (archive.startsWith(store.root())) {
            throw new ProjectStorageException(INVALID_PATH, "share destination must be outside the project");
        }
        if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(archive.toString());
        }
        Path temp = parent.resolve(".share-" + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    ZipOutputStream zip = new ZipOutputStream(Channels.newOutputStream(channel))) {
                zip.putNextEntry(new ZipEntry("snapshot.json"));
                zip.write(StoreJson.manifest(snapshot, store.limits().maxManifestBytes()));
                zip.closeEntry();
                for (BlobRef ref : LocalProjectStore.contentReferences(snapshot.state())) {
                    zip.putNextEntry(new ZipEntry(ref.relativePath()));
                    store.copyContent(ref, zip);
                    zip.closeEntry();
                }
                zip.finish();
                zip.flush();
                channel.force(true);
            }
            if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(archive.toString());
            }
            // The complete archive is closed and forced; nothing is visible under the target name yet.
            // An entry changed by another process in the meantime must not publish a stale share.
            store.beforeExportPublish();
            store.recheckBeforeExportPublish();
            publishNew(temp, archive);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** ATOMIC_MOVE may replace an existing target even without REPLACE_EXISTING. */
    static void publishNew(Path completedFile, Path destination) throws IOException {
        try {
            // Both paths share a directory: link the already closed/forced file with create-new semantics.
            // The caller removes its temporary name afterwards; the published name retains the contents.
            Files.createLink(destination, completedFile);
        } catch (UnsupportedOperationException failure) {
            throw new IOException("filesystem cannot publish a complete share file without replacement", failure);
        }
    }

    static SavedProject importSnapshot(Path archive, Path newDirectory, StoreLimits limits)
                                                                                            throws IOException {
        // The complete whitelist is derived before creating the destination, never from zip paths.
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
            throw new ProjectStorageException(INVALID_PATH, "share package must be a regular file");
        }
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Map<String, ZipEntry> entries = inspect(zip, limits);
            ZipEntry metadata = entries.get("snapshot.json");
            if (metadata == null) {
                throw new ProjectStorageException(INVALID_FORMAT, "snapshot metadata is missing");
            }
            byte[] manifest;
            try (InputStream input = zip.getInputStream(metadata)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                LocalProjectStore.transfer(input, output, limits.maxManifestBytes(), LocalProjectStore.sha256());
                manifest = output.toByteArray();
            }
            SavedProject source = StoreJson.readManifest(manifest, limits);
            LocalProjectStore.validateState(source.state(), limits);
            Set<BlobRef> references = LocalProjectStore.contentReferences(source.state());
            Set<String> allowed = new HashSet<>();
            allowed.add("snapshot.json");
            for (BlobRef ref : references) {
                allowed.add(ref.relativePath());
                ZipEntry entry = entries.get(ref.relativePath());
                if (entry == null || entry.getSize() != ref.size()) {
                    throw new ProjectStorageException(CORRUPT_CONTENT, "share dependency is missing or has wrong size");
                }
            }
            if (!allowed.equals(entries.keySet())) {
                throw new ProjectStorageException(INVALID_FORMAT, "package has undeclared content");
            }
            try (LocalProjectStore store = LocalProjectStore.create(newDirectory, limits)) {
                store.validateState(source.state());
                for (BlobRef ref : references) {
                    try (InputStream input = zip.getInputStream(entries.get(ref.relativePath()))) {
                        store.stageExpected(ref, input);
                    }
                }
                ProjectState imported = source.state().withOrigin(
                        new ProjectState.Origin(source.state().projectId(), source.commitId()));
                return store.commit(null, UUID.randomUUID(), imported).saved();
            }
        }
    }

    private static Map<String, ZipEntry> inspect(ZipFile zip, StoreLimits limits) throws IOException {
        Map<String, ZipEntry> entries = new HashMap<>();
        long expanded = 0;
        long maximum = limits.maxProjectBytes() > Long.MAX_VALUE - limits.maxManifestBytes() ? Long.MAX_VALUE : limits.maxProjectBytes() + limits.maxManifestBytes();
        var enumeration = zip.entries();
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !(name.equals("snapshot.json") || name.matches("(documents|assets)/[0-9a-f]{64}\\.blob"))) {
                throw new ProjectStorageException(INVALID_PATH, "unexpected share entry path");
            }
            if (entries.putIfAbsent(name, entry) != null) {
                throw new ProjectStorageException(INVALID_FORMAT, "duplicate share entry");
            }
            if (entries.size() > (long) limits.maxEntries() + 1) {
                throw new ProjectStorageException(LIMIT_EXCEEDED, "share entry count exceeds budget");
            }
            long size = entry.getSize();
            long entryLimit = name.equals("snapshot.json") ? limits.maxManifestBytes() : limits.maxBlobBytes();
            if (size < 0 || size > entryLimit || size > maximum - expanded) {
                throw new ProjectStorageException(LIMIT_EXCEEDED, "share expanded size exceeds budget");
            }
            expanded += size;
        }
        return entries;
    }
}
