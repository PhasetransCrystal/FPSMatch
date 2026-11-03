package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;

/** Runtime-only fixed capture; only its issuing handle supplies confirmation behavior. */
abstract class ResourceSaveCapture {

    abstract ResourceToken token();

    abstract ResourceCatalog catalog();

    abstract long metadataBytes();

    abstract boolean canEnqueue();

    abstract void queued();

    abstract void confirm();
}
