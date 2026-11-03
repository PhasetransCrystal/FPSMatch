package net.ptcrys.fpsmatch.editor.common.codec;

import net.ptcrys.fpsmatch.editor.common.model.FieldPath;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues;

import java.util.Optional;

/**
 * Streaming SHA-256 fingerprint of the complete canonical request object (R06.2b3, ENVELOPE-CODEC
 * section 3).
 *
 * <p>
 * The fingerprint is the SHA-256 of the canonical UTF-8 bytes of exactly one JSON object: the
 * nine reserved members of the request root plus the two reserved members of its {@code target}
 * object, merged with every unknown member that belongs to those same two owners. The merge is the
 * canonical object rule of the frozen codec: keys ascend by {@link String#compareTo(String)}, the
 * unknown members are consumed straight from the certified canonical text through
 * {@link CanonicalObjectMembers}, and each member is written as its complete quoted key token, a
 * colon and its complete value token, separated by commas and closed by one brace.
 * </p>
 *
 * <p>
 * Nothing is materialised. The only long lived objects of a call are one bounded
 * {@link Utf8Sha256} sink, one {@link CanonicalObjectMembers.Cursor} per merged owner and the single
 * {@link CanonicalObjectMembers.Member} that is currently being merged; unknown keys and values are
 * appended as UTF-16 ranges of the certified canonical text the cursor already holds, never as a
 * {@code substring}, a {@code getBytes} result or a rebuilt tree. The cumulative canonical input of
 * one call may exceed the 8 MiB P02 budget of one member container, because the two owners are two
 * independent containers; the sink budget is the conservative 64 MiB total guard of this call.
 * </p>
 *
 * <h2>Wire reserved collision</h2>
 *
 * <p>
 * Before the sink exists, both owners are walked once with a cursor and every <em>decoded</em>
 * member key is compared, case sensitively, with the reserved key set of its own owner. A collision
 * is reported with code {@code RESERVED_COLLISION}, the owner of the offending object and the path
 * of the offending key relative to that owner, and no cause. The comparison uses the decoded key of
 * the member list of the owning object only: a member whose raw spelling differs from its decoded
 * text still collides with the reserved key of that owner; a differently cased member, a nested
 * unknown member and the reserved keys of the other owner are not collisions.
 * </p>
 *
 * <h2>What this method is not</h2>
 *
 * <p>
 * It writes and hashes a fingerprint; it authenticates nothing. Actor permissions, the operation
 * kind that an operation is allowed to use, the existence of a payload or of a migration and the
 * consistency of a migration digest with a migration record are all service rules of later
 * increments and are deliberately absent. A value that carries no migration digest writes the JSON
 * literal {@code null} and participates in the hash exactly as it is; it is never replaced by
 * {@code digest(null)} and it is never repaired. An accepted input, an accepted member reference and
 * the certified canonical text of both owners are read only: they are not modified, not normalised,
 * not re-frozen and not copied.
 * </p>
 *
 * <p>
 * This method proves the content identity of one request. It is not a signature, it does not
 * prove who sent the request and it cannot certify that any stored raw text was produced by a
 * trusted actor.
 * </p>
 */
public final class RequestFingerprint {

    /**
     * Rejected reserved member key collision of one fingerprint owner (ENVELOPE-CODEC section 5).
     */
    private static final String CODE_RESERVED_COLLISION = "RESERVED_COLLISION";

    /**
     * Total canonical UTF-8 budget of one fingerprint computation, 64 MiB: the conservative outer
     * guard of the whole canonical request object, not a per-owner budget.
     */
    private static final long MAX_CANONICAL_UTF8_BYTES = 67108864L;

    /** Pointer used to canonicalise {@code expectedRevision} through the frozen exact number rule. */
    private static final String EXPECTED_REVISION_POINTER = "/expectedRevision";

    /** Canonical spelling of the numeric schema and protocol version of this fingerprint schema. */
    private static final String VERSION_ONE = "1";

