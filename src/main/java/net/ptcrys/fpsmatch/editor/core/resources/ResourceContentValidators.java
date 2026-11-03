package net.ptcrys.fpsmatch.editor.core.resources;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionKind;

/** Executable validation contributions alongside the unchanged producer support declarations. */
public final class ResourceContentValidators {

    /** Validators use the same contribution id and owner as their producer declaration. */
    public static final ExtensionKind<ResourceContentValidator> KIND = new ExtensionKind<>(
            EditorId.parse("editor:resource_content_validator"), ResourceContentValidator.class);

    private ResourceContentValidators() {}
}
