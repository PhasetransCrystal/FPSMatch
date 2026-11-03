package net.ptcrys.fpsmatch.editor.common.model;

/**
 * Reference to one map definition inside one world. Repository lookups always use the scoped store
 * of {@link #worldId()} together with this definition id; there is no concatenated target key.
 */
public record DefinitionRef(Ids.WorldId worldId, Ids.DefinitionId definitionId) {

    public DefinitionRef {
        if (worldId == null) {
            throw new IllegalArgumentException("worldId must not be null");
        }
        if (definitionId == null) {
            throw new IllegalArgumentException("definitionId must not be null");
        }
    }
}