    /** Canonical spelling of an explicit JSON null written for an absent optional digest. */
    private static final String JSON_NULL = "null";

    private RequestFingerprint() {}

    /**
     * Computes the complete request fingerprint of one value.
     *
     * <p>
     * The result is the lowercase hexadecimal SHA-256 of the canonical UTF-8 bytes of the nine
     * root members {@code actorUuid}, {@code expectedRevision}, {@code migrationDigest},
     * {@code operationKind}, {@code payloadDigest}, {@code protocolVersion}, {@code requestId},
     * {@code schemaVersion} and {@code target}, merged in ascending key order with the unknown
     * members of {@code value.members()}, where {@code target} is itself an object holding
     * {@code definitionId} and {@code worldId} merged the same way with the unknown members of
     * {@code value.target().members()}.
     * </p>
     *
     * <p>
     * The call is independent of every other call: no static state, no cache and no shared sink
     * exists, so two calls with different inputs never influence each other, and the same input
     * always produces the same text. The value and both of its member containers are never
     * modified.
     * </p>
     *
     * @param value the complete fingerprint inputs; a Java {@code null} is rejected first with
     *              {@link IllegalArgumentException}
     * @return the 64 lowercase hexadecimal digits of the complete canonical request object
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @throws EnvelopeException        with code {@code RESERVED_COLLISION} if the decoded key of a member
     *                                  of {@code value.members()} is one of the nine reserved root keys, or if the
     *                                  decoded key of
     *                                  a member of {@code value.target().members()} is {@code definitionId} or
     *                                  {@code worldId};
     *                                  the owner is the root for a root collision and the {@code target} field for a
     *                                  target
     *                                  collision, the relative path is the offending key under that owner, and the
     *                                  cause is
     *                                  {@code null} because no underlying failure exists
     * @throws JsonContractException    if the frozen exact number rule rejects the canonical spelling
     *                                  of {@code expectedRevision}, which cannot happen for the accepted numeric range
     *                                  of the
     *                                  inputs value
     */
    public static String sha256(PersistenceValues.FingerprintInputs value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        requireNoReservedCollision(value);
        final Utf8Sha256 sink = new Utf8Sha256(MAX_CANONICAL_UTF8_BYTES);
        writeRequestObject(sink, value);
        return sink.finish();
    }

