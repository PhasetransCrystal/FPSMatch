package net.ptcrys.fpsmatch.editor.common.model.persistence;

import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.model.Ids;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Source and mapping value types of the persistence envelope (contract R06.2a2, ENVELOPE-CODEC
 * section 2 and section 6).
 *
 * <p>
 * This class is the first increment of {@code PersistenceValues}. It publishes six enums and four
 * records and nothing else: no public constant, no public helper, no factory and no additional
 * production type. Later increments (migration record, fingerprint, receipt, envelope, codec and
 * storage) extend the same file and are deliberately not implemented here.
 * </p>
 *
 * <h2>What these values are</h2>
 *
 * <p>
 * They are structural pure values produced at the service boundary. Every reference that is
 * accepted is kept as the same reference the caller supplied, and every text value is kept as the
 * original {@link String}: nothing is normalised, trimmed, case folded, re-encoded, re-parsed or
 * copied here. The two digests of {@link SourceHash} and every {@link ExactJson.FrozenJson} member
 * container are text the boundary already produced; this file stores them, it never recomputes them,
 * and it never claims that the content of a source matched the digest that came with it.
 * </p>
 *
 * <h2>What these values are not</h2>
 *
 * <p>
 * The rules that span several entries - the uniqueness of a {@code (sourceDocument, segments)}
 * pair, the single target of a team, the shared target of spawns and regions, the exactly one
 * definition entry matching the envelope definition id, and the rule that an absent cfg forbids a
 * cfg entry - are <em>aggregate</em> rules. They belong to the later migration aggregate and codec
 * and are deliberately not checked inside a single record, because one value on its own cannot
 * answer them. Similarly an imported or committed source is not certified here: these invariants
 * only prove that the structure is a well formed pure value.
 * </p>
 *
 * <h2>Errors</h2>
 *
 * <p>
 * Every rejected input, including a rejected {@code null} reference, raises
 * {@link IllegalArgumentException} with a plain JDK exception. No error code is published, so no
 * caller can depend on a specific message.
 * </p>
 */
public final class PersistenceValues {

    /**
     * Maximum UTF-16 character length accepted for {@link SourceScope#legacyMapKey()}.
     *
     * <p>
     * It is the UTF-16 length that is checked first, exactly as written in the contract; the
     * decoded UTF-8 budget of the same field is {@link #MAX_LEGACY_MAP_KEY_UTF8_BYTES}.
     * </p>
     */
    private static final int MAX_LEGACY_MAP_KEY_CHARS = 65536;

    /** Maximum decoded UTF-8 bytes accepted for {@link SourceScope#legacyMapKey()}. */
    private static final int MAX_LEGACY_MAP_KEY_UTF8_BYTES = 65536;

    /** Maximum ASCII characters accepted for {@link SourceScope#modeId()}. */
    private static final int MAX_MODE_ID_LENGTH = 191;

    /** Maximum number of entries accepted for {@link IdMapEntry#sourceSegments()}. */
    private static final int MAX_SOURCE_SEGMENTS = 64;

    /** Maximum decoded UTF-8 bytes accepted for one entry of {@link IdMapEntry#sourceSegments()}. */
    private static final int MAX_SEGMENT_UTF8_BYTES = 1024;

    /** Required text length of each digest carried by {@link SourceHash}. */
    private static final int SHA256_HEX_LENGTH = 64;

    private PersistenceValues() {}

    /**
     * Kind of identity an {@link IdMapEntry} resolves to.
     *
     * <p>
     * The kind is the wire kind of the target, not a statement about the document the entry came
     * from: only {@link #DEFINITION} pins its source document, see
     * {@link IdMapEntry#IdMapEntry}.
     */
    public enum IdKind {
        /** The single map definition of the envelope. */
        DEFINITION,
        /** A team inside the definition. */
        TEAM,
        /** A spawn inside the definition. */
        SPAWN,
        /** A region inside the definition. */
        REGION
    }

    /**
     * Document family an identity was read from.
     *
     * <p>
     * It names the origin document only; it carries no precedence, no currency and no merge
     * order.
     * </p>
     */
    public enum SourceDocument {
        /** The JSON definition document. */
        JSON,
        /** The legacy {@code .cfg} document. */
        CFG
    }

    /**
     * How the bytes of a source were obtained.
     *
     * <p>
     * The kind describes the transport of the bytes. It is stored next to the digests of
     * {@link SourceHash} but it neither proves nor is proven by them.
     * </p>
     */
    public enum SourceKind {
        /** Raw file bytes were read. */
        FILE_BYTES,
        /** Text was obtained as JDBC UTF-8 text. */
        JDBC_UTF8_TEXT
    }

    /**
     * Whether a legacy {@code .cfg} side file was present.
     *
     * <p>
     * This is a value of the source boundary. It is not derived from the {@code cfg} field of
     * {@link SourceHashes}, which is simply an optional hash; the two belong to different records
     * and are reconciled by the later migration aggregate.
     * </p>
     */
    public enum CfgState {
        /** The cfg side file was present. */
        PRESENT,
        /** The cfg side file was absent. */
        ABSENT
    }

    /**
     * What to do when the cfg side file is absent.
     *
     * <p>
     * A policy value only; it performs no fallback and reads nothing.
     * </p>
     */
    public enum MissingCfgPolicy {
        /** Reject the operation. */
        REJECT,
        /** Continue with provider defaults. */
        PROVIDER_DEFAULTS
    }

    /**
     * Which persistence operation produced an envelope.
     *
     * <p>
     * A label only; it selects no behaviour in this file.
     * </p>
     */
    public enum OperationKind {
        /** An ordinary map save. */
        SAVE_MAP,
        /** A migration of an existing map. */
        MIGRATE_MAP
    }

