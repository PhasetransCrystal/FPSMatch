package net.ptcrys.fpsmatch.editor.core.worldio;

import java.io.IOException;
import java.nio.file.Path;

/** Trusted read-only node authentication, without following links or reparse points. */
@FunctionalInterface
public interface FileIdentityProvider {

    FileNodeIdentity identity(Path path) throws IOException;
}
