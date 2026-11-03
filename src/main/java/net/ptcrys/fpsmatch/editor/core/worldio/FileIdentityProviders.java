package net.ptcrys.fpsmatch.editor.core.worldio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;

/** Explicit physical identity sources. No OS backend is loaded implicitly. */
public final class FileIdentityProviders {

    private static final LinkOption[] NOFOLLOW = { LinkOption.NOFOLLOW_LINKS };
    private static final FileIdentityProvider JDK = FileIdentityProviders::jdkIdentity;

    private FileIdentityProviders() {}

    public static FileIdentityProvider jdk() {
        return JDK;
    }

    private static FileNodeIdentity jdkIdentity(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
        if (attrs.isSymbolicLink() || attrs.isOther() || (Files.getFileAttributeView(path, DosFileAttributeView.class, NOFOLLOW) != null && Boolean.TRUE.equals(Files.readAttributes(path, "dos:*", NOFOLLOW).get("reparsePoint")))) {
            throw new WorldIdentityException(WorldIdentityException.Code.CORRUPT, "Unsupported filesystem node");
        }
        Object key = attrs.fileKey();
        if (key == null) {
            throw new WorldIdentityException(WorldIdentityException.Code.UNSUPPORTED,
                    "Stable JDK filesystem identity unavailable");
        }
        return new FileNodeIdentity(key.getClass().getName(), key.toString());
    }
}
