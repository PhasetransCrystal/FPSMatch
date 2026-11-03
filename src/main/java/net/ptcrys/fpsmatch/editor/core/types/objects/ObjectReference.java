package net.ptcrys.fpsmatch.editor.core.types.objects;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.util.UUID;

/** A local reference; the catalog validates its components before issuing a node. */
public record ObjectReference(EditorId property, UUID target) {}
