package net.ptcrys.fpsmatch.editor.core.resources;

import java.io.IOException;

/** Cooperative cancellation of resource content validation, without a publishable result. */
public final class ResourceValidationCancelledException extends IOException {

    private static final long serialVersionUID = 1L;

    /** Creates the cancellation signal. */
    public ResourceValidationCancelledException() {
        super();
    }
}