    /**
     * Identity of a source scope: which world, which mode and which legacy map key were read.
     *
     * @param worldId      identity of the world the source belongs to, kept as the same typed value,
     *                     never derived from a directory or a save name
     * @param modeId       the {@code namespace:path} mode identifier, kept as the same {@link String},
     *                     never normalised, never case folded and never resolved against a registry; an unknown
     *                     but syntactically legal mode is accepted
     * @param legacyMapKey the original legacy map key, an origin identity and not a file path, so
     *                     empty, blank, control characters, dots, separators, colons and emoji all stay as they
     *                     were given and are never sanitised or truncated
     * @param members      the frozen member container, the same non null object shaped
     *                     {@link ExactJson.FrozenJson} reference the caller supplied
     */
    public record SourceScope(Ids.WorldId worldId, String modeId, String legacyMapKey,
                              ExactJson.FrozenJson members) {

        /**
         * Validates every component and keeps the accepted references unchanged.
         *
         * @throws IllegalArgumentException if any component is null, if {@code modeId} is not a
         *                                  legal {@code namespace:path} of at most 191 ASCII characters, if
         *                                  {@code legacyMapKey} exceeds 65536 UTF-16 characters or 65536 decoded UTF-8
         *                                  bytes
         *                                  or holds an unpaired surrogate, or if {@code members} is not an object
         *                                  shaped
         *                                  frozen JSON value
         */
        public SourceScope {
            requireReference(worldId, "worldId");
            modeId = requireModeId(modeId);
            legacyMapKey = requireText(legacyMapKey, "legacyMapKey",
                    MAX_LEGACY_MAP_KEY_CHARS, MAX_LEGACY_MAP_KEY_UTF8_BYTES);
            members = requireMembers(members);
        }
    }

    /**
     * The two digests of one source together with the transport kind they were read with.
     *
     * <p>
     * The digests are lower case 64 character SHA-256 text as produced at the service boundary.
     * They are stored, never recomputed and never substituted for one another: an all zero digest is
     * legal and two different digests are legal as well. This record therefore records digests, it
     * does not itself certify that the source content matched them.
     * </p>
     *
     * @param sourceKind      how the bytes were obtained
     * @param rawSha256       SHA-256 of the raw source bytes
     * @param canonicalSha256 SHA-256 of the canonical form of the source
     * @param members         the frozen member container, the same non null object shaped reference
     */
    public record SourceHash(SourceKind sourceKind, String rawSha256, String canonicalSha256,
                             ExactJson.FrozenJson members) {

        /**
         * Validates every component and keeps the accepted references unchanged.
         *
         * @throws IllegalArgumentException if any component is null, if either digest is not
         *                                  exactly 64 lower case ASCII hexadecimal characters, or if {@code members} is
         *                                  not
         *                                  an object shaped frozen JSON value
         */
        public SourceHash {
            sourceKind = requireReference(sourceKind, "sourceKind");
            rawSha256 = requireDigest(rawSha256, "rawSha256");
            canonicalSha256 = requireDigest(canonicalSha256, "canonicalSha256");
            members = requireMembers(members);
        }
    }

    /**
     * The hashes of the JSON source and, optionally, of the cfg source.
     *
     * <p>
     * This value is not the result of reading a source. The optional cfg hash is an
     * {@link Optional} that is kept as supplied: an empty optional is a legal, meaningful value and
     * is neither an error nor an assertion that the cfg file was absent. Whether a cfg was present
     * is expressed by {@link CfgState} in the later migration record.
     * </p>
     *
     * @param json    the required JSON source hash
     * @param cfg     the optional cfg source hash, kept as the same {@link Optional} value
     * @param members the frozen member container, the same non null object shaped reference
     */
    public record SourceHashes(SourceHash json, Optional<SourceHash> cfg,
                               ExactJson.FrozenJson members) {

        /**
         * Validates every component and keeps the accepted references unchanged.
         *
         * @throws IllegalArgumentException if any component is null, which for {@code cfg} means a
         *                                  null {@link Optional} and not an empty one, or if {@code members} is not an
         *                                  object
         *                                  shaped frozen JSON value
         */
        public SourceHashes {
            json = requireReference(json, "json");
            cfg = requireReference(cfg, "cfg");
            members = requireMembers(members);
        }
    }

    /**
     * One entry of the source to identity mapping.
     *
     * <p>
     * The source segments are an independent list value, not a JSON pointer and not a structural
     * path: the separators {@code '/'}, {@code '.'} and {@code ':'} inside a segment are ordinary
     * text, so segments are never split, joined or reinterpreted, duplicate segments are allowed,
     * and two entries that differ only in how their segments are divided are different values.
     * </p>
     *
     * <p>
     * {@link IdKind#DEFINITION} is the one kind whose source document is pinned by this record:
     * it must be {@link SourceDocument#JSON} with no segments at all, because the definition is the
     * document itself and never an anchor inside it. Every other kind may come from JSON or from CFG
     * and may carry empty segments. Nothing here rewrites the origin anchor of a kind, and the
     * cross entry rules - the uniqueness of a {@code (sourceDocument, segments)} pair, the single
     * target of a team, the shared target of spawns and regions, and the exactly one definition
     * entry matching the envelope definition id - are aggregate rules of the later migration
     * aggregate and codec. Two records that would collide there can therefore both be constructed
     * here.
     * </p>
     *
     * @param sourceDocument the document the entry was read from
     * @param kind           the kind of the target identity
     * @param sourceSegments the source anchor segments, at most 64 entries of at most 1024 decoded
     *                       UTF-8 bytes each, copied once into an unmodifiable list
     * @param id             the target identity, any non null {@link UUID} including the all zero one, never
     *                       parsed, completed, routed or looked up
     * @param members        the frozen member container, the same non null object shaped reference
     */
    public record IdMapEntry(SourceDocument sourceDocument, IdKind kind, List<String> sourceSegments,
                             UUID id, ExactJson.FrozenJson members) {

        /**
         * Validates every component and copies the segment list exactly once.
         *
         * @throws IllegalArgumentException if any component is null, if the supplied segment list
         *                                  reports more than 64 entries or yields a wrong number of them, if any
         *                                  segment is
         *                                  null, holds an unpaired surrogate or exceeds 1024 decoded UTF-8 bytes, if
         *                                  the
         *                                  combination of kind and document is not legal, or if {@code members} is not
         *                                  an
         *                                  object shaped frozen JSON value
         */
        public IdMapEntry {
            sourceDocument = requireReference(sourceDocument, "sourceDocument");
            kind = requireReference(kind, "kind");
            sourceSegments = requireSegments(sourceSegments);
            id = requireReference(id, "id");
            members = requireMembers(members);
            requireDefinitionAnchor(sourceDocument, kind, sourceSegments);
        }
    }

    // ------------------------------------------------------------------
    // shared validation
    // ------------------------------------------------------------------