    /**
     * Computes the complete migration fingerprint of one value.
     *
     * <p>
     * The digested object is the migration value itself: one canonical object holding the seven
     * reserved members {@code cfgState}, {@code idMap}, {@code legacyCfg}, {@code legacyJson},
     * {@code missingCfgPolicy}, {@code sourceHashes} and {@code sourceScope}, merged in ascending
     * key order with the unknown members of {@code value.members()}. No outer {@code migration}
     * property, no schema version, no authority and no envelope identity is added, so the digest
     * depends on the migration content alone.
     * </p>
     *
     * <p>
     * Every nested owner is written the same way: {@code sourceScope} holds {@code legacyMapKey},
     * {@code modeId} and {@code worldId}, {@code sourceHashes} holds {@code cfg} and {@code json},
     * each source hash holds {@code canonicalSha256}, {@code rawSha256} and {@code sourceKind}, and
     * every entry of the {@code idMap} array holds {@code id}, {@code kind}, {@code sourceDocument}
     * and {@code sourceSegments}. The array keeps its original order and is never sorted, and the
     * member container of one occurrence is merged at that occurrence even when the same frozen
     * reference is used by several owners, so no member is ever deduplicated.
     * </p>
     *
     * <p>
     * The value is treated as the already validated immutable candidate that it is: both stored
     * digests are written as they are and are never recomputed, substituted or cross-checked
     * against the legacy canonical text, no raw source is read, no identity is repaired and no
     * envelope target is verified. A candidate whose cfg is absent with a {@code REJECT} policy is
     * hashed like any other legal combination, and a present cfg whose frozen value describes JSON
     * null writes that canonical null instead of becoming an absent cfg.
     * </p>
     *
     * <p>
     * The call is independent of every other call: no static state, no cache and no shared sink
     * exists. The opaque legacy values are appended from the canonical text of their own frozen
     * container, so every accepted member reference, list, segment and canonical text is preserved
     * and is never modified, re-frozen or copied.
     * </p>
     *
     * @param value the complete migration candidate; a Java {@code null} is rejected first with
     *              {@link IllegalArgumentException}
     * @return the 64 lowercase hexadecimal digits of the complete canonical migration object
     * @throws IllegalArgumentException if {@code value} is {@code null}, or if the complete
     *                                  canonical UTF-8 text exceeds the 64 MiB total guard of this digest, which the
     *                                  sink
     *                                  reports as its own argument failure and which this method never maps to a code,
     *                                  to an
     *                                  envelope error or to a partial digest
     * @throws EnvelopeException        with code {@code RESERVED_COLLISION} if the decoded key of one
     *                                  member collides with the reserved key set of that same owner; the owner is
     *                                  {@code /migration}, {@code /migration/sourceScope},
     *                                  {@code /migration/sourceHashes},
     *                                  {@code /migration/sourceHashes/json}, {@code /migration/sourceHashes/cfg} or
     *                                  {@code /migration/idMap/{index}}, the relative path is the offending key under
     *                                  the root
     *                                  without the owner prefix, and the cause is {@code null} because no underlying
     *                                  failure
     *                                  exists
     */
    public static String migrationSha256(PersistenceValues.MigrationRecord value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        requireNoMigrationCollision(value);
        final Utf8Sha256 sink = new Utf8Sha256(MAX_CANONICAL_UTF8_BYTES);
        writeMigrationObject(sink, value);
        return sink.finish();
    }

    // ------------------------------------------------------------------
    // reserved collision: both owners are walked before the sink exists
    // ------------------------------------------------------------------

    /**
     * Walks the complete member list of both owners and rejects any decoded key that belongs to the
     * reserved key set of that same owner.
     *
     * <p>
     * The walk is exhaustive and happens before any byte is hashed, so a colliding request can
     * never produce a partial digest. The reserved sets are the nine root keys and the two target
     * keys; a member of one owner is never compared against the reserved set of the other owner.
     * </p>
     */
    private static void requireNoReservedCollision(PersistenceValues.FingerprintInputs value) {
        final FieldPath root = FieldPath.root();
        final FieldPath targetOwner = root.child("target");
        walkForReserved(value.members(), root, root, true);
        walkForReserved(value.target().members(), targetOwner, root, false);
    }

    /**
     * Consumes one complete member cursor and rejects the first reserved decoded key it meets.
     *
     * @param members      object shaped frozen member container of the owner that is being checked
     * @param owner        path of the container that owns the offending member
     * @param relativeBase path the reported relative path is built from
     * @param rootKeys     whether the reserved set is the nine root keys instead of the two target keys
     * @throws EnvelopeException with code {@code RESERVED_COLLISION} on the first reserved key
     */
    private static void walkForReserved(ExactJson.FrozenJson members, FieldPath owner,
                                        FieldPath relativeBase, boolean rootKeys) {
        final CanonicalObjectMembers.Cursor cursor = CanonicalObjectMembers.cursor(members);
        while (cursor.hasNext()) {
            final String key = cursor.next().key();
            final boolean reserved = rootKeys ? isReservedRootKey(key) : isReservedTargetKey(key);
            if (reserved) {
                throw new EnvelopeException(CODE_RESERVED_COLLISION, owner,
                        relativeBase.child(key), "reserved member key \"" + key + "\" in " + owner.pointer() + " collides with the fingerprint schema");
            }
        }
    }

    /** Reports whether one decoded key is one of the nine reserved keys of the root object. */
    private static boolean isReservedRootKey(String key) {
        return key.equals("actorUuid") || key.equals("expectedRevision") || key.equals("migrationDigest") || key.equals("operationKind") || key.equals("payloadDigest") || key.equals("protocolVersion") || key.equals("requestId") || key.equals("schemaVersion") || key.equals("target");
    }

