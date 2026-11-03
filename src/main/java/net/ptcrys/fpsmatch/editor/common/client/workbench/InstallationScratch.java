package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.FileNodeIdentity;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Owns only the newly allocated installation input directory, never retained installation state. */
final class InstallationScratch implements AutoCloseable {

    private final Path root;
    private final FileIdentityProvider identities;
    private final FileNodeIdentity rootIdentity;
    private boolean closed;

    private InstallationScratch(Path root, FileIdentityProvider identities, FileNodeIdentity rootIdentity) {
        this.root = root;
        this.identities = identities;
        this.rootIdentity = rootIdentity;
    }

    static InstallationScratch create(FileIdentityProvider identities) throws IOException {
        Objects.requireNonNull(identities, "identities");
        return capture(Files.createTempDirectory("fpsmatch-install-"), identities);
    }

    static InstallationScratch create(Path parent, FileIdentityProvider identities) throws IOException {
        Objects.requireNonNull(identities, "identities");
        return capture(Files.createTempDirectory(parent, "fpsmatch-install-"), identities);
    }

    private static InstallationScratch capture(Path allocated, FileIdentityProvider identities) throws IOException {
        Path root = allocated.toAbsolutePath().normalize();
        BasicFileAttributes allocatedAttributes = null;
        FileIdentityProvider allocationProvider = null;
        FileNodeIdentity allocationIdentity = null;
        try {
            allocatedAttributes = attributes(root);
            requireDirectory(root, allocatedAttributes);
            // Windows JDKs may omit fileKey. Capture an independent physical key
            // before invoking the caller's provider, including providers that fail.
            if (allocatedAttributes.fileKey() == null && "\\".equals(FileSystems.getDefault().getSeparator()) && root.getFileSystem() == FileSystems.getDefault()) {
                allocationProvider = new WindowsFileIdentityProvider();
                allocationIdentity = identity(allocationProvider, root);
            }
            FileNodeIdentity captured = identity(identities, root);
            requireDirectory(root, attributes(root));
            if (!sameAllocation(root, allocatedAttributes, allocationProvider, allocationIdentity)) {
                throw new IOException("Installation scratch allocation was replaced: " + root);
            }
            return new InstallationScratch(root, identities, captured);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                // No recursive fallback before ownership is authenticated. Only this still-empty
                // allocation may be removed; a replacement node must survive the failure.
                BasicFileAttributes current = attributes(root);
                if (allocatedAttributes == null || !sameAllocation(root, allocatedAttributes, allocationProvider, allocationIdentity)) {
                    throw new IOException("Cannot authenticate failed installation scratch allocation: " + root);
                }
                requireDirectory(root, current);
                Files.delete(root);
            } catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static boolean sameAllocation(Path root, BasicFileAttributes allocated,
                                          FileIdentityProvider allocationProvider, FileNodeIdentity allocationIdentity) throws IOException {
        if (allocated.fileKey() != null) return Objects.equals(allocated.fileKey(), attributes(root).fileKey());
        return allocationIdentity != null && allocationIdentity.equals(identity(allocationProvider, root));
    }

    Path archive() {
        return root.resolve("input.zip");
    }

    Path projectDirectory() {
        return root.resolve("project");
    }

    Path root() {
        return root;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        requireIdentity(root, rootIdentity);
        Deque<Directory> parents = new ArrayDeque<>();
        // The default walk does not follow links. Authentication in preVisitDirectory
        // rejects Windows junctions/reparse points before any children are visited.
        Files.walkFileTree(root, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                verifyParents(parents);
                requireDirectory(directory, attrs);
                FileNodeIdentity captured = identity(identities, directory);
                if (directory.equals(root) && !captured.equals(rootIdentity)) {
                    throw new IOException("Installation scratch root was replaced: " + root);
                }
                parents.addLast(new Directory(directory, captured));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                verifyParents(parents);
                if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) {
                    throw new IOException("Unsupported installation scratch node: " + file);
                }
                identity(identities, file);
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                verifyParents(parents);
                Files.delete(directory);
                parents.removeLast();
                return FileVisitResult.CONTINUE;
            }
        });
        closed = true;
    }

    private void verifyParents(Deque<Directory> parents) throws IOException {
        for (Directory directory : parents) requireIdentity(directory.path(), directory.identity());
    }

    private void requireIdentity(Path directory, FileNodeIdentity expected) throws IOException {
        if (!directory.isAbsolute() || !directory.normalize().equals(directory) || !directory.startsWith(root)) {
            throw new IOException("Invalid installation scratch path: " + directory);
        }
        requireDirectory(directory, attributes(directory));
        if (!expected.equals(identity(identities, directory))) {
            throw new IOException("Installation scratch directory was replaced: " + directory);
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static void requireDirectory(Path path, BasicFileAttributes attrs) throws IOException {
        if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) {
            throw new IOException("Unsupported installation scratch directory: " + path);
        }
    }

    private static FileNodeIdentity identity(FileIdentityProvider identities, Path path) throws IOException {
        try {
            FileNodeIdentity result = identities.identity(path);
            if (result == null) throw new IOException("Missing installation scratch identity: " + path);
            return result;
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("Unsupported installation scratch identity: " + path, unsupported);
        }
    }

    private record Directory(Path path, FileNodeIdentity identity) {}
}
