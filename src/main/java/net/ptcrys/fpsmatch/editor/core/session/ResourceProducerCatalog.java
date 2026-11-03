package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceContentValidator;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceContentValidators;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducerSpec;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceVersion;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Activated resource producer declarations read from one frozen registry.
 *
 * <p>
 * This catalog holds the immutable id to {@link ResourceProducerSpec} table of the real registry
 * it was activated from. There is no alternate input: a fabricated snapshot or an external map can
 * never be handed in, so a producer can only be resolved through a kind the registry itself defined
 * and published.
 * </p>
 *
 * <p>
 * Resolution is exact and never repairs stored data. The stored importer id selects the producer
 * under the resource producer kind - no other kind, no namespace inference and no default producer -
 * and the stored {@code importerVersion} must be an exact member of that producer's declared
 * readable versions. A missing producer or an undeclared version is reported as a rejection instead
 * of being rewritten, downgraded to another algorithm version or executed: declaring support is not
 * an importer, and this catalog never imports, migrates or touches stored bytes.
 * </p>
 *
 * <p>
 * The successful result is the real contribution key of the producer, not a file location or a
 * module version. The owner module and its required modules are collected from the same registry's
 * metadata - typically through
 * {@link DocumentTypeCatalog#requiredModules(java.util.Set, java.util.Set)} - so the algorithm
 * version declared by the version stays a separate fact from the build version of that module.
 * </p>
 */
public final class ResourceProducerCatalog {

    private final Map<EditorId, ResourceProducerSpec> producers;
    private final ExtensionRegistry registry;
    private final Map<EditorId, ResourceContentValidator> validators;

    private ResourceProducerCatalog(ExtensionRegistry registry) {
        this.registry = registry;
        Map<EditorId, ResourceProducerSpec> producers = registry.entries(ResourceProducers.KIND);
        this.validators = registry.defines(ResourceContentValidators.KIND) ? registry.entries(ResourceContentValidators.KIND) : Map.of();
        this.producers = Collections.unmodifiableMap(new TreeMap<>(producers));
    }

    /**
     * Reads the producers of one frozen registry, keyed by contribution id.
     *
     * @throws IllegalArgumentException if the resource producer kind was never defined in
     *                                  {@code registry}; an assembly that publishes resource versions without the
     *                                  extension
     *                                  point they are registered under is a programming error, not an empty-but-healthy
     *                                  state
     * @throws NullPointerException     if {@code registry} is null
     */
    public static ResourceProducerCatalog activate(ExtensionRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        // The registry's own lookup is the only source of producers; an unavailable contribution is
        // already absent here, so it is reported later exactly like a producer that was never
        // registered at all.
        return new ResourceProducerCatalog(registry);
    }

    /**
     * The contribution a stored version's producer must be explained by.
     *
     * @return the resource producer contribution key of {@code version.importer()}
     * @throws NullPointerException if {@code version} is null
     * @throws SessionRejection     if no producer is registered under the stored importer id, or if
     *                              the registered producer does not declare the stored
     *                              {@code importerVersion}. Both rejections carry the version as
     *                              their target and ask the caller to preserve the original data
     */
    public ContributionKey requireContribution(ResourceVersion version) {
        Objects.requireNonNull(version, "version");
        ResourceProducerSpec producer = producers.get(version.importer());
        if (producer == null) {
            throw ProducerMessages.resourceProducerUnavailable(version.id(), version.importer());
        }
        if (!producer.readableVersions().contains(version.importerVersion())) {
            throw ProducerMessages.resourceProducerVersionUnavailable(version.id(),
                    version.importer(), version.importerVersion());
        }
        return new ContributionKey(ResourceProducers.KIND.id(), version.importer());
    }

    /** Resolves the exact executable validator and verifies its real declaration owner. */
    public ContributionKey requireValidationContribution(ResourceVersion version) {
        ContributionKey declaration = requireContribution(version);
        if (!validators.containsKey(version.importer())) {
            throw ValidationMessages.unavailable(version);
        }
        ContributionKey key = new ContributionKey(ResourceContentValidators.KIND.id(), version.importer());
        if (!registry.metadata().contributions().get(declaration).owner().equals(
                registry.metadata().contributions().get(key).owner())) {
            throw ValidationMessages.ownerMismatch(version);
        }
        return key;
    }

    ResourceContentValidator validator(ResourceVersion version) {
        requireValidationContribution(version);
        return validators.get(version.importer());
    }
}
