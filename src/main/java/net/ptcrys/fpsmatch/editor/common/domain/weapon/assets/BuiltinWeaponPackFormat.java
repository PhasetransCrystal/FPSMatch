package net.ptcrys.fpsmatch.editor.common.domain.weapon.assets;

import net.ptcrys.fpsmatch.editor.api.*;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.common.domain.weapon.WeaponItemValues;
import net.ptcrys.fpsmatch.editor.core.resources.*;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Exact physical source packs, including shared validation for assets that reference a source. */
public final class BuiltinWeaponPackFormat {

    public static final EditorId TYPE = EditorId.parse("fpsmatch:weapon_builtin_pack");
    public static final EditorId BYTES = EditorId.parse("fpsmatch:weapon_pack_bytes");
    public static final EditorId CODEC = EditorId.parse("fpsmatch:weapon_pack_edpack");
    public static final EditorId TACZ_IMPORTER = EditorId.parse("fpsmatch:tacz_builtin_pack");
    public static final EditorId LR_IMPORTER = EditorId.parse("fpsmatch:lrtactical_builtin_pack");
    public static final String VERSION = "1";

    private BuiltinWeaponPackFormat() {}

    /** Selection and exact fingerprints are part of this readable format, not editable settings. */
    public enum Source {

        TACZ_DEFAULT("tacz_default", WeaponItemValues.TACZ_MODULE, TACZ_IMPORTER,
                "d8cfadd19fd760573967d78e1bb25defda4c649c2655a8b033d9a70dd4caa176",
                "assets/tacz/custom/tacz_default_gun/",
                "b78a8ccb35df1de91fe35a731ac100bd1ccf2e74a47313d0a29c812f94b89603", 73043671L, 2939),
        TACZ_RESOURCES("tacz_resources", WeaponItemValues.TACZ_MODULE, TACZ_IMPORTER,
                "d8cfadd19fd760573967d78e1bb25defda4c649c2655a8b033d9a70dd4caa176", "",
                "efc80349daaa4797e48fb15f0a21e0b5c58cb4dc77247d9d5a98979eb7ee39d2", 1042902L, 174),
        LR_RESOURCES("lr_resources", WeaponItemValues.LR_MODULE, LR_IMPORTER,
                "930372cab33e1f563f597c74f9155b5ed25e6f2b0ff7e12dfc86255e1020ef2d", "",
                "e418adf86cad4724efd3f9d32190eb47305dd5fbaac3841c2a3fd0e0cfcfea23", 7888461L, 293);

        private final String key, containerSha256, rootPrefix, snapshotSha256;
        private final EditorId owner, importer;
        private final long snapshotBytes;
        private final int entryCount;

        Source(String key, EditorId owner, EditorId importer, String containerSha256, String rootPrefix,
               String snapshotSha256, long snapshotBytes, int entryCount) {
            this.key = key;
            this.owner = owner;
            this.importer = importer;
            this.containerSha256 = containerSha256;
            this.rootPrefix = rootPrefix;
            this.snapshotSha256 = snapshotSha256;
            this.snapshotBytes = snapshotBytes;
            this.entryCount = entryCount;
        }

        public String key() {
            return key;
        }

        public EditorId owner() {
            return owner;
        }

        public EditorId importer() {
            return importer;
        }

        public String containerSha256() {
            return containerSha256;
        }

        public String rootPrefix() {
            return rootPrefix;
        }

        public String snapshotSha256() {
            return snapshotSha256;
        }

        public long snapshotBytes() {
            return snapshotBytes;
        }

        public int entryCount() {
            return entryCount;
        }

        public Map<String, String> settings() {
            return Map.of("source", key, "containerSha256", containerSha256, "rootPrefix", rootPrefix);
        }

        public boolean selects(String path) {
            if (this == TACZ_DEFAULT) return path.startsWith(rootPrefix);
            return (path.startsWith("assets/") || path.startsWith("data/")) && !path.contains("/custom/");
        }