    /** Reports whether one decoded key is one of the two reserved keys of the target object. */
    private static boolean isReservedTargetKey(String key) {
        return key.equals("definitionId") || key.equals("worldId");
    }

    // ------------------------------------------------------------------
    // migration reserved collision: every owner is walked in fixed order
    // ------------------------------------------------------------------

    /** Reserved key set of the migration root object itself. */
    private static final int KEYS_MIGRATION = 1;

    /** Reserved key set of the nested {@code sourceScope} owner. */
    private static final int KEYS_SOURCE_SCOPE = 2;

    /** Reserved key set of the nested {@code sourceHashes} owner. */
    private static final int KEYS_SOURCE_HASHES = 3;

    /** Reserved key set of one source hash object, used for both the {@code json} and the cfg hash. */
    private static final int KEYS_SOURCE_HASH = 4;

    /** Reserved key set of one {@code idMap} entry object. */
    private static final int KEYS_ID_MAP_ENTRY = 5;

    /**
     * Walks every owner of the migration in the fixed contract order and rejects the first decoded
     * key that belongs to the reserved key set of that same owner.
     *
     * <p>
     * The walk is exhaustive and happens before any byte is hashed, so a colliding candidate can
     * never produce a partial digest. The order is: the migration members, the {@code sourceScope}
     * members, the {@code sourceHashes} members, the members of the required {@code json} hash, the
     * members of a present {@code cfg} hash, and finally the members of every {@code idMap} entry in
     * its original order. Each owner is compared only against its own key set, case sensitively, so
     * a nested member that carries the same name as a known key of another owner stays legal, and
     * the opaque legacy values are never traversed.
     * </p>
     *
     * <p>
     * The owner is the container that owns the offending member, the relative path is that same
     * decoded key directly under the root without the owner prefix, and the cause is {@code null}
     * because no underlying failure exists.
     * </p>
     */
    private static void requireNoMigrationCollision(PersistenceValues.MigrationRecord value) {
        final FieldPath root = FieldPath.root();
        final FieldPath migration = root.child("migration");
        walkForMigrationReserved(value.members(), migration, root, KEYS_MIGRATION);
        final FieldPath scope = migration.child("sourceScope");
        walkForMigrationReserved(value.sourceScope().members(), scope, root, KEYS_SOURCE_SCOPE);
        final FieldPath hashes = migration.child("sourceHashes");
        walkForMigrationReserved(value.sourceHashes().members(), hashes, root, KEYS_SOURCE_HASHES);
        walkForMigrationReserved(value.sourceHashes().json().members(), hashes.child("json"), root,
                KEYS_SOURCE_HASH);
        final PersistenceValues.SourceHash cfg = value.sourceHashes().cfg().orElse(null);
        if (cfg != null) {
            walkForMigrationReserved(cfg.members(), hashes.child("cfg"), root, KEYS_SOURCE_HASH);
        }
        final FieldPath idMap = migration.child("idMap");
        for (int index = 0; index < value.idMap().size(); index++) {
            walkForMigrationReserved(value.idMap().get(index).members(),
                    idMap.child(Integer.toString(index)), root, KEYS_ID_MAP_ENTRY);
        }
    }

    /**
     * Consumes one complete member cursor of one migration owner and rejects its first reserved
     * decoded key.
     *
     * @param members      object shaped frozen member container of the owner that is being checked
     * @param owner        path of the container that owns the offending member
     * @param relativeBase path the reported relative path is built from, always the root
     * @param keySet       selector of the reserved key set of this owner
     * @throws EnvelopeException with code {@code RESERVED_COLLISION} on the first reserved key
     */
    private static void walkForMigrationReserved(ExactJson.FrozenJson members, FieldPath owner,
                                                 FieldPath relativeBase, int keySet) {
        final CanonicalObjectMembers.Cursor cursor = CanonicalObjectMembers.cursor(members);
        while (cursor.hasNext()) {
            final String key = cursor.next().key();
            if (isReservedMigrationKey(keySet, key)) {
                throw new EnvelopeException(CODE_RESERVED_COLLISION, owner, relativeBase.child(key),
                        "reserved member key \"" + key + "\" in " + owner.pointer() + " collides with the migration fingerprint schema");
            }
        }
    }

