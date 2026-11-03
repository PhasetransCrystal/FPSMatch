package net.ptcrys.fpsmatch.editor.core.types.objects;

import static net.ptcrys.fpsmatch.editor.core.types.objects.ObjectGraphFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypeCatalog;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypes;

class ObjectCatalogTest {
    @Test
    void objectUsesTheRealValueRegistrationOwnerAndCompleteLockedDependencies() {
        Probe type = new Probe();
        var nested = id("other:nested");
        var nestedOwner = id("other:module");
        var resource = uuid(90);
        var versions = Set.of(new ResourceRef(resource, uuid(91)), new ResourceRef(resource, uuid(92)));
        var externalDocument = uuid(99);
        type.dependencyReader = value -> new DocumentDependencies(Set.of(externalDocument), versions, Set.of(key(nested)));
        ExtensionRegistryBuilder builder = builder();
        builder.addModule(new ModuleSpec(nestedOwner, "4.5.6", API, Set.of(), Set.of()));
        publish(builder, nested, nestedOwner, new Plain());
        publish(builder, TYPE, OWNER, type);
        ExtensionRegistry registry = builder.freeze();
        assertTrue(registry.metadata().problems().isEmpty());
        ObjectCatalog catalog = ObjectCatalog.activate(registry);
        ObjectNode node = node(catalog, ROOT, null, "payload");
        assertEquals(OWNER, node.value().owner());
        assertEquals(TYPE, node.value().typeId());
        assertEquals(POSITION, node.role());
        assertEquals(SEMANTIC, node.valueRole());
        assertEquals(nestedOwner, ValueTypeCatalog.activate(registry).find(nested).orElseThrow().owner());
        DocumentDependencies dependencies = new ObjectGraphCodec(catalog).dependencies(one(node));
        assertEquals(Set.of(externalDocument), dependencies.documents());
        assertEquals(versions, dependencies.resources());
        assertEquals(Set.of(key(TYPE), key(nested)), dependencies.contributions());
    }

    @Test
    void ordinaryValuesCannotBecomeObjectsAndCaptureRequiresTheExactDeclaredClass() {
        Plain ordinary = new Plain();
        ExtensionRegistry registry = registry(ordinary);
        assertTrue(ValueTypeCatalog.activate(registry).find(TYPE).isPresent());
        failure(() -> node(ObjectCatalog.activate(registry), ROOT, null, "payload"), "type_unavailable");
        assertTrue(ordinary.callbacks.isEmpty());

        Probe object = new Probe();
        ObjectCatalog catalog = catalog(object);
        failure(() -> catalog.capture(ROOT, Optional.empty(), POSITION, TYPE, CharSequence.class, "payload"), "invalid_node");
        assertTrue(object.callbacks.isEmpty(), "assignability must not substitute for exact metadata Class");
        assertEquals("payload", node(catalog, ROOT, null, "payload").value().content());
    }

    @Test
    void missingRequiredContributionNeverReachesObjectActivationOrDecoding() {
        Probe type = new Probe();
        ExtensionRegistryBuilder builder = builder();
        builder.add(ValueTypes.KIND, new Contribution(key(TYPE), OWNER, Contribution.Side.COMMON,
                Set.of(key(id("missing:required_value")))), type);
        ExtensionRegistry registry = builder.freeze();
        assertTrue(registry.find(ValueTypes.KIND, TYPE).isEmpty());
        ObjectCatalog catalog = ObjectCatalog.activate(registry);
        failure(() -> node(catalog, ROOT, null, "opaque"), "type_unavailable");
        byte[] input = wire(ROOT, new WireNode(ROOT, null, "opaque"));
        byte[] original = input.clone();
        failure(() -> new ObjectGraphCodec(catalog).decode(input), "type_unavailable");
        assertArrayEquals(original, input);
        assertEquals(0, type.roleReads);
        assertTrue(type.callbacks.isEmpty());
    }

