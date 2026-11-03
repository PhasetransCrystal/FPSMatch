package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.api.extensions.*;
import net.ptcrys.fpsmatch.editor.api.registry.*;
import net.ptcrys.fpsmatch.editor.core.installation.InstallationPlan;
import net.ptcrys.fpsmatch.editor.core.session.SavedRuntimeAdapters;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;

import java.util.*;

/** Product discovery metadata. The registered native factory remains the installation authority. */
public record RuntimeInstallationProfile(EditorId documentType, EditorId adapterId, EditorId factoryId,
                                         String gameType, TextContent label) {

    public static final ExtensionKind<RuntimeInstallationProfile> KIND = new ExtensionKind<>(
            EditorId.parse("editor:runtime_installation_profile"), RuntimeInstallationProfile.class);

    public RuntimeInstallationProfile {
        Objects.requireNonNull(documentType);
        Objects.requireNonNull(adapterId);
        Objects.requireNonNull(factoryId);
        Objects.requireNonNull(label);
        new InstallationPlan.Key(gameType, "validation");
    }

    public static void register(ExtensionRegistryBuilder builder, EditorId id, EditorId owner,
                                RuntimeInstallationProfile profile) {
        builder.define(KIND);
        builder.add(KIND, new Contribution(new ContributionKey(KIND.id(), id), owner, Contribution.Side.COMMON,
                Set.of(new ContributionKey(DocumentTypes.KIND.id(), profile.documentType()),
                        new ContributionKey(SavedRuntimeAdapters.KIND.id(), profile.adapterId()),
                        new ContributionKey(EditorId.parse("editor:native_map_factory"), profile.factoryId()))),
                profile);
    }
}
