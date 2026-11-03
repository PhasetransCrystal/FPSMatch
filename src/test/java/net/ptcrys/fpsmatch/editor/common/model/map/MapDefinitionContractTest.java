package net.ptcrys.fpsmatch.editor.common.model.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.model.Ids;
import net.ptcrys.fpsmatch.editor.common.model.geometry.BoxCorners;
import net.ptcrys.fpsmatch.editor.common.model.geometry.IntPosition;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Orientation;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Formal contract tests for the R05.3 aggregate {@link MapDefinition}.
 *
 * <p><strong>What this class is.</strong> These are the formal, focused JUnit tests of the single
 * immutable candidate definition. They cover exactly the vectors the task card fixes: full member
 * retention and the standard record value semantics, the scalar rules inherited from the frozen
 * {@code MapScalars}, the four collection budgets at the limit and one past it together with the
 * early guard that refuses a lying size before any element is touched, the honest mismatch between a
 * declared size and what a traversal really yields, the defence copy that isolates the definition
 * from a later mutation of the caller's list, the unmodifiability of the published lists, the three
 * identity domains (team uniqueness, the shared spawn and region object domain, and the deliberate
 * non collision of distinct typed ids with equal UUIDs), the resolved team and dimension references,
 * the retention of repeated and same-UUID resource references, the retention of every frozen JSON
 * shape, the per position map extras, and the payload lower bound including its per occurrence
 * semantics, its empty extras zero contribution, its 64 MiB rejection of a large but individually
 * legal payload, and a thousand sample deterministic oracle.</p>
 *
 * <p><strong>What this class is not.</strong> A green run here is author side evidence only. It is
 * not the P05-MAP gate, which only the primary agent can grant, and it proves nothing about the
 * complete P05 milestone beyond this point. Nothing here claims wire round-tripping, codec merge,
 * reserved key handling, field name or punctuation or depth accounting, provider schema validation,
 * storage, a resource library, a running map or a mature UI. The payload lower bound checked by the
 * production type is deliberately not the complete wire budget: that belongs to the single formal
 * codec of R06.1, so no test here fabricates a passing result for an exact 8 MiB complete wire.</p>
 *
 * <p><strong>Oracle independence.</strong> The thousand sample oracle builds its own expected id
 * sets, team reference verdicts and dimension verdicts <em>before</em> the production constructor is
 * called, from a fixed seed, and then compares the observed accept or reject outcome and the
 * observed input order against that pre-computed expectation. No sample is selected, filtered or
 * discarded on the basis of what production answered, so the oracle cannot be satisfied by
 * production agreeing with itself.</p>
 *
 * <p><strong>Assertion discipline.</strong> Every rejection asserts {@link IllegalArgumentException}
 * itself, so a {@code NullPointerException} can never be mistaken for a contract rejection, and every
 * message assertion checks only a stable fragment such as the member name, never a whole sentence.
 * Byte budgets are asserted from the real encoding rather than from a Java character count. Frozen
 * carriers are asserted with {@code assertSame} where "reused, not re-frozen" is the property under
 * test, and the published lists are asserted with {@code assertNotSame} against the input, which is
 * what makes the defence copy observable.</p>
 */
@DisplayName("R05.3 map definition aggregate contract")
class MapDefinitionContractTest {

