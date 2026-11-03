package net.ptcrys.fpsmatch.editor.common.backend.windows;

import net.ptcrys.fpsmatch.editor.core.worldio.FileNodeIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

/** Real Windows node tests: a skipped test is not a claim of filesystem support. */
@EnabledOnOs(OS.WINDOWS)
class WindowsFileIdentityProviderTest {
    @TempDir
    Path temporary;

    private final WindowsFileIdentityProvider provider = new WindowsFileIdentityProvider();

    @Test
    void readsStableFullIdentityAndNormalizesExplicitInput() throws IOException {
        Path file = Files.writeString(temporary.resolve("node.txt"), "first");
        FileNodeIdentity first = provider.identity(file);
        assertEquals("win32-file-id-128", first.provider());
        assertTrue(first.key().matches("[0-9a-f]{48}"));
        assertEquals(first, provider.identity(file));
        assertEquals(first, provider.identity(temporary.resolve("missing/../node.txt")));
        Files.writeString(file, "changed contents");
        assertEquals(first, provider.identity(file));
        assertEquals(provider.identity(temporary), provider.identity(temporary.resolve(".")));
    }

    @Test
    void hardLinksReferToTheSamePhysicalNode() throws IOException {
        Path original = Files.writeString(temporary.resolve("original"), "bytes");
        Path link = Files.createLink(temporary.resolve("alias"), original);
        assertEquals(provider.identity(original), provider.identity(link));
    }

    @Test
    void replacingFileWithIdenticalBytesChangesIdentity() throws IOException {
        Path file = Files.writeString(temporary.resolve("record"), "same bytes");
        FileNodeIdentity original = provider.identity(file);
        // Retain the old node so the filesystem cannot recycle its ID for the replacement.
        Files.move(file, temporary.resolve("retired"));
        Files.writeString(file, "same bytes");
        assertNotEquals(original, provider.identity(file));
    }

    @Test
    void deletedFileReportsTheNativeFailure() throws IOException {
        Path file = Files.writeString(temporary.resolve("deleted"), "bytes");
        provider.identity(file);
        Files.delete(file);
        IOException failure = assertThrows(IOException.class, () -> provider.identity(file));
        assertTrue(failure.getMessage().contains("CreateFileW"));
        assertTrue(failure.getMessage().contains("Win32 error 2)"), failure.getMessage());
    }

    @Test
    void fileCanBeRenamedAndDeletedAfterIdentityRead() throws IOException {
        Path file = Files.writeString(temporary.resolve("released"), "bytes");
        provider.identity(file);
        // This checks caller-visible availability. The provider shares DELETE, so
        // successful rename/delete alone is not proof against a leaked handle.
        Path renamed = Files.move(file, temporary.resolve("renamed"));
        Files.delete(renamed);
    }

    @Test
    void rejectsRealSymbolicLinkWithoutFollowingTarget() throws IOException {
        Path target = Files.writeString(temporary.resolve("target"), "bytes");
        Path link = temporary.resolve("symbolic-link");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | AccessDeniedException unsupported) {
            abort("Cannot create real symbolic link on this filesystem/account: " + unsupported);
        } catch (FileSystemException failure) {
            String reason = String.valueOf(failure.getReason()).toLowerCase(Locale.ROOT);
            if (reason.contains("privilege") || reason.contains("1314") || reason.contains("特权")) {
                abort("Symbolic-link creation privilege unavailable: " + failure);
            }
            throw failure;
        }
        IOException failure = assertThrows(IOException.class, () -> provider.identity(link));
        assertTrue(failure.getMessage().contains("Reparse point"), failure.getMessage());
        assertEquals("bytes", Files.readString(target));
    }

    @Test
    void supportsPathsBeyondLegacyMaxPath() throws IOException {
        Path directory = temporary;
        while (directory.toString().length() < 280) {
            directory = Files.createDirectory(directory.resolve("long-path-segment"));
        }
        Path file = Files.writeString(directory.resolve("node.txt"), "bytes");
        assertEquals(provider.identity(file), provider.identity(file));
    }

    @Test
    void rejectsNonDefaultFilesystemAndNull() throws IOException {
        URI zip = URI.create("jar:" + temporary.resolve("nodes.zip").toUri());
        try (FileSystem filesystem = FileSystems.newFileSystem(zip, Map.of("create", "true"))) {
            Path entry = Files.writeString(filesystem.getPath("/node"), "bytes");
            assertThrows(UnsupportedOperationException.class, () -> provider.identity(entry));
        }
        assertThrows(NullPointerException.class, () -> provider.identity(null));
    }
}