    /** Reports whether one decoded key belongs to the reserved set of the selected migration owner. */
    private static boolean isReservedMigrationKey(int keySet, String key) {
        switch (keySet) {
            case KEYS_MIGRATION:
                return key.equals("cfgState") || key.equals("idMap") || key.equals("legacyCfg") || key.equals("legacyJson") || key.equals("missingCfgPolicy") || key.equals("sourceHashes") || key.equals("sourceScope");
            case KEYS_SOURCE_SCOPE:
                return key.equals("legacyMapKey") || key.equals("modeId") || key.equals("worldId");
            case KEYS_SOURCE_HASHES:
                return key.equals("cfg") || key.equals("json");
            case KEYS_SOURCE_HASH:
                return key.equals("canonicalSha256") || key.equals("rawSha256") || key.equals("sourceKind");
            case KEYS_ID_MAP_ENTRY:
                return key.equals("id") || key.equals("kind") || key.equals("sourceDocument") || key.equals("sourceSegments");
            default:
                throw new IllegalArgumentException("unknown reserved key set " + keySet);
        }
    }

    // ------------------------------------------------------------------
    // canonical write: one merged object per owner
    // ------------------------------------------------------------------

    /**
     * Writes the root object of the fingerprint in ascending canonical key order.
     *
     * <p>
     * The nine known members are emitted in the fixed comparison order of the contract. Every
     * known value is written from the already validated input value: the two identities are the
     * canonical lowercase wire text, the two versions are the literal {@code 1}, the two digests are
     * the original 64 lowercase hexadecimal texts, the operation kind is the exact enum name, the
     * expected revision goes through the frozen exact number rule, an absent migration digest is the
     * literal {@code null}, and the target is the nested merged object.
     * </p>
     */
    private static void writeRequestObject(Utf8Sha256 sink,
                                           PersistenceValues.FingerprintInputs value) {
        final Merge merge = new Merge(sink, value.members());
        sink.append("{");
        merge.knownString("actorUuid", value.actorUuid().toString());
        merge.knownRaw("expectedRevision", canonicalRevision(value.expectedRevision()));
        merge.knownOptionalText("migrationDigest", value.migrationDigest());
        merge.knownString("operationKind", value.operationKind().name());
        merge.knownString("payloadDigest", value.payloadDigest());
        merge.knownRaw("protocolVersion", VERSION_ONE);
        merge.knownString("requestId", value.requestId().wire());
        merge.knownRaw("schemaVersion", VERSION_ONE);
        merge.knownObjectStart("target");
        writeTargetObject(sink, value.target());
        merge.close();
    }

    /**
     * Writes the nested target object with the same merge rule as the root: the two known members in
     * ascending key order, every unknown member of this owner in its own ascending position, one
     * closed brace.
     */
    private static void writeTargetObject(Utf8Sha256 sink,
                                          PersistenceValues.FingerprintTarget target) {
        final Merge merge = new Merge(sink, target.members());
        sink.append("{");
        merge.knownString("definitionId", target.target().definitionId().wire());
        merge.knownString("worldId", target.target().worldId().wire());
        merge.close();
    }

    // ------------------------------------------------------------------
    // migration canonical write: the value itself and every nested owner
    // ------------------------------------------------------------------

    /** Lowercase hexadecimal digits of the four character short escape of a control character. */
    private static final String HEX_DIGITS = "0123456789abcdef";

