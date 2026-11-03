package net.ptcrys.fpsmatch.editor.common.backend.windows;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Process-level evidence against one leaked native handle per identity query. */
@EnabledOnOs(OS.WINDOWS)
class WindowsHandleLifetimeTest {
    @TempDir Path temporary;

    @Test
    void repeatedSuccessfulAndMissingQueriesDoNotAccumulateNativeHandles() throws IOException {
        WindowsFileIdentityProvider provider = new WindowsFileIdentityProvider();
        Path file = Files.writeString(temporary.resolve("node"), "bytes");
        Path missing = temporary.resolve("missing");
        Counts counts = Native.load("kernel32", Counts.class);
        for (int i = 0; i < 128; i++) provider.identity(file);
        int before = handles(counts);
        for (int i = 0; i < 1024; i++) {
            provider.identity(file);
            provider.identity(temporary);
            try {
                provider.identity(missing);
                throw new AssertionError("Missing path was accepted");
            } catch (IOException expected) {
                // Both the failure and success paths must return without a live handle.
            }
        }
        int after = handles(counts);
        System.out.println("WIN32_HANDLE_LIFETIME before=" + before + " after=" + after
                + " successful=2048 missing=1024");
        // Allow unrelated test-runner housekeeping while still detecting a per-query leak.
        assertTrue(after - before <= 8, "Native handles accumulated: " + before + " -> " + after);
    }

    private static int handles(Counts counts) throws IOException {
        try (Memory value = new Memory(4)) {
            if (counts.GetProcessHandleCount(counts.GetCurrentProcess(), value) == 0) {
                throw new IOException("GetProcessHandleCount failed: " + Native.getLastError());
            }
            return value.getInt(0);
        }
    }

    public interface Counts extends StdCallLibrary {
        Pointer GetCurrentProcess();
        int GetProcessHandleCount(Pointer process, Pointer count);
    }
}