    @Test
    void semanticRoleIsFrozenOnceAndActivationFailureKeepsItsOriginalCause() {
        RuntimeException original = new IllegalStateException("private detail");
        Probe broken = new Probe() { @Override public net.ptcrys.fpsmatch.editor.api.EditorId role() { throw original; } };
        Probe healthy = new Probe();
        ExtensionRegistryBuilder builder = builder();
        publish(builder, TYPE, OWNER, healthy);
        var brokenId = id("vendor:broken");
        publish(builder, brokenId, OWNER, broken);
        ObjectCatalog catalog = ObjectCatalog.activate(builder.freeze());
        assertSame(original, catalog.activationFailures().get(brokenId));
        assertThrows(UnsupportedOperationException.class, () -> catalog.activationFailures().clear());
        healthy.semantic = id("vendor:changed");
        assertEquals(SEMANTIC, node(catalog, ROOT, null, "payload").valueRole());
        assertEquals(1, healthy.roleReads);
        failure(() -> catalog.capture(CHILD, Optional.of(ROOT), POSITION, brokenId, String.class, "bad"), "type_unavailable");
        AssertionError fatal = new AssertionError("fatal");
        Probe fatalType = new Probe() { @Override public net.ptcrys.fpsmatch.editor.api.EditorId role() { throw fatal; } };
        assertSame(fatal, assertThrows(AssertionError.class, () -> catalog(fatalType)));
        Probe nullRole = new Probe(); nullRole.semantic = null;
        ObjectCatalog nullRoleCatalog = catalog(nullRole);
        assertTrue(nullRoleCatalog.activationFailures().containsKey(TYPE));
        failure(() -> node(nullRoleCatalog, ROOT, null, "payload"), "type_unavailable");
    }

    @Test
    void foreignAdoptionRecomputesSemanticRoleReferencesDependenciesAndOwnership() {
        Probe sourceType = new Probe();
        sourceType.semantic = id("source:semantic");
        sourceType.referenceReader = value -> Set.of(new ObjectReference(LINK, uuid(400)));
        sourceType.dependencyReader = value -> new DocumentDependencies(Set.of(uuid(401)), Set.of(), Set.of());
        ObjectCatalog source = catalog(sourceType);
        ObjectNode foreign = node(source, ROOT, null, "wire-payload");
        byte[] original = foreign.value().encoding();

        Probe targetType = new Probe();
        targetType.semantic = id("target:semantic");
        targetType.referenceReader = value -> Set.of(new ObjectReference(LINK, ROOT));
        ResourceRef locked = new ResourceRef(uuid(500), uuid(501));
        targetType.dependencyReader = value -> new DocumentDependencies(Set.of(uuid(502)), Set.of(locked), Set.of());
        ObjectCatalog target = catalog(targetType);
        ObjectGraphCodec codec = new ObjectGraphCodec(target);
        assertFalse(target.owns(foreign));
        failure(() -> codec.encode(one(foreign)), "invalid_node");
        failure(() -> codec.dependencies(one(foreign)), "invalid_node");
        assertFalse(codec.validate(one(foreign)).isEmpty());
        assertTrue(targetType.callbacks.isEmpty(), "non-snapshot APIs must reject foreign issuers");
        ObjectNode adopted = codec.snapshot(one(foreign)).nodes().get(0);
        assertNotSame(foreign, adopted);
        assertTrue(target.owns(adopted));
        assertFalse(source.owns(adopted));
        assertEquals(id("target:semantic"), adopted.valueRole());
        assertEquals(POSITION, adopted.role());
        assertEquals(Set.of(new ObjectReference(LINK, ROOT)), adopted.references());
        assertEquals(Set.of(uuid(502)), adopted.value().dependencies().documents());
        assertEquals(Set.of(locked), adopted.value().dependencies().resources());
        assertEquals(List.of("wire-payload"), targetType.decoded);
        assertArrayEquals(original, foreign.value().encoding(), "mutating target decoder cannot corrupt source");
        targetType.callbacks.clear();
        assertSame(adopted, target.adopt(adopted));
        assertTrue(targetType.callbacks.isEmpty());
    }