    /**
     * Writes the complete migration object in ascending canonical key order.
     *
     * <p>
     * The seven known members are emitted in the fixed comparison order of the contract:
     * {@code cfgState}, {@code idMap}, {@code legacyCfg}, {@code legacyJson},
     * {@code missingCfgPolicy}, {@code sourceHashes} and {@code sourceScope}. Every known value is
     * written from the already validated input value: the cfg state and the missing cfg policy are
     * the exact enum names, the id map is the nested array, the legacy JSON is the complete canonical
     * text of its own frozen value, and a present legacy cfg writes that canonical value while an
     * absent one writes the literal {@code null}.
     * </p>
     */
    private static void writeMigrationObject(Utf8Sha256 sink,
                                             PersistenceValues.MigrationRecord value) {
        final Merge merge = new Merge(sink, value.members());
        sink.append("{");
        merge.knownString("cfgState", value.cfgState().name());
        merge.knownObjectStart("idMap");
        writeIdMapArray(sink, value);
        final ExactJson.FrozenJson legacyCfg = value.legacyCfg().orElse(null);
        merge.knownRaw("legacyCfg", legacyCfg == null ? JSON_NULL : legacyCfg.canonical());
        merge.knownRaw("legacyJson", value.legacyJson().canonical());
        final PersistenceValues.MissingCfgPolicy policy = value.missingCfgPolicy().orElse(null);
        if (policy == null) {
            merge.knownRaw("missingCfgPolicy", JSON_NULL);
        } else {
            merge.knownString("missingCfgPolicy", policy.name());
        }
        merge.knownObjectStart("sourceHashes");
        writeSourceHashesObject(sink, value.sourceHashes());
        merge.knownObjectStart("sourceScope");
        writeSourceScopeObject(sink, value.sourceScope());
        merge.close();
    }

    /**
     * Writes the {@code idMap} array in its original order, without sorting and without any
     * identity based deduplication: the entry at one position is written at that same position.
     */
    private static void writeIdMapArray(Utf8Sha256 sink, PersistenceValues.MigrationRecord value) {
        sink.append("[");
        for (int index = 0; index < value.idMap().size(); index++) {
            if (index > 0) {
                sink.append(",");
            }
            writeIdMapEntryObject(sink, value.idMap().get(index));
        }
        sink.append("]");
    }

    /**
     * Writes one id map entry object in ascending canonical key order: {@code id} as the canonical
     * lowercase UUID text, {@code kind} and {@code sourceDocument} as their exact enum names, and
     * {@code sourceSegments} as the original ordered array of independently quoted segments.
     */
    private static void writeIdMapEntryObject(Utf8Sha256 sink, PersistenceValues.IdMapEntry entry) {
        final Merge merge = new Merge(sink, entry.members());
        sink.append("{");
        merge.knownString("id", entry.id().toString());
        merge.knownString("kind", entry.kind().name());
        merge.knownString("sourceDocument", entry.sourceDocument().name());
        merge.knownObjectStart("sourceSegments");
        sink.append("[");
        for (int segment = 0; segment < entry.sourceSegments().size(); segment++) {
            if (segment > 0) {
                sink.append(",");
            }
            writeQuoted(sink, entry.sourceSegments().get(segment));
        }
        sink.append("]");
        merge.close();
    }

    /**
     * Writes the nested {@code sourceHashes} object: {@code cfg} first, as a nested source hash
     * object when it is present and as the literal {@code null} when it is absent, then the required
     * {@code json} source hash object.
     */
    private static void writeSourceHashesObject(Utf8Sha256 sink,
                                                PersistenceValues.SourceHashes hashes) {
        final Merge merge = new Merge(sink, hashes.members());
        sink.append("{");
        final PersistenceValues.SourceHash cfg = hashes.cfg().orElse(null);
        if (cfg == null) {
            merge.knownRaw("cfg", JSON_NULL);
        } else {
            merge.knownObjectStart("cfg");
            writeSourceHashObject(sink, cfg);
        }
        merge.knownObjectStart("json");
        writeSourceHashObject(sink, hashes.json());
        merge.close();
    }

