package net.ptcrys.fpsmatch.editor.common.backend.windows;

import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.FileNodeIdentity;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Objects;

/** Reads the physical node's Win32 volume and 128-bit file ID without following that node. */
public final class WindowsFileIdentityProvider implements FileIdentityProvider {

    public WindowsFileIdentityProvider() {}

    @Override
    public FileNodeIdentity identity(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (path.getFileSystem() != FileSystems.getDefault() || !"\\".equals(path.getFileSystem().getSeparator()) || !"file".equalsIgnoreCase(path.getFileSystem().provider().getScheme())) {
            throw new UnsupportedOperationException("Windows default file system required");
        }
        Path absolute = path.toAbsolutePath().normalize();
        return NativeAccess.identity(absolute, extendedPath(absolute));
    }

    private static String extendedPath(Path absolute) {
        String value = absolute.toString();
        if (value.startsWith("\\\\?\\")) {
            return value;
        }
        if (value.startsWith("\\\\")) {
            return "\\\\?\\UNC\\" + value.substring(2);
        }
        return "\\\\?\\" + value;
    }

    static IOException nativeFailure(String operation, Path path, int error) {
        return new IOException(operation + " failed (Win32 error " + Integer.toUnsignedString(error) + "): " + path);
    }

    static IOException queryFailure(String operation, Path path, int error) {
        IOException failure = nativeFailure(operation, path, error);
        if (error == 1 || error == 50) { // ERROR_INVALID_FUNCTION / ERROR_NOT_SUPPORTED
            throw new UnsupportedOperationException(failure.getMessage(), failure);
        }
        return failure;
    }

    // Loading this provider or constructing it does not load JNA/native code.
    private static final class NativeAccess {

        private static final Kernel32 API = Native.load("kernel32", Kernel32.class);
        private static final int FILE_READ_ATTRIBUTES = 0x80;
        private static final int SHARE_READ_WRITE_DELETE = 7;
        private static final int OPEN_EXISTING = 3;
        private static final int OPEN_NODE = 0x02000000 | 0x00200000;
        private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x400;
        private static final int FILE_ATTRIBUTE_TAG_INFO = 9;
        private static final int FILE_ID_INFO = 18;

        private NativeAccess() {}

        private static FileNodeIdentity identity(Path path, String nativePath) throws IOException {
            Pointer raw = API.CreateFileW(new WString(nativePath), FILE_READ_ATTRIBUTES,
                    SHARE_READ_WRITE_DELETE, null, OPEN_EXISTING, OPEN_NODE, null);
            if (raw == null || Pointer.nativeValue(raw) == -1L || (Native.POINTER_SIZE == 4 && Pointer.nativeValue(raw) == 0xffffffffL)) {
                throw nativeFailure("CreateFileW", path, Native.getLastError());
            }
            try (Handle handle = new Handle(raw, path);
                    Memory attributes = new Memory(8);
                    Memory identity = new Memory(24)) {
                // FILE_ATTRIBUTE_TAG_INFO is two DWORDs; FILE_ID_INFO is a ULONGLONG
                // followed by FILE_ID_128 (BYTE[16]). Preserve all 24 raw bytes.
                if (API.GetFileInformationByHandleEx(handle.value, FILE_ATTRIBUTE_TAG_INFO,
                        attributes, 8) == 0) {
                    throw queryFailure("GetFileInformationByHandleEx(FileAttributeTagInfo)",
                            path, Native.getLastError());
                }
                if ((attributes.getInt(0) & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                    throw new IOException("Reparse point is not supported: " + path);
                }
                if (API.GetFileInformationByHandleEx(handle.value, FILE_ID_INFO, identity, 24) == 0) {
                    throw queryFailure("GetFileInformationByHandleEx(FileIdInfo)",
                            path, Native.getLastError());
                }
                return new FileNodeIdentity("win32-file-id-128",
                        HexFormat.of().formatHex(identity.getByteArray(0, 24)));
            }
        }

        private static final class Handle implements AutoCloseable {

            private final Pointer value;
            private final Path path;

            private Handle(Pointer value, Path path) {
                this.value = value;
                this.path = path;
            }

            @Override
            public void close() throws IOException {
                if (API.CloseHandle(value) == 0) {
                    throw nativeFailure("CloseHandle", path, Native.getLastError());
                }
            }
        }

        public interface Kernel32 extends StdCallLibrary {

            Pointer CreateFileW(WString name, int access, int share, Pointer security,
                                int creation, int flags, Pointer template);

            int GetFileInformationByHandleEx(Pointer handle, int informationClass,
                                             Pointer information, int size);

            int CloseHandle(Pointer handle);
        }
    }
}
