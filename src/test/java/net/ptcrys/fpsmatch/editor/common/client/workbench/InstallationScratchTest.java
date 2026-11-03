package net.ptcrys.fpsmatch.editor.common.client.workbench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProviders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class InstallationScratchTest {
    @TempDir Path temporary;
    private final FileIdentityProvider identities = System.getProperty("os.name").startsWith("Windows")
            ? new WindowsFileIdentityProvider() : FileIdentityProviders.jdk();

    @Test void closesNestedInstallationAndEmptyDirectoriesWithoutTouchingSiblings() throws IOException {
        Path sibling = Files.writeString(temporary.resolve("retained-installation"), "retained");
        InstallationScratch scratch = InstallationScratch.create(temporary, identities);
        assertTrue(scratch.root().isAbsolute());
        assertEquals(scratch.root().resolve("input.zip"), scratch.archive());
        assertEquals(scratch.root().resolve("project"), scratch.projectDirectory());
        Files.writeString(scratch.archive(), "captured archive");
        Path nested = Files.createDirectories(scratch.projectDirectory().resolve("assets/nested"));
        Files.writeString(nested.resolve("blob"), "asset bytes");
        Files.createDirectories(scratch.projectDirectory().resolve("empty/also-empty"));

        scratch.close();
        scratch.close();

        assertFalse(Files.exists(scratch.root()));
        assertEquals("retained", Files.readString(sibling));
    }

    @Test void closesAnUnusedAllocation() throws IOException {
        InstallationScratch scratch = InstallationScratch.create(temporary, identities);
        scratch.close();
        assertFalse(Files.exists(scratch.root()));
    }

    @Test void refusesARealRootReplacementAndPreservesBothNodes() throws IOException {
        InstallationScratch scratch = InstallationScratch.create(temporary, identities);
        Files.writeString(scratch.archive(), "owned input");
        Path retained = temporary.resolve("renamed-original");
        Files.move(scratch.root(), retained);
        Files.createDirectory(scratch.root());
        Path replacement = Files.writeString(scratch.root().resolve("replacement"), "foreign bytes");

        assertThrows(IOException.class, scratch::close);
        assertThrows(IOException.class, scratch::close);

        assertEquals("foreign bytes", Files.readString(replacement));
        assertEquals("owned input", Files.readString(retained.resolve("input.zip")));
    }

    @Test void failedInitialIdentityDoesNotLeakTheNewEmptyAllocation() throws IOException {
        IOException unavailable = new IOException("identity unavailable");
        IOException failure = assertThrows(IOException.class,
                () -> InstallationScratch.create(temporary, path -> { throw unavailable; }));

        assertSame(unavailable, failure);
        try (var entries = Files.list(temporary)) {
            assertEquals(0, entries.count());
        }
    }

    @Test void initialIdentityFailureDoesNotDeleteAReplacementEmptyRoot() throws IOException {
        Path[] replacement = new Path[1];
        Path retained = temporary.resolve("original-allocation");
        assertThrows(IOException.class, () -> InstallationScratch.create(temporary, path -> {
            Files.move(path, retained);
            replacement[0] = Files.createDirectory(path);
            throw new IOException("identity failed after root replacement");
        }));

        assertTrue(Files.isDirectory(retained));
        assertTrue(Files.isDirectory(replacement[0]));
    }

    @Test void initialIdentitySuccessCannotClaimAReplacementRoot() throws IOException {
        Path[] replacement = new Path[1];
        Path retained = temporary.resolve("original-allocation");
        assertThrows(IOException.class, () -> InstallationScratch.create(temporary, path -> {
            Files.move(path, retained);
            replacement[0] = Files.createDirectory(path);
            return identities.identity(path);
        }));

        assertTrue(Files.isDirectory(retained));
        assertTrue(Files.isDirectory(replacement[0]));
    }

    @Test void rejectedChildDirectoryIsNotTraversedAndCleanupCanBeRetried() throws IOException {
        AtomicBoolean reject = new AtomicBoolean(true);
        FileIdentityProvider checked = path -> {
            if (reject.get() && path.getFileName().toString().equals("unsupported")) {
                throw new UnsupportedOperationException("directory authentication unsupported");
            }
            return identities.identity(path);
        };
        InstallationScratch scratch = InstallationScratch.create(temporary, checked);
        Path child = Files.createDirectory(scratch.projectDirectory());
        Path unsupported = Files.createDirectory(child.resolve("unsupported"));
        Path retained = Files.writeString(unsupported.resolve("keep"), "must survive");

        IOException failure = assertThrows(IOException.class, scratch::close);
        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
        assertEquals("must survive", Files.readString(retained));
        assertTrue(Files.isDirectory(scratch.root()));

        reject.set(false);
        scratch.close();
        assertFalse(Files.exists(scratch.root()));
    }

    @Test void rootAuthenticationFailurePreservesAllContents() throws IOException {
        AtomicBoolean reject = new AtomicBoolean(false);
        InstallationScratch scratch = InstallationScratch.create(temporary, path -> {
            if (reject.get()) throw new IOException("authentication lost");
            return identities.identity(path);
        });
        Files.writeString(scratch.archive(), "archive");
        reject.set(true);

        assertThrows(IOException.class, scratch::close);

        assertEquals("archive", Files.readString(scratch.archive()));
    }
}
