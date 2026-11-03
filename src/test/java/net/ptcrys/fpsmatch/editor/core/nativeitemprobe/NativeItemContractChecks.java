package net.ptcrys.fpsmatch.editor.core.nativeitemprobe;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionKind;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.item.NativeItemCatalog;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.item.NativeItemException;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.item.NativeItemFactories;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.item.NativeItemFactory;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.objects.ObjectCatalog;
import net.ptcrys.fpsmatch.editor.core.types.objects.ObjectNode;
import net.ptcrys.fpsmatch.editor.core.types.objects.ObjectType;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypes;

/** Invoked by the tool mod on its real title owner, after Forge has initialized Items. */
final class NativeItemContractChecks {
    private static final ApiVersion API = new ApiVersion(1, 0);
    private static final EditorId OWNER = id("native_item_probe:third_party");
    private static final EditorId TYPE = id("native_item_probe:item");
    private static final EditorId CODEC = id("native_item_probe:item_codec");
    private static final EditorId ROLE = id("fpsmatch:item");
    private static final EditorId POSITION = id("native_item_probe:slot");
    private static final AtomicInteger THIRD_PARTY_CALLS = new AtomicInteger();

    private NativeItemContractChecks() { }

    /** The exact public extension path used by other mods; no native stack exists at registration. */
    static void register(ExtensionRegistryBuilder builder) {
        builder.define(ValueTypes.KIND);
        builder.addModule(new ModuleSpec(OWNER, "1.0.0", API, Set.of(), Set.of()));
        publish(builder, TYPE, new ProbeType(1, ROLE, CODEC, Set.of()));
        NativeItemFactories.register(builder, TYPE, OWNER,
                new NativeItemFactory<>(ProbeValue.class, false, value -> {
                    THIRD_PARTY_CALLS.incrementAndGet();
                    return new ItemStack(Items.DIAMOND, value.count());
                }));
    }

    static Map<String, Object> run(ExtensionRegistry actual) throws Exception {
        List<String> checks = new ArrayList<>();
        ObjectCatalog actualObjects = ObjectCatalog.activate(actual);
        ObjectNode thirdParty = node(actualObjects, TYPE, 7);
        byte[] original = thirdParty.value().encoding();
        require(thirdParty.value().owner().equals(OWNER), "actual third-party owner");
        require(thirdParty.value().typeId().equals(TYPE), "actual third-party type");
        require(thirdParty.value().schemaVersion() == 1 && thirdParty.value().codecId().equals(CODEC)
                && Arrays.equals(original, ByteBuffer.allocate(Integer.BYTES).putInt(7).array()),
                "actual strict schema one count encoding");
        require(actual.metadata().contributions().get(factoryKey(TYPE)).requiredContributions()
                .contains(valueKey(TYPE)), "actual factory declares necessary value contribution");
        require(thirdParty.valueRole().equals(ROLE) && thirdParty.role().equals(POSITION),
                "position role must differ from semantic valueRole");
        require(thirdParty.value().dependencies().contributions().equals(Set.of(valueKey(TYPE))),
                "activated value must add its own contribution to empty dependencies");
        int before = THIRD_PARTY_CALLS.get();
        try (NativeItemCatalog catalog = NativeItemCatalog.activate(actual, ROLE)) {
            require(!catalog.activationFailures().containsKey(TYPE), "actual factory activation");
            ItemStack first = catalog.create(thirdParty, () -> false);
            require(first.getItem() == Items.DIAMOND && first.getCount() == 7, "actual diamond/count");
            first.setCount(1);
            first.getOrCreateTag().putInt("caller", 123);
            ItemStack second = catalog.create(thirdParty, () -> false);
            require(second != first && second.getCount() == 7 && second.getTag() == null,
                    "actual third-party return isolation");
            require(THIRD_PARTY_CALLS.get() == before + 2, "actual registered callback invoked twice");
            require(Arrays.equals(original, thirdParty.value().encoding())
                    && thirdParty.value().content().equals(new ProbeValue(7)), "pure value unchanged");
        }
        checks.add("actual_registry_third_party_owner_type_count_and_value_isolation");

        registrationChecks(checks);
        runtimeChecks(checks);
        return Map.of("checks", List.copyOf(checks), "thirdParty", Map.of(
                "owner", OWNER.toString(), "type", TYPE.toString(), "valueRole", ROLE.toString(),
                "positionRole", POSITION.toString(), "item", "minecraft:diamond", "count", 7,
                "registry", "actual_title_registry"), "newborn", Map.of(
                "nativeStacks", "real_forge_initialized_ItemStack", "execution", "title_owner",
                "capabilityIsolation", "not_asserted", "worldEntered", false));
    }