    private static final Ids.DefinitionId DEFINITION_ID =
            new Ids.DefinitionId(UUID.fromString("11111111-2222-4333-8444-555555555555"));
    private static final Ids.WorldId WORLD_ID =
            new Ids.WorldId(UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa"));
    private static final UUID SHARED_UUID = UUID.fromString("0f1e2d3c-4b5a-4697-8879-9a0b1c2d3e4f");

    private static final String MODE = "ptcrys:defuse";
    private static final String OTHER_MODE = "unknown:future_mode";
    private static final String DIMENSION = "minecraft:overworld";
    private static final String OTHER_DIMENSION = "minecraft:the_nether";
    private static final String KIND = "ptcrys:zone";
    private static final String RESOURCE_KIND = "ptcrys:image";
    private static final String DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static final ExactJson.FrozenJson EMPTY_OBJECT = ExactJson.freeze(new JsonObject());
    private static final WireExtras.MapExtras MAP_EXTRAS = new WireExtras.MapExtras(
            EMPTY_OBJECT, EMPTY_OBJECT, EMPTY_OBJECT, EMPTY_OBJECT);
    private static final WireExtras.TeamExtras TEAM_EXTRAS =
            new WireExtras.TeamExtras(EMPTY_OBJECT);
    private static final WireExtras.SpawnExtras SPAWN_EXTRAS = new WireExtras.SpawnExtras(
            EMPTY_OBJECT, EMPTY_OBJECT, EMPTY_OBJECT);
    private static final WireExtras.RegionExtras REGION_EXTRAS = new WireExtras.RegionExtras(
            EMPTY_OBJECT, EMPTY_OBJECT, EMPTY_OBJECT, EMPTY_OBJECT);
    private static final WireExtras.ResourceExtras RESOURCE_EXTRAS =
            new WireExtras.ResourceExtras(EMPTY_OBJECT);

    // ------------------------------------------------------------------
    // element factories
    // ------------------------------------------------------------------

    private static Ids.TeamId teamId(int seed) {
        return new Ids.TeamId(new UUID(0x7000L + seed, 0x8000L + seed));
    }

    private static Ids.ObjectId objectId(int seed) {
        return new Ids.ObjectId(new UUID(0x9000L + seed, 0xa000L + seed));
    }

    private static TeamDefinition team(int seed) {
        return new TeamDefinition(teamId(seed), "team" + seed, EMPTY_OBJECT, EMPTY_OBJECT,
                TEAM_EXTRAS);
    }

    private static SpawnDefinition spawn(int seed, Ids.TeamId owner, String dimension) {
        return new SpawnDefinition(objectId(seed), "spawn" + seed, owner, dimension,
                new Position(1.5d, 64.0d, -2.5d), new Orientation(90.0f, 0.0f),
                EMPTY_OBJECT, SPAWN_EXTRAS);
    }

    private static SpawnDefinition spawnOf(int seed, Ids.TeamId owner) {
        return spawn(seed, owner, DIMENSION);
    }

    private static RegionDefinition region(int seed) {
        return new RegionDefinition(objectId(seed), "region" + seed, KIND,
                new BoxCorners(new IntPosition(0, 64, 0), new IntPosition(16, 72, 16)),
                EMPTY_OBJECT, EMPTY_OBJECT, REGION_EXTRAS);
    }

    private static ResourceRevisionRef reference(long revision) {
        return new ResourceRevisionRef(RESOURCE_KIND, SHARED_UUID, revision, DIGEST,
                RESOURCE_EXTRAS);
    }

    /**
     * Builds a complete, legal definition with the supplied four collections.
     *
     * <p>The team list is derived from the spawns when the caller leaves it empty, so a test that only
     * cares about spawns does not have to spell the teams out. Every other member is a fixed legal
     * value.</p>
     */
    private static MapDefinition definition(List<TeamDefinition> teams,
            List<SpawnDefinition> spawns, List<RegionDefinition> regions,
            List<ResourceRevisionRef> resources) {
        return new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, DIMENSION, "map", "desc",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(100, 100, 100)),
                teams, spawns, regions, EMPTY_OBJECT, resources, EMPTY_OBJECT, MAP_EXTRAS);
    }

    /** A legal definition owning one team, one spawn of that team, one region and one resource. */
    private static MapDefinition minimal() {
        return definition(List.of(team(1)), List.of(spawnOf(2, teamId(1))),
                List.of(region(3)), List.of(reference(1L)));
    }

    /** Repeats a value into a fresh list of {@code count} identical elements. */
    private static <T> List<T> repeat(T value, int count) {
        List<T> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(value);
        }
        return list;
    }

    /** A body of {@code ascii} one byte characters, so its UTF-8 size equals its length. */
    private static String ascii(int ascii) {
        return "a".repeat(ascii);
    }

    /** A frozen object holding {@code count} members named {@code k0}, {@code k1}, ... of the same value. */
    private static ExactJson.FrozenJson frozenMembers(int count, JsonElement value) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < count; i++) {
            object.add("k" + i, value);
        }
        return ExactJson.freeze(object);
    }

    /** The frozen codec's per string ceiling, mirrored here so the carriers stay constructible. */
    private static final int MAX_STRING_UTF8_BYTES = 65536;

    /** A spawn whose {@code extensions} carry the given frozen value. */
    private static SpawnDefinition spawnWithExtensions(Ids.TeamId owner,
            ExactJson.FrozenJson extensions) {
        return new SpawnDefinition(objectId(2), "spawn", owner, DIMENSION,
                new Position(1.5d, 64.0d, -2.5d), new Orientation(90.0f, 0.0f), extensions,
                SPAWN_EXTRAS);
    }

    // ------------------------------------------------------------------
    // R05.3b payload position builders
    //
    // Every payload of an element is reachable through a dedicated factory below, so a vector that
    // wants to put weight in one exact slot - the team config, the team extensions, one team extras
    // member, a region config, a resource extras member - does not have to fake it by repeating
    // spawns. That matters because the defect these tests guard against was precisely a slot that
    // production never charged: a test that can only load spawns cannot see it.
    // ------------------------------------------------------------------

    /** A team whose {@code config} carries the given frozen value. */
    private static TeamDefinition teamWithConfig(int seed, ExactJson.FrozenJson config) {
        return new TeamDefinition(teamId(seed), "team" + seed, config, EMPTY_OBJECT, TEAM_EXTRAS);
    }

    /** A team whose {@code extensions} carry the given frozen value. */
    private static TeamDefinition teamWithExtensions(int seed, ExactJson.FrozenJson extensions) {
        return new TeamDefinition(teamId(seed), "team" + seed, EMPTY_OBJECT, extensions,
                TEAM_EXTRAS);
    }

    /** A team whose {@link WireExtras.TeamExtras} members carry the given frozen value. */
    private static TeamDefinition teamWithExtras(int seed, ExactJson.FrozenJson extrasMembers) {
        return new TeamDefinition(teamId(seed), "team" + seed, EMPTY_OBJECT, EMPTY_OBJECT,
                new WireExtras.TeamExtras(extrasMembers));
    }

    /** A region whose {@code config} carries the given frozen value. */
    private static RegionDefinition regionWithConfig(int seed, ExactJson.FrozenJson config) {
        return new RegionDefinition(objectId(seed), "region" + seed, KIND,
                new BoxCorners(new IntPosition(0, 64, 0), new IntPosition(16, 72, 16)),
                config, EMPTY_OBJECT, REGION_EXTRAS);
    }

    /** A resource reference whose {@link WireExtras.ResourceExtras} members carry the value. */
    private static ResourceRevisionRef referenceWithExtras(long revision,
            ExactJson.FrozenJson extrasMembers) {
        return new ResourceRevisionRef(RESOURCE_KIND, SHARED_UUID, revision, DIGEST,
                new WireExtras.ResourceExtras(extrasMembers));
    }

    /** Map extras with only {@code members} set, so the other three positions stay empty. */
    private static WireExtras.MapExtras mapExtras(ExactJson.FrozenJson members) {
        return new WireExtras.MapExtras(members, EMPTY_OBJECT, EMPTY_OBJECT, EMPTY_OBJECT);
    }

    /**
     * A definition whose root {@code rules} and {@code extensions} carry the two supplied payloads
     * and whose four collections are empty.
     *
     * <p>Both root anchors are charged by production before any list is copied, so this is the shape
     * that reaches an exact budget without any contribution from the collections.</p>
     */
    private static MapDefinition rootedAt(ExactJson.FrozenJson rules,
            ExactJson.FrozenJson extensions) {
        return new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, DIMENSION, "map", "desc",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(100, 100, 100)),
                List.of(), List.of(), List.of(), rules, List.of(), extensions, MAP_EXTRAS);
    }

    /**
     * The same shape with one extra known payload carried by the map level extras.
     *
     * <p>Production charges an extras member as {@code utf8Size() - 2}, so a three byte addition
     * contributes a single byte to the bound: that is how a boundary vector reaches an exact total
     * without building a second multi mebibyte tree.</p>
     */
    private static MapDefinition rootedAt(ExactJson.FrozenJson rules,
            ExactJson.FrozenJson extensions, ExactJson.FrozenJson addition) {
        WireExtras.MapExtras extras = new WireExtras.MapExtras(addition, EMPTY_OBJECT, EMPTY_OBJECT,
                EMPTY_OBJECT);
        return new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, DIMENSION, "map", "desc",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(100, 100, 100)),
                List.of(), List.of(), List.of(), rules, List.of(), extensions, extras);
    }

    /** The number of member positions one {@code WireExtras.MapExtras} carries. */
    private static final int MAP_EXTRAS_SLOTS = 4;

    /**
     * Map extras with exactly one member position loaded.
     *
     * <p>The other three positions stay the shared empty object, so a map can never hide a position
     * that production forgot to charge behind another loaded position.</p>
     *
     * @param slot the position to load, from 0 to 3
     * @param members the member object of that position
     * @return the extras component
     */
    private static WireExtras.MapExtras mapExtrasAt(int slot, ExactJson.FrozenJson members) {
        return switch (slot) {
            case 0 -> new WireExtras.MapExtras(members, EMPTY_OBJECT, EMPTY_OBJECT, EMPTY_OBJECT);
            case 1 -> new WireExtras.MapExtras(EMPTY_OBJECT, members, EMPTY_OBJECT, EMPTY_OBJECT);
            case 2 -> new WireExtras.MapExtras(EMPTY_OBJECT, EMPTY_OBJECT, members, EMPTY_OBJECT);
            case 3 -> new WireExtras.MapExtras(EMPTY_OBJECT, EMPTY_OBJECT, EMPTY_OBJECT, members);
            default -> throw new IllegalArgumentException("there is no MapExtras position " + slot);
        };
    }

    /**
     * The anchored root shape with one member position of the map extras loaded.
     *
     * @param rules the root rules payload
     * @param extensions the root extensions payload
     * @param slot the map extras position that carries {@code members}
     * @param members the member object of that position
     * @return the definition
     */
    private static MapDefinition rootedAt(ExactJson.FrozenJson rules, ExactJson.FrozenJson extensions,
            int slot, ExactJson.FrozenJson members) {
        return new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, DIMENSION, "map", "desc",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(100, 100, 100)),
                List.of(), List.of(), List.of(), rules, List.of(), extensions,
                mapExtrasAt(slot, members));
    }

    /**
     * Reads the member object published in one map extras position.
     *
     * @param extras the published extras component
     * @param slot the position to read, from 0 to 3
     * @return the frozen member object of that position
     */
    private static ExactJson.FrozenJson publishedExtrasSlot(WireExtras.MapExtras extras, int slot) {
        return switch (slot) {
            case 0 -> extras.members();
            case 1 -> extras.boundaryMembers();
            case 2 -> extras.boundaryFirstMembers();
            case 3 -> extras.boundarySecondMembers();
            default -> throw new IllegalArgumentException("there is no MapExtras position " + slot);
        };
    }

    /**
     * A frozen object of exactly {@code targetBytes} canonical UTF-8 bytes, built from 64 members.
     *
     * <p>This is the fixed large payload fixture: a single JSON string may not exceed
     * {@value #MAX_STRING_UTF8_BYTES} UTF-8 bytes, so a multi megabyte payload cannot be one long
     * string and must be a sum over members. The shape is deliberately fixed rather than searched, so
     * the arithmetic is auditable by hand:</p>
     *
     * <ul>
     *   <li>64 members with the four character keys {@code k000} to {@code k063};</li>
     *   <li>the first 63 values are {@value #MAX_STRING_UTF8_BYTES} ASCII bytes each;</li>
     *   <li>the last value absorbs the remainder,
     *       {@code targetBytes - 641 - 63 * 65536}.</li>
     * </ul>
     *
     * <p>The 641 bytes are the fixed scaffolding: two root braces, plus nine bytes per member for the
     * key quotes, the colon and the value quotes, plus one comma between each of the 63 pairs. A legal
     * target is therefore exactly {@code 641 + 63 * 65536} to {@code 641 + 64 * 65536}, that is 4129409
     * to 4194945 bytes.</p>
     *
     * <p>The canonical text is assembled by this test with a preset capacity and is measured by the
     * platform UTF-8 encoder before it is handed to {@link ExactJson#parse(String)}; the codec's own
     * measurement is only a cross check afterwards. A drift in this reasoning therefore fails here
     * rather than silently moving a boundary vector.</p>
     *
     * @param targetBytes the exact canonical width, from 4129409 to 4194945 bytes
     * @throws IllegalArgumentException if the requested width needs an illegal last value
     */
    private static ExactJson.FrozenJson wideCarrier(int targetBytes) {
        // A fresh multi mebibyte carrier is about to be assembled, so whatever a previous fixture
        // left behind is released before the new one is built: the 64 MiB run has to build exactly
        // the same fixtures as the large heap run, and holding dead carriers beside a live one is
        // what would make the small heap fail. This is fixture bookkeeping, not a production claim.
        releaseLargeFixtureGarbage();
        int lastValue = targetBytes - WIDE_CARRIER_SCAFFOLD_BYTES
                - (WIDE_CARRIER_MEMBERS - 1) * MAX_STRING_UTF8_BYTES;
        if (lastValue < 0 || lastValue > MAX_STRING_UTF8_BYTES) {
            throw new IllegalArgumentException("target " + targetBytes
                    + " needs an illegal last value length " + lastValue);
        }
        // The expected canonical text is assembled here, by this test, with a preset capacity, and is
        // never produced by freeze, toJson or utf8Size: an expectation that came back out of the
        // codec could only ever agree with itself. The width is then measured by the platform UTF-8
        // encoder before the codec sees the text, so the fixture is checked against an authority
        // outside the production code path.
        String filler = ascii(MAX_STRING_UTF8_BYTES);
        StringBuilder text = new StringBuilder(targetBytes + WIDE_CARRIER_MEMBERS);
        text.append('{');
        for (int i = 0; i < WIDE_CARRIER_MEMBERS - 1; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append('"').append(wideKey(i)).append("\":\"").append(filler).append('"');
        }
        text.append(',').append('"').append(wideKey(WIDE_CARRIER_MEMBERS - 1)).append("\":\"")
                .append(ascii(lastValue)).append('"').append('}');
        String canonical = text.toString();
        text = null;
        int jdkWidth = jdkUtf8Width(canonical);
        if (jdkWidth != targetBytes) {
            throw new AssertionError("the JDK measured " + jdkWidth + " UTF-8 bytes for a fixture"
                    + " assembled as " + targetBytes + " bytes");
        }
        // The builder's own copy of the text is dead once the string exists, but it is still on the
        // heap and would sit beside the tree, the canonical string and the canonical byte array that
        // the codec is about to allocate. Releasing it here is what keeps the construction peak of a
        // four mebibyte carrier inside the small heap.
        releaseLargeFixtureGarbage();
        ExactJson.FrozenJson carrier = ExactJson.parse(canonical);
        // The source text is dead from here on and is dropped explicitly rather than staying alive
        // beside the frozen tree while the codec's own measurement is cross checked below.
        canonical = null;
        assertEquals(targetBytes, carrier.utf8Size(),
                "the frozen carrier must measure exactly the requested width");
        return carrier;
    }

    /** Block size of the scratch buffer the JDK encoder writes through; no large buffer is needed. */
    private static final int JDK_ENCODER_SCRATCH_BYTES = 8192;

    /**
     * Measures the exact UTF-8 width of {@code text} with the platform encoder.
     *
     * <p>This is the width authority of every large fixture. The encoder runs with its default
     * reporting action, so an unencodable character surfaces as a failure instead of a replacement
     * byte, and it writes through one small fixed scratch buffer: only the byte count is kept, so no
     * array proportional to the text is retained and none is even allocated.</p>
     *
     * @param text the text whose exact UTF-8 length is required
     * @return the exact UTF-8 width of {@code text} in bytes
     */
    private static int jdkUtf8Width(String text) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
        CharBuffer source = CharBuffer.wrap(text);
        ByteBuffer sink = ByteBuffer.allocate(JDK_ENCODER_SCRATCH_BYTES);
        long total = 0;
        while (true) {
            CoderResult step = encoder.encode(source, sink, true);
            total += drain(sink);
            if (step.isUnderflow()) {
                break;
            }
            if (step.isError()) {
                throw new AssertionError("the UTF-8 encoder refused the fixture text: " + step);
            }
        }
        while (true) {
            CoderResult step = encoder.flush(sink);
            total += drain(sink);
            if (step.isUnderflow()) {
                break;
            }
            if (step.isError()) {
                throw new AssertionError("the UTF-8 encoder failed to flush: " + step);
            }
        }
        if (total > Integer.MAX_VALUE) {
            throw new AssertionError("the fixture text is wider than an int: " + total + " bytes");
        }
        return (int) total;
    }

    /** Copies the scratch buffer out to its byte count and empties it for the next round. */
    private static int drain(ByteBuffer sink) {
        sink.flip();
        int bytes = sink.remaining();
        sink.clear();
        return bytes;
    }

    /**
     * Asks the runtime for a collection between two large fixtures.
     *
     * <p>This is fixture management inside this test only: it keeps the construction peak of two
     * multi mebibyte payloads below a small heap. It is not a claim about production, which performs
     * no collection of its own and promises no collection guarantee to any caller.</p>
     */
    private static void releaseLargeFixtureGarbage() {
        System.gc();
    }

    /** Members of the fixed large payload fixture. */
    private static final int WIDE_CARRIER_MEMBERS = 64;

    /**
     * Fixed scaffolding bytes of the large payload fixture: two root braces, nine bytes per member for
     * the key quotes, the colon and the value quotes, and one comma between each of the 63 pairs.
     */
    private static final int WIDE_CARRIER_SCAFFOLD_BYTES = 641;

    /**
     * A frozen JSON <em>object</em> of exactly {@code width} canonical UTF-8 bytes, for adding a few
     * bytes to an anchored width without building a second multi mebibyte tree.
     *
     * <p>An extras member must be an object, so the fixture is {@code {"a":<digits>}} whose scaffolding
     * is six bytes: the braces, the quoted key and the colon. The digits are chosen so the requested
     * width is reached exactly, and only widths from seven bytes up are expressible. The assembled
     * width is first measured by the platform UTF-8 encoder and only then cross checked against the
     * codec's own measurement, so the two authorities have to agree.</p>
     */
    private static ExactJson.FrozenJson narrowCarrier(int width) {
        int digits = width - NARROW_CARRIER_SCAFFOLD_BYTES;
        if (digits < 1) {
            throw new IllegalArgumentException("a narrow object cannot be " + width + " bytes wide");
        }
        // A leading one keeps the text from being read as an octal escape or losing digits, so the
        // canonical output is exactly `digits` characters wide.
        StringBuilder digitsText = new StringBuilder(digits);
        digitsText.append('1');
        for (int i = 1; i < digits; i++) {
            digitsText.append('0');
        }
        StringBuilder json = new StringBuilder(width + 8);
        json.append("{\"a\":").append(digitsText).append('}');
        String canonical = json.toString();
        int jdkWidth = jdkUtf8Width(canonical);
        if (jdkWidth != width) {
            throw new AssertionError("the JDK measured " + jdkWidth + " UTF-8 bytes for a narrow"
                    + " fixture assembled as " + width + " bytes");
        }
        ExactJson.FrozenJson carrier = ExactJson.parse(canonical);
        assertEquals(width, carrier.utf8Size(),
                "the narrow carrier must measure exactly the requested width");
        return carrier;
    }

    /**
     * Scaffolding of the narrow object fixture: the opening brace, the quote, the key, the quote, the
     * colon and the closing brace.
     */
    private static final int NARROW_CARRIER_SCAFFOLD_BYTES = 6;

    /** The four character key {@code k000} to {@code k063} of the large payload fixture. */
    private static String wideKey(int index) {
        return String.format("k%03d", index);
    }

    // ------------------------------------------------------------------
    // 1. retention, value semantics and the defence copy
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every member is retained and the two typed identities stay distinct")
    void membersAreRetained() {
        MapDefinition map = minimal();
        assertEquals(DEFINITION_ID, map.id());
        assertEquals(WORLD_ID, map.worldId());
        assertEquals(MODE, map.modeId());
        assertEquals(DIMENSION, map.dimensionId());
        assertEquals("map", map.displayName());
        assertEquals("desc", map.description());
        assertEquals(new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(100, 100, 100)),
                map.boundary());
        assertEquals(List.of(team(1)), map.teams());
        assertEquals(1, map.spawns().size());
        assertEquals(List.of(region(3)), map.regions());
        assertEquals(List.of(reference(1L)), map.resources());
        assertSame(EMPTY_OBJECT, map.rules());
        assertSame(EMPTY_OBJECT, map.extensions());
        assertSame(MAP_EXTRAS, map.wireExtras());
    }

    @Test
    @DisplayName("the same UUID in three typed identity kinds is legal and never merged")
    void distinctTypedIdentitiesWithEqualUuidDoNotCollide() {
        Ids.TeamId sharedTeam = new Ids.TeamId(SHARED_UUID);
        Ids.ObjectId sharedObject = new Ids.ObjectId(SHARED_UUID);
        Ids.DefinitionId sharedDefinition = new Ids.DefinitionId(SHARED_UUID);
        TeamDefinition owner = new TeamDefinition(sharedTeam, "t", EMPTY_OBJECT, EMPTY_OBJECT,
                TEAM_EXTRAS);
        SpawnDefinition point = new SpawnDefinition(sharedObject, "s", sharedTeam, DIMENSION,
                new Position(0.0d, 0.0d, 0.0d), new Orientation(0.0f, 0.0f), EMPTY_OBJECT,
                SPAWN_EXTRAS);
        MapDefinition map = new MapDefinition(sharedDefinition, new Ids.WorldId(SHARED_UUID), MODE,
                DIMENSION, "", "", new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(0, 0, 0)),
                List.of(owner), List.of(point), List.of(), EMPTY_OBJECT, List.of(), EMPTY_OBJECT,
                MAP_EXTRAS);
        assertEquals(1, map.teams().size());
        assertEquals(1, map.spawns().size());
        assertNotEquals(map.id(), map.worldId());
    }

    @Test
    @DisplayName("the boundary keeps a reversed and a saturated corner order unchanged")
    void boundaryOrderIsPreserved() {
        BoxCorners reversed = new BoxCorners(new IntPosition(10, 70, 10), new IntPosition(0, 64, 0));
        BoxCorners saturated = new BoxCorners(new IntPosition(Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MIN_VALUE), new IntPosition(Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE));
        MapDefinition one = new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, DIMENSION, "", "",
                reversed, List.of(), List.of(), List.of(), EMPTY_OBJECT, List.of(), EMPTY_OBJECT,
                MAP_EXTRAS);
        MapDefinition two = new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, DIMENSION, "", "",
                saturated, List.of(), List.of(), List.of(), EMPTY_OBJECT, List.of(), EMPTY_OBJECT,
                MAP_EXTRAS);
        assertSame(reversed, one.boundary());
        assertEquals(new IntPosition(10, 70, 10), one.boundary().first());
        assertEquals(new IntPosition(0, 64, 0), one.boundary().second());
        assertEquals(Integer.MIN_VALUE, two.boundary().first().x());
        assertEquals(Integer.MAX_VALUE, two.boundary().second().x());
    }

    @Test
    @DisplayName("mutating the caller's lists after construction does not change the definition")
    void inputMutationDoesNotChangeTheDefinition() {
        List<TeamDefinition> teams = new ArrayList<>(List.of(team(1)));
        List<SpawnDefinition> spawns = new ArrayList<>(List.of(spawnOf(2, teamId(1))));
        List<RegionDefinition> regions = new ArrayList<>(List.of(region(3)));
        List<ResourceRevisionRef> resources = new ArrayList<>(List.of(reference(1L)));
        MapDefinition map = definition(teams, spawns, regions, resources);

        teams.clear();
        spawns.set(0, spawnOf(9, teamId(1)));
        regions.clear();
        resources.add(reference(2L));

        assertEquals(List.of(team(1)), map.teams());
        assertEquals(1, map.spawns().size());
        assertEquals(objectId(2), map.spawns().get(0).id());
        assertEquals(List.of(region(3)), map.regions());
        assertEquals(List.of(reference(1L)), map.resources());
        assertNotSame(teams, map.teams());
        assertNotSame(spawns, map.spawns());
        assertNotSame(regions, map.regions());
        assertNotSame(resources, map.resources());
    }

    @Test
    @DisplayName("the published four lists are unmodifiable")
    void publishedListsAreUnmodifiable() {
        MapDefinition map = minimal();
        assertThrows(UnsupportedOperationException.class, () -> map.teams().add(team(9)));
        assertThrows(UnsupportedOperationException.class,
                () -> map.spawns().clear());
        assertThrows(UnsupportedOperationException.class, () -> map.regions().set(0, region(9)));
        assertThrows(UnsupportedOperationException.class, () -> map.resources().clear());
    }

    @Test
    @DisplayName("order and duplicates are preserved while names, corners and coordinates may repeat")
    void orderAndDuplicatesArePreserved() {
        TeamDefinition first = team(1);
        TeamDefinition sameNameDifferentId = new TeamDefinition(teamId(2), "team1", EMPTY_OBJECT,
                EMPTY_OBJECT, TEAM_EXTRAS);
        SpawnDefinition a = spawnOf(3, teamId(1));
        SpawnDefinition b = spawnOf(4, teamId(1));
        RegionDefinition r1 = region(5);
        RegionDefinition r2 = region(6);
        MapDefinition map = definition(List.of(first, sameNameDifferentId), List.of(b, a),
                List.of(r2, r1), List.of(reference(2L), reference(1L)));
        assertEquals(List.of(teamId(1), teamId(2)),
                List.of(map.teams().get(0).id(), map.teams().get(1).id()));
        assertEquals("team1", map.teams().get(1).name());
        assertEquals(List.of(objectId(4), objectId(3)),
                List.of(map.spawns().get(0).id(), map.spawns().get(1).id()));
        assertEquals(objectId(6), map.regions().get(0).id());
        assertEquals(List.of(2L, 1L),
                List.of(map.resources().get(0).revision(), map.resources().get(1).revision()));
    }

    @Test
    @DisplayName("standard record equals and hashCode agree for equal candidates and differ otherwise")
    void recordValueSemantics() {
        assertEquals(minimal(), minimal());
        assertEquals(minimal().hashCode(), minimal().hashCode());
        MapDefinition other = new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, DIMENSION, "map",
                "desc",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(100, 100, 100)),
                List.of(team(1)), List.of(spawnOf(2, teamId(1))), List.of(region(3)), EMPTY_OBJECT,
                List.of(reference(1L)), EMPTY_OBJECT, MAP_EXTRAS);
        assertEquals(minimal(), other);
        assertEquals(minimal().hashCode(), other.hashCode());
        assertNotEquals(minimal(), definition(List.of(team(1)), List.of(spawnOf(2, teamId(1))),
                List.of(region(3)), List.of(reference(2L))));
    }

    // ------------------------------------------------------------------
    // 2. scalar rules and null policy
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every required reference rejects null with IllegalArgumentException")
    void requiredReferencesRejectNull() {
        assertThrows(IllegalArgumentException.class, () -> new MapDefinition(null, WORLD_ID, MODE,
                DIMENSION, "n", "d", box(), List.of(), List.of(), List.of(), EMPTY_OBJECT,
                List.of(), EMPTY_OBJECT, MAP_EXTRAS));
        assertThrows(IllegalArgumentException.class, () -> new MapDefinition(DEFINITION_ID, null,
                MODE, DIMENSION, "n", "d", box(), List.of(), List.of(), List.of(), EMPTY_OBJECT,
                List.of(), EMPTY_OBJECT, MAP_EXTRAS));
        assertThrows(IllegalArgumentException.class, () -> new MapDefinition(DEFINITION_ID, WORLD_ID,
                null, DIMENSION, "n", "d", box(), List.of(), List.of(), List.of(), EMPTY_OBJECT,
                List.of(), EMPTY_OBJECT, MAP_EXTRAS));
        assertThrows(IllegalArgumentException.class, () -> new MapDefinition(DEFINITION_ID, WORLD_ID,
                MODE, null, "n", "d", box(), List.of(), List.of(), List.of(), EMPTY_OBJECT,
                List.of(), EMPTY_OBJECT, MAP_EXTRAS));
        assertThrows(IllegalArgumentException.class, () -> new MapDefinition(DEFINITION_ID, WORLD_ID,
                MODE, DIMENSION, null, "d", box(), List.of(), List.of(), List.of(), EMPTY_OBJECT,
                List.of(), EMPTY_OBJECT, MAP_EXTRAS));
        assertThrows(IllegalArgumentException.class, () -> new MapDefinition(DEFINITION_ID, WORLD_ID,
                MODE, DIMENSION, "n", null, box(), List.of(), List.of(), List.of(), EMPTY_OBJECT,
                List.of(), EMPTY_OBJECT, MAP_EXTRAS));
        assertThrows(IllegalArgumentException.class, () -> new MapDefinition(DEFINITION_ID, WORLD_ID,
                MODE, DIMENSION, "n", "d", null, List.of(), List.of(), List.of(), EMPTY_OBJECT,
                List.of(), EMPTY_OBJECT, MAP_EXTRAS));
        assertThrows(IllegalArgumentException.class, () -> new MapDefinition(DEFINITION_ID, WORLD_ID,
                MODE, DIMENSION, "n", "d", box(), null, List.of(), List.of(), EMPTY_OBJECT,
                List.of(), EMPTY_OBJECT, MAP_EXTRAS));
        assertThrows(IllegalArgumentException.class, () -> new MapDefinition(DEFINITION_ID, WORLD_ID,
                MODE, DIMENSION, "n", "d", box(), List.of(), null, List.of(), EMPTY_OBJECT,
                List.of(), EMPTY_OBJECT, MAP_EXTRAS));
        assertThrows(IllegalArgumentException.class, () -> new MapDefinition(DEFINITION_ID, WORLD_ID,
                MODE, DIMENSION, "n", "d", box(), List.of(), List.of(), null, EMPTY_OBJECT,
                List.of(), EMPTY_OBJECT, MAP_EXTRAS));
        assertThrows(IllegalArgumentException.class, () -> new MapDefinition(DEFINITION_ID, WORLD_ID,
                MODE, DIMENSION, "n", "d", box(), List.of(), List.of(), List.of(), null,
                List.of(), EMPTY_OBJECT, MAP_EXTRAS));
        assertThrows(IllegalArgumentException.class, () -> new MapDefinition(DEFINITION_ID, WORLD_ID,
                MODE, DIMENSION, "n", "d", box(), List.of(), List.of(), List.of(), EMPTY_OBJECT,
                null, EMPTY_OBJECT, MAP_EXTRAS));
        assertThrows(IllegalArgumentException.class, () -> new MapDefinition(DEFINITION_ID, WORLD_ID,
                MODE, DIMENSION, "n", "d", box(), List.of(), List.of(), List.of(), EMPTY_OBJECT,
                List.of(), null, MAP_EXTRAS));
        assertThrows(IllegalArgumentException.class, () -> new MapDefinition(DEFINITION_ID, WORLD_ID,
                MODE, DIMENSION, "n", "d", box(), List.of(), List.of(), List.of(), EMPTY_OBJECT,
                List.of(), EMPTY_OBJECT, null));
    }

    /** A small legal boundary box used by the vectors that only exercise another member. */
    private static BoxCorners box() {
        return new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1));
    }

    @Test
    @DisplayName("a null element in any of the four lists is rejected and never a NullPointerException")
    void nullListElementsAreRejected() {
        IllegalArgumentException teams = assertThrows(IllegalArgumentException.class,
                () -> definition(Arrays.asList(team(1), null), List.of(), List.of(), List.of()));
        assertTrue(teams.getMessage().contains("teams"));
        IllegalArgumentException spawns = assertThrows(IllegalArgumentException.class,
                () -> definition(List.of(team(1)), Arrays.asList(spawnOf(2, teamId(1)), null),
                        List.of(), List.of()));
        assertTrue(spawns.getMessage().contains("spawns"));
        IllegalArgumentException regions = assertThrows(IllegalArgumentException.class,
                () -> definition(List.of(), List.of(), Arrays.asList(region(3), null), List.of()));
        assertTrue(regions.getMessage().contains("regions"));
        IllegalArgumentException resources = assertThrows(IllegalArgumentException.class,
                () -> definition(List.of(), List.of(), List.of(),
                        Arrays.asList(reference(1L), null)));
        assertTrue(resources.getMessage().contains("resources"));
    }

    @Test
    @DisplayName("the display name allows the empty string while the mode and dimension must be namespaced")
    void scalarBoundariesFollowMapScalars() {
        MapDefinition emptyName = new MapDefinition(DEFINITION_ID, WORLD_ID, OTHER_MODE, DIMENSION,
                "", "", new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)),
                List.of(), List.of(), List.of(), EMPTY_OBJECT, List.of(), EMPTY_OBJECT, MAP_EXTRAS);
        assertEquals("", emptyName.displayName());
        assertEquals(OTHER_MODE, emptyName.modeId());

        assertThrows(IllegalArgumentException.class, () -> definitionWithMode("defuse"));
        assertThrows(IllegalArgumentException.class, () -> definitionWithMode("Ptcrys:defuse"));
        assertThrows(IllegalArgumentException.class, () -> definitionWithMode("ptcrys:"));
        assertThrows(IllegalArgumentException.class, () -> definitionWithMode(":defuse"));
        assertThrows(IllegalArgumentException.class, () -> definitionWithMode("a:b:c"));
        assertThrows(IllegalArgumentException.class, () -> definitionWithMode("ptcrys:Defuse"));
        assertThrows(IllegalArgumentException.class, () -> definitionWithDimension("overworld"));
        assertThrows(IllegalArgumentException.class, () -> definitionWithDimension("Minecraft:ow"));
        assertThrows(IllegalArgumentException.class, () -> definitionWithDimension("a:b:c"));
    }

    private static MapDefinition definitionWithMode(String modeId) {
        return new MapDefinition(DEFINITION_ID, WORLD_ID, modeId, DIMENSION, "n", "d",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)),
                List.of(), List.of(), List.of(), EMPTY_OBJECT, List.of(), EMPTY_OBJECT, MAP_EXTRAS);
    }

    private static MapDefinition definitionWithDimension(String dimensionId) {
        return new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, dimensionId, "n", "d",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)),
                List.of(), List.of(), List.of(), EMPTY_OBJECT, List.of(), EMPTY_OBJECT, MAP_EXTRAS);
    }

    private static MapDefinition definitionWithName(String displayName, String description) {
        return new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, DIMENSION, displayName, description,
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)),
                List.of(), List.of(), List.of(), EMPTY_OBJECT, List.of(), EMPTY_OBJECT, MAP_EXTRAS);
    }

    /**
     * Scalar boundary vectors of the name and the description, shared by both parameterized forms.
     *
     * <p>Each vector is a display name, a description and whether the pair is legal. Byte budgets are
     * asserted from the real encoding of the body, never from a Java character count.</p>
     */
    private static Stream<Arguments> scalarVectors() {
        String ascii512 = ascii(512);
        String ascii513 = ascii(513);
        String cjk512 = "汉".repeat(170) + ascii(2);
        String cjk513 = "汉".repeat(171);
        String desc8192 = ascii(8192);
        String desc8193 = ascii(8193);
        String descCjk8192 = "汉".repeat(2730) + ascii(2);
        String emoji128 = "\uD83D\uDE00".repeat(128);
        String emoji129 = "\uD83D\uDE00".repeat(129);
        return Stream.of(
                Arguments.of("ascii512", ascii512, "d", true),
                Arguments.of("ascii513", ascii513, "d", false),
                Arguments.of("cjk512", cjk512, "d", true),
                Arguments.of("cjk513", cjk513, "d", false),
                Arguments.of("emoji128", emoji128, "d", true),
                Arguments.of("emoji129", emoji129, "d", false),
                Arguments.of("emptyName", "", "d", true),
                Arguments.of("spaceName", " ", "d", true),
                Arguments.of("nullUnit", "a\u0000b", "d", false),
                Arguments.of("unit01", "a\u0001b", "d", false),
                Arguments.of("unit1f", "a\u001Fb", "d", false),
                Arguments.of("unit20kept", "a\u0020b", "d", true),
                Arguments.of("delKept", "a\u007Fb", "d", true),
                Arguments.of("highSurrogate", "a\uD83Db", "d", false),
                Arguments.of("lowSurrogate", "a\uDE00b", "d", false),
                Arguments.of("descAscii8192", "n", desc8192, true),
                Arguments.of("descAscii8193", "n", desc8193, false),
                Arguments.of("descCjk8192", "n", descCjk8192, true),
                Arguments.of("descNewline", "n", "a\nb\tc", true),
                Arguments.of("descEmoji", "n", "\uD83D\uDE00", true),
                Arguments.of("descLoneSurrogate", "n", "a\uD83Db", false));
    }

    @ParameterizedTest(name = "scalar {0} legal={3}")
    @MethodSource("scalarVectors")
    @DisplayName("name and description budgets follow the frozen scalar rules")
    void nameAndDescriptionBudgets(String label, String displayName, String description,
            boolean legal) {
        if (legal) {
            MapDefinition map = definitionWithName(displayName, description);
            assertEquals(displayName, map.displayName());
            assertEquals(description, map.description());
        } else {
            assertThrows(IllegalArgumentException.class,
                    () -> definitionWithName(displayName, description));
        }
    }

    // ------------------------------------------------------------------
    // 3. size guard, bounded traversal and the budgets
    // ------------------------------------------------------------------

    /**
     * A list that reports a chosen size and fails if any element is ever touched.
     *
     * <p>This is the instrument that makes the early guard observable: if the production constructor
     * tried to copy, iterate or convert this list to an array before checking the declared size, the
     * assertions inside would surface as an {@link AssertionError} and the test would fail loudly
     * instead of quietly passing.</p>
     */
    private static final class LyingSizeList<T> extends AbstractList<T> {

        private final int declaredSize;

        LyingSizeList(int declaredSize) {
            this.declaredSize = declaredSize;
        }

        @Override
        public T get(int index) {
            throw new AssertionError("element access is not allowed before the size guard: index "
                    + index);
        }

        @Override
        public int size() {
            return declaredSize;
        }

        @Override
        public Object[] toArray() {
            throw new AssertionError("toArray must never be called on a rejected list");
        }

        @Override
        public Iterator<T> iterator() {
            throw new AssertionError("iterator must never be created on a rejected list");
        }
    }

    /**
     * A list that hides extra elements: its {@code size()} is honest about the prefix while its
     * iterator yields more, which is exactly the case a declared size cannot be trusted for.
     */
    private static final class LongerThanDeclaredList<T> extends AbstractList<T> {

        private final List<T> actual;

        private final int declaredSize;

        LongerThanDeclaredList(List<T> actual, int declaredSize) {
            this.actual = actual;
            this.declaredSize = declaredSize;
        }

        @Override
        public T get(int index) {
            return actual.get(index);
        }

        @Override
        public int size() {
            return declaredSize;
        }

        @Override
        public Iterator<T> iterator() {
            return actual.iterator();
        }
    }

    /**
     * A list that hides fewer elements than it reports: its iterator ends early while {@code size()}
     * claims the budget, which must be refused rather than silently accepted with a short list.
     */
    private static final class ShorterThanDeclaredList<T> extends AbstractList<T> {

        private final List<T> actual;

        private final int declaredSize;

        ShorterThanDeclaredList(List<T> actual, int declaredSize) {
            this.actual = actual;
            this.declaredSize = declaredSize;
        }

        @Override
        public T get(int index) {
            if (index >= actual.size()) {
                throw new IndexOutOfBoundsException("declared size exceeds the real content");
            }
            return actual.get(index);
        }

        @Override
        public int size() {
            return declaredSize;
        }

        @Override
        public Iterator<T> iterator() {
            return actual.iterator();
        }
    }

    /**
     * A list whose iterator simply ends before the declared size without ever throwing.
     *
     * <p>{@link ShorterThanDeclaredList} cannot express this case because {@link AbstractList} builds
     * its iterator on {@code get}, which would raise an index failure first. This class supplies its
     * own shorter iterator so the production check for "the iterator ended early" is what actually
     * triggers.</p>
     */
    private static final class TruncatedIteratorList<T> extends AbstractList<T> {

        private final List<T> actual;

        private final int declaredSize;

        TruncatedIteratorList(List<T> actual, int declaredSize) {
            this.actual = actual;
            this.declaredSize = declaredSize;
        }

        @Override
        public T get(int index) {
            return actual.get(index);
        }

        @Override
        public int size() {
            return declaredSize;
        }

        @Override
        public Iterator<T> iterator() {
            Iterator<T> delegate = actual.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return delegate.hasNext();
                }

                @Override
                public T next() {
                    if (!delegate.hasNext()) {
                        throw new NoSuchElementException("iterator ended before the declared size");
                    }
                    return delegate.next();
                }
            };
        }
    }

    @Test
    @DisplayName("an oversized claimed size is refused before any element access or allocation")
    void oversizedClaimedSizeIsRefusedEarly() {
        LyingSizeList<TeamDefinition> teams = new LyingSizeList<>(Integer.MAX_VALUE);
        LyingSizeList<SpawnDefinition> spawns = new LyingSizeList<>(Integer.MAX_VALUE);
        LyingSizeList<RegionDefinition> regions = new LyingSizeList<>(Integer.MAX_VALUE);
        LyingSizeList<ResourceRevisionRef> resources = new LyingSizeList<>(Integer.MAX_VALUE);
        IllegalArgumentException teamFailure = assertThrows(IllegalArgumentException.class,
                () -> definition(teams, List.of(), List.of(), List.of()));
        assertTrue(teamFailure.getMessage().contains("teams"));
        assertThrows(IllegalArgumentException.class,
                () -> definition(List.of(), spawns, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> definition(List.of(), List.of(), regions, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> definition(List.of(), List.of(), List.of(), resources));
    }

    @Test
    @DisplayName("a negative claimed size is refused")
    void negativeClaimedSizeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> definition(new LyingSizeList<>(-1),
                List.of(), List.of(), List.of()));
    }

    /**
     * The four budgets at the limit and one past it.
     *
     * <p>The team list is derived per vector so that every accepted spawn still has an owner, and the
     * one-past vectors stay cheap: the guard rejects them from the declared size alone, so no element
     * list of that size is ever built.</p>
     */
    private static Stream<Arguments> budgetVectors() {
        return Stream.of(
                Arguments.of("teamsAtLimit", 64, true),
                Arguments.of("teamsOverLimit", 65, false),
                Arguments.of("spawnsAtLimit", 16384, true),
                Arguments.of("spawnsOverLimit", 16385, false),
                Arguments.of("regionsAtLimit", 1024, true),
                Arguments.of("regionsOverLimit", 1025, false),
                Arguments.of("resourcesAtLimit", 1024, true),
                Arguments.of("resourcesOverLimit", 1025, false));
    }

    @ParameterizedTest(name = "budget {0} legal={2}")
    @MethodSource("budgetVectors")
    @DisplayName("each collection budget accepts its limit and refuses one past it")
    void collectionBudgets(String label, int count, boolean legal) {
        List<TeamDefinition> teams = new ArrayList<>();
        List<SpawnDefinition> spawns = new ArrayList<>();
        List<RegionDefinition> regions = new ArrayList<>();
        List<ResourceRevisionRef> resources = new ArrayList<>();
        Ids.TeamId firstTeam = teamId(1);
        switch (label) {
            case "teamsAtLimit", "teamsOverLimit" -> {
                for (int i = 0; i < count; i++) {
                    teams.add(team(i));
                }
                if (legal) {
                    spawns.add(spawnOf(100000, firstTeam));
                }
            }
            case "spawnsAtLimit", "spawnsOverLimit" -> {
                teams.add(team(1));
                for (int i = 0; i < count; i++) {
                    spawns.add(spawnOf(i, firstTeam));
                }
            }
            case "regionsAtLimit", "regionsOverLimit" -> {
                for (int i = 0; i < count; i++) {
                    regions.add(region(i));
                }
            }
            case "resourcesAtLimit", "resourcesOverLimit" -> {
                for (int i = 0; i < count; i++) {
                    resources.add(reference(i + 1L));
                }
            }
            default -> throw new IllegalStateException("unmapped budget vector " + label);
        }
        if (legal) {
            MapDefinition map = definition(teams, spawns, regions, resources);
            int observed = switch (label) {
                case "teamsAtLimit" -> map.teams().size();
                case "spawnsAtLimit" -> map.spawns().size();
                case "regionsAtLimit" -> map.regions().size();
                default -> map.resources().size();
            };
            assertEquals(count, observed);
        } else {
            assertThrows(IllegalArgumentException.class,
                    () -> definition(teams, spawns, regions, resources));
        }
    }

    @Test
    @DisplayName("a traversal that yields more elements than declared is refused")
    void longerTraversalIsRefused() {
        List<TeamDefinition> three = List.of(team(1), team(2), team(3));
        assertThrows(IllegalArgumentException.class,
                () -> definition(new LongerThanDeclaredList<>(three, 2), List.of(), List.of(),
                        List.of()));
    }

    @Test
    @DisplayName("a traversal that ends before the declared size is refused")
    void shorterTraversalIsRefused() {
        List<TeamDefinition> one = List.of(team(1));
        assertThrows(IllegalArgumentException.class,
                () -> definition(new TruncatedIteratorList<>(one, 3), List.of(), List.of(),
                        List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> definition(new ShorterThanDeclaredList<>(one, 3), List.of(), List.of(),
                        List.of()));
    }

    @Test
    @DisplayName("a concurrent mutation of the caller's list during construction is not repaired")
    void concurrentMutationIsNotRepaired() {
        List<TeamDefinition> shrinking = new ArrayList<>(List.of(team(1), team(2), team(3)));
        List<TeamDefinition> declaredThree = new AbstractList<>() {
            @Override
            public TeamDefinition get(int index) {
                if (index == 0) {
                    shrinking.remove(0);
                }
                return shrinking.get(index);
            }

            @Override
            public int size() {
                return 3;
            }
        };
        assertThrows(RuntimeException.class,
                () -> definition(declaredThree, List.of(), List.of(), List.of()));
    }

    // ------------------------------------------------------------------
    // 4. identity domains and references
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a repeated team identity is refused while any order of teams resolves its spawns")
    void repeatedTeamIdentityIsRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> definition(List.of(team(1), team(1)), List.of(), List.of(), List.of()));
        assertTrue(failure.getMessage().contains("TeamId"));
    }

    @Test
    @DisplayName("a repeated object identity is refused inside and across the spawn and region lists")
    void repeatedObjectIdentityIsRefused() {
        Ids.TeamId owner = teamId(1);
        IllegalArgumentException sameSpawn = assertThrows(IllegalArgumentException.class,
                () -> definition(List.of(team(1)),
                        List.of(spawnOf(2, owner), spawnOf(2, owner)), List.of(), List.of()));
        assertTrue(sameSpawn.getMessage().contains("ObjectId"));
        IllegalArgumentException sameRegion = assertThrows(IllegalArgumentException.class,
                () -> definition(List.of(), List.of(), List.of(region(3), region(3)), List.of()));
        assertTrue(sameRegion.getMessage().contains("ObjectId"));
        IllegalArgumentException across = assertThrows(IllegalArgumentException.class,
                () -> definition(List.of(team(1)), List.of(spawnOf(2, owner)), List.of(region(2)),
                        List.of()));
        assertTrue(across.getMessage().contains("ObjectId"));
    }

    @Test
    @DisplayName("a dangling team reference is refused instead of being repaired")
    void danglingTeamReferenceIsRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> definition(List.of(team(1)), List.of(spawnOf(2, teamId(9))), List.of(),
                        List.of()));
        assertTrue(failure.getMessage().contains("teamId"));
    }

    @Test
    @DisplayName("the whole team list is resolved before any spawn, so team order cannot decide")
    void teamOrderDoesNotDecideReferenceResolution() {
        Ids.TeamId late = teamId(7);
        MapDefinition map = definition(List.of(team(1), team(2), team(3), team(4), team(5),
                team(6), team(7)), List.of(spawnOf(100, late)), List.of(), List.of());
        assertEquals(teamId(7), map.spawns().get(0).teamId());
        MapDefinition reversed = definition(List.of(team(7), team(6), team(5), team(4), team(3),
                team(2), team(1)), List.of(spawnOf(100, late)), List.of(), List.of());
        assertEquals(reversed.spawns().get(0).teamId(), map.spawns().get(0).teamId());
    }

    @Test
    @DisplayName("a cross dimension spawn is refused while the bound dimension is carried through")
    void crossDimensionSpawnIsRefused() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> definition(List.of(team(1)),
                        List.of(spawn(2, teamId(1), OTHER_DIMENSION)), List.of(), List.of()));
        assertTrue(failure.getMessage().contains("dimensionId"));
        MapDefinition map = definition(List.of(team(1)),
                List.of(spawn(2, teamId(1), DIMENSION)), List.of(), List.of());
        assertEquals(DIMENSION, map.dimensionId());
        assertEquals(DIMENSION, map.spawns().get(0).dimensionId());
    }

    @Test
    @DisplayName("four empty lists are a legal definition because business minima are not checked here")
    void emptyCollectionsAreLegal() {
        MapDefinition map = definition(List.of(), List.of(), List.of(), List.of());
        assertTrue(map.teams().isEmpty());
        assertTrue(map.spawns().isEmpty());
        assertTrue(map.regions().isEmpty());
        assertTrue(map.resources().isEmpty());
    }

    // ------------------------------------------------------------------
    // 5. resources are values
    // ------------------------------------------------------------------

    @Test
    @DisplayName("repeated identical references and same UUID revisions are both retained in order")
    void resourceReferencesAreValuesNotIdentities() {
        MapDefinition map = definition(List.of(), List.of(), List.of(),
                List.of(reference(1L), reference(1L), reference(7L), reference(Long.MAX_VALUE)));
        assertEquals(4, map.resources().size());
        assertEquals(1L, map.resources().get(0).revision());
        assertEquals(1L, map.resources().get(1).revision());
        assertEquals(7L, map.resources().get(2).revision());
        assertEquals(Long.MAX_VALUE, map.resources().get(3).revision());
        assertEquals(map.resources().get(0), map.resources().get(1));
    }

    @Test
    @DisplayName("two references sharing a UUID but a different kind or revision are distinct values")
    void sameUuidDifferentKindOrRevisionStaysDistinct() {
        ResourceRevisionRef first = reference(1L);
        ResourceRevisionRef otherKind = new ResourceRevisionRef("ptcrys:other", SHARED_UUID, 1L,
                DIGEST, RESOURCE_EXTRAS);
        MapDefinition map = definition(List.of(), List.of(), List.of(),
                List.of(first, otherKind, reference(2L)));
        assertEquals(3, map.resources().size());
        assertNotEquals(map.resources().get(0), map.resources().get(1));
        assertNotEquals(map.resources().get(0), map.resources().get(2));
    }

    @Test
    @DisplayName("a resource occurrence takes part in no object identity domain")
    void resourceOccurrencesDoNotEnterTheObjectIdentityDomain() {
        Ids.TeamId owner = teamId(1);
        MapDefinition map = definition(List.of(team(1)), List.of(spawnOf(2, owner)), List.of(),
                repeat(reference(1L), 5));
        assertEquals(5, map.resources().size());
        assertEquals(1, map.spawns().size());
    }

    // ------------------------------------------------------------------
    // 6. frozen JSON retention and wire extras
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every frozen carrier is reused by identity and any legal shape is kept")
    void frozenCarriersAreReusedAndShapesAreKept() {
        JsonArray array = new JsonArray();
        array.add(new JsonPrimitive(1));
        array.add(JsonNull.INSTANCE);
        ExactJson.FrozenJson asArray = ExactJson.freeze(array);
        ExactJson.FrozenJson asNull = ExactJson.freeze(JsonNull.INSTANCE);
        ExactJson.FrozenJson asNumber = ExactJson.freeze(new JsonPrimitive(1));
        JsonObject bigObject = new JsonObject();
        bigObject.add("big", new JsonPrimitive("12345678901234567890123456789012345678901234567890"));
        bigObject.add("", new JsonPrimitive("empty key"));
        ExactJson.FrozenJson big = ExactJson.freeze(bigObject);

        Ids.TeamId owner = teamId(1);
        MapDefinition map = new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, DIMENSION, "n", "d",
                box(), List.of(team(1)), List.of(spawnWithExtensions(owner, asArray)), List.of(),
                asArray, List.of(), asNull, MAP_EXTRAS);
        assertSame(asArray, map.rules());
        assertSame(asNull, map.extensions());
        assertSame(asArray, map.spawns().get(0).extensions());
        // The definition reuses the carrier it is handed; it never re-freezes it. Freezing again
        // produces an equal but distinct instance, which is what this asserts about the input path.
        assertNotSame(asNumber, ExactJson.freeze(new JsonPrimitive(1)));
        assertEquals(asNumber, ExactJson.freeze(new JsonPrimitive(1)));
        assertEquals("12345678901234567890123456789012345678901234567890",
                big.toJson().getAsJsonObject().get("big").getAsString());
        assertTrue(big.toJson().getAsJsonObject().has(""));
    }

    @Test
    @DisplayName("a polluted source tree and a recomputed canonical copy never change the definition")
    void frozenCarriersAreIsolatedFromSourceMutation() {
        JsonObject source = new JsonObject();
        source.add("futureFlag", new JsonPrimitive(true));
        ExactJson.FrozenJson frozen = ExactJson.freeze(source);
        MapDefinition map = new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, DIMENSION, "n", "d",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)),
                List.of(), List.of(), List.of(), frozen, List.of(), frozen, MAP_EXTRAS);
        MapDefinition snapshot = definition(List.of(), List.of(), List.of(), List.of());

        source.add("futureFlag", new JsonPrimitive(false));
        source.add("leak", new JsonPrimitive("polluted"));
        JsonElement copy = frozen.toJson();
        copy.getAsJsonObject().add("futureFlag", new JsonPrimitive(false));
        String canonical = frozen.canonical();
        byte[] bytes = frozen.canonicalUtf8();
        Arrays.fill(bytes, (byte) 0);

        assertEquals("{\"futureFlag\":true}", frozen.canonical());
        assertEquals(canonical, map.extensions().canonical());
        assertSame(frozen, map.rules());
        assertSame(frozen, map.extensions());
        assertNotEquals(snapshot, map);
    }

    @Test
    @DisplayName("the same known value with a different extras value produces unequal definitions")
    void differentExtrasValuesChangeEquality() {
        ExactJson.FrozenJson empty = EMPTY_OBJECT;
        ExactJson.FrozenJson flagTrue = frozenMembers(1, new JsonPrimitive(true));
        ExactJson.FrozenJson flagFalse = frozenMembers(1, new JsonPrimitive(false));
        WireExtras.MapExtras withTrue = new WireExtras.MapExtras(flagTrue, empty, empty, empty);
        WireExtras.MapExtras withFalse = new WireExtras.MapExtras(flagFalse, empty, empty, empty);
        MapDefinition one = withMapExtras(withTrue, empty, empty);
        MapDefinition two = withMapExtras(withFalse, empty, empty);
        assertNotEquals(one, two);
        assertNotEquals(one.hashCode(), two.hashCode());
        assertNotEquals(one, two);
    }

    @Test
    @DisplayName("geometry position extras stay in their own semantic positions")
    void geometryExtrasStayInTheirOwnPositions() {
        ExactJson.FrozenJson hint1 = frozenMembers(1, new JsonPrimitive("first"));
        ExactJson.FrozenJson hint2 = frozenMembers(1, new JsonPrimitive("second"));
        ExactJson.FrozenJson empty = EMPTY_OBJECT;
        WireExtras.MapExtras split = new WireExtras.MapExtras(empty, hint1, hint2, empty);
        WireExtras.MapExtras swapped = new WireExtras.MapExtras(empty, hint2, hint1, empty);
        ExactJson.FrozenJson pair = frozenMembers(1, new JsonPrimitive(1));
        MapDefinition first = withMapExtras(split, pair, empty);
        MapDefinition second = withMapExtras(swapped, pair, empty);
        assertNotEquals(first, second);
        assertEquals("first", first.wireExtras().boundaryMembers().toJson().getAsJsonObject()
                .get("k0").getAsString());
        assertEquals("second", first.wireExtras().boundaryFirstMembers().toJson()
                .getAsJsonObject().get("k0").getAsString());
    }

    /**
     * Builds a definition whose rules, extensions and map extras are supplied, so the extras
     * per position semantics can be compared without repeating the fourteen arguments each time.
     */
    private static MapDefinition withMapExtras(WireExtras.MapExtras extras,
            ExactJson.FrozenJson rules, ExactJson.FrozenJson extensionValue) {
        return new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, DIMENSION, "n", "d",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)),
                List.of(), List.of(), List.of(), rules, List.of(), extensionValue, extras);
    }

    @Test
    @DisplayName("two occurrences of one resource identity keep their own distinct extras")
    void resourceOccurrencesKeepTheirOwnExtras() {
        ExactJson.FrozenJson firstMembers = frozenMembers(1, new JsonPrimitive("first"));
        ExactJson.FrozenJson secondMembers = frozenMembers(1, new JsonPrimitive("second"));
        ResourceRevisionRef first = new ResourceRevisionRef(RESOURCE_KIND, SHARED_UUID, 1L, DIGEST,
                new WireExtras.ResourceExtras(firstMembers));
        ResourceRevisionRef second = new ResourceRevisionRef(RESOURCE_KIND, SHARED_UUID, 1L, DIGEST,
                new WireExtras.ResourceExtras(secondMembers));
        MapDefinition map = definition(List.of(), List.of(), List.of(), List.of(first, second));
        assertEquals(2, map.resources().size());
        assertNotEquals(map.resources().get(0), map.resources().get(1));
        assertSame(firstMembers, map.resources().get(0).wireExtras().members());
        assertSame(secondMembers, map.resources().get(1).wireExtras().members());
    }

    // ------------------------------------------------------------------
    // 7. payload lower bound
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an empty extras object contributes zero and is never rejected as if it were two bytes")
    void emptyExtrasContributeZero() {
        MapDefinition map = definition(List.of(team(1)), List.of(spawnOf(2, teamId(1))),
                List.of(region(3)), List.of(reference(1L)));
        assertEquals(EMPTY_OBJECT, map.wireExtras().members());
        assertEquals(2, EMPTY_OBJECT.utf8Size());
        assertNotNullDefinition(map);
    }

    /** Observes that a definition was constructed rather than throwing, without a redundant check. */
    private static void assertNotNullDefinition(MapDefinition map) {
        assertEquals(DEFINITION_ID, map.id());
    }

    @Test
    @DisplayName("a shared frozen payload is charged once per occurrence, not once per identity")
    void sharedPayloadIsChargedPerOccurrence() {
        // The same frozen instance is reused across many spawns, so the only way the budget can be
        // reached is by charging it once per occurrence. Identity deduplication would charge it a
        // single time and accept every repetition below.
        ExactJson.FrozenJson shared = heavyCarrier();
        int perOccurrence = shared.utf8Size();
        Ids.TeamId owner = teamId(1);

        List<SpawnDefinition> one = new ArrayList<>();
        one.add(spawnWithExtensions(owner, shared));
        assertEquals(1, new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, DIMENSION, "n", "d",
                box(), List.of(team(1)), distinctSpawns(one), List.of(), EMPTY_OBJECT, List.of(),
                EMPTY_OBJECT, MAP_EXTRAS).spawns().size());
        assertTrue(perOccurrence < ExactJson.MAX_UTF8_BYTES,
                "one occurrence must stay below the budget");

        // Two occurrences are already MAX + 32. Charging the payload once per identity would still
        // accept this, so the refusal is attributable to the repetition alone.
        List<SpawnDefinition> tooMany = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            tooMany.add(spawnWithExtensions(owner, shared));
        }
        assertTrue((long) perOccurrence * 2 > ExactJson.MAX_UTF8_BYTES,
                "the refused payload must exceed the budget");
        budgetRefusal(() -> new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, DIMENSION, "n", "d",
                box(), List.of(team(1)), distinctSpawns(tooMany), List.of(), EMPTY_OBJECT,
                List.of(), EMPTY_OBJECT, MAP_EXTRAS));
    }

    /**
     * Rebuilds a list of spawns that share one extensions value into spawns with distinct identities,
     * so a repeated payload can be charged while the identity rules stay satisfied.
     */
    private static List<SpawnDefinition> distinctSpawns(List<SpawnDefinition> template) {
        List<SpawnDefinition> result = new ArrayList<>(template.size());
        for (int i = 0; i < template.size(); i++) {
            SpawnDefinition source = template.get(i);
            result.add(new SpawnDefinition(objectId(1000 + i), source.name(), source.teamId(),
                    source.dimensionId(), source.position(), source.orientation(),
                    source.extensions(), source.wireExtras()));
        }
        return result;
    }

    @Test
    @DisplayName("individually legal carriers whose per occurrence sum exceeds the budget are refused")
    void budgetExceededByIndividuallyLegalCarriers() {
        // One carrier, individually legal at half the budget plus sixteen, reused in two spawns. Two
        // occurrences are already MAX + 32, so the refusal is caused by the repetition alone and never
        // by a single payload being over the codec's own freeze ceiling.
        ExactJson.FrozenJson shared = heavyCarrier();
        assertTrue((long) shared.utf8Size() * 2 > ExactJson.MAX_UTF8_BYTES,
                "two occurrences must exceed the budget");

        Ids.TeamId owner = teamId(1);
        List<SpawnDefinition> spawns = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            spawns.add(new SpawnDefinition(objectId(500 + i), "s", owner, DIMENSION,
                    new Position(0.0d, 0.0d, 0.0d), new Orientation(0.0f, 0.0f), shared,
                    SPAWN_EXTRAS));
        }
        budgetRefusal(() -> new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, DIMENSION, "n", "d",
                box(), List.of(team(1)), spawns, List.of(), EMPTY_OBJECT, List.of(),
                EMPTY_OBJECT, MAP_EXTRAS));
    }

    @Test
    @DisplayName("members of a nested extras component are all charged, not only the root members")
    void everyExtraMemberIsCharged() {
        // The root members are empty and each of the three nested positions repeats the same large
        // member. Charging only the root position, or only the first nested position, never reaches the
        // budget; charging every position does, so the refusal is attributable to the nested members.
        ExactJson.FrozenJson large = heavyCarrier();
        assertTrue((long) (large.utf8Size() - EXTRAS_CONTAINER_UTF8_BYTES) * 2
                        > ExactJson.MAX_UTF8_BYTES,
                "two nested occurrences must already exceed the budget");
        WireExtras.MapExtras nested = new WireExtras.MapExtras(EMPTY_OBJECT, large, large, large);
        budgetRefusal(() -> new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, DIMENSION, "n", "d",
                box(), List.of(), List.of(), List.of(), EMPTY_OBJECT, List.of(),
                EMPTY_OBJECT, nested));
    }

    @Test
    @DisplayName("a rejected oversized payload publishes nothing and leaves no partial definition")
    void rejectedPayloadPublishesNothing() {
        // Four spawns repeat one individually legal carrier, so the aggregate bound is MAX + 64 and the
        // refusal happens part way through the spawn list. Nothing from the abandoned construction may
        // become observable: a later minimal definition must still be built normally.
        ExactJson.FrozenJson carrier = heavyCarrier();
        assertTrue((long) carrier.utf8Size() * 4 > ExactJson.MAX_UTF8_BYTES,
                "four occurrences must exceed the budget");
        Ids.TeamId owner = teamId(1);
        List<SpawnDefinition> spawns = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            spawns.add(new SpawnDefinition(objectId(2 + i), "s", owner, DIMENSION,
                    new Position(0.0d, 0.0d, 0.0d), new Orientation(0.0f, 0.0f), carrier,
                    SPAWN_EXTRAS));
        }
        budgetRefusal(() -> new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, DIMENSION, "n", "d",
                box(), List.of(team(1)), spawns, List.of(), EMPTY_OBJECT, List.of(),
                EMPTY_OBJECT, MAP_EXTRAS));
        MapDefinition after = minimal();
        assertEquals(1, after.spawns().size());
    }

    // ------------------------------------------------------------------
    // 8. independent oracle
    // ------------------------------------------------------------------

    /** One oracle sample: an expectation computed before production is asked anything. */
    private record OracleSample(int index, List<TeamDefinition> teams, List<SpawnDefinition> spawns,
            List<RegionDefinition> regions, List<ResourceRevisionRef> resources,
            String dimensionId, boolean expectedAccept, List<Ids.TeamId> expectedTeamOrder,
            List<Ids.ObjectId> expectedSpawnOrder, List<Ids.ObjectId> expectedRegionOrder) {
    }

    /**
     * Builds one thousand deterministic samples from a fixed seed, together with the verdict and the
     * expected order computed independently of production.
     *
     * <p>The mix is fixed by construction and not adapted to any observed result: every sample index
     * that is divisible by a fixed period introduces one defect - a duplicate team identity, a
     * duplicate object identity inside the spawns, a cross list object identity, a dangling team
     * reference or a cross dimension spawn - and the remaining indices are legal. The expected id
     * sets, the reference verdicts and the dimension verdicts are all derived here from the sample
     * definition alone, so a production answer can only agree or disagree with an expectation that
     * already existed.</p>
     */
    /**
     * Decides, from the generated lists alone, whether the definition they form is contract legal.
     *
     * <p>This is the independent oracle. It never consults the injected defect flags and never asks
     * production; it rebuilds the four rules directly from the data:</p>
     *
     * <ul>
     *   <li>a {@link Ids.TeamId} may appear at most once in the team list;</li>
     *   <li>an {@link Ids.ObjectId} may appear at most once across the spawns and the regions
     *       together, because they share one object identity domain;</li>
     *   <li>every spawn's team reference must name a team that the sample actually declares;</li>
     *   <li>every spawn's dimension must equal the definition dimension.</li>
     * </ul>
     *
     * <p>Deriving the verdict this way is what makes the oracle independent rather than a restatement
     * of the sample generator: the check can disagree with the flags, and if it does the sample
     * construction itself is wrong and the test says so.</p>
     */
    private static boolean independentlyAcceptable(List<TeamDefinition> teams,
            List<SpawnDefinition> spawns, List<RegionDefinition> regions,
            List<ResourceRevisionRef> resources, String dimensionId) {
        Set<Ids.TeamId> declaredTeams = new HashSet<>();
        for (TeamDefinition team : teams) {
            if (!declaredTeams.add(team.id())) {
                return false;
            }
        }
        Set<Ids.ObjectId> objectIdentities = new HashSet<>();
        for (SpawnDefinition spawn : spawns) {
            if (!objectIdentities.add(spawn.id())) {
                return false;
            }
            if (!declaredTeams.contains(spawn.teamId())) {
                return false;
            }
            if (!dimensionId.equals(spawn.dimensionId())) {
                return false;
            }
        }
        for (RegionDefinition region : regions) {
            if (!objectIdentities.add(region.id())) {
                return false;
            }
        }
        // Resources are values, not identities: repetition and order are legal, so the resource list
        // contributes nothing to this verdict.
        return true;
    }

    private static List<OracleSample> oracleSamples() {
        List<OracleSample> samples = new ArrayList<>(1000);
        java.util.Random random = new java.util.Random(20261014L);
        for (int index = 0; index < 1000; index++) {
            int teamCount = random.nextInt(4) + 1;
            int spawnCount = random.nextInt(4) + 1;
            int regionCount = random.nextInt(3);
            int resourceCount = random.nextInt(3);
            boolean duplicateTeam = index % 50 == 7;
            boolean duplicateSpawn = index % 50 == 11;
            boolean crossListObject = index % 50 == 17;
            boolean danglingTeam = index % 50 == 23;
            boolean foreignDimension = index % 50 == 29;

            List<Ids.TeamId> teamIds = new ArrayList<>();
            List<TeamDefinition> teams = new ArrayList<>();
            for (int i = 0; i < teamCount; i++) {
                Ids.TeamId identity = teamId(index * 31 + i);
                teamIds.add(identity);
                teams.add(new TeamDefinition(identity, "t" + index + "_" + i, EMPTY_OBJECT,
                        EMPTY_OBJECT, TEAM_EXTRAS));
            }
            List<Ids.TeamId> expectedTeamOrder = new ArrayList<>(teamIds);
            if (duplicateTeam) {
                teams.add(teams.get(0));
            }

            List<Ids.ObjectId> spawnIds = new ArrayList<>();
            List<Ids.ObjectId> expectedSpawnOrder = new ArrayList<>();
            List<SpawnDefinition> spawns = new ArrayList<>();
            for (int i = 0; i < spawnCount; i++) {
                Ids.ObjectId identity = objectId(index * 37 + i);
                spawnIds.add(identity);
                expectedSpawnOrder.add(identity);
                boolean foreign = foreignDimension && i == spawnCount - 1;
                boolean dangling = danglingTeam && i == spawnCount - 1;
                Ids.TeamId owner = dangling ? teamId(999_999) : teamIds.get(i % teamIds.size());
                spawns.add(new SpawnDefinition(identity, "s" + index + "_" + i, owner,
                        foreign ? OTHER_DIMENSION : DIMENSION,
                        new Position(1.5d, 64.0d, -2.5d), new Orientation(90.0f, 0.0f),
                        EMPTY_OBJECT, SPAWN_EXTRAS));
            }
            if (duplicateSpawn) {
                spawns.add(spawns.get(0));
            }

            List<Ids.ObjectId> expectedRegionOrder = new ArrayList<>();
            List<RegionDefinition> regions = new ArrayList<>();
            for (int i = 0; i < regionCount; i++) {
                Ids.ObjectId identity = crossListObject && i == regionCount - 1
                        ? spawnIds.get(0) : objectId(index * 41 + i);
                expectedRegionOrder.add(identity);
                regions.add(new RegionDefinition(identity, "r" + index + "_" + i, KIND,
                        new BoxCorners(new IntPosition(0, 64, 0), new IntPosition(16, 72, 16)),
                        EMPTY_OBJECT, EMPTY_OBJECT, REGION_EXTRAS));
            }
            if (crossListObject && regionCount == 0) {
                regions.add(new RegionDefinition(spawnIds.get(0), "r", KIND,
                        new BoxCorners(new IntPosition(0, 64, 0), new IntPosition(16, 72, 16)),
                        EMPTY_OBJECT, EMPTY_OBJECT, REGION_EXTRAS));
                expectedRegionOrder.add(spawnIds.get(0));
            }

            List<ResourceRevisionRef> resources = new ArrayList<>();
            for (int i = 0; i < resourceCount; i++) {
                resources.add(reference(i + 1L));
            }

            boolean expectedAccept = !duplicateTeam && !duplicateSpawn
                    && !(crossListObject && !regions.isEmpty()) && !danglingTeam
                    && !(foreignDimension && spawnCount > 0);

            // The verdict above is derived from the injected defect flags. It is not trusted as the
            // expectation: the lists that were actually generated are re-examined here, independently
            // of the flags, and the two derivations must agree. A duplicate identity, a dangling team
            // reference or a foreign dimension that the flags failed to describe would therefore be
            // caught as a disagreement instead of silently shifting an expectation.
            boolean derivedAccept = independentlyAcceptable(teams, spawns, regions, resources, DIMENSION);
            assertEquals(derivedAccept, expectedAccept,
                    "the injected flags and the generated lists must imply the same verdict for sample "
                            + index);
            samples.add(new OracleSample(index, teams, spawns, regions, resources, DIMENSION,
                    expectedAccept, expectedTeamOrder, expectedSpawnOrder, expectedRegionOrder));
        }
        return samples;
    }

    @Test
    @DisplayName("one thousand fixed seed samples agree with an independently computed oracle")
    void thousandSampleOracle() {
        List<OracleSample> samples = oracleSamples();
        assertEquals(1000, samples.size());
        int accepted = 0;
        int rejected = 0;
        for (OracleSample sample : samples) {
            if (sample.expectedAccept()) {
                MapDefinition map = definition(sample.teams(), sample.spawns(), sample.regions(),
                        sample.resources());
                accepted++;
                assertEquals(sample.expectedTeamOrder(),
                        map.teams().stream().map(TeamDefinition::id).toList(),
                        "team order must be preserved for sample " + sample.index());
                assertEquals(sample.expectedSpawnOrder(),
                        map.spawns().stream().map(SpawnDefinition::id).toList(),
                        "spawn order must be preserved for sample " + sample.index());
                assertEquals(sample.expectedRegionOrder(),
                        map.regions().stream().map(RegionDefinition::id).toList(),
                        "region order must be preserved for sample " + sample.index());
                // Resources are values, so repetition and order are both legal and the oracle list was
                // built before production ran: the published list must carry exactly those elements,
                // in that order, and as the very same instances.
                assertEquals(sample.resources(), map.resources(),
                        "resource order must be preserved for sample " + sample.index());
                assertEquals(sample.resources().size(), map.resources().size(),
                        "no resource may be added or dropped for sample " + sample.index());
                for (int i = 0; i < sample.resources().size(); i++) {
                    assertSame(sample.resources().get(i), map.resources().get(i),
                            "resource " + i + " must be published as the same instance for sample "
                                    + sample.index());
                }
                assertEquals(sample.dimensionId(), map.dimensionId());
                for (SpawnDefinition point : map.spawns()) {
                    assertTrue(sample.expectedTeamOrder().contains(point.teamId()),
                            "an accepted spawn must own a team of this definition");
                }
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> definition(sample.teams(), sample.spawns(), sample.regions(),
                                sample.resources()),
                        "sample " + sample.index() + " must be refused");
                rejected++;
            }
        }
        assertTrue(accepted > 0, "the oracle must contain accepted samples");
        assertTrue(rejected > 0, "the oracle must contain rejected samples");
        assertEquals(1000, accepted + rejected);
    }

    @Test
    @DisplayName("the oracle contains a fixed proportion of each defect class")
    void oracleDefectMixIsDeterministic() {
        List<OracleSample> samples = oracleSamples();
        long rejected = samples.stream().filter(sample -> !sample.expectedAccept()).count();
        assertEquals(20L * 5, rejected);
        assertEquals(oracleSamples().get(0), samples.get(0));
        assertEquals(oracleSamples().get(999), samples.get(999));
    }

    @Test
    @DisplayName("no mutable list is reachable from a definition and no input list is shared")
    void noMutableListIsReachable() {
        List<TeamDefinition> teams = new ArrayList<>(List.of(team(1)));
        MapDefinition map = definition(teams, List.of(spawnOf(2, teamId(1))), List.of(),
                List.of(reference(1L)));
        assertFalse(map.teams() instanceof ArrayList);
        assertThrows(UnsupportedOperationException.class, () -> map.teams().remove(0));
        assertSame(teams.get(0), map.teams().get(0));
        assertNotSame(teams, map.teams());
        assertEquals(Collections.unmodifiableList(List.of(team(1))), map.teams());
    }

    // ==================================================================
    // R05.3b aggregate budget across every owner
    //
    // The three defects these tests exist for are all of one kind: a payload position that the
    // aggregate lower bound never charged. They are invisible to any vector that loads only one list,
    // because each individual list stayed comfortably inside the budget on its own. Every vector
    // below therefore puts weight in the exact slot under test, keeps all object identities distinct
    // and gives every spawn a real owning team, so the budget - not the identity or reference rule -
    // is provably what refuses the input, and each assertion pins that with a message check.
    // ==================================================================

    /** Runs a construction and returns the refusal, asserting it came from the payload budget. */
    private static IllegalArgumentException budgetRefusal(Executable body) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, body);
        assertTrue(failure.getMessage().contains("lower bound"),
                "the refusal must come from the payload lower bound: " + failure.getMessage());
        return failure;
    }

    /**
     * The shared large payload used by the aggregate vectors: exactly half the budget plus sixteen.
     *
     * <p>A single carrier may not exceed {@link ExactJson#MAX_UTF8_BYTES}, because the codec refuses
     * to freeze canonical output above it, so no individually illegal carrier can be built at all.
     * Twice this width is {@code MAX_UTF8_BYTES + 32}, so two occurrences already cross the budget
     * while each one is legal on its own - the aggregate case - and one carrier is reused for both
     * occurrences so the vectors never hold several multi mebibyte trees at once.</p>
     */
    private static ExactJson.FrozenJson heavyCarrier() {
        if (HEAVY_CARRIER == null) {
            ExactJson.FrozenJson carrier = wideCarrier((ExactJson.MAX_UTF8_BYTES / 2) + 16);
            assertTrue(carrier.utf8Size() <= ExactJson.MAX_UTF8_BYTES,
                    "the single carrier must stay individually legal");
            assertTrue((long) carrier.utf8Size() * 2 > ExactJson.MAX_UTF8_BYTES,
                    "two occurrences must exceed the budget");
            HEAVY_CARRIER = carrier;
        }
        return HEAVY_CARRIER;
    }

    /**
     * The one heavy carrier every large vector shares.
     *
     * <p>It is built once per vector and reused inside that vector, so a vector that weighs it twice
     * weighs the same instance while a fresh four mebibyte object for every occurrence would make the
     * constrained heap measure the test's own allocation instead of the production bound it is meant
     * to pin. The cache does not outlive the vector: the lifecycle hooks below clear it and ask for a
     * collection before and after every vector, so a vector that needs a heavy carrier of its own does
     * not carry the one an earlier vector left behind.</p>
     */
    private static ExactJson.FrozenJson HEAVY_CARRIER;

    /**
     * Drops the shared heavy carrier before a vector runs and asks the test side to collect it.
     *
     * <p>This is fixture bookkeeping, not a claim about production memory management: the carrier is a
     * multi mebibyte object the whole class would otherwise keep alive while a vector assembles its own
     * multi mebibyte payload, which is what the constrained heap run cannot afford.</p>
     */
    @BeforeEach
    void releaseHeavyCarrierBeforeVector() {
        HEAVY_CARRIER = null;
        releaseLargeFixtureGarbage();
    }

    /**
     * Drops the shared heavy carrier after a vector runs and asks the test side to collect it.
     *
     * <p>The next vector therefore starts without the previous vector's tree, which is what keeps the
     * peak of the class independent of the order the vectors run in.</p>
     */
    @AfterEach
    void releaseHeavyCarrierAfterVector() {
        HEAVY_CARRIER = null;
        releaseLargeFixtureGarbage();
    }

    @Test
    @DisplayName("a team config alone can exhaust the budget without any spawn")
    void teamConfigIsCharged() {
        ExactJson.FrozenJson heavy = heavyCarrier();
        List<TeamDefinition> teams = List.of(teamWithConfig(1, heavy), teamWithConfig(2, heavy));
        assertTrue((long) heavy.utf8Size() + heavy.utf8Size() > ExactJson.MAX_UTF8_BYTES,
                "the two legal team payloads must together exceed the budget");

        budgetRefusal(() -> definition(teams, List.of(), List.of(), List.of()));
    }

    @Test
    @DisplayName("a team extensions payload alone can exhaust the budget")
    void teamExtensionsAreCharged() {
        ExactJson.FrozenJson heavy = heavyCarrier();
        List<TeamDefinition> teams = List.of(teamWithExtensions(1, heavy),
                teamWithExtensions(2, heavy));

        budgetRefusal(() -> definition(teams, List.of(), List.of(), List.of()));
    }

    @Test
    @DisplayName("a team extras member alone can exhaust the budget")
    void teamExtrasAreCharged() {
        // The contributors are charged by member bytes, so each occurrence contributes the carrier
        // width minus the container braces; the sum still crosses the budget.
        ExactJson.FrozenJson heavy = heavyCarrier();
        assertTrue(heavy.utf8Size() - 2 > 0, "a large member contributes its payload bytes");
        assertTrue((long) (heavy.utf8Size() - 2) * 2 > ExactJson.MAX_UTF8_BYTES,
                "two charged extras members must exceed the budget");
        List<TeamDefinition> teams = List.of(teamWithExtras(1, heavy), teamWithExtras(2, heavy));

        budgetRefusal(() -> definition(teams, List.of(), List.of(), List.of()));
    }

    @Test
    @DisplayName("spawns and regions each fit the budget but are refused together")
    void spawnsAndRegionsShareOneBound() {
        // Each position on its own is below the budget; only their sum crosses it. This is the exact
        // shape that a per helper bound restarting from the root anchors cannot see, because each
        // helper compares its own running total against the budget and both totals pass.
        ExactJson.FrozenJson spawnPayload = heavyCarrier();
        ExactJson.FrozenJson regionPayload = heavyCarrier();
        assertTrue((long) spawnPayload.utf8Size() + regionPayload.utf8Size()
                        > ExactJson.MAX_UTF8_BYTES,
                "the two legal payloads must together exceed the budget");
        assertTrue(spawnPayload.utf8Size() <= ExactJson.MAX_UTF8_BYTES
                        && regionPayload.utf8Size() <= ExactJson.MAX_UTF8_BYTES,
                "each payload must be individually legal");

        Ids.TeamId owner = teamId(1);
        List<SpawnDefinition> spawns = List.of(spawnWithExtensions(owner, spawnPayload));
        List<RegionDefinition> regions = List.of(regionWithConfig(50, regionPayload));

        budgetRefusal(() -> definition(List.of(team(1)), spawns, regions, List.of()));
    }

    @Test
    @DisplayName("a region and a resource reference are refused together although each fits")
    void regionsAndResourcesShareOneBound() {
        // The resource list is the last owner charged, so a bound that restarts inside the resource
        // helper cannot see the region weight that came before it. The resource occurrence
        // contributes its member bytes, so the pair sums to twice the carrier minus the braces.
        ExactJson.FrozenJson regionPayload = heavyCarrier();
        ExactJson.FrozenJson resourceMembers = heavyCarrier();
        assertTrue((long) regionPayload.utf8Size() + (resourceMembers.utf8Size() - 2)
                        > ExactJson.MAX_UTF8_BYTES,
                "the region and the charged resource member must together exceed the budget");
        List<RegionDefinition> regions = List.of(regionWithConfig(50, regionPayload));
        List<ResourceRevisionRef> resources = List.of(referenceWithExtras(1L, resourceMembers));

        budgetRefusal(() -> definition(List.of(), List.of(), regions, resources));
    }

    @Test
    @DisplayName("a legal small team payload in every slot is accepted and keeps its order")
    void smallTeamPayloadsInEverySlotAreAccepted() {
        // The counterpart of the refusals above: charging a position must not mean rejecting a legal
        // one. All three team slots carry a real payload and the definition must survive.
        ExactJson.FrozenJson small = frozenMembers(4, new JsonPrimitive(ascii(16)));
        List<TeamDefinition> teams = List.of(
                new TeamDefinition(teamId(1), "a", small, small, new WireExtras.TeamExtras(small)),
                new TeamDefinition(teamId(2), "b", small, small, new WireExtras.TeamExtras(small)));

        MapDefinition map = definition(teams, List.of(), List.of(), List.of());

        assertEquals(2, map.teams().size());
        assertEquals(teamId(1), map.teams().get(0).id());
        assertEquals(teamId(2), map.teams().get(1).id());
        assertSame(small, map.teams().get(0).config());
        assertSame(small, map.teams().get(0).extensions());
        assertSame(small, map.teams().get(0).wireExtras().members());
    }

    @Test
    @DisplayName("one shared frozen team payload is charged once per occurrence")
    void sharedTeamPayloadIsChargedPerOccurrence() {
        // A single carrier that is legal on its own, reused across disjoint teams. Identity
        // deduplication would charge it once and accept; charging per occurrence must refuse.
        ExactJson.FrozenJson shared = heavyCarrier();
        List<TeamDefinition> teams = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            teams.add(teamWithConfig(i + 1, shared));
        }
        assertTrue((long) shared.utf8Size() * 2 > ExactJson.MAX_UTF8_BYTES,
                "the reused payload must exceed the budget when charged twice");

        budgetRefusal(() -> definition(teams, List.of(), List.of(), List.of()));
    }

    // ------------------------------------------------------------------
    // R05.3b exact boundary: the structural lower bound against the budget
    // ------------------------------------------------------------------

    /** The fixed canonical text of the nested extras member the boundary vectors use. */
    private static final String NESTED_EXTRAS_TEXT = "{\"future\":0}";

    /**
     * The nested extras object the boundary vectors use, {@code {"future":0}}.
     *
     * <p>The value comes from the fixed literal above, and the literal is measured by the platform
     * UTF-8 encoder before {@link ExactJson#parse(String)} sees it, so the twelve byte width the whole
     * boundary family is built on is known before the codec is asked anything.</p>
     */
    private static final ExactJson.FrozenJson NESTED_EXTRAS = parsedNestedExtras();

    private static ExactJson.FrozenJson parsedNestedExtras() {
        int jdkWidth = jdkUtf8Width(NESTED_EXTRAS_TEXT);
        if (jdkWidth != 12) {
            throw new IllegalStateException(NESTED_EXTRAS_TEXT + " must be twelve UTF-8 bytes but"
                    + " the JDK encoder measured " + jdkWidth);
        }
        return ExactJson.parse(NESTED_EXTRAS_TEXT);
    }

    @Test
    @DisplayName("the nested extras fixture really is twelve bytes wide and contributes ten")
    void nestedExtrasFixtureWidthIsMeasured() {
        // The whole boundary family depends on this one number, so it is measured by the platform
        // encoder and cross checked against the frozen codec rather than assumed, and the container
        // overhead is asserted separately.
        assertEquals(12, jdkUtf8Width(NESTED_EXTRAS_TEXT),
                "{\"future\":0} must be twelve UTF-8 bytes for the JDK encoder");
        assertEquals(12, NESTED_EXTRAS.utf8Size(),
                "{\"future\":0} must be twelve canonical UTF-8 bytes");
        assertEquals(2, EXTRAS_CONTAINER_UTF8_BYTES,
                "one extras container must cost exactly two bytes");
        assertEquals(10, NESTED_EXTRAS.utf8Size() - EXTRAS_CONTAINER_UTF8_BYTES);
    }

    /** Mirrors the production constant so the boundary arithmetic is explicit in the test. */
    private static final int EXTRAS_CONTAINER_UTF8_BYTES = 2;

    /** Half the payload budget: the root anchor the near boundary family is built around. */
    private static final int HALF_BUDGET = ExactJson.MAX_UTF8_BYTES / 2;

    @Test
    @DisplayName("two root anchors summing to the budget exactly are accepted")
    void twoRootAnchorsAtTheBudgetAreAccepted() {
        // The budget is reached by two known payloads, one in each root anchor, with every collection
        // and every extras member empty. Both anchors share one instance, so only a single carrier
        // tree is alive: the bound counts occurrences, never identities, and half plus half is exactly
        // the budget, so the definition must be accepted.
        ExactJson.FrozenJson anchor = wideCarrier(HALF_BUDGET);
        assertEquals(HALF_BUDGET, anchor.utf8Size());
        assertEquals(ExactJson.MAX_UTF8_BYTES, (long) anchor.utf8Size() * 2);

        MapDefinition map = rootedAt(anchor, anchor);

        assertSame(anchor, map.rules());
        assertSame(anchor, map.extensions());
    }

    /** The narrowest legal extras member, {@code {"a":1}}: seven bytes, contributing five. */
    private static final int SMALLEST_MEMBER_BYTES = NARROW_CARRIER_SCAFFOLD_BYTES + 1;

    @Test
    @DisplayName("two root anchors one byte over the budget are refused")
    void twoRootAnchorsOneAboveTheBudgetAreRefused() {
        // One shared wide carrier fills one anchor and is reused as the other one, and a single small
        // extras member puts the bound at exactly MAX + 1. Each occurrence is individually legal and
        // only one multi mebibyte tree is ever alive, so the refusal can only come from charging every
        // occurrence of every root position and every member of the extras object.
        ExactJson.FrozenJson anchor = wideCarrier(HALF_BUDGET - 2);
        ExactJson.FrozenJson addition = narrowCarrier(SMALLEST_MEMBER_BYTES);
        long total = 2L * anchor.utf8Size() + (addition.utf8Size() - EXTRAS_CONTAINER_UTF8_BYTES);
        assertEquals(ExactJson.MAX_UTF8_BYTES + 1L, total,
                "the two anchors plus the member contribution must sit exactly one byte over the budget");

        budgetRefusal(() -> rootedAt(anchor, anchor, addition));
    }

    @Test
    @DisplayName("two root anchors one byte below the budget are accepted")
    void twoRootAnchorsOneBelowTheBudgetAreAccepted() {
        // The same member and the same shape, with the anchors one byte narrower, which puts the bound
        // exactly one byte below the budget: the definition must be accepted. The refusal above is
        // therefore attributable to that single byte and to no other rule.
        ExactJson.FrozenJson anchor = wideCarrier(HALF_BUDGET - 3);
        ExactJson.FrozenJson addition = narrowCarrier(SMALLEST_MEMBER_BYTES);
        long total = 2L * anchor.utf8Size() + (addition.utf8Size() - EXTRAS_CONTAINER_UTF8_BYTES);
        assertEquals(ExactJson.MAX_UTF8_BYTES - 1L, total,
                "the two anchors plus the member contribution must sit exactly one byte below the budget");

        MapDefinition map = rootedAt(anchor, anchor, addition);

        assertSame(anchor, map.rules());
        assertSame(anchor, map.extensions());
        assertEquals(SMALLEST_MEMBER_BYTES, map.wireExtras().members().utf8Size());
    }

    @Test
    @DisplayName("two root anchors one byte below the budget with no member are accepted")
    void twoRootAnchorsWithoutMembersBelowTheBudgetAreAccepted() {
        // The member contribution is zero here, so the same anchored width as the refusal above minus
        // one byte is accepted: this pins the member term itself, not only the anchor term.
        ExactJson.FrozenJson anchor = wideCarrier(HALF_BUDGET - 1);
        long total = 2L * anchor.utf8Size();
        assertEquals(ExactJson.MAX_UTF8_BYTES - 2L, total);

        MapDefinition map = rootedAt(anchor, anchor);

        assertSame(anchor, map.rules());
        assertSame(anchor, map.extensions());
    }

    @Test
    @DisplayName("a nested extras member contributes its width minus the container, not the whole object")
    void nestedExtrasMemberContributesItsPayload() {
        // The anchored pair is short by exactly the member contribution of {"future":0}: twelve bytes
        // of object, charged as ten because the container braces are the owner's, not the member's.
        // Charging the member in full, or charging nothing for a non empty member, both land off the
        // boundary and are caught by the acceptance and the refusal below. The two directions live in
        // two helpers with a collection request between them, so the accepted anchored pair is dead
        // before the one byte wider pair is built: three carriers of this size alive at once is what
        // the 64 MiB run could not afford, while both fixtures and both verdicts are unchanged.
        assertNestedExtrasMemberAtTheBudgetIsAccepted();
        releaseLargeFixtureGarbage();
        assertNestedExtrasMemberAboveTheBudgetIsRefused();
    }

    /** Accepts the anchored pair whose member contribution reaches the budget exactly. */
    private static void assertNestedExtrasMemberAtTheBudgetIsAccepted() {
        ExactJson.FrozenJson rules = heavyCarrier();
        ExactJson.FrozenJson extensions =
                wideCarrier(Math.toIntExact(ExactJson.MAX_UTF8_BYTES - 10L - rules.utf8Size()));
        assertEquals(ExactJson.MAX_UTF8_BYTES - 10L, rules.utf8Size() + extensions.utf8Size());
        WireExtras.MapExtras extras = mapExtras(NESTED_EXTRAS);

        MapDefinition map = new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, DIMENSION, "map",
                "desc", new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(100, 100, 100)),
                List.of(), List.of(), List.of(), rules, List.of(), extensions, extras);
        assertSame(NESTED_EXTRAS, map.wireExtras().members());
    }

    /** Refuses the same anchored pair one byte wider, with the same member. */
    private static void assertNestedExtrasMemberAboveTheBudgetIsRefused() {
        // One more byte of known payload on top of the same member crosses the budget.
        ExactJson.FrozenJson rules = heavyCarrier();
        ExactJson.FrozenJson overBound =
                wideCarrier(Math.toIntExact(ExactJson.MAX_UTF8_BYTES + 1L - 10 - rules.utf8Size()));
        WireExtras.MapExtras extras = mapExtras(NESTED_EXTRAS);
        assertEquals(ExactJson.MAX_UTF8_BYTES + 1,
                rules.utf8Size() + overBound.utf8Size() + 10);
        budgetRefusal(() -> new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, DIMENSION, "map",
                "desc", new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(100, 100, 100)),
                List.of(), List.of(), List.of(), rules, List.of(), overBound, extras));
    }

    @Test
    @DisplayName("each non empty extras position of a root owner is charged separately")
    void everyRootExtrasPositionIsChargedSeparately() {
        // The defect these vectors guard against is a root extras object whose positions are charged as
        // one object: a weight that belongs to any position other than the charged one is then free.
        // Each position is therefore loaded on its own, exactly one of the four carries {"future":0}
        // while the other three stay empty, so a production that charges only some of the positions
        // accepts the over budget case of every skipped position and reddens here. Each call repeats
        // the arithmetic of its own expected total. The rules payload is shared by all eight calls and
        // the extensions payload of one direction is built once and reused by the four positions of
        // that direction, with a collection request before the next one is assembled: two neighbouring
        // eight mebibyte pairs are then never alive at the same time, which is what keeps the
        // construction peak of this vector class inside a small heap without dropping a position.
        assertEquals(4, MAP_EXTRAS_SLOTS);
        ExactJson.FrozenJson rules = heavyCarrier();
        ExactJson.FrozenJson atTheBudget = wideCarrier(Math.toIntExact(
                ExactJson.MAX_UTF8_BYTES - NESTED_EXTRAS.utf8Size() + EXTRAS_CONTAINER_UTF8_BYTES
                        - rules.utf8Size()));
        for (int slot = 0; slot < MAP_EXTRAS_SLOTS; slot++) {
            assertExtrasSlotIsChargedAtTheBudget(rules, atTheBudget, slot);
        }

        atTheBudget = null;
        releaseLargeFixtureGarbage();
        ExactJson.FrozenJson aboveTheBudget = wideCarrier(Math.toIntExact(
                ExactJson.MAX_UTF8_BYTES + 1L - NESTED_EXTRAS.utf8Size()
                        + EXTRAS_CONTAINER_UTF8_BYTES - rules.utf8Size()));
        for (int slot = 0; slot < MAP_EXTRAS_SLOTS; slot++) {
            assertExtrasSlotAboveTheBudgetIsRefused(rules, aboveTheBudget, slot);
        }
    }

    /**
     * Asserts that one root extras position, loaded alone, is accepted at the exact budget.
     *
     * <p>The two known payloads are the root {@code rules} and the root {@code extensions}; their
     * widths plus the member contribution of {@code {"future":0}} are asserted to be exactly the
     * budget before the constructor is asked. The {@code rules} payload is the shared heavy carrier,
     * whose width is {@code MAX / 2 + 16}, so the remainder the {@code extensions} payload has to
     * express stays inside the width band this fixture family can build (4129409 to 4194945 bytes)
     * instead of asking one carrier for a width no member sum can reach.</p>
     *
     * @param rules the shared known payload of the root rules slot
     * @param extensions the known payload of the root extensions slot, at the exact budget
     * @param slot the position that carries the member
     */
    private static void assertExtrasSlotIsChargedAtTheBudget(ExactJson.FrozenJson rules,
            ExactJson.FrozenJson extensions, int slot) {
        long expected = rules.utf8Size() + extensions.utf8Size()
                + NESTED_EXTRAS.utf8Size() - EXTRAS_CONTAINER_UTF8_BYTES;
        assertEquals(ExactJson.MAX_UTF8_BYTES, expected,
                "the two known payloads and position " + slot + " must reach the budget exactly");

        MapDefinition map = rootedAt(rules, extensions, slot, NESTED_EXTRAS);

        assertSame(NESTED_EXTRAS, publishedExtrasSlot(map.wireExtras(), slot),
                "position " + slot + " must publish the member object it was given");
        for (int other = 0; other < MAP_EXTRAS_SLOTS; other++) {
            if (other != slot) {
                assertSame(EMPTY_OBJECT, publishedExtrasSlot(map.wireExtras(), other),
                        "position " + other + " must stay empty while " + slot + " is loaded");
            }
        }
    }

    /**
     * Asserts that the same single position, one byte over the budget, is refused.
     *
     * @param rules the shared known payload of the root rules slot
     * @param extensions the known payload of the root extensions slot, one byte over the budget
     * @param slot the position that carries the member
     */
    private static void assertExtrasSlotAboveTheBudgetIsRefused(ExactJson.FrozenJson rules,
            ExactJson.FrozenJson extensions, int slot) {
        long expected = rules.utf8Size() + extensions.utf8Size()
                + NESTED_EXTRAS.utf8Size() - EXTRAS_CONTAINER_UTF8_BYTES;
        assertEquals(ExactJson.MAX_UTF8_BYTES + 1L, expected,
                "the two known payloads and position " + slot + " must sit one byte over the budget");

        budgetRefusal(() -> rootedAt(rules, extensions, slot, NESTED_EXTRAS));
    }

    @Test
    @DisplayName("a size that changes after the first read never enlarges an accepted collection")
    void changingSizeIsReadExactlyOnce() {
        // Each list claims 0 the first time its size is read, while actually holding one element, and
        // would answer 65 (or throw) on any later read. An implementation that asks again while looping
        // would take the later answer as licence to copy elements past the declared bound. Capturing
        // the first answer makes the claimed 0 decisive, so every definition is refused - by the
        // production rule that a traversal may not exceed the reported size - and every list reports
        // exactly one read. All four collections are covered because a bound that only holds for the
        // team list would still copy an unbounded spawn, region or resource list.
        SizeThenThrowsList<TeamDefinition> teams = new SizeThenThrowsList<>(0, team(1));
        SizeThenThrowsList<SpawnDefinition> spawns =
                new SizeThenThrowsList<>(0, spawnOf(1, teamId(1)));
        SizeThenThrowsList<RegionDefinition> regions = new SizeThenThrowsList<>(0, region(3));
        SizeThenThrowsList<ResourceRevisionRef> resources =
                new SizeThenThrowsList<>(0, reference(1L));

        assertThrows(IllegalArgumentException.class,
                () -> definition(teams, List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> definition(List.of(), spawns, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> definition(List.of(), List.of(), regions, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> definition(List.of(), List.of(), List.of(), resources));

        assertEquals(1, teams.sizeReadings(), "the team input size must be read exactly once");
        assertEquals(1, spawns.sizeReadings(), "the spawn input size must be read exactly once");
        assertEquals(1, regions.sizeReadings(), "the region input size must be read exactly once");
        assertEquals(1, resources.sizeReadings(), "the resource input size must be read exactly once");
    }

    @Test
    @DisplayName("a list that reports zero but iterates forever is refused without one next call")
    void zeroSizeWithInfiniteIteratorIsRefusedWithoutIterating() {
        // Each collection is exercised on its own, with the other three legal and empty and with the
        // owning team present where a spawn needs one, because a traversal bound that is only correct
        // for the team list would still copy an unbounded spawn, region or resource list and leave the
        // shared case of the old vector green. Not one next() call is allowed: a source reported as
        // empty must be refused on the strength of the traversal itself, before it is advanced once.
        // The endless source keeps insisting it has another element, so the only reason a call is
        // refused here is that contradiction, never an exception raised by this fixture.
        InfiniteList<TeamDefinition> teams = new InfiniteList<>();
        assertThrows(IllegalArgumentException.class,
                () -> definition(teams, List.of(), List.of(), List.of()));
        assertZeroReportWasNotIterated(teams, "teams");

        InfiniteList<SpawnDefinition> spawns = new InfiniteList<>();
        assertThrows(IllegalArgumentException.class,
                () -> definition(List.of(team(1)), spawns, List.of(), List.of()));
        assertZeroReportWasNotIterated(spawns, "spawns");

        InfiniteList<RegionDefinition> regions = new InfiniteList<>();
        assertThrows(IllegalArgumentException.class,
                () -> definition(List.of(), List.of(), regions, List.of()));
        assertZeroReportWasNotIterated(regions, "regions");

        InfiniteList<ResourceRevisionRef> resources = new InfiniteList<>();
        assertThrows(IllegalArgumentException.class,
                () -> definition(List.of(), List.of(), List.of(), resources));
        assertZeroReportWasNotIterated(resources, "resources");
    }

    /**
     * Asserts that one source reported as empty was read once, opened once and never advanced.
     *
     * @param source the instrumented source of one collection
     * @param label the collection name used in failure messages
     */
    private static void assertZeroReportWasNotIterated(InfiniteList<?> source, String label) {
        assertEquals(1, source.sizeReadings(), label + ": size() must be read exactly once");
        assertEquals(1, source.iteratorCalls(),
                label + ": the traversal must be opened exactly once");
        assertEquals(0, source.nextCalls(), label + ": not one next() call is allowed");
    }

    @Test
    @DisplayName("an iterator that supplies fewer or more elements than captured is refused")
    void iteratorLengthMismatchIsRefused() {
        // The captured declared size is the only count production may trust, so the traversal is
        // checked in both directions for every collection, one instrumented source per case, with the
        // exact number of next() calls asserted afterwards. The supplied elements are valid for
        // production (unique identities, a spawn that names a real team of the same dimension,
        // resources that may legally repeat) so a refusal can only be attributed to the length rule.
        // The limits are literals, as they are everywhere in this file, so the oracle is not borrowed
        // from production.
        LengthFamily<TeamDefinition> teams = new LengthFamily<>("teams", 64,
                List.of(team(1)), List.of(team(1), team(2), team(3)), i -> team(i + 1),
                list -> definition(list, List.of(), List.of(), List.of()), MapDefinition::teams);
        assertTraversalLengthsAreBounded(teams);

        LengthFamily<SpawnDefinition> spawns = new LengthFamily<>("spawns", 16384,
                List.of(spawnOf(1, teamId(1))),
                List.of(spawnOf(1, teamId(1)), spawnOf(2, teamId(1)), spawnOf(3, teamId(1))),
                i -> spawnOf(i + 1, teamId(1)),
                list -> definition(List.of(team(1)), list, List.of(), List.of()),
                MapDefinition::spawns);
        assertTraversalLengthsAreBounded(spawns);

        LengthFamily<RegionDefinition> regions = new LengthFamily<>("regions", 1024,
                List.of(region(1)), List.of(region(1), region(2), region(3)), i -> region(i + 1),
                list -> definition(List.of(), List.of(), list, List.of()), MapDefinition::regions);
        assertTraversalLengthsAreBounded(regions);

        LengthFamily<ResourceRevisionRef> resources = new LengthFamily<>("resources", 1024,
                List.of(reference(1L)),
                List.of(reference(1L), reference(1L), reference(1L)), i -> reference(1L),
                list -> definition(List.of(), List.of(), List.of(), list),
                MapDefinition::resources);
        assertTraversalLengthsAreBounded(resources);

        // The same shape reached through the pre existing instrument, so the traversal bound is not
        // tied to one helper class.
        List<TeamDefinition> viaInstrument =
                new LongerThanDeclaredList<>(List.of(team(1), team(2), team(3)), 2);
        assertThrows(IllegalArgumentException.class,
                () -> definition(viaInstrument, List.of(), List.of(), List.of()));
    }

    /**
     * One collection family of the traversal length matrix.
     *
     * @param label the collection name used in failure messages
     * @param limit the declared limit of that collection, as a literal oracle
     * @param single a legal element list holding exactly one element
     * @param triple a legal element list holding exactly three elements
     * @param generator supplies the element of an endless source by index
     * @param owner runs production with the lying list injected into this family's position
     * @param published reads the collection the definition publishes
     */
    private record LengthFamily<T>(String label, int limit, List<T> single, List<T> triple,
            IntFunction<T> generator, Function<List<T>, MapDefinition> owner,
            Function<MapDefinition, List<T>> published) {
    }

    /**
     * Runs the five length cases of one collection and asserts the exact call counts of each of them.
     *
     * <p>The three finite cases pin both directions of the traversal bound and the accepted one; the
     * two endless cases pin that a source reported as empty is never advanced and that an endless
     * source declared at the limit is consumed exactly up to that limit and then refused. Every case
     * asserts that {@code size()} was read exactly once, which the instrument turns into an immediate
     * failure on any later read.</p>
     *
     * @param family the collection family to exercise
     */
    private static <T> void assertTraversalLengthsAreBounded(LengthFamily<T> family) {
        String label = family.label();

        LengthLyingList<T> tooShort = LengthLyingList.finite(family.single(), 3);
        assertThrows(IllegalArgumentException.class, () -> family.owner().apply(tooShort),
                label + ": three declared with one supplied element must be refused");
        assertEquals(1, tooShort.nextCalls(),
                label + ": the one supplied element must be consumed before the mismatch is seen");
        assertEquals(1, tooShort.iteratorCalls(), label + ": the traversal is opened exactly once");
        assertEquals(1, tooShort.sizeReadings(), label + ": size() is read exactly once");

        LengthLyingList<T> tooLong = LengthLyingList.finite(family.triple(), 2);
        assertThrows(IllegalArgumentException.class, () -> family.owner().apply(tooLong),
                label + ": two declared with three supplied elements must be refused");
        assertEquals(2, tooLong.nextCalls(),
                label + ": the traversal must stop after the declared count");
        assertEquals(1, tooLong.sizeReadings(), label + ": size() is read exactly once");

        LengthLyingList<T> exact = LengthLyingList.finite(family.single(), 1);
        MapDefinition map = family.owner().apply(exact);
        assertEquals(1, exact.nextCalls(),
                label + ": the accepted element must come through the counted iterator");
        assertEquals(1, exact.sizeReadings(), label + ": size() is read exactly once");
        List<T> published = family.published().apply(map);
        assertEquals(1, published.size(), label + ": the accepted element must be published");
        assertSame(family.single().get(0), published.get(0),
                label + ": the published element must be the very instance that was traversed");

        LengthLyingList<T> empty = LengthLyingList.infinite(0, family.generator());
        assertThrows(IllegalArgumentException.class, () -> family.owner().apply(empty),
                label + ": an endless source reported as empty must be refused");
        assertEquals(0, empty.nextCalls(), label + ": not one next() call is allowed");
        assertEquals(1, empty.iteratorCalls(), label + ": the traversal is opened exactly once");
        assertEquals(1, empty.sizeReadings(), label + ": size() is read exactly once");

        LengthLyingList<T> atLimit = LengthLyingList.infinite(family.limit(), family.generator());
        assertThrows(IllegalArgumentException.class, () -> family.owner().apply(atLimit),
                label + ": an endless source declared at the limit must be refused");
        assertEquals(family.limit(), atLimit.nextCalls(),
                label + ": exactly the declared count may be consumed, no more");
        assertEquals(1, atLimit.sizeReadings(), label + ": size() is read exactly once");
    }

    /**
     * A list whose declared size, traversal and every alternative read are instrumented.
     *
     * <p>The counting is what turns "the size is read once" and "the loop consumes at most the declared
     * number of elements" from claims about the source into measurements, and it also makes the route
     * observable: both {@code toArray} overloads and {@code get} trip an assertion, so an
     * implementation that copies the source by any route other than the counted iterator cannot pass
     * quietly. {@code size()} answers the declared count on its first call and throws on any later one,
     * so a re-read fails instead of silently answering a second, different value. A finite source
     * supplies exactly the elements it holds while declaring whatever count the case needs; an endless
     * source keeps supplying valid elements, so a refusal is never attributable to this fixture.</p>
     */
    private static final class LengthLyingList<T> extends AbstractList<T> {

        private final List<T> actual;

        private final IntFunction<T> endless;

        private final int declaredSize;

        private int sizeReadings;

        private int iteratorCalls;

        private int nextCalls;

        private LengthLyingList(List<T> actual, IntFunction<T> endless, int declaredSize) {
            this.actual = actual;
            this.endless = endless;
            this.declaredSize = declaredSize;
        }

        /**
         * A finite source that supplies {@code actual} while declaring {@code declaredSize}.
         *
         * @param actual the elements the iterator really supplies
         * @param declaredSize the count {@code size()} answers once
         * @return the instrumented list
         */
        static <T> LengthLyingList<T> finite(List<T> actual, int declaredSize) {
            return new LengthLyingList<>(actual, null, declaredSize);
        }

        /**
         * An endless source whose every element is valid for production.
         *
         * @param declaredSize the count {@code size()} answers once
         * @param generator supplies the element of the given index
         * @return the instrumented list
         */
        static <T> LengthLyingList<T> infinite(int declaredSize, IntFunction<T> generator) {
            return new LengthLyingList<>(null, generator, declaredSize);
        }

        int sizeReadings() {
            return sizeReadings;
        }

        int iteratorCalls() {
            return iteratorCalls;
        }

        int nextCalls() {
            return nextCalls;
        }

        @Override
        public T get(int index) {
            throw new AssertionError("the source must be read through its counted iterator, not get");
        }

        @Override
        public Object[] toArray() {
            throw new AssertionError("the source must not be copied through toArray");
        }

        @Override
        public <E> E[] toArray(E[] target) {
            throw new AssertionError("the source must not be copied through toArray");
        }

        @Override
        public int size() {
            sizeReadings++;
            if (sizeReadings > 1) {
                throw new AssertionError("size() must be read exactly once but was read "
                        + sizeReadings + " times");
            }
            return declaredSize;
        }

        @Override
        public Iterator<T> iterator() {
            iteratorCalls++;
            if (actual != null) {
                Iterator<T> delegate = actual.iterator();
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return delegate.hasNext();
                    }

                    @Override
                    public T next() {
                        nextCalls++;
                        return delegate.next();
                    }
                };
            }
            return new Iterator<>() {
                private int index;

                @Override
                public boolean hasNext() {
                    return true;
                }

                @Override
                public T next() {
                    nextCalls++;
                    return endless.apply(index++);
                }
            };
        }
    }

    /**
     * A list that claims a fixed size on its first {@code size()} answer and whose every later answer
     * throws, while actually holding one element.
     *
     * <p>This makes the number of size reads directly observable: an implementation that saves the
     * first answer never asks again, while one that re-reads gets an {@link AssertionError} rather than
     * a second, larger value. The single real element is what makes the claimed size a lie that
     * production must refuse on the strength of its own traversal rule, so the refusal under test comes
     * from the record and not from an exception thrown by this fixture. That element may only be
     * reached through the iterator: both {@code toArray} overloads and {@code get} trip an assertion,
     * so a copy that bypasses the counted iterator cannot pass quietly.</p>
     */
    private static final class SizeThenThrowsList<T> extends AbstractList<T> {

        private final int firstAnswer;

        private final T element;

        private int readings;

        SizeThenThrowsList(int firstAnswer, T element) {
            this.firstAnswer = firstAnswer;
            this.element = element;
        }

        /** How many times {@code size()} was called, which must be exactly one. */
        int sizeReadings() {
            return readings;
        }

        @Override
        public T get(int index) {
            throw new AssertionError("the element must be reached through the iterator, not get");
        }

        @Override
        public int size() {
            readings++;
            if (readings > 1) {
                throw new AssertionError("size() must be read exactly once but was read "
                        + readings + " times");
            }
            return firstAnswer;
        }

        @Override
        public Iterator<T> iterator() {
            return List.of(element).iterator();
        }

        @Override
        public Object[] toArray() {
            throw new AssertionError("the input list must not be copied through toArray");
        }

        @Override
        public <E> E[] toArray(E[] target) {
            throw new AssertionError("the input list must not be copied through toArray");
        }
    }

    /**
     * A list that reports zero elements and whose iterator never ends.
     *
     * <p>The zero report is honest about the prefix the production guard should trust, and the endless
     * iterator is the trap: any implementation that starts iterating a list it was told is empty would
     * allocate forever. Counting {@code next()} calls turns "it hung" into an exact assertion, and the
     * iterator trips an assertion on its first {@code next()} so the failure is immediate and names the
     * rule that was broken. {@code size()} answers zero once and throws on any later read, and both
     * {@code toArray} overloads and {@code get} trip an assertion, so neither a re-read nor a copy that
     * bypasses the iterator can pass quietly.</p>
     */
    private static final class InfiniteList<T> extends AbstractList<T> {

        private int sizeReadings;

        private int iteratorCalls;

        private int nextCalls;

        /** How many times {@code size()} was called, which must be exactly one. */
        int sizeReadings() {
            return sizeReadings;
        }

        /** How many times {@code iterator()} was called, which must be exactly one. */
        int iteratorCalls() {
            return iteratorCalls;
        }

        /** How many times the iterator's {@code next()} was called, which must be zero. */
        int nextCalls() {
            return nextCalls;
        }

        @Override
        public T get(int index) {
            throw new AssertionError("element access is not allowed on a list reported as empty");
        }

        @Override
        public Object[] toArray() {
            throw new AssertionError("a list reported as empty must not be copied through toArray");
        }

        @Override
        public <E> E[] toArray(E[] target) {
            throw new AssertionError("a list reported as empty must not be copied through toArray");
        }

        @Override
        public int size() {
            sizeReadings++;
            if (sizeReadings > 1) {
                throw new AssertionError("size() must be read exactly once but was read "
                        + sizeReadings + " times");
            }
            return 0;
        }

        @Override
        public Iterator<T> iterator() {
            iteratorCalls++;
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return true;
                }

                @Override
                public T next() {
                    nextCalls++;
                    throw new AssertionError("a list reported as empty must never be advanced");
                }
            };
        }
    }

    @Test
    @DisplayName("an empty extras member contributes zero and never its container braces")
    void emptyExtrasMemberContributesZeroAtTheBoundary() {
        // The anchored pair fills the budget to the last byte. If an empty extras container were
        // charged, even as its two braces, this definition would be refused; acceptance is therefore
        // the observable proof that an empty member costs nothing.
        ExactJson.FrozenJson rules = heavyCarrier();
        ExactJson.FrozenJson extensions =
                wideCarrier(Math.toIntExact(ExactJson.MAX_UTF8_BYTES - rules.utf8Size()));
        assertEquals(ExactJson.MAX_UTF8_BYTES, rules.utf8Size() + extensions.utf8Size());

        MapDefinition map = new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, DIMENSION, "map",
                "desc", new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(100, 100, 100)),
                List.of(), List.of(), List.of(), rules, List.of(), extensions,
                mapExtras(EMPTY_OBJECT));
        assertSame(EMPTY_OBJECT, map.wireExtras().members());
    }

    @Test
    @DisplayName("resources with empty extras at the exact budget are accepted")
    void resourceEmptyExtrasAtTheBudgetIsAccepted() {
        // A resource owner always carries a wireExtras component. Its empty container must not be
        // charged, so a definition whose known anchors already fill the budget still leaves room for
        // the resource references themselves, and their repetition stays legal.
        ExactJson.FrozenJson rules = heavyCarrier();
        ExactJson.FrozenJson extensions =
                wideCarrier(Math.toIntExact(ExactJson.MAX_UTF8_BYTES - rules.utf8Size()));
        assertEquals(ExactJson.MAX_UTF8_BYTES, rules.utf8Size() + extensions.utf8Size());
        List<ResourceRevisionRef> resources = List.of(reference(1L), reference(1L));

        MapDefinition map = new MapDefinition(DEFINITION_ID, WORLD_ID, MODE, DIMENSION, "map",
                "desc", new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(100, 100, 100)),
                List.of(), List.of(), List.of(), rules, resources, extensions, MAP_EXTRAS);

        assertEquals(2, map.resources().size());
        assertEquals(List.of(reference(1L), reference(1L)), map.resources());
    }
}