    /**
     * Requires a non null reference and returns it unchanged.
     *
     * @param value candidate reference
     * @param name  field name used in the message
     * @param <T>   reference type
     * @return the same reference
     * @throws IllegalArgumentException if {@code value} is null, never {@link NullPointerException}
     */
    private static <T> T requireReference(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    /**
     * Requires a frozen member container that is a non null object.
     *
     * <p>
     * The frozen value is examined only through its canonical text, whose first non escaped
     * character of an object is {@code '{'}. The same reference is returned, never a parse, a deep
     * copy or a re-freeze, so unknown members, empty keys, JSON nulls and exact large numbers are
     * all carried over untouched; collision with reserved names and the complete budget are checks
     * of the later codec.
     * </p>
     *
     * @param members candidate member container
     *
     * @return the same reference
     * @throws IllegalArgumentException if {@code members} is null or is not an object shaped frozen
     *                                  JSON value
     */
    private static ExactJson.FrozenJson requireMembers(ExactJson.FrozenJson members) {
        requireReference(members, "members");
        final String canonical = members.canonical();
        if (canonical.isEmpty() || canonical.charAt(0) != '{') {
            throw new IllegalArgumentException("members must be an object shaped frozen JSON value");
        }
        return members;
    }

    /**
     * Requires a SHA-256 digest as lower case 64 character ASCII hexadecimal text.
     *
     * <p>
     * The length is checked before any character is read, and then one single pass checks the
     * alphabet. The text is not trimmed, not case folded, not recomputed and never turned into
     * bytes, so an all zero digest and two different digests are both accepted.
     * </p>
     *
     * @param digest candidate digest text
     * @param name   field name used in the message
     * @return the same {@link String}
     * @throws IllegalArgumentException if {@code digest} is null, is not 64 characters long, or
     *                                  holds a character outside {@code [0-9a-f]}
     */
    private static String requireDigest(String digest, String name) {
        requireReference(digest, name);
        if (digest.length() != SHA256_HEX_LENGTH) {
            throw new IllegalArgumentException(
                    name + " must be exactly " + SHA256_HEX_LENGTH + " characters, got " + digest.length());
        }
        for (int i = 0; i < SHA256_HEX_LENGTH; i++) {
            final char c = digest.charAt(i);
            final boolean lowerHex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!lowerHex) {
                throw new IllegalArgumentException(
                        name + " must be lower case hexadecimal, found '" + c + "' at index " + i);
            }
        }
        return digest;
    }