    private static void registrationChecks(List<String> checks) {
        // Even an empty contribution set must retain both exact kind contracts.
        ExtensionRegistryBuilder missingNativeKind = new ExtensionRegistryBuilder(API, Contribution.Side.COMMON);
        missingNativeKind.define(ValueTypes.KIND);
        activationRejected(missingNativeKind.freeze());
        checks.add("undefined_native_factory_kind_fails_closed");

        ExtensionRegistryBuilder missingValueKind = new ExtensionRegistryBuilder(API, Contribution.Side.COMMON);
        missingValueKind.define(NativeItemFactories.KIND);
        activationRejected(missingValueKind.freeze());
        checks.add("undefined_value_type_kind_fails_closed");

        ExtensionRegistryBuilder wrongNativeKind = new ExtensionRegistryBuilder(API, Contribution.Side.COMMON);
        wrongNativeKind.define(ValueTypes.KIND);
        wrongNativeKind.define(new ExtensionKind<>(NativeItemFactories.KIND.id(), String.class));
        activationRejected(wrongNativeKind.freeze());
        ExtensionRegistryBuilder wrongValueKind = new ExtensionRegistryBuilder(API, Contribution.Side.COMMON);
        wrongValueKind.define(NativeItemFactories.KIND);
        wrongValueKind.define(new ExtensionKind<>(ValueTypes.KIND.id(), String.class));
        activationRejected(wrongValueKind.freeze());
        checks.add("factory_and_value_kind_ids_with_wrong_value_class_fail_closed");

        // A different type ID is never substituted just because its content has the same Class.
        EditorId alien = id("native_item_probe:other_item");
        ExtensionRegistryBuilder foreignBuilder = builder();
        publish(foreignBuilder, alien, goodType());
        ObjectNode alienNode = node(ObjectCatalog.activate(foreignBuilder.freeze()), alien, 5);
        try (NativeItemCatalog catalog = NativeItemCatalog.activate(registry(value -> diamond(value.count())), ROLE)) {
            failure(() -> catalog.create(alienNode, () -> false), "editor:objects/type_unavailable");
        }
        checks.add("same_class_different_type_id_rejected");

        ExtensionRegistryBuilder missing = builder();
        NativeItemFactories.register(missing, TYPE, OWNER, factory(value -> diamond(value.count())));
        ExtensionRegistry missingRegistry = missing.freeze();
        registryProblem(missingRegistry, "missing_contribution");
        require(missingRegistry.find(NativeItemFactories.KIND, TYPE).isEmpty(), "missing codec factory withheld");
        checks.add("missing_value_codec_required_contribution_withheld");

        ExtensionRegistryBuilder absentMetadata = builder();
        publish(absentMetadata, TYPE, goodType());
        absentMetadata.add(NativeItemFactories.KIND, new Contribution(factoryKey(TYPE), OWNER,
                Contribution.Side.COMMON, Set.of()), factory(value -> diamond(value.count())));
        activationFailure(absentMetadata.freeze());
        checks.add("factory_missing_required_value_metadata_activation_failure");

        ExtensionRegistryBuilder duplicate = builder();
        publish(duplicate, TYPE, goodType());
        NativeItemFactories.register(duplicate, TYPE, OWNER, factory(value -> diamond(value.count())));
        NativeItemFactories.register(duplicate, TYPE, OWNER, factory(value -> diamond(value.count())));
        ExtensionRegistry duplicateRegistry = duplicate.freeze();
        registryProblem(duplicateRegistry, "duplicate_contribution");
        require(duplicateRegistry.find(NativeItemFactories.KIND, TYPE).isEmpty(), "duplicate factory withheld");
        try (NativeItemCatalog catalog = NativeItemCatalog.activate(duplicateRegistry, ROLE)) {
            failure(() -> catalog.create(node(ObjectCatalog.activate(duplicateRegistry), TYPE, 3), () -> false),
                    nativeCode("unavailable"));
        }
        checks.add("duplicate_factory_unavailable_without_last_writer_wins");

        ExtensionRegistryBuilder wrongClass = builder();
        publish(wrongClass, TYPE, goodType());
        NativeItemFactories.register(wrongClass, TYPE, OWNER,
                new NativeItemFactory<>(String.class, false, value -> diamond(1)));
        activationFailure(wrongClass.freeze());
        for (ProbeType broken : List.of(new ProbeType(1, POSITION, CODEC, Set.of()),
                new ProbeType(1, ROLE, null, Set.of()))) {
            ExtensionRegistryBuilder bad = builder();
            publish(bad, TYPE, broken);
            NativeItemFactories.register(bad, TYPE, OWNER, factory(value -> diamond(value.count())));
            activationFailure(bad.freeze());
        }
        ExtensionRegistryBuilder nullRole = builder();
        publish(nullRole, TYPE, new ProbeType(1, null, CODEC, Set.of()));
        NativeItemFactories.register(nullRole, TYPE, OWNER, factory(value -> diamond(value.count())));
        try (NativeItemCatalog catalog = NativeItemCatalog.activate(nullRole.freeze(), ROLE)) {
            require(code(catalog.activationFailures().get(TYPE)).equals(id("editor:objects/invalid_node")),
                    "null object role activation diagnostic preserved");
        }
        checks.add("wrong_class_wrong_or_null_role_and_missing_codec_activation_failure");

        ExtensionRegistryBuilder schemaTwo = builder();
        publish(schemaTwo, TYPE, new ProbeType(2, ROLE, CODEC, Set.of()));
        ObjectNode foreignSchema = node(ObjectCatalog.activate(schemaTwo.freeze()), TYPE, 11);
        byte[] retained = foreignSchema.value().encoding();
        try (NativeItemCatalog catalog = NativeItemCatalog.activate(registry(value -> diamond(value.count())), ROLE)) {
            failure(() -> catalog.create(foreignSchema, () -> false), "editor:codec/unsupported_schema");
        }
        require(Arrays.equals(retained, foreignSchema.value().encoding()), "schema failure preserves source encoding");
        checks.add("foreign_schema_two_rejected_by_current_schema_one");

        ExtensionRegistryBuilder foreignRole = builder();
        publish(foreignRole, TYPE, new ProbeType(1, POSITION, CODEC, Set.of()));
        ObjectNode wrongRole = node(ObjectCatalog.activate(foreignRole.freeze()), TYPE, 4);
        try (NativeItemCatalog catalog = NativeItemCatalog.activate(registry(value -> diamond(value.count())), ROLE)) {
            failure(() -> catalog.create(wrongRole, () -> false), nativeCode("invalid_node"));
        }
        checks.add("foreign_node_wrong_value_role_rejected");

        ExtensionRegistryBuilder absentDependency = builder();
        publish(absentDependency, TYPE, new ProbeType(1, ROLE, CODEC,
                Set.of(valueKey(id("native_item_probe:unavailable_dependency")))));
        NativeItemFactories.register(absentDependency, TYPE, OWNER, factory(value -> diamond(value.count())));
        ExtensionRegistry unresolved = absentDependency.freeze();
        try (NativeItemCatalog catalog = NativeItemCatalog.activate(unresolved, ROLE)) {
            failure(() -> catalog.create(node(ObjectCatalog.activate(unresolved), TYPE, 2), () -> false),
                    nativeCode("invalid_node"));
        }
        checks.add("adopted_value_unavailable_contribution_dependency_rejected");
    }