        /** Builds a structural candidate from an already staged exact blob, without claiming validation. */
        public ResourceCatalog catalog(UUID resource, UUID version, UUID sourceEntry, BlobRef blob) {
            if (blob.kind() != BlobRef.Kind.ASSET || blob.size() != snapshotBytes || !blob.sha256().equals(snapshotSha256))
                throw new IllegalArgumentException("unexpected builtin pack bytes");
            var entry = new ProjectState.Entry(sourceEntry, owner.toString(), BYTES.toString(), CODEC.toString(), 1,
                    key, blob, Set.of(), Set.of());
            var descriptor = new ResourceVersion(version, resource, importer, VERSION, settings(), sourceEntry, Set.of(), Set.of());
            var result = new ResourceCatalog(Map.of(resource, new LogicalResource(resource, TYPE, key, version)),
                    Map.of(version, descriptor), Map.of(sourceEntry, entry));
            ResourceStorageBridge.planAll(result);
            return result;
        }
    }

    public static void registerTacz(ExtensionRegistryBuilder builder) {
        register(builder, TACZ_IMPORTER, WeaponItemValues.TACZ_MODULE);
    }

    public static void registerLrTactical(ExtensionRegistryBuilder builder) {
        register(builder, LR_IMPORTER, WeaponItemValues.LR_MODULE);
    }

    private static void register(ExtensionRegistryBuilder builder, EditorId importer, EditorId owner) {
        builder.define(ResourceProducers.KIND);
        builder.define(ResourceContentValidators.KIND);
        ContributionKey producer = new ContributionKey(ResourceProducers.KIND.id(), importer);
        builder.add(ResourceProducers.KIND, new Contribution(producer, owner, Contribution.Side.COMMON, Set.of()),
                new ResourceProducerSpec(Set.of(VERSION)));
        builder.add(ResourceContentValidators.KIND, new Contribution(
                new ContributionKey(ResourceContentValidators.KIND.id(), importer), owner, Contribution.Side.COMMON, Set.of(producer)),
                (version, content) -> validate(importer, version, content));
    }

    private static List<Problem> validate(EditorId importer, ResourceVersion version, ResourceContent content) throws IOException {
        content.checkCancelled();
        Source source = matchingSource(version, content);
        if (source == null || !source.importer.equals(importer)) return invalid(version, "descriptor");
        if (source == Source.TACZ_DEFAULT) {
            for (ResourceVersion dependent : content.versions().values()) {
                content.checkCancelled();
                if (dependent.importer().equals(WeaponItemValues.TACZ_IMPORTER) && dependent.dependencyVersions().contains(version.id()))
                    return TaczBuiltinAssetFormat.validatePack(version, content);
            }
        }
        // This is an exact byte allowlist. Metadata alone never certifies the stored source:
        // copyContent authenticates every byte against the fixed length and SHA, with cancellation.
        content.copyContent(version.sourceEntry(), OutputStream.nullOutputStream());
        content.checkCancelled();
        return List.of();
    }

    /** Structure only, used before actual byte authentication by the asset format in this package. */
    static Source matchingSource(ResourceVersion version, ResourceContent content) {
        Source source = null;
        for (Source candidate : Source.values()) if (candidate.importer.equals(version.importer()) && candidate.settings().equals(version.settings()))
            source = candidate;
        if (source == null || !version.importerVersion().equals(VERSION) || !TYPE.equals(content.resourceTypes().get(version.resourceId())) || version.sourceEntry() == null || !version.dependencyVersions().isEmpty() || !version.outputEntries().isEmpty())
            return null;
        ProjectState.Entry bytes = content.byteEntries().get(version.sourceEntry());
        if (bytes == null || !bytes.ownerModule().equals(source.owner.toString()) || !bytes.typeId().equals(BYTES.toString()) || !bytes.codecId().equals(CODEC.toString()) || bytes.schemaVersion() != 1 || !bytes.documentDependencies().isEmpty() || !bytes.assetDependencies().isEmpty() || bytes.content().kind() != BlobRef.Kind.ASSET || bytes.content().size() != source.snapshotBytes || !bytes.content().sha256().equals(source.snapshotSha256))
            return null;
        return source;
    }

    private static List<Problem> invalid(ResourceVersion version, String field) {
        return List.of(new Problem(Problem.Severity.ERROR, EditorId.parse("fpsmatch:weapon_pack/invalid_format"),
                "resource:" + version.id(), new TextContent.Message("fpsmatch.editor.weapon_pack.invalid_format",
                        List.of(new TextContent.LiteralArgument(field)))));
    }
}
