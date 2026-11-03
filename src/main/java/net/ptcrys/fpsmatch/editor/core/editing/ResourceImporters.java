package net.ptcrys.fpsmatch.editor.core.editing;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.*;
import net.ptcrys.fpsmatch.editor.api.registry.*;
import net.ptcrys.fpsmatch.editor.core.resources.*;

import java.util.Objects;
import java.util.Set;

/** Import UI contributions require the same owner's real producer and content validator. */
public final class ResourceImporters {

    public static final ExtensionKind<ResourceImporter> KIND = new ExtensionKind<>(
            EditorId.parse("editor:resource_importer"), ResourceImporter.class);

    private ResourceImporters() {}

    public static void register(ExtensionRegistryBuilder builder, EditorId id, EditorId owner, ResourceImporter importer) {
        builder.define(KIND);
        builder.add(KIND, new Contribution(new ContributionKey(KIND.id(), id), owner, Contribution.Side.COMMON,
                Set.of(new ContributionKey(ResourceProducers.KIND.id(), id),
                        new ContributionKey(ResourceContentValidators.KIND.id(), id))),
                importer);
    }

    public static void require(ExtensionRegistry registry, EditorId id, ResourceImporter importer) {
        if (registry.find(KIND, id).orElseThrow() != importer) throw new IllegalArgumentException("foreign resource importer");
        var metadata = registry.metadata().contributions();
        var ui = Objects.requireNonNull(metadata.get(new ContributionKey(KIND.id(), id)));
        for (EditorId kind : Set.of(ResourceProducers.KIND.id(), ResourceContentValidators.KIND.id())) {
            var contribution = Objects.requireNonNull(metadata.get(new ContributionKey(kind, id)));
            if (ui.side() != Contribution.Side.COMMON || contribution.side() != Contribution.Side.COMMON || !ui.owner().equals(contribution.owner()) || !ui.requiredContributions().contains(contribution.key()))
                throw new IllegalArgumentException("resource importer owner differs");
        }
    }
}