    /**
     * Requires a legal {@code namespace:path} mode identifier.
     *
     * <p>
     * Order of checks: null, then the 191 character limit, then the single colon, then the two
     * non empty sides, then the ASCII alphabet of each side character by character. The namespace
     * accepts {@code [a-z0-9_.-]} and the path accepts {@code [a-z0-9_./-]}, so the path may hold a
     * slash and the namespace may not. The value is not matched against a regular expression, not
     * encoded to bytes, not trimmed, not case folded, not NFC normalised and not given a default
     * namespace; an unknown but syntactically legal mode is accepted, and the original
     * {@link String} is returned as the same reference.
     * </p>
     *
     * @param modeId candidate mode identifier
     * @return the same {@link String}
     * @throws IllegalArgumentException if {@code modeId} is null, is longer than 191 characters,
     *                                  does not hold exactly one colon with non empty sides, or holds a character
     *                                  outside the
     *                                  alphabet of its side
     */
    private static String requireModeId(String modeId) {
        requireReference(modeId, "modeId");
        final int length = modeId.length();
        if (length > MAX_MODE_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "modeId must be at most " + MAX_MODE_ID_LENGTH + " characters, got " + length);
        }
        int colon = -1;
        for (int i = 0; i < length; i++) {
            if (modeId.charAt(i) == ':') {
                if (colon >= 0) {
                    throw new IllegalArgumentException(
                            "modeId must hold exactly one colon, found another at index " + i);
                }
                colon = i;
            }
        }
        if (colon < 0) {
            throw new IllegalArgumentException("modeId must hold exactly one colon, found none");
        }
        if (colon == 0 || colon == length - 1) {
            throw new IllegalArgumentException("modeId must have a non empty namespace and path");
        }
        for (int i = 0; i < colon; i++) {
            if (!isNamespaceChar(modeId.charAt(i))) {
                throw new IllegalArgumentException(
                        "modeId namespace must be ASCII [a-z0-9_.-], found '" + modeId.charAt(i) + "' at index " + i);
            }
        }
        for (int i = colon + 1; i < length; i++) {
            if (!isPathChar(modeId.charAt(i))) {
                throw new IllegalArgumentException(
                        "modeId path must be ASCII [a-z0-9_./-], found '" + modeId.charAt(i) + "' at index " + i);
            }
        }
        return modeId;
    }

    /** Tests one namespace character of a mode identifier. */
    private static boolean isNamespaceChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
    }

    /** Tests one path character of a mode identifier. */
    private static boolean isPathChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '/' || c == '-';
    }

    /**
     * Requires text whose UTF-16 length and decoded UTF-8 size both stay inside their budgets.
     *
     * <p>
     * The UTF-16 length is checked first, before any character is scanned. The scan is then a
     * single left to right pass that adds one, two, three or four to a running total: ASCII adds
     * one, {@code U+0080..U+07FF} adds two, another non surrogate BMP character adds three, and a
     * high surrogate followed by a low surrogate adds four and consumes both. An unpaired surrogate
     * in either role is rejected at once, and the running total is compared with the byte budget
     * after every addition, so an over budget value is rejected as soon as it passes the limit
     * instead of after a second scan. Nothing is encoded to a byte array, normalised, cloned or
     * sub stringed, and the original {@link String} is returned as the same reference.
     * </p>
     *
     * @param text         candidate text
     * @param name         field name used in the message
     * @param maxChars     UTF-16 length budget
     * @param maxUtf8Bytes decoded UTF-8 byte budget
     * @return the same {@link String}
     * @throws IllegalArgumentException if {@code text} is null, is longer than {@code maxChars}, or
     *                                  holds an unpaired surrogate or more than {@code maxUtf8Bytes} decoded bytes
     */
    private static String requireText(String text, String name, int maxChars, int maxUtf8Bytes) {
        requireReference(text, name);
        final int length = text.length();
        if (length > maxChars) {
            throw new IllegalArgumentException(
                    name + " must be at most " + maxChars + " UTF-16 characters, got " + length);
        }
        int bytes = 0;
        for (int i = 0; i < length; i++) {
            final char c = text.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 >= length || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    throw new IllegalArgumentException(
                            name + " has an unpaired high surrogate at index " + i);
                }
                bytes += 4;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException(
                        name + " has an unpaired low surrogate at index " + i);
            } else {
                bytes += 3;
            }
            if (bytes > maxUtf8Bytes) {
                throw new IllegalArgumentException(
                        name + " must be at most " + maxUtf8Bytes + " decoded UTF-8 bytes");
            }
        }
        return text;
    }

    /**
     * Requires the source segments and copies them exactly once into an immutable list.
     *
     * <p>
     * The reported size is read exactly once and captured. It is rejected before anything else if
     * it is negative or larger than 64, and the rejection happens before any iteration, before any
     * {@code get} or {@code toArray} call and before any allocation sized by the reported value, so
     * a list that claims an impossible size cannot make this method allocate. At most that many
     * elements are then taken with {@code next} and validated into a local list: a shorter list, a
     * null segment and a segment that is not legal Unicode or exceeds 1024 decoded UTF-8 bytes are
     * all rejected, and one extra {@code hasNext} check at the end rejects a list that still holds
     * more entries without calling {@code next} again, so an endless source that under reports or
     * claims zero is rejected after at most 64 reads. Every segment keeps its own identity as the
     * same {@link String}, and empty segments, control characters and separator characters are all
     * legal, so the value carries slash, dot and colon as ordinary text and duplicate segments are
     * allowed. The captured size is never read a second time, the source list is never turned into
     * an array, and the source is never trusted as a whole. Only after every check has passed is the
     * local list copied once with {@link List#copyOf}, which is the caller visible unmodifiable
     * value. A source that is mutated concurrently by the caller during this call is not covered by
     * any success guarantee, and no {@link Error} or {@link Throwable} is caught.
     * </p>
     *
     * @param sourceSegments candidate segments, never null
     * @return an unmodifiable list of the same {@link String} references in the original order
     * @throws IllegalArgumentException if {@code sourceSegments} is null, reports a negative or
     *                                  oversized size, yields fewer or more than the reported number of entries, or
     *                                  holds a
     *                                  null, an unpaired surrogate or an over budget segment
     */
    private static List<String> requireSegments(List<String> sourceSegments) {
        requireReference(sourceSegments, "sourceSegments");
        final int captured = sourceSegments.size();
        if (captured < 0 || captured > MAX_SOURCE_SEGMENTS) {
            throw new IllegalArgumentException(
                    "sourceSegments must hold at most " + MAX_SOURCE_SEGMENTS + " entries, reported " + captured);
        }
        final List<String> validated = new ArrayList<>(captured);
        int seen = 0;
        final java.util.Iterator<String> iterator = sourceSegments.iterator();
        while (seen < captured) {
            if (!iterator.hasNext()) {
                throw new IllegalArgumentException(
                        "sourceSegments reported " + captured + " entries but yielded only " + seen);
            }
            validated.add(requireText(iterator.next(), "sourceSegments[" + seen + "]",
                    MAX_SEGMENT_UTF8_BYTES, MAX_SEGMENT_UTF8_BYTES));
            seen++;
        }
        if (iterator.hasNext()) {
            throw new IllegalArgumentException(
                    "sourceSegments reported " + captured + " entries but holds more");
        }
        return List.copyOf(validated);
    }

    /**
     * Requires the one document and segment combination this record pins itself.
     *
     * <p>
     * A definition entry is the document itself, so it must come from JSON and must carry no
     * segment at all; a cfg sourced or anchored definition would claim that the definition lives
     * inside a legacy file. Every other kind accepts both documents and any segment count,
     * including zero and the repeated separators that are ordinary text. The remaining cross entry
     * rules are aggregate rules and are deliberately not checked here.
     * </p>
     *
     * @param sourceDocument the document the entry was read from
     * @param kind           the kind of the target identity
     * @param sourceSegments the already validated segments
     * @throws IllegalArgumentException if the kind is a definition and the document is not JSON or
     *                                  the segments are not empty
     */
    private static void requireDefinitionAnchor(SourceDocument sourceDocument, IdKind kind,
                                                List<String> sourceSegments) {
        if (kind != IdKind.DEFINITION) {
            return;
        }
        if (sourceDocument != SourceDocument.JSON) {
            throw new IllegalArgumentException("a DEFINITION entry must come from the JSON document");
        }
        if (!sourceSegments.isEmpty()) {
            throw new IllegalArgumentException("a DEFINITION entry must carry no source segment");
        }
    }

    // ------------------------------------------------------------------
    // R06.2a3: the migration aggregate candidate
    // ------------------------------------------------------------------

    /** Maximum number of entries accepted for {@link MigrationRecord#idMap()}. */
    private static final int MAX_ID_MAP_ENTRIES = 17473;

    /** Maximum number of {@link IdKind#TEAM} targets accepted in one migration candidate. */
    private static final int MAX_TEAM_TARGETS = 64;

    /** Maximum number of {@link IdKind#SPAWN} targets accepted in one migration candidate. */
    private static final int MAX_SPAWN_TARGETS = 16384;

    /** Maximum number of {@link IdKind#REGION} targets accepted in one migration candidate. */
    private static final int MAX_REGION_TARGETS = 1024;

    /**
     * One structurally complete migration candidate: a source scope, the two digests of that source,
     * the cfg state with its owned optionals, the source to identity mapping, and the three frozen
     * JSON containers of the legacy payload and its members.
     *
     * <p>
     * This is a pure structural candidate and not a committed migration. It never reads a file,
     * never authenticates a raw or canonical digest, never chooses a provider default and never
     * recomputes a digest, and it never claims that a source matched the digest that came with it.
     * Every accepted reference is kept as the same reference the caller supplied, except the entry
     * list, which is copied exactly once into an immutable value.
     * </p>
     *
     * <p>
     * The cfg combination is fixed: a {@link CfgState#PRESENT} candidate must carry a cfg source
     * hash, a legacy cfg and no missing cfg policy, while a {@link CfgState#ABSENT} candidate must
     * carry no cfg source hash, no legacy cfg and exactly one missing cfg policy. Nothing is filled
     * in, defaulted or repaired to make a broken combination pass. An absent cfg with
     * {@link MissingCfgPolicy#REJECT} is a legal uncommitted candidate: a read error is never
     * rewritten as {@code ABSENT}, and it is a later service that must refuse the migration.
     * </p>
     *
     * <p>
     * {@link SourceKind} of a {@link SourceHash} only keeps the kind a digest was read with; no
     * JSON file, CFG file or JDBC route is guessed here. Routing and the digest comparison against
     * the real raw and canonical copies belong to the later source reader and codec.
     * </p>
     *
     * @param sourceScope      the one immutable scope shared by the whole mapping
     * @param sourceHashes     the digests of the source together with their own member container
     * @param cfgState         whether a legacy cfg exists at all
     * @param missingCfgPolicy the policy when the cfg is absent, present exactly then
     * @param idMap            the source to identity mapping, at most 17473 entries, copied once into an
     *                         unmodifiable list in the original order
     * @param legacyJson       the authenticated legacy JSON root, kept as the same reference whatever its
     *                         frozen shape is
     * @param legacyCfg        the authenticated legacy cfg root, present exactly when the cfg exists; a
     *                         present value may itself describe JSON null, which is different from an empty optional
     * @param members          the frozen member container of this record, the same non null object shaped
     *                         reference
     */
    public record MigrationRecord(SourceScope sourceScope, SourceHashes sourceHashes,
                                  CfgState cfgState, Optional<MissingCfgPolicy> missingCfgPolicy, List<IdMapEntry> idMap,
                                  ExactJson.FrozenJson legacyJson, Optional<ExactJson.FrozenJson> legacyCfg,
                                  ExactJson.FrozenJson members) {

        /**
         * Validates the references, then the cfg combination, then copies the entry list once and
         * checks the aggregate constraints.
         *
         * @throws IllegalArgumentException if any component is null, which for the three optionals
         *                                  means a null {@link Optional} rather than an empty one, if {@code members}
         *                                  is not
         *                                  an object shaped frozen JSON value, if the cfg combination is not one of the
         *                                  two
         *                                  legal ones, if the entry list reports a negative or oversized size, yields a
         *                                  wrong
         *                                  number of entries or holds a null entry, or if the mapping breaks one of the
         *                                  aggregate constraints described by {@link #idMap()}
         */
        public MigrationRecord {
            sourceScope = requireReference(sourceScope, "sourceScope");
            sourceHashes = requireReference(sourceHashes, "sourceHashes");
            cfgState = requireReference(cfgState, "cfgState");
            missingCfgPolicy = requireReference(missingCfgPolicy, "missingCfgPolicy");
            idMap = requireReference(idMap, "idMap");
            legacyJson = requireReference(legacyJson, "legacyJson");
            legacyCfg = requireReference(legacyCfg, "legacyCfg");
            members = requireMembers(members);
            requireCfgCombination(cfgState, sourceHashes.cfg(), missingCfgPolicy, legacyCfg);
            idMap = requireIdMap(idMap, cfgState);
        }
    }

    /**
     * The complete source position of one entry inside the migration scope.
     *
     * <p>
     * It is exactly the document and the whole segment list. It deliberately holds neither the
     * kind nor the target identity, it forms no JSON pointer, it concatenates no string and it never
     * normalises an index or a numeric looking segment, so {@code ["a/b"]} and {@code ["a", "b"]},
     * {@code ["01"]} and {@code ["1"]}, and any NFC and NFD spelling stay different positions.
     * Structural equality and hash code of the immutable segment list are the only identity used.
     * </p>
     */
    private record SourceLocation(SourceDocument sourceDocument, List<String> sourceSegments) {

        private SourceLocation {
            requireReference(sourceDocument, "sourceDocument");
            requireReference(sourceSegments, "sourceSegments");
        }
    }

    /**
     * Requires one of the two legal cfg combinations and rejects every other one.
     *
     * <p>
     * A present cfg needs a cfg digest, a legacy cfg, and no missing cfg policy; an absent cfg
     * needs no cfg digest, no legacy cfg, and exactly one missing cfg policy. The absent combination
     * is legal for both policies, so a candidate that carries {@code REJECT} is still constructible
     * and only a later service refuses the migration. No field is defaulted, substituted or invented
     * to repair a broken combination.
     * </p>
     *
     * @param cfgState         the state under test
     * @param sourceCfg        the cfg digest of the source hashes
     * @param missingCfgPolicy the policy component
     * @param legacyCfg        the legacy cfg component
     * @throws IllegalArgumentException if the combination is not one of the two legal ones
     */
    private static void requireCfgCombination(CfgState cfgState, Optional<SourceHash> sourceCfg,
                                              Optional<MissingCfgPolicy> missingCfgPolicy, Optional<ExactJson.FrozenJson> legacyCfg) {
        if (cfgState == CfgState.PRESENT) {
            if (!sourceCfg.isPresent()) {
                throw new IllegalArgumentException(
                        "a PRESENT cfg state needs a cfg source hash");
            }
            if (!legacyCfg.isPresent()) {
                throw new IllegalArgumentException("a PRESENT cfg state needs a legacy cfg");
            }
            if (missingCfgPolicy.isPresent()) {
                throw new IllegalArgumentException(
                        "a PRESENT cfg state must not carry a missing cfg policy");
            }
            return;
        }
        if (sourceCfg.isPresent()) {
            throw new IllegalArgumentException(
                    "an ABSENT cfg state must not carry a cfg source hash");
        }
        if (legacyCfg.isPresent()) {
            throw new IllegalArgumentException("an ABSENT cfg state must not carry a legacy cfg");
        }
        if (!missingCfgPolicy.isPresent()) {
            throw new IllegalArgumentException(
                    "an ABSENT cfg state needs a missing cfg policy");
        }
    }

    /**
     * Requires the bounded entry list and every aggregate constraint that spans entries.
     *
     * <p>
     * The reported size is read exactly once and captured before any iteration, any capacity
     * allocation, any {@code get} or any {@code toArray}: a negative size, a size above 17473, and a
     * declared impossible size are all rejected at once, so a list that claims zero entries while
     * being endless is rejected without a single {@code next} call. At most the reported number of
     * entries are then taken with {@code next} into a local list; a shorter list, one extra
     * {@code hasNext} at the end, and a null entry are all rejected, and the size is never read a
     * second time. A concurrent mutation by the caller is not covered by any success guarantee, and
     * no {@link Error} or {@link Throwable} is caught.
     * </p>
     *
     * <p>
     * Each entry is checked in a fixed order: first its own kind quota, then whether an absent cfg
     * forbids the entry, then the uniqueness of its complete source position, then the uniqueness of
     * its target inside its own domain, and only then is it published into the local list. The source
     * position is the document together with the whole segment list, so two entries of different
     * kinds and different targets still collide when they claim the same position, and an entry that
     * differs only in the target collides as well. Source uniqueness and target uniqueness are
     * independent conditions and each is reported on its own.
     * </p>
     *
     * <p>
     * The target domains are separate sets: teams have their own, while spawns and regions share
     * one. The same UUID may therefore be a definition, a team and an object at the same time, and
     * the three domains are never merged. {@link IdKind#DEFINITION} has no target domain of its own
     * and must appear exactly once, so an empty mapping is not a legal candidate; the envelope
     * definition id comparison belongs to the codec, because this record has no target.
     * </p>
     *
     * @param idMap    the candidate entries, never null
     * @param cfgState the already checked cfg state
     * @return an unmodifiable list of the same entry references in the original order
     * @throws IllegalArgumentException if the size is negative or above 17473, fewer or more entries
     *                                  are yielded than reported, an entry is null, a kind quota, the absent cfg rule,
     *                                  the
     *                                  source position rule, the target rule or the exactly one definition rule is
     *                                  broken
     */
    private static List<IdMapEntry> requireIdMap(List<IdMapEntry> idMap, CfgState cfgState) {
        final int captured = idMap.size();
        if (captured < 0 || captured > MAX_ID_MAP_ENTRIES) {
            throw new IllegalArgumentException(
                    "idMap must hold at most " + MAX_ID_MAP_ENTRIES + " entries, reported " + captured);
        }
        final List<IdMapEntry> validated = new ArrayList<>(captured);
        final java.util.Set<SourceLocation> sources = new java.util.HashSet<>();
        final java.util.Set<UUID> teamTargets = new java.util.HashSet<>();
        final java.util.Set<UUID> objectTargets = new java.util.HashSet<>();
        int definitions = 0;
        int teams = 0;
        int spawns = 0;
        int regions = 0;
        int seen = 0;
        final java.util.Iterator<IdMapEntry> iterator = idMap.iterator();
        while (seen < captured) {
            if (!iterator.hasNext()) {
                throw new IllegalArgumentException(
                        "idMap reported " + captured + " entries but yielded only " + seen);
            }
            final IdMapEntry entry = requireReference(iterator.next(), "idMap[" + seen + "]");

            // 1. this kind's own quota, checked before the entry is published anywhere.
            switch (entry.kind()) {
                case DEFINITION -> {
                    definitions++;
                    if (definitions > 1) {
                        throw new IllegalArgumentException(
                                "a migration candidate accepts at most one DEFINITION entry");
                    }
                }
                case TEAM -> {
                    teams++;
                    if (teams > MAX_TEAM_TARGETS) {
                        throw new IllegalArgumentException("a migration candidate accepts at most " + MAX_TEAM_TARGETS + " TEAM entries");
                    }
                }
                case SPAWN -> {
                    spawns++;
                    if (spawns > MAX_SPAWN_TARGETS) {
                        throw new IllegalArgumentException("a migration candidate accepts at most " + MAX_SPAWN_TARGETS + " SPAWN entries");
                    }
                }
                case REGION -> {
                    regions++;
                    if (regions > MAX_REGION_TARGETS) {
                        throw new IllegalArgumentException("a migration candidate accepts at most " + MAX_REGION_TARGETS + " REGION entries");
                    }
                }
            }

            // 2. an absent cfg forbids any cfg sourced or cfg anchored entry.
            if (cfgState == CfgState.ABSENT && entry.sourceDocument() == SourceDocument.CFG) {
                throw new IllegalArgumentException(
                        "an ABSENT cfg state forbids a CFG sourced entry");
            }

            // 3. the complete source position must be unique inside this one scope.
            if (!sources.add(new SourceLocation(entry.sourceDocument(), entry.sourceSegments()))) {
                throw new IllegalArgumentException(
                        "two entries must not claim the same source document and segments");
            }

            // 4. the target must be unique inside its own domain, if the kind has one.
            switch (entry.kind()) {
                case TEAM -> {
                    if (!teamTargets.add(entry.id())) {
                        throw new IllegalArgumentException(
                                "two TEAM entries must not share the same target UUID");
                    }
                }
                case SPAWN, REGION -> {
                    if (!objectTargets.add(entry.id())) {
                        throw new IllegalArgumentException(
                                "SPAWN and REGION entries must not share the same target UUID");
                    }
                }
                case DEFINITION -> {
                    // no target domain: a definition is the document itself
                }
            }

            // 5. only now is the entry published into the local list.
            validated.add(entry);
            seen++;
        }
        if (iterator.hasNext()) {
            throw new IllegalArgumentException(
                    "idMap reported " + captured + " entries but holds more");
        }
        if (definitions != 1) {
            throw new IllegalArgumentException(
                    "a migration candidate needs exactly one DEFINITION entry, found " + definitions);
        }
        return List.copyOf(validated);
    }

    /**
     * One fingerprint target: the definition reference plus the frozen member container of that same
     * definition (contract R06.2a4, ENVELOPE-CODEC section 3 and section 6).
     *
     * <p>
     * The target is the reference to one map definition inside one world. It is stored as the same
     * reference the caller supplied: it is never copied, never resolved, never looked up and never
     * compared against a store. The world id and the definition id inside it are deliberately not
     * re-validated here, because {@code DefinitionRef} already certifies that both are present and
     * that a repository lookup always uses the scoped {@code (worldId, definitionId)} pair.
     * </p>
     *
     * <p>
     * The member container is the same frozen value the caller supplied, examined only far enough
     * to prove that it is object shaped.
     * </p>
     */
    public record FingerprintTarget(
                                    net.ptcrys.fpsmatch.editor.common.model.DefinitionRef target,
                                    ExactJson.FrozenJson members) {

        /**
         * @throws IllegalArgumentException if {@code target} is null, if {@code members} is null or
         *                                  if {@code members} is not an object shaped frozen JSON value
         */
        public FingerprintTarget {
            target = requireReference(target, "target");
            members = requireMembers(members);
        }
    }

    /**
     * Every input a later independent fingerprint computation needs, and nothing else (contract
     * R06.2a4, ENVELOPE-CODEC section 3).
     *
     * <p>
     * This record only <em>collects</em> the inputs. It does not compute a fingerprint, it does not
     * verify one, it does not canonicalise anything and it does not authenticate the payload: the
     * fingerprint algorithm itself is a later, independent increment. In the same way
     * {@link #migrationDigest()} is carried as an input value only, so a {@code MIGRATE_MAP} request
     * without a migration digest is structurally legal here and the business rule that binds the
     * digest of a migration to an operation kind belongs to the migration aggregate, not to this
     * value.
     * </p>
     *
     * <p>
     * Every accepted reference is kept as the same reference, including the {@link Optional}
     * itself and its present text, so no parse, no deep copy and no re-freeze happens in this
     * record.
     * </p>
     *
     * @param actorUuid        authenticated actor that issued the request
     * @param target           fingerprint target of the request
     * @param requestId        identity of the request
     * @param expectedRevision revision the request was built against, zero for a create
     * @param payloadDigest    SHA-256 of the request payload as lower case 64 character hexadecimal
     * @param operationKind    kind of operation the request asks for
     * @param migrationDigest  optional SHA-256 of the migration source, in the same digest format
     * @param members          frozen member container of the request
     */
    public record FingerprintInputs(UUID actorUuid, FingerprintTarget target,
                                    Ids.RequestId requestId, long expectedRevision, String payloadDigest,
                                    OperationKind operationKind, Optional<String> migrationDigest,
                                    ExactJson.FrozenJson members) {

        /**
         * Order of checks: every reference and null first, then the digest text of the payload and
         * of a present migration digest, then the member container shape, and the numeric range of
         * {@code expectedRevision} last.
         *
         * @throws IllegalArgumentException if any reference is null, if a digest text is null or is
         *                                  not lower case 64 character hexadecimal, if {@code members} is not object
         *                                  shaped
         *                                  frozen JSON, or if {@code expectedRevision} is negative
         */
        public FingerprintInputs {
            actorUuid = requireReference(actorUuid, "actorUuid");
            target = requireReference(target, "target");
            requestId = requireReference(requestId, "requestId");
            operationKind = requireReference(operationKind, "operationKind");
            migrationDigest = requireReference(migrationDigest, "migrationDigest");
            payloadDigest = requireDigest(payloadDigest, "payloadDigest");
            final String presentMigrationDigest = migrationDigest.orElse(null);
            if (presentMigrationDigest != null) {
                requireDigest(presentMigrationDigest, "migrationDigest");
            }
            members = requireMembers(members);
            if (expectedRevision < 0) {
                throw new IllegalArgumentException(
                        "expectedRevision must not be negative, got " + expectedRevision);
            }
        }
    }

    /**
     * The receipt of one committed request (contract R06.2a4, ENVELOPE-CODEC section 3 and section
     * 6).
     *
     * <p>
     * A receipt records what the service committed. It is not a proof: it stores the fingerprint
     * text and the fingerprint inputs side by side without recomputing or comparing the fingerprint,
     * so a receipt whose fingerprint text does not describe its own inputs is still structurally
     * legal here. The container, the transaction, the history lookup and every budget of the wire
     * form are later increments.
     * </p>
     *
     * <p>
     * The three text and identity fields that describe the same request as
     * {@link #fingerprintInputs()} must agree with it by {@code equals}: the actor, the request
     * identity and the payload digest. They are compared, never replaced, so each value keeps its own
     * reference even when an equal but distinct instance was supplied. The revision rule is complete:
     * a content changing commit of revision {@code n} is exactly {@code n + 1} and must refuse
     * {@link Long#MAX_VALUE} before it adds one, while a commit that changes no content keeps the
     * expected revision and therefore needs an expected revision of at least one.
     * </p>
     *
     * @param actorUuid             authenticated actor that issued the committed request
     * @param requestId             identity of the committed request
     * @param fingerprint           SHA-256 fingerprint text as lower case 64 character hexadecimal
     * @param fingerprintInputs     the complete inputs the fingerprint was computed from
     * @param committedRevision     revision the commit produced
     * @param payloadDigest         SHA-256 of the committed payload as lower case 64 character hexadecimal
     * @param contentChanged        whether the commit changed the content of the definition
     * @param recordedAtEpochMillis commit time in milliseconds since the epoch
     * @param members               frozen member container of the receipt
     */
    public record CommitReceipt(UUID actorUuid, Ids.RequestId requestId, String fingerprint,
                                FingerprintInputs fingerprintInputs, long committedRevision, String payloadDigest,
                                boolean contentChanged, long recordedAtEpochMillis, ExactJson.FrozenJson members) {

        /**
         * Order of checks: every reference and null first, then both digest texts, then the member
         * container shape, then the numeric ranges, and the cross component agreement last.
         *
         * @throws IllegalArgumentException if any reference is null, if a digest text is null or is
         *                                  not lower case 64 character hexadecimal, if {@code members} is not object
         *                                  shaped
         *                                  frozen JSON, if {@code committedRevision} is below one, if
         *                                  {@code recordedAtEpochMillis} is negative, if the actor, the request
         *                                  identity or
         *                                  the payload digest does not equal the corresponding fingerprint input, or if
         *                                  the
         *                                  revision does not follow the exact rule of {@code contentChanged}
         */
        public CommitReceipt {
            actorUuid = requireReference(actorUuid, "actorUuid");
            requestId = requireReference(requestId, "requestId");
            fingerprintInputs = requireReference(fingerprintInputs, "fingerprintInputs");
            fingerprint = requireDigest(fingerprint, "fingerprint");
            payloadDigest = requireDigest(payloadDigest, "payloadDigest");
            members = requireMembers(members);
            if (committedRevision < 1) {
                throw new IllegalArgumentException(
                        "committedRevision must be at least 1, got " + committedRevision);
            }
            if (recordedAtEpochMillis < 0) {
                throw new IllegalArgumentException(
                        "recordedAtEpochMillis must not be negative, got " + recordedAtEpochMillis);
            }
            if (!actorUuid.equals(fingerprintInputs.actorUuid())) {
                throw new IllegalArgumentException(
                        "actorUuid must equal the actor of the fingerprint inputs");
            }
            if (!requestId.equals(fingerprintInputs.requestId())) {
                throw new IllegalArgumentException(
                        "requestId must equal the request of the fingerprint inputs");
            }
            if (!payloadDigest.equals(fingerprintInputs.payloadDigest())) {
                throw new IllegalArgumentException(
                        "payloadDigest must equal the payload digest of the fingerprint inputs");
            }
            final long expectedRevision = fingerprintInputs.expectedRevision();
            if (contentChanged) {
                if (expectedRevision == Long.MAX_VALUE) {
                    throw new IllegalArgumentException(
                            "a content changing commit cannot advance past Long.MAX_VALUE");
                }
                if (committedRevision != expectedRevision + 1) {
                    throw new IllegalArgumentException(
                            "a content changing commit must commit expectedRevision + 1");
                }
            } else {
                if (expectedRevision < 1) {
                    throw new IllegalArgumentException(
                            "a commit that changes no content needs an expected revision of at least 1");
                }
                if (committedRevision != expectedRevision) {
                    throw new IllegalArgumentException(
                            "a commit that changes no content must keep the expected revision");
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // R06.2a5: the definition envelope candidate
    // ---------------------------------------------------------------------------------------------

    /**
     * Maximum number of commit receipts accepted for {@link DefinitionEnvelope#receipts()}.
     *
     * <p>
     * The budget is checked against the size the caller's own {@link List} reports, before that
     * list is traversed, so a list that lies about an enormous size is refused without any
     * allocation proportional to the lie.
     * </p>
     */
    private static final int MAX_RECEIPTS = 1024;

    /**
     * The candidate persistence envelope of one map definition (contract R06.2a5, ENVELOPE-CODEC
     * section 6).
     *
     * <p>
     * This is a pure structural value: it binds the {@link
     * net.ptcrys.fpsmatch.editor.common.model.DefinitionRef} the payload belongs to, the revision the
     * payload was recorded at, the digest of the exact payload bytes, the already validated {@link
     * net.ptcrys.fpsmatch.editor.common.model.map.MapDefinition} candidate, the optional migration
     * record that produced this revision, the commit receipts known for this definition and the
     * frozen member container the codec carries for the envelope.
     * </p>
     *
     * <h2>What it checks</h2>
     *
     * <p>
     * Every required reference is non-null, {@code revision} is at least one,
     * {@code payloadDigest} is exactly 64 lower case hexadecimal characters and {@code members} is
     * object shaped frozen JSON. The receipt list is copied defensively: at most
     * {@value #MAX_RECEIPTS} entries, read through a single iterator bounded by the size captured
     * once, with every element non-null, and published as an unmodifiable list that keeps the
     * original order, the original element instances and any repetition.
     * </p>
     *
     * <h2>What it deliberately does not check</h2>
     *
     * <p>
     * This envelope is a candidate, not a certified record. It never verifies that {@code target}
     * matches the identity inside {@code payload}, that the payload digest was computed from the
     * payload, that {@code migration} belongs to this target or scope, that a receipt names this
     * target or this revision, that {@code revision} follows the receipt fingerprint, that actors or
     * requests are unique, or anything about reserved keys, fingerprints, metadata budgets or age.
     * Those are cross record rules of the still missing envelope codec: they are intentionally left
     * to that codec, which must reject such an envelope, and their absence here is not permission to
     * commit one. Every reference is kept as supplied, nothing is parsed, deep copied, re-encoded,
     * defaulted or assigned a new identity, and {@link Optional#empty()} simply means that no
     * migration was recorded.
     * </p>
     *
     * @param target        the definition this envelope belongs to; required and not checked against the
     *                      payload identity
     * @param revision      the recorded revision, at least one
     * @param payloadDigest the 64 character lower case hexadecimal digest of the payload bytes; it is
     *                      stored, never recomputed
     * @param payload       the validated map definition candidate, kept by reference
     * @param migration     the migration that produced this revision, or {@link Optional#empty()}
     * @param receipts      the commit receipts, at most {@value #MAX_RECEIPTS}, copied defensively
     * @param members       the frozen member container; it must be object shaped
     * @throws IllegalArgumentException if any required reference is null, if the digest is malformed,
     *                                  if the members are not object shaped frozen JSON, if the revision is below one,
     *                                  or if
     *                                  the receipt list is over budget, dishonest about its size, holds a null element
     *                                  or
     *                                  yields fewer or more elements than it reported
     */
    public record DefinitionEnvelope(
                                     net.ptcrys.fpsmatch.editor.common.model.DefinitionRef target,
                                     long revision, String payloadDigest,
                                     net.ptcrys.fpsmatch.editor.common.model.map.MapDefinition payload,
                                     Optional<MigrationRecord> migration,
                                     List<CommitReceipt> receipts, ExactJson.FrozenJson members) {

        /**
         * Order of checks: the required references first, then the digest text and the member
         * container shape, then the numeric range, and the receipt copy last, so a rejected reference
         * or a rejected shape never costs a traversal of the receipt list.
         *
         * @throws IllegalArgumentException if any required reference is null, if {@code payloadDigest}
         *                                  is null or is not lower case 64 character hexadecimal, if {@code members} is
         *                                  not
         *                                  object shaped frozen JSON, if {@code revision} is below one, or if
         *                                  {@code receipts} is null, over budget, dishonest about its size, holds a
         *                                  null
         *                                  element, or yields fewer or more elements than reported
         */
        public DefinitionEnvelope {
            target = requireReference(target, "target");
            payload = requireReference(payload, "payload");
            migration = requireReference(migration, "migration");
            payloadDigest = requireDigest(payloadDigest, "payloadDigest");
            members = requireMembers(members);
            if (revision < 1) {
                throw new IllegalArgumentException("revision must be at least 1, got " + revision);
            }
            receipts = requireReceipts(receipts);
        }
    }

    /**
     * Validates and copies the receipt list of one {@link DefinitionEnvelope}.
     *
     * <p>
     * The size is read exactly once and decides everything that follows: a negative size or a size
     * above {@link #MAX_RECEIPTS} is refused before any {@code get}, {@code toArray} or iterator call
     * and before any allocation proportional to the reported size. Only then is the list traversed,
     * through its own iterator and at most {@code captured + 1} times, into a local list whose
     * capacity is at most {@value #MAX_RECEIPTS}. A traversal that ends early, a null element, or a
     * traversal that still has an element after the reported count is refused. A second call to
     * {@code size()} is never made, so a list that changes what it reports cannot slip past the
     * captured bound, and the source list is never asked for an array. The returned list is an
     * unmodifiable copy that keeps the input order, the input duplicates and the very same element
     * instances; nothing is deduplicated, sorted or dropped, and no {@code Throwable} is caught.
     * </p>
     *
     * @param receipts the caller supplied list, possibly a hostile or lazy implementation
     * @return an unmodifiable copy holding exactly the captured number of non-null receipts
     * @throws IllegalArgumentException if the list is null, reports a negative or over budget size,
     *                                  yields fewer or more elements than it reported, or yields a null element
     */
    private static List<CommitReceipt> requireReceipts(List<CommitReceipt> receipts) {
        requireReference(receipts, "receipts");
        final int captured = receipts.size();
        if (captured < 0 || captured > MAX_RECEIPTS) {
            throw new IllegalArgumentException("receipts must hold between 0 and " + MAX_RECEIPTS + " entries, reported " + captured);
        }
        final List<CommitReceipt> validated = new ArrayList<>(captured);
        final java.util.Iterator<CommitReceipt> iterator = receipts.iterator();
        int read = 0;
        while (read < captured) {
            if (!iterator.hasNext()) {
                throw new IllegalArgumentException("receipts reported " + captured + " entries but the traversal ended after " + read);
            }
            final CommitReceipt receipt = iterator.next();
            if (receipt == null) {
                throw new IllegalArgumentException("receipts[" + read + "] must not be null");
            }
            validated.add(receipt);
            read++;
        }
        if (iterator.hasNext()) {
            throw new IllegalArgumentException("receipts reported " + captured + " entries but the traversal yields more");
        }
        return List.copyOf(validated);
    }
}
