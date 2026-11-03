package net.ptcrys.fpsmatch.editor.core.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import org.junit.jupiter.api.Test;

/** Public registration contract; runtime validation and context enforcement have separate tests. */
class ResourceContentRegistrationTest {

    @Test
    void declarationAndExecutableValidatorShareAnImporterIdInOneRegistry() {
        ApiVersion api = new ApiVersion(1, 0);
        EditorId owner = EditorId.parse("synthetic:module");
        EditorId importer = EditorId.parse("synthetic:format");
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        builder.define(ResourceProducers.KIND);
        builder.define(ResourceContentValidators.KIND);
        builder.addModule(new ModuleSpec(owner, "1.0", api, Set.of(), Set.of()));
        ResourceProducerSpec declaration = new ResourceProducerSpec(Set.of("exact-legacy-version"));
        ResourceContentValidator validator = (version, content) -> List.of();
        ContributionKey declarationKey = new ContributionKey(ResourceProducers.KIND.id(), importer);
        ContributionKey validatorKey = new ContributionKey(ResourceContentValidators.KIND.id(), importer);
        builder.add(ResourceProducers.KIND,
                new Contribution(declarationKey, owner, Contribution.Side.COMMON, Set.of()), declaration);
        builder.add(ResourceContentValidators.KIND,
                new Contribution(validatorKey, owner, Contribution.Side.COMMON, Set.of()), validator);

        ExtensionRegistry registry = builder.freeze();

        assertEquals(EditorId.parse("editor:resource_content_validator"),
                ResourceContentValidators.KIND.id());
        assertSame(ResourceContentValidator.class, ResourceContentValidators.KIND.valueType());
        assertTrue(registry.defines(ResourceContentValidators.KIND));
        assertSame(declaration, registry.find(ResourceProducers.KIND, importer).orElseThrow());
        assertSame(validator, registry.find(ResourceContentValidators.KIND, importer).orElseThrow());
        assertEquals(owner, registry.metadata().contributions().get(declarationKey).owner());
        assertEquals(owner, registry.metadata().contributions().get(validatorKey).owner());
        assertTrue(registry.metadata().problems().isEmpty());
    }
}