    @Test
    void repeatedActivationOfTheSameRegistryStillRequiresAdoption() {
        Probe type = new Probe();
        ExtensionRegistry registry = registry(type);
        ObjectCatalog first = ObjectCatalog.activate(registry);
        ObjectCatalog second = ObjectCatalog.activate(registry);
        ObjectNode original = node(first, ROOT, null, "same-registry");
        assertTrue(first.owns(original));
        assertFalse(second.owns(original));
        assertFalse(first.owns(null));
        type.callbacks.clear();
        ObjectNode adopted = second.adopt(original);
        assertTrue(second.owns(adopted));
        assertEquals(List.of("same-registry"), type.decoded);
    }

    @Test
    void referenceFailuresDoNotAlterExistingSnapshotsOrExposeAPartialCandidate() {
        Probe type = new Probe();
        Set<ObjectReference> mutable = new LinkedHashSet<>(List.of(new ObjectReference(LINK, ROOT)));
        type.referenceReader = value -> mutable;
        ObjectCatalog catalog = catalog(type);
        ObjectNode original = node(catalog, ROOT, null, "before");
        ObjectGraphCodec codec = new ObjectGraphCodec(catalog);
        byte[] before = codec.encode(one(original));
        mutable.clear();
        assertEquals(Set.of(new ObjectReference(LINK, ROOT)), original.references());
        assertThrows(UnsupportedOperationException.class, () -> original.references().clear());
        type.referenceReader = value -> { throw new IllegalStateException("private callback text"); };
        failure(() -> node(catalog, CHILD, ROOT, "after"), "invalid_node");
        assertArrayEquals(before, codec.encode(one(original)));
        type.referenceReader = value -> null;
        failure(() -> node(catalog, CHILD, ROOT, "after"), "invalid_node");
        Set<ObjectReference> invalid = new LinkedHashSet<>(); invalid.add(null);
        type.referenceReader = value -> invalid;
        failure(() -> node(catalog, CHILD, ROOT, "after"), "invalid_node");
        type.referenceReader = value -> Set.of();
        assertEquals(CHILD, node(catalog, CHILD, ROOT, "retry").id(), "failed capture reserves no identity");
        assertArrayEquals(before, codec.encode(one(original)));
    }

    @Test
    void referencesReadTheCapturedSnapshotAndFailedForeignReferencesLeaveBothGraphsUsable() {
        Probe sourceType = new Probe();
        ObjectCatalog source = catalog(sourceType);
        ObjectGraph graph = new ObjectGraph(ROOT, List.of(node(source, ROOT, null, "root"), node(source, CHILD, ROOT, "child")));
        ObjectGraphCodec sourceCodec = new ObjectGraphCodec(source);
        byte[] original = sourceCodec.encode(graph);
        Probe targetType = new Probe() {
            @Override public String snapshot(String value) { callbacks.add("snapshot"); return value.toUpperCase(java.util.Locale.ROOT); }
        };
        targetType.referenceReader = value -> {
            assertEquals(value.toUpperCase(java.util.Locale.ROOT), value, "refs must see the codec snapshot");
            if (value.equals("CHILD")) throw new IllegalStateException("unpublishable candidate");
            return Set.of(new ObjectReference(LINK, ROOT));
        };
        ObjectCatalog target = catalog(targetType);
        ObjectGraphCodec targetCodec = new ObjectGraphCodec(target);
        ObjectGraph before = one(node(target, ROOT, null, "before"));
        byte[] targetBefore = targetCodec.encode(before);
        failure(() -> targetCodec.snapshot(graph), "invalid_node");
        assertEquals(List.of("root", "child"), targetType.decoded);
        assertArrayEquals(original, sourceCodec.encode(graph));
        assertArrayEquals(targetBefore, targetCodec.encode(before));
        targetType.referenceReader = value -> Set.of(new ObjectReference(LINK, ROOT));
        assertEquals(List.of("ROOT", "CHILD"), targetCodec.snapshot(graph).nodes().stream().map(node -> node.value().content()).toList());
    }
}