    private static void runtimeChecks(List<String> checks) throws Exception {
        for (Function<ProbeValue, ItemStack> provider : List.<Function<ProbeValue, ItemStack>>of(
                value -> null, value -> { throw new IllegalArgumentException("fixture provider"); })) {
            ExtensionRegistry registry = registry(provider);
            try (NativeItemCatalog catalog = NativeItemCatalog.activate(registry, ROLE)) {
                failure(() -> catalog.create(node(ObjectCatalog.activate(registry), TYPE, 4), () -> false),
                        nativeCode("factory_failed"));
            }
        }
        ExtensionRegistry empty = registry(value -> ItemStack.EMPTY);
        try (NativeItemCatalog catalog = NativeItemCatalog.activate(empty, ROLE)) {
            failure(() -> catalog.create(node(ObjectCatalog.activate(empty), TYPE, 4), () -> false),
                    nativeCode("empty_result"));
        }
        checks.add("null_exception_and_unexpected_empty_provider_results_rejected");

        RuntimeException providerCause = new IllegalStateException("fixture provider cause");
        ExtensionRegistry throwing = registry(value -> { throw providerCause; });
        try (NativeItemCatalog catalog = NativeItemCatalog.activate(throwing, ROLE)) {
            RuntimeException failure = failure(() -> catalog.create(
                    node(ObjectCatalog.activate(throwing), TYPE, 4), () -> false), nativeCode("factory_failed"));
            require(failure.getCause() == providerCause, "provider cause retained");
        }
        checks.add("provider_runtime_failure_preserves_cause");

        ExtensionRegistryBuilder permitted = builder();
        publish(permitted, TYPE, goodType());
        NativeItemFactories.register(permitted, TYPE, OWNER,
                new NativeItemFactory<>(ProbeValue.class, true, value -> ItemStack.EMPTY));
        ExtensionRegistry permittedRegistry = permitted.freeze();
        try (NativeItemCatalog catalog = NativeItemCatalog.activate(permittedRegistry, ROLE)) {
            ObjectNode emptyNode = node(ObjectCatalog.activate(permittedRegistry), TYPE, 1);
            ItemStack first = catalog.create(emptyNode, () -> false);
            ItemStack second = catalog.create(emptyNode, () -> false);
            require(first.isEmpty() && second.isEmpty() && first != second && first != ItemStack.EMPTY
                    && second != ItemStack.EMPTY && first.getCount() == 0 && first.getTag() == null,
                    "permitted empty returns independent canonical stacks");
        }
        ItemStack semanticEmpty = new ItemStack(Items.AIR, 5);
        require(semanticEmpty.isEmpty() && semanticEmpty.getCount() == 0
                && semanticEmpty.getItem() == Items.AIR && semanticEmpty.getTag() == null,
                "AIR five is semantic empty through public observations");
        ExtensionRegistryBuilder semantic = builder();
        publish(semantic, TYPE, goodType());
        NativeItemFactories.register(semantic, TYPE, OWNER,
                new NativeItemFactory<>(ProbeValue.class, true, value -> semanticEmpty));
        ExtensionRegistry semanticRegistry = semantic.freeze();
        try (NativeItemCatalog catalog = NativeItemCatalog.activate(semanticRegistry, ROLE)) {
            ObjectNode emptyNode = node(ObjectCatalog.activate(semanticRegistry), TYPE, 1);
            ItemStack first = catalog.create(emptyNode, () -> false);
            ItemStack second = catalog.create(emptyNode, () -> false);
            require(first != semanticEmpty && second != semanticEmpty && first != second
                    && first != ItemStack.EMPTY && second != ItemStack.EMPTY
                    && first.isEmpty() && second.isEmpty() && first.getCount() == 0 && second.getCount() == 0
                    && first.getItem() == Items.AIR && second.getItem() == Items.AIR
                    && first.getTag() == null && second.getTag() == null,
                    "semantic AIR five normalizes to independent public AIR zero results");
        }
        // Hidden raw count is deliberately neither reflected nor serialized for this contract.
        checks.add("permitted_semantic_air_five_normalizes_without_hidden_count_authentication");
        for (Function<ProbeValue, ItemStack> invalidEmpty : List.<Function<ProbeValue, ItemStack>>of(
                value -> null, value -> {
                    ItemStack result = new ItemStack(Items.AIR, 0);
                    result.setTag(new CompoundTag()); return result;
                })) {
            ExtensionRegistryBuilder invalid = builder();
            publish(invalid, TYPE, goodType());
            NativeItemFactories.register(invalid, TYPE, OWNER,
                    new NativeItemFactory<>(ProbeValue.class, true, invalidEmpty));
            ExtensionRegistry invalidRegistry = invalid.freeze();
            try (NativeItemCatalog catalog = NativeItemCatalog.activate(invalidRegistry, ROLE)) {
                failure(() -> catalog.create(node(ObjectCatalog.activate(invalidRegistry), TYPE, 1), () -> false),
                        nativeCode("factory_failed"));
            }
        }
        checks.add("permitted_empty_is_fresh_and_canonical_null_and_tagged_empty_rejected");

        ItemStack borrowed = diamond(9);
        CompoundTag nested = new CompoundTag(); nested.putInt("value", 17);
        borrowed.getOrCreateTag().put("nested", nested);
        ExtensionRegistry shared = registry(value -> borrowed);
        ObjectNode sharedNode = node(ObjectCatalog.activate(shared), TYPE, 9);
        try (NativeItemCatalog catalog = NativeItemCatalog.activate(shared, ROLE)) {
            ItemStack first = catalog.create(sharedNode, () -> false);
            ItemStack second = catalog.create(sharedNode, () -> false);
            require(first != borrowed && second != borrowed && first != second, "distinct stack copies");
            require(first.getTag() != borrowed.getTag() && first.getTag() != second.getTag(), "distinct outer tags");
            first.getTag().getCompound("nested").putInt("value", 99); first.setCount(2);
            require(second.getCount() == 9 && borrowed.getCount() == 9
                    && second.getTag().getCompound("nested").getInt("value") == 17
                    && borrowed.getTag().getCompound("nested").getInt("value") == 17, "deep tag/count isolation");
            AtomicInteger checkpoint = new AtomicInteger();
            failure(() -> catalog.create(sharedNode, () -> {
                if (checkpoint.incrementAndGet() == 4) {
                    borrowed.getTag().getCompound("nested").putInt("value", 23);
                }
                return false;
            }), nativeCode("factory_failed"));
            require(checkpoint.get() == 4 && borrowed.getTag().getCompound("nested").getInt("value") == 23,
                    "last callback actually mutated borrowed nested tag");
            borrowed.getTag().getCompound("nested").putInt("value", 17);
            checkpoint.set(0);
            failure(() -> catalog.create(sharedNode, () -> {
                if (checkpoint.incrementAndGet() == 3) {
                    borrowed.getTag().getCompound("nested").putInt("value", 31);
                }
                return false;
            }), nativeCode("factory_failed"));
            require(checkpoint.get() == 3, "borrowed mutation detected before copying");
            borrowed.getTag().getCompound("nested").putInt("value", 17);
            require(catalog.create(sharedNode, () -> false).getCount() == 9, "failure clears busy");
        }
        checks.add("shared_stack_deep_tag_isolation_and_final_callback_snapshot_guard");

        AtomicInteger calls = new AtomicInteger();
        ExtensionRegistry counted = registry(value -> { calls.incrementAndGet(); return diamond(value.count()); });
        ObjectNode node = node(ObjectCatalog.activate(counted), TYPE, 6);
        try (NativeItemCatalog catalog = NativeItemCatalog.activate(counted, ROLE)) {
            failure(() -> catalog.create(node, () -> true), nativeCode("cancelled"));
            require(calls.get() == 0, "entry cancel must not invoke provider");
            AtomicInteger checkpoint = new AtomicInteger();
            failure(() -> catalog.create(node, () -> checkpoint.incrementAndGet() == 3), nativeCode("cancelled"));
            require(calls.get() == 1, "post provider cancellation actually follows construction");
            RuntimeException callbackCause = new IllegalStateException("fixture callback");
            RuntimeException callbackFailure = failure(() -> catalog.create(node, () -> { throw callbackCause; }),
                    nativeCode("factory_failed"));
            require(callbackFailure.getCause() == callbackCause, "callback cause preserved");
            RuntimeException reentrant = failure(() -> catalog.create(node, () -> {
                catalog.create(node, () -> false); return false;
            }), nativeCode("factory_failed"));
            require(reentrant.getCause() instanceof NativeItemException, "reentry cause typed");
            require(((NativeItemException) reentrant.getCause()).problem().code().equals(id(nativeCode("busy"))),
                    "reentry cause busy");
            require(catalog.create(node, () -> false).getCount() == 6, "callback failures clear busy");
        }
        checks.add("entry_and_post_factory_cancellation_callback_cause_reentry_and_recovery");

        NativeItemCatalog closing = NativeItemCatalog.activate(counted, ROLE);
        try {
            failure(() -> closing.create(node, () -> { closing.close(); return true; }), nativeCode("closed"));
            failure(() -> closing.create(node, () -> false), nativeCode("closed"));
            closing.close(); closing.close();
        } finally { closing.close(); }
        checks.add("callback_close_prioritizes_typed_closed_and_close_is_idempotent");

        try (NativeItemCatalog catalog = NativeItemCatalog.activate(counted, ROLE)) {
            failure(() -> catalog.create(null, () -> false), nativeCode("invalid_node"));
            failure(() -> catalog.create(node, null), nativeCode("invalid_node"));
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    failure(() -> catalog.create(node, () -> false), nativeCode("wrong_thread"));
                    failure(catalog::close, nativeCode("wrong_thread"));
                } catch (Throwable failure) { workerFailure.set(failure); }
            }, "native-item-contract-owner-check");
            worker.setDaemon(true);
            try {
                worker.start(); worker.join(5000);
                require(!worker.isAlive(), "owner check worker finishes within timeout");
                if (workerFailure.get() != null) throw new AssertionError("owner check worker", workerFailure.get());
            } finally {
                if (worker.isAlive()) { worker.interrupt(); worker.join(1000); }
            }
            require(catalog.create(node, () -> false).getCount() == 6, "off-owner close leaves owner open");
        }
        checks.add("invalid_inputs_off_owner_create_and_close_leave_owner_usable");
    }

    private static void activationRejected(ExtensionRegistry registry) {
        failure(() -> {
            try (NativeItemCatalog catalog = NativeItemCatalog.activate(registry, ROLE)) {
                // Unexpected activation success still closes the catalog before failure() rejects it.
                require(catalog.activationFailures().isEmpty(), "empty malformed-kind registry");
            }
        }, nativeCode("invalid_registration"));
    }

    private static void activationFailure(ExtensionRegistry registry) {
        try (NativeItemCatalog catalog = NativeItemCatalog.activate(registry, ROLE)) {
            RuntimeException failure = catalog.activationFailures().get(TYPE);
            require(failure != null, "invalid registration must be an activation failure");
            require(code(failure).equals(id(nativeCode("invalid_registration"))), "typed activation failure");
            try { catalog.activationFailures().clear(); throw new AssertionError("mutable failure map"); }
            catch (UnsupportedOperationException expected) { /* immutable diagnostic snapshot */ }
        }
    }

    private static void registryProblem(ExtensionRegistry registry, String reason) {
        require(registry.metadata().problems().stream().anyMatch(problem ->
                problem.code().equals(id("editor:registry/" + reason))), "registry problem " + reason);
    }

    private static RuntimeException failure(Runnable operation, String expected) {
        try { operation.run(); }
        catch (RuntimeException failure) {
            require(code(failure).equals(id(expected)), "expected problem code " + expected);
            return failure;
        }
        throw new AssertionError("expected failure " + expected);
    }

    private static EditorId code(RuntimeException failure) {
        Problem problem;
        if (failure instanceof NativeItemException nativeFailure) problem = nativeFailure.problem();
        else if (failure instanceof DocumentCodecException codecFailure) problem = codecFailure.problems().get(0);
        else throw new AssertionError("failure lacks typed Problem", failure);
        require(problem.severity() == Problem.Severity.ERROR, "typed failure severity");
        return problem.code();
    }

    private static ExtensionRegistryBuilder builder() {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, Contribution.Side.COMMON);
        builder.define(ValueTypes.KIND); builder.define(NativeItemFactories.KIND);
        builder.addModule(new ModuleSpec(OWNER, "1.0.0", API, Set.of(), Set.of()));
        return builder;
    }

    private static ExtensionRegistry registry(Function<ProbeValue, ItemStack> provider) {
        ExtensionRegistryBuilder builder = builder();
        publish(builder, TYPE, goodType());
        NativeItemFactories.register(builder, TYPE, OWNER, factory(provider));
        ExtensionRegistry registry = builder.freeze();
        require(registry.metadata().problems().isEmpty(), "healthy isolated registry");
        return registry;
    }

    private static NativeItemFactory<ProbeValue> factory(Function<ProbeValue, ItemStack> provider) {
        return new NativeItemFactory<>(ProbeValue.class, false, provider);
    }

    private static ProbeType goodType() { return new ProbeType(1, ROLE, CODEC, Set.of()); }
    private static void publish(ExtensionRegistryBuilder builder, EditorId type, ProbeType implementation) {
        builder.add(ValueTypes.KIND, new Contribution(valueKey(type), OWNER,
                Contribution.Side.COMMON, Set.of()), implementation);
    }
    private static ObjectNode node(ObjectCatalog catalog, EditorId type, int count) {
        return catalog.capture(UUID.randomUUID(), Optional.empty(), POSITION, type,
                ProbeValue.class, new ProbeValue(count));
    }
    private static ItemStack diamond(int count) { return new ItemStack(Items.DIAMOND, count); }
    private static ContributionKey valueKey(EditorId type) { return new ContributionKey(ValueTypes.KIND.id(), type); }
    private static ContributionKey factoryKey(EditorId type) { return new ContributionKey(NativeItemFactories.KIND.id(), type); }
    private static EditorId id(String value) { return EditorId.parse(value); }
    private static String nativeCode(String reason) { return "editor:native_items/" + reason; }
    private static void require(boolean condition, String check) { if (!condition) throw new AssertionError(check); }

    private record ProbeValue(int count) {
        private ProbeValue { if (count < 1 || count > 127) throw new IllegalArgumentException("count"); }
    }

    /** Four exact big-endian bytes; configurable descriptors are only for isolated negative cases. */
    private record ProbeType(int schemaVersion, EditorId role, EditorId codecId,
            Set<ContributionKey> contributionDependencies) implements ObjectType<ProbeValue> {
        @Override public Class<ProbeValue> valueType() { return ProbeValue.class; }
        @Override public Set<Integer> readableSchemas() { return Set.of(schemaVersion); }
        @Override public ProbeValue decode(int schema, byte[] encoding) {
            if (schema != schemaVersion || encoding.length != Integer.BYTES) {
                throw new IllegalArgumentException("strict probe schema or byte length");
            }
            return new ProbeValue(ByteBuffer.wrap(encoding).getInt());
        }
        @Override public ProbeValue snapshot(ProbeValue value) { return new ProbeValue(value.count()); }
        @Override public List<Problem> validate(ProbeValue value) { return List.of(); }
        @Override public byte[] encode(ProbeValue value) {
            return ByteBuffer.allocate(Integer.BYTES).putInt(value.count()).array();
        }
        @Override public DocumentDependencies dependencies(ProbeValue value) {
            return new DocumentDependencies(Set.of(), Set.of(), contributionDependencies);
        }
    }
}
