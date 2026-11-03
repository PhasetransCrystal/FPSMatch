package net.ptcrys.fpsmatch.editor.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Contract tests for the strongly typed identities and target references of the world editor
 * rewrite (contract R1, section 1). Tests never assert on exact message text, only on the thrown
 * exception type and on behavioural results.
 */
class IdentityContractTest {

    private static final UUID SAMPLE = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID OTHER = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final String SAMPLE_WIRE = "123e4567-e89b-12d3-a456-426614174000";
    private static final String OTHER_WIRE = "0f8fad5b-d9cb-469f-a165-70867728950e";

    // ---------------------------------------------------------------- identity type registry

    /** Every nested record of {@link Ids} that implements {@link Ids.TypedId}. */
    private static List<Class<?>> identityTypes() {
        List<Class<?>> types = new ArrayList<>();
        for (Class<?> nested : Ids.class.getDeclaredClasses()) {
            if (Ids.TypedId.class.isAssignableFrom(nested) && nested.isRecord()) {
                types.add(nested);
            }
        }
        return types;
    }

    private static Object construct(Class<?> type, UUID value) {
        Constructor<?> ctor;
        try {
            ctor = type.getDeclaredConstructor(UUID.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("missing (UUID) constructor on " + type.getSimpleName(), e);
        }
        try {
            return ctor.newInstance(value);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("cannot construct " + type.getSimpleName(), cause);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot construct " + type.getSimpleName(), e);
        }
    }

    private static Object parse(Class<?> type, String wire) {
        Method parse;
        try {
            parse = type.getDeclaredMethod("parse", String.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("missing parse(String) on " + type.getSimpleName(), e);
        }
        assertTrue(Modifier.isStatic(parse.getModifiers()), type.getSimpleName() + ".parse must be static");
        assertSame(type, parse.getReturnType(), type.getSimpleName() + ".parse must return its own type");
        assertTrue(Modifier.isPublic(parse.getModifiers()), type.getSimpleName() + ".parse must be public");
        try {
            return parse.invoke(null, wire);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(type.getSimpleName() + ".parse threw a checked exception", cause);
        } catch (IllegalAccessException e) {
            throw new AssertionError("cannot call " + type.getSimpleName() + ".parse", e);
        }
    }

    private static Object valueOf(Object typedId) {
        try {
            Method value = Ids.TypedId.class.getMethod("value");
            return value.invoke(typedId);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot read value()", e);
        }
    }

    private static String wireOf(Object typedId) {
        try {
            Method wire = Ids.TypedId.class.getMethod("wire");
            return (String) wire.invoke(typedId);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot read wire()", e);
        }
    }

    // ---------------------------------------------------------------- container shape

    @Test
    void idsContainerIsFinalAndNotInstantiable() {
        assertTrue(Modifier.isFinal(Ids.class.getModifiers()), "Ids must be final");
        Constructor<?>[] ctors = Ids.class.getDeclaredConstructors();
        assertEquals(1, ctors.length, "Ids must declare exactly one constructor");
        assertTrue(Modifier.isPrivate(ctors[0].getModifiers()), "Ids constructor must be private");
        assertTrue(Ids.TypedId.class.isInterface(), "TypedId must be an interface");
    }

    @Test
    void exactlyThirteenTypedIdentitiesAreDeclared() {
        List<String> simpleNames = new ArrayList<>();
        for (Class<?> type : identityTypes()) {
            simpleNames.add(type.getSimpleName());
        }
        assertEquals(13, simpleNames.size(), "expected 13 typed ids, got " + simpleNames);
        for (String expected : List.of("WorldId", "DefinitionId", "TeamId", "ObjectId", "InstanceId",
                "IncarnationId", "RunId", "RequestId", "ConnectionId", "DocumentId", "GestureId",
                "OperationId", "SubscriptionId")) {
            assertTrue(simpleNames.contains(expected), "missing typed id " + expected);
            Class<?> type = null;
            for (Class<?> candidate : identityTypes()) {
                if (candidate.getSimpleName().equals(expected)) {
                    type = candidate;
                }
            }
            assertNotNull(type);
            try {
                Constructor<?> ctor = type.getDeclaredConstructor(UUID.class);
                assertEquals(1, ctor.getParameterCount(), expected + " must have a single UUID component");
                assertEquals(UUID.class, ctor.getParameterTypes()[0]);
            } catch (NoSuchMethodException e) {
                throw new AssertionError(expected + " must declare a (UUID) canonical constructor", e);
            }
        }
    }

    // ---------------------------------------------------------------- per type: parse/construct

    @Test
    void parseAcceptsFullLowerAndUpperWireForEveryType() {
        for (Class<?> type : identityTypes()) {
            String name = type.getSimpleName();

            Object fromLower = parse(type, SAMPLE_WIRE);
            assertEquals(SAMPLE, valueOf(fromLower), name + ": lower wire must round-trip the UUID");
            assertEquals(SAMPLE_WIRE, wireOf(fromLower), name + ": wire must be canonical lower case");

            Object fromUpper = parse(type, SAMPLE_WIRE.toUpperCase(Locale.ROOT));
            assertEquals(fromLower, fromUpper, name + ": upper wire must parse to the same identity");
            assertEquals(SAMPLE_WIRE, wireOf(fromUpper), name + ": wire must be lower case even for upper input");
            assertEquals(fromUpper.hashCode(), fromLower.hashCode(), name + ": equal ids must share hashCode");

            Object direct = construct(type, SAMPLE);
            assertEquals(fromLower, direct, name + ": constructor and parse must agree");
            assertEquals(SAMPLE_WIRE, wireOf(direct), name + ": constructed wire must be lower case");
        }
    }

    @Test
    void malformedWireIsRejectedForEveryType() {
        String shortForm = "123e4567e89b12d3a456426614174000";
        String hyphenShortForm = "1-1-1-1-1";
        String nonHexGroup = "123e4567-e89b-12d3-a456-42661417400g";
        String blankGroup = "123e4567-e89b-12d3-a456-42661417400-";
        String paddedGroups = "123e4567-00000-0000-0000-00000000000e";
        String tooLong = SAMPLE_WIRE + "0";
        String tooShort = SAMPLE_WIRE.substring(0, 35);
        String leadingSpace = " " + SAMPLE_WIRE;
        String trailingSpace = SAMPLE_WIRE + " ";
        String bothSpaces = " " + SAMPLE_WIRE + " ";

        for (Class<?> type : identityTypes()) {
            String name = type.getSimpleName();
            assertEquals(36, SAMPLE_WIRE.length());

            assertThrows(IllegalArgumentException.class, () -> parse(type, null), name + ": null wire");
            assertThrows(IllegalArgumentException.class, () -> parse(type, ""), name + ": empty wire");
            assertThrows(IllegalArgumentException.class, () -> parse(type, " "), name + ": blank wire");
            assertThrows(IllegalArgumentException.class, () -> parse(type, shortForm), name + ": 32 char short form");
            assertThrows(IllegalArgumentException.class, () -> parse(type, hyphenShortForm), name + ": 1-1-1-1-1 form");
            assertThrows(IllegalArgumentException.class, () -> parse(type, nonHexGroup), name + ": non hex group");
            assertThrows(IllegalArgumentException.class, () -> parse(type, blankGroup), name + ": malformed group");
            assertThrows(IllegalArgumentException.class, () -> parse(type, paddedGroups), name + ": non canonical groups");
            assertThrows(IllegalArgumentException.class, () -> parse(type, tooLong), name + ": 37 chars");
            assertThrows(IllegalArgumentException.class, () -> parse(type, tooShort), name + ": 35 chars");
            assertThrows(IllegalArgumentException.class, () -> parse(type, leadingSpace), name + ": leading space");
            assertThrows(IllegalArgumentException.class, () -> parse(type, trailingSpace), name + ": trailing space");
            assertThrows(IllegalArgumentException.class, () -> parse(type, bothSpaces), name + ": surrounding spaces");
            assertThrows(IllegalArgumentException.class, () -> parse(type, "00000000-0000-0000-0000-00000000000\n"),
                    name + ": newline suffix");

            assertThrows(IllegalArgumentException.class, () -> construct(type, null), name + ": null value");
        }
    }

    @Test
    void parseNeverGeneratesAnIdentityAndNeverTrims() {
        for (Class<?> type : identityTypes()) {
            String name = type.getSimpleName();
            Object parsed = parse(type, SAMPLE_WIRE.toUpperCase(Locale.ROOT));
            assertNotEquals(construct(type, OTHER), parsed, name + ": parse must not invent a different identity");
            assertEquals(SAMPLE, valueOf(parsed), name + ": parse must keep the requested identity");
            assertEquals(SAMPLE_WIRE, wireOf(parse(type, wireOf(parsed))), name + ": wire must be parseable again");
        }
    }

    @Test
    void identitiesWithSameUuidButDifferentTypesAreNeverEqual() {
        Set<Object> set = new HashSet<>();
        for (Class<?> type : identityTypes()) {
            Object lower = parse(type, SAMPLE_WIRE);
            Object upper = parse(type, SAMPLE_WIRE.toUpperCase(Locale.ROOT));
            assertTrue(set.add(lower) || set.contains(lower), "duplicate element handling");
            set.add(upper);
        }
        assertEquals(13, set.size(), "same UUID across 13 identity types must yield 13 distinct elements");

        Object worldId = parse(Ids.WorldId.class, SAMPLE_WIRE);
        Object definitionId = parse(Ids.DefinitionId.class, SAMPLE_WIRE);
        Object instanceId = parse(Ids.InstanceId.class, SAMPLE_WIRE);
        assertNotEquals(worldId, definitionId, "WorldId must not equal DefinitionId for the same UUID");
        assertNotEquals(worldId, instanceId, "WorldId must not equal InstanceId for the same UUID");
        assertNotEquals(definitionId, instanceId, "DefinitionId must not equal InstanceId for the same UUID");

        assertNotEquals(Ids.WorldId.parse(SAMPLE_WIRE), Ids.WorldId.parse(OTHER_WIRE),
                "different UUIDs of the same type must differ");
        assertFalse(Ids.WorldId.parse(SAMPLE_WIRE).equals(null));
        assertEquals(Ids.WorldId.parse(SAMPLE_WIRE), Ids.WorldId.parse(SAMPLE_WIRE));
    }

    @Test
    void typedIdValueAndWireAreConsistentForEachType() {
        for (Class<?> type : identityTypes()) {
            String name = type.getSimpleName();
            Object typed = parse(type, SAMPLE_WIRE);
            assertTrue(Ids.TypedId.class.isInstance(typed), name + " must implement TypedId");
            assertTrue(valueOf(typed) instanceof UUID, name + ": value() must be a UUID");
            assertEquals(SAMPLE, valueOf(typed), name + ": value() must be the parsed UUID");
            assertEquals(SAMPLE_WIRE, wireOf(typed), name + ": wire() must be canonical lower case");
            assertEquals(valueOf(typed).toString(), wireOf(typed), name + ": default wire() is UUID.toString()");
        }
    }

    // ---------------------------------------------------------------- refs: WorldRef

    @Test
    void worldRefAcceptsLegalDimensions() {
        Ids.WorldId worldId = Ids.WorldId.parse(SAMPLE_WIRE);
        for (String dimension : List.of(
                "minecraft:overworld",
                "minecraft:the_nether",
                "blockoffensive:cs/map1",
                "a:0",
                "a.b-c_d:0-9_./-",
                "minecraft:path/sub.path",
                "0:0")) {
            WorldRef ref = new WorldRef(worldId, dimension);
            assertEquals(dimension, ref.dimensionId(), "dimension must be kept verbatim: " + dimension);
            assertEquals(worldId, ref.worldId());
        }
    }

    @Test
    void worldRefRejectsMalformedDimensions() {
        Ids.WorldId worldId = Ids.WorldId.parse(SAMPLE_WIRE);
        for (String bad : List.of(
                "",
                ":overworld",
                "minecraft:",
                "overworld",
                "a:b:c",
                "minecraft:over:world",
                "Minecraft:overworld",
                "minecraft:Overworld",
                "minecraft:over world",
                "mine craft:overworld",
                " minecraft:overworld",
                "minecraft:overworld ",
                "minecraft:overw\u00f6rld",
                "minecraft:over\tabc",
                "a/b:c",
                "minecraft:over+world",
                "minecraft:over*world",
                "minecraft:over'world",
                "minecraft:over\"world",
                "minecraft:over:world/",
                "a b:overworld",
                "minecraft:overworld\n")) {
            assertThrows(IllegalArgumentException.class, () -> new WorldRef(worldId, bad),
                    "dimension must be rejected: " + bad.replace("\n", "\\n"));
        }
        assertThrows(IllegalArgumentException.class, () -> new WorldRef(worldId, null), "null dimension");
        assertThrows(IllegalArgumentException.class, () -> new WorldRef(null, "minecraft:overworld"), "null worldId");
    }

    @Test
    void worldRefDimensionLengthBoundaryIs191() {
        Ids.WorldId worldId = Ids.WorldId.parse(SAMPLE_WIRE);
        String namespace = "minecraft";
        String path191 = "a".repeat(191 - namespace.length() - 1);
        String dimension191 = namespace + ":" + path191;
        assertEquals(191, dimension191.length());
        assertEquals(dimension191, new WorldRef(worldId, dimension191).dimensionId(),
                "191 character dimension must be accepted");

        String dimension192 = namespace + ":" + path191 + "a";
        assertEquals(192, dimension192.length());
        assertThrows(IllegalArgumentException.class, () -> new WorldRef(worldId, dimension192),
                "192 character dimension must be rejected");

        String longNamespace191 = "n".repeat(191 - 1 - 1) + ":" + "b";
        assertEquals(191, longNamespace191.length());
        assertEquals(longNamespace191, new WorldRef(worldId, longNamespace191).dimensionId(),
                "191 character dimension with a long namespace must be accepted");

        assertThrows(IllegalArgumentException.class, () -> new WorldRef(worldId, "n".repeat(191) + ":" + "b"),
                "namespace longer than the dimension limit must be rejected");
        assertThrows(IllegalArgumentException.class, () -> new WorldRef(worldId, "a:" + "b".repeat(190)),
                "path longer than the dimension limit must be rejected");
    }

    @Test
    void worldRefDoesNotDefaultNamespaceAndDoesNotTrim() {
        Ids.WorldId worldId = Ids.WorldId.parse(SAMPLE_WIRE);
        assertThrows(IllegalArgumentException.class, () -> new WorldRef(worldId, "overworld"),
                "namespace must never be filled in implicitly");
        assertThrows(IllegalArgumentException.class, () -> new WorldRef(worldId, " minecraft:overworld"),
                "dimension must never be trimmed");
        WorldRef ref = new WorldRef(worldId, "minecraft:overworld");
        assertEquals("minecraft:overworld", ref.dimensionId());
        assertNotEquals("overworld", ref.dimensionId());
    }

    @Test
    void worldRefEqualityUsesWorldAndDimension() {
        Ids.WorldId firstWorld = Ids.WorldId.parse(SAMPLE_WIRE);
        Ids.WorldId secondWorld = Ids.WorldId.parse(OTHER_WIRE);
        WorldRef a = new WorldRef(firstWorld, "minecraft:overworld");
        WorldRef b = new WorldRef(Ids.WorldId.parse(SAMPLE_WIRE), "minecraft:overworld");
        WorldRef c = new WorldRef(firstWorld, "minecraft:the_nether");
        WorldRef d = new WorldRef(secondWorld, "minecraft:overworld");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c, "same world with a different dimension must differ");
        assertNotEquals(a, d, "same dimension in a different world must differ");
    }

    // ---------------------------------------------------------------- refs: DefinitionRef

    @Test
    void definitionRefBehavesAsScopedTarget() {
        Ids.WorldId world = Ids.WorldId.parse(SAMPLE_WIRE);
        Ids.WorldId otherWorld = Ids.WorldId.parse(OTHER_WIRE);
        Ids.DefinitionId definition = Ids.DefinitionId.parse(SAMPLE_WIRE);
        Ids.DefinitionId otherDefinition = Ids.DefinitionId.parse(OTHER_WIRE);

        DefinitionRef ref = new DefinitionRef(world, definition);
        assertEquals(world, ref.worldId());
        assertEquals(definition, ref.definitionId());
        assertEquals(ref, new DefinitionRef(Ids.WorldId.parse(SAMPLE_WIRE), Ids.DefinitionId.parse(SAMPLE_WIRE)));
        assertEquals(ref.hashCode(), new DefinitionRef(world, definition).hashCode());

        assertNotEquals(ref, new DefinitionRef(otherWorld, definition),
                "same DefinitionId in a different world must not be equal");
        assertNotEquals(ref, new DefinitionRef(world, otherDefinition));

        assertThrows(IllegalArgumentException.class, () -> new DefinitionRef(null, definition), "null worldId");
        assertThrows(IllegalArgumentException.class, () -> new DefinitionRef(world, null), "null definitionId");
    }

    // ---------------------------------------------------------------- refs: InstanceRef

    @Test
    void instanceRefDistinguishesDimensionAndIncarnation() {
        WorldRef world = new WorldRef(Ids.WorldId.parse(SAMPLE_WIRE), "minecraft:overworld");
        WorldRef otherDimension = new WorldRef(Ids.WorldId.parse(SAMPLE_WIRE), "minecraft:the_nether");
        WorldRef otherWorld = new WorldRef(Ids.WorldId.parse(OTHER_WIRE), "minecraft:overworld");
        Ids.InstanceId instanceId = Ids.InstanceId.parse(SAMPLE_WIRE);
        Ids.IncarnationId incarnation = Ids.IncarnationId.parse(SAMPLE_WIRE);
        Ids.IncarnationId otherIncarnation = Ids.IncarnationId.parse(OTHER_WIRE);

        InstanceRef ref = new InstanceRef(world, instanceId, incarnation);
        assertEquals(world, ref.world());
        assertEquals(instanceId, ref.instanceId());
        assertEquals(incarnation, ref.incarnationId());
        assertEquals(ref, new InstanceRef(new WorldRef(Ids.WorldId.parse(SAMPLE_WIRE), "minecraft:overworld"),
                Ids.InstanceId.parse(SAMPLE_WIRE), Ids.IncarnationId.parse(SAMPLE_WIRE)));

        assertNotEquals(ref, new InstanceRef(otherDimension, instanceId, incarnation),
                "same InstanceId in a different dimension must not be equal");
        assertNotEquals(ref, new InstanceRef(otherWorld, instanceId, incarnation),
                "same InstanceId in a different world must not be equal");
        assertNotEquals(ref, new InstanceRef(world, instanceId, otherIncarnation),
                "same InstanceId with a different incarnation must not be equal");
        assertNotEquals(ref, new InstanceRef(world, Ids.InstanceId.parse(OTHER_WIRE), incarnation));

        Set<Object> set = new HashSet<>();
        set.add(ref);
        set.add(new InstanceRef(otherDimension, instanceId, incarnation));
        set.add(new InstanceRef(otherWorld, instanceId, incarnation));
        set.add(new InstanceRef(world, instanceId, otherIncarnation));
        assertEquals(4, set.size(), "instance targets must stay distinct inside a set");

        assertThrows(IllegalArgumentException.class, () -> new InstanceRef(null, instanceId, incarnation), "null world");
        assertThrows(IllegalArgumentException.class, () -> new InstanceRef(world, null, incarnation), "null instanceId");
        assertThrows(IllegalArgumentException.class, () -> new InstanceRef(world, instanceId, null), "null incarnation");
    }

    @Test
    void instanceRefRejectsMalformedDimensionThroughWorldRef() {
        Ids.WorldId worldId = Ids.WorldId.parse(SAMPLE_WIRE);
        assertThrows(IllegalArgumentException.class,
                () -> new InstanceRef(new WorldRef(worldId, "overworld"), Ids.InstanceId.parse(SAMPLE_WIRE),
                        Ids.IncarnationId.parse(SAMPLE_WIRE)));
    }

    // ---------------------------------------------------------------- refs: RunRef

    @Test
    void runRefDistinguishesRunIdAndInstance() {
        InstanceRef instance = new InstanceRef(
                new WorldRef(Ids.WorldId.parse(SAMPLE_WIRE), "minecraft:overworld"),
                Ids.InstanceId.parse(SAMPLE_WIRE), Ids.IncarnationId.parse(SAMPLE_WIRE));
        InstanceRef otherInstance = new InstanceRef(
                new WorldRef(Ids.WorldId.parse(SAMPLE_WIRE), "minecraft:overworld"),
                Ids.InstanceId.parse(SAMPLE_WIRE), Ids.IncarnationId.parse(OTHER_WIRE));
        Ids.RunId runId = Ids.RunId.parse(SAMPLE_WIRE);

        RunRef ref = new RunRef(instance, runId);
        assertEquals(instance, ref.instance());
        assertEquals(runId, ref.runId());
        assertEquals(ref, new RunRef(instance, Ids.RunId.parse(SAMPLE_WIRE)));
        assertEquals(ref.hashCode(), new RunRef(instance, runId).hashCode());

        assertNotEquals(ref, new RunRef(instance, Ids.RunId.parse(OTHER_WIRE)),
                "different RunId must not be equal");
        assertNotEquals(ref, new RunRef(otherInstance, runId),
                "same RunId on a different incarnation must not be equal");

        Set<Object> set = new HashSet<>();
        set.add(ref);
        set.add(new RunRef(instance, Ids.RunId.parse(OTHER_WIRE)));
        set.add(new RunRef(otherInstance, runId));
        assertEquals(3, set.size(), "run targets must stay distinct inside a set");

        assertThrows(IllegalArgumentException.class, () -> new RunRef(null, runId), "null instance");
        assertThrows(IllegalArgumentException.class, () -> new RunRef(instance, null), "null runId");
    }

    // ---------------------------------------------------------------- cross type probes

    @Test
    void refIdentitiesAreNeverMergedAcrossWorldAndType() {
        String uuid = SAMPLE_WIRE;
        WorldRef worldA = new WorldRef(Ids.WorldId.parse(uuid), "minecraft:overworld");
        WorldRef worldB = new WorldRef(Ids.WorldId.parse(uuid), "minecraft:the_nether");

        DefinitionRef definitionInA = new DefinitionRef(worldA.worldId(), Ids.DefinitionId.parse(uuid));
        DefinitionRef definitionInB = new DefinitionRef(worldB.worldId(), Ids.DefinitionId.parse(uuid));
        assertEquals(definitionInA, definitionInB, "definition refs are scoped by world id, not by dimension");

        InstanceRef instanceInA = new InstanceRef(worldA, Ids.InstanceId.parse(uuid), Ids.IncarnationId.parse(uuid));
        InstanceRef instanceInB = new InstanceRef(worldB, Ids.InstanceId.parse(uuid), Ids.IncarnationId.parse(uuid));
        assertNotEquals(instanceInA, instanceInB, "instance refs include their world reference");

        Set<Object> all = new HashSet<>();
        all.add(worldA);
        all.add(worldB);
        all.add(definitionInA);
        all.add(instanceInA);
        all.add(new RunRef(instanceInA, Ids.RunId.parse(uuid)));
        all.add(Ids.WorldId.parse(uuid));
        all.add(Ids.DefinitionId.parse(uuid));
        assertEquals(7, all.size(), "refs and ids must not collapse into each other");
    }
}