    /**
     * Writes one source hash object in ascending canonical key order: {@code canonicalSha256},
     * {@code rawSha256} and {@code sourceKind}, all as their original stored spellings.
     */
    private static void writeSourceHashObject(Utf8Sha256 sink, PersistenceValues.SourceHash hash) {
        final Merge merge = new Merge(sink, hash.members());
        sink.append("{");
        merge.knownString("canonicalSha256", hash.canonicalSha256());
        merge.knownString("rawSha256", hash.rawSha256());
        merge.knownString("sourceKind", hash.sourceKind().name());
        merge.close();
    }

    /**
     * Writes the nested {@code sourceScope} object in ascending canonical key order:
     * {@code legacyMapKey} through the canonical quoting rule, then {@code modeId} and
     * {@code worldId} as their original ASCII wire text.
     */
    private static void writeSourceScopeObject(Utf8Sha256 sink, PersistenceValues.SourceScope scope) {
        final Merge merge = new Merge(sink, scope.members());
        sink.append("{");
        merge.knownObjectStart("legacyMapKey");
        writeQuoted(sink, scope.legacyMapKey());
        merge.knownString("modeId", scope.modeId());
        merge.knownString("worldId", scope.worldId().wire());
        merge.close();
    }

    /**
     * Writes one already validated text as one canonical quoted JSON string.
     *
     * <p>
     * The escape rule is the frozen P02 rule: quote and backslash use their two character short
     * escape, backspace, tab, newline, form feed and carriage return use the named short escape,
     * every other character below U+0020 uses the lowercase four character {@code \}{@code u00xx}
     * form, and
     * slash, DEL and every non ASCII character are written verbatim without normalisation. The text
     * is appended as continuous ranges of the original string, so no character sized copy is created
     * and every escape token stays a fixed bounded ASCII literal. A surrogate pair is never split,
     * because only an escaped character ends a range and no surrogate is ever escaped.
     * </p>
     */
    private static void writeQuoted(Utf8Sha256 sink, String text) {
        sink.append("\"");
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            final int character = text.charAt(index);
            if (character >= 0x20 && character != '"' && character != '\\') {
                continue;
            }
            if (start < index) {
                sink.append(text, start, index);
            }
            writeEscape(sink, character);
            start = index + 1;
        }
        if (start < text.length()) {
            sink.append(text, start, text.length());
        }
        sink.append("\"");
    }

    /**
     * Writes the canonical escape token of one character that must be escaped.
     *
     * @param character one decoded character of an already validated text
     */
    private static void writeEscape(Utf8Sha256 sink, int character) {
        switch (character) {
            case '"':
                sink.append("\\\"");
                break;
            case '\\':
                sink.append("\\\\");
                break;
            case 8:
                sink.append("\\b");
                break;
            case 9:
                sink.append("\\t");
                break;
            case 10:
                sink.append("\\n");
                break;
            case 12:
                sink.append("\\f");
                break;
            case 13:
                sink.append("\\r");
                break;
            default:
                sink.append("\\u00");
                sink.append(HEX_DIGITS, character >> 4, (character >> 4) + 1);
                sink.append(HEX_DIGITS, character & 0x0F, (character & 0x0F) + 1);
                break;
        }
    }

    /**
     * Canonical spelling of the expected revision through the frozen exact number rule.
     *
     * <p>
     * The decimal long text is only the literal that is handed to the rule; the emitted token is
     * the canonical spelling the rule returns, so a value whose canonical form is scientific is
     * written in that form and never as a trailing zero decimal run.
     * </p>
     */
    private static String canonicalRevision(long value) {
        return ExactJsonNumber.parse(Long.toString(value), EXPECTED_REVISION_POINTER).canonical();
    }

    /**
     * One merged canonical object of the fingerprint: a fresh cursor over one certified member
     * container, the single member that is currently held, and the separator state of this object.
     *
     * <p>
     * The merge is a two way merge of two ascending key sequences. The known sequence is supplied
     * by the caller as the fixed ordered calls of the owner, one call per known member; the unknown
     * sequence is consumed from the cursor. Before one known member is written, every unknown member
     * whose decoded key sorts strictly before that known key is written first, so the emitted order
     * is the ascending canonical order of the merged member set. Since the reserved collision walk
     * already rejected every unknown key equal to a known key of the same owner, the two sequences
     * share no key.
     * </p>
     *
     * <p>
     * The instance holds exactly one {@link CanonicalObjectMembers.Member} at a time and nothing
     * else per member; the key and the value of an unknown member are handed to the sink as the
     * ranges of the canonical text the cursor owns.
     * </p>
     */
    private static final class Merge {

        private final Utf8Sha256 sink;

        private final CanonicalObjectMembers.Cursor unknown;

        private CanonicalObjectMembers.Member current;

        private boolean first = true;

        /**
         * Opens one merge over the certified members of one owner and positions it on the first
         * member without consuming it twice.
         *
         * @param sink    sink of the current fingerprint computation
         * @param members certified canonical object of the owner; already proven object shaped by
         *                the value type
         */
        Merge(Utf8Sha256 sink, ExactJson.FrozenJson members) {
            this.sink = sink;
            this.unknown = CanonicalObjectMembers.cursor(members);
            this.current = this.unknown.hasNext() ? this.unknown.next() : null;
        }

        /**
         * Writes one known member whose value is quoted text that the value type already proved to
         * be safe ASCII, exactly as the wire spells it.
         */
        void knownString(String key, String text) {
            beginKnown(key);
            this.sink.append("\"");
            this.sink.append(text);
            this.sink.append("\"");
        }

        /**
         * Writes one known member whose value is already a complete canonical token, such as a
         * canonical number or the literal {@code null}.
         */
        void knownRaw(String key, String token) {
            beginKnown(key);
            this.sink.append(token);
        }

        /**
         * Writes one known member whose optional text is the JSON literal {@code null} when it is
         * absent and the original quoted text when it is present.
         */
        void knownOptionalText(String key, Optional<String> text) {
            beginKnown(key);
            final String present = text.orElse(null);
            if (present == null) {
                this.sink.append(JSON_NULL);
            } else {
                this.sink.append("\"");
                this.sink.append(present);
                this.sink.append("\"");
            }
        }

        /**
         * Writes the key and the colon of one known member whose value is a nested merged object
         * written by the caller immediately afterwards.
         */
        void knownObjectStart(String key) {
            beginKnown(key);
        }

        /** Writes every still unconsumed unknown member and closes the object with one brace. */
        void close() {
            while (this.current != null) {
                emitUnknown();
            }
            this.sink.append("}");
        }

        /**
         * Flushes the unknown members that sort before one known key and then writes that known
         * member's quoted key and its colon.
         */
        private void beginKnown(String key) {
            flushUnknownsBefore(key);
            separator();
            this.sink.append("\"");
            this.sink.append(key);
            this.sink.append("\":");
        }

        /**
         * Writes every unknown member whose decoded key sorts strictly before {@code knownKey} and
         * stops on the first member that does not, leaving it held for a later step.
         */
        private void flushUnknownsBefore(String knownKey) {
            while (this.current != null && this.current.key().compareTo(knownKey) < 0) {
                emitUnknown();
            }
        }

        /**
         * Writes one held unknown member from the ranges of the certified canonical text and
         * advances the cursor by exactly that one member.
         */
        private void emitUnknown() {
            final String canonical = this.unknown.canonical();
            separator();
            this.sink.append(canonical, this.current.keyStart(), this.current.keyEnd());
            this.sink.append(":");
            this.sink.append(canonical, this.current.valueStart(), this.current.valueEnd());
            this.current = this.unknown.hasNext() ? this.unknown.next() : null;
        }

        /** Writes the separating comma of every member after the first one of this object. */
        private void separator() {
            if (!this.first) {
                this.sink.append(",");
            }
            this.first = false;
        }
    }
}
