package net.ptcrys.fpsmatch.editor.common.backend.windows;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Error classification only; these cases do not authenticate any physical node. */
class WindowsNativeFailureTest {
    private static final Path PATH = Path.of("node");

    @Test
    void onlyKnownQueryCapabilityErrorsAreUnsupportedAndRetainNativeCause() {
        for (String operation : new String[] {
                "GetFileInformationByHandleEx(FileAttributeTagInfo)",
                "GetFileInformationByHandleEx(FileIdInfo)" }) {
            for (int error : new int[] {1, 50}) {
                UnsupportedOperationException failure = assertThrows(
                        UnsupportedOperationException.class,
                        () -> WindowsFileIdentityProvider.queryFailure(operation, PATH, error));
                IOException cause = assertInstanceOf(IOException.class, failure.getCause());
                assertTrue(cause.getMessage().contains("Win32 error " + error + ")"));
                assertTrue(cause.getMessage().contains(operation));
                assertEquals(cause.getMessage(), failure.getMessage());
            }
        }
    }

    @Test
    void permissionDeviceAndUnknownQueryErrorsRemainIoFailures() {
        for (int error : new int[] {2, 5, 32, 1117, -1}) {
            IOException failure = WindowsFileIdentityProvider.queryFailure("query", PATH, error);
            assertEquals(IOException.class, failure.getClass());
            assertTrue(failure.getMessage().contains(
                    "Win32 error " + Integer.toUnsignedString(error) + ")"));
        }
    }

    @Test
    void closeAndOpenErrorsDoNotUseQueryCapabilityClassification() {
        for (String operation : new String[] {"CloseHandle", "CreateFileW"}) {
            for (int error : new int[] {1, 50, 5, 1117}) {
                IOException failure = WindowsFileIdentityProvider.nativeFailure(operation, PATH, error);
                assertEquals(IOException.class, failure.getClass());
                assertTrue(failure.getMessage().contains(operation));
                assertTrue(failure.getMessage().contains("Win32 error " + error + ")"));
            }
        }
    }
}
