package net.ptcrys.fpsmatch.editor.common.model;

import java.util.UUID;

/**
 * Strongly typed identity container for the world editor rewrite (contract R1, section 1).
 *
 * <p>
 * Every identity is a full 36 character UUID wrapped in a dedicated record type, so that a
 * world id, a definition id, an instance id or a connection id can never be merged just because
 * their wire text happens to be identical. There is no cross type conversion: the only accepted
 * construction inputs are an explicit {@link UUID} instance or the canonical 36 character wire
 * text accepted by {@link TypedId#wire()}.
 *
 * <p>
 * The class is a non instantiable final container and holds no mutable state.
 */
public final class Ids {

    private Ids() {}

    /**
     * Common contract of every typed identity: a non null {@link UUID} value plus its canonical,
     * always lower case 36 character wire form.
     */
    public interface TypedId {

        UUID value();

        default String wire() {
            return value().toString();
        }
    }

    /**
     * Identity of a persistent world. Never derived from a save name or a directory name.
     */
    public record WorldId(UUID value) implements TypedId {

        public WorldId {
            value = requireUuid(value);
        }

        public static WorldId parse(String wire) {
            return new WorldId(parseCanonicalUuid(wire));
        }
    }

    /**
     * Identity of a map definition inside one world.
     */
    public record DefinitionId(UUID value) implements TypedId {

        public DefinitionId {
            value = requireUuid(value);
        }

        public static DefinitionId parse(String wire) {
            return new DefinitionId(parseCanonicalUuid(wire));
        }
    }

    /**
     * Identity of a team inside one map definition.
     */
    public record TeamId(UUID value) implements TypedId {

        public TeamId {
            value = requireUuid(value);
        }

        public static TeamId parse(String wire) {
            return new TeamId(parseCanonicalUuid(wire));
        }
    }

    /**
     * Identity of a placeable object (spawn, region, ...) inside one map definition.
     */
    public record ObjectId(UUID value) implements TypedId {

        public ObjectId {
            value = requireUuid(value);
        }

        public static ObjectId parse(String wire) {
            return new ObjectId(parseCanonicalUuid(wire));
        }
    }

    /**
     * Identity of one runtime instance of a map definition.
     */
    public record InstanceId(UUID value) implements TypedId {

        public InstanceId {
            value = requireUuid(value);
        }

        public static InstanceId parse(String wire) {
            return new InstanceId(parseCanonicalUuid(wire));
        }
    }

    /**
     * Identity of one specific generation (incarnation) of an instance.
     */
    public record IncarnationId(UUID value) implements TypedId {

        public IncarnationId {
            value = requireUuid(value);
        }

        public static IncarnationId parse(String wire) {
            return new IncarnationId(parseCanonicalUuid(wire));
        }
    }

    /**
     * Identity of one run (match execution) on an instance incarnation.
     */
    public record RunId(UUID value) implements TypedId {

        public RunId {
            value = requireUuid(value);
        }

        public static RunId parse(String wire) {
            return new RunId(parseCanonicalUuid(wire));
        }
    }

    /**
     * Identity of one client request, used for deduplication and receipt lookup.
     */
    public record RequestId(UUID value) implements TypedId {

        public RequestId {
            value = requireUuid(value);
        }

        public static RequestId parse(String wire) {
            return new RequestId(parseCanonicalUuid(wire));
        }
    }

    /**
     * Identity of one authenticated client connection.
     */
    public record ConnectionId(UUID value) implements TypedId {

        public ConnectionId {
            value = requireUuid(value);
        }

        public static ConnectionId parse(String wire) {
            return new ConnectionId(parseCanonicalUuid(wire));
        }
    }

    /**
     * Identity of one open editor document.
     */
    public record DocumentId(UUID value) implements TypedId {

        public DocumentId {
            value = requireUuid(value);
        }

        public static DocumentId parse(String wire) {
            return new DocumentId(parseCanonicalUuid(wire));
        }
    }

    /**
     * Identity of one user gesture (drag, resize, ...) that may coalesce commands.
     */
    public record GestureId(UUID value) implements TypedId {

        public GestureId {
            value = requireUuid(value);
        }

        public static GestureId parse(String wire) {
            return new GestureId(parseCanonicalUuid(wire));
        }
    }

    /**
     * Identity of one long running world operation.
     */
    public record OperationId(UUID value) implements TypedId {

        public OperationId {
            value = requireUuid(value);
        }

        public static OperationId parse(String wire) {
            return new OperationId(parseCanonicalUuid(wire));
        }
    }

    /**
     * Identity of one diagnostics subscription.
     */
    public record SubscriptionId(UUID value) implements TypedId {

        public SubscriptionId {
            value = requireUuid(value);
        }

        public static SubscriptionId parse(String wire) {
            return new SubscriptionId(parseCanonicalUuid(wire));
        }
    }

    private static UUID requireUuid(UUID value) {
        if (value == null) {
            throw new IllegalArgumentException("typed id value must not be null");
        }
        return value;
    }

    /**
     * Parses the canonical wire form of an identity. Only complete 36 character UUID text is
     * accepted; a fully upper case input is allowed and normalized to lower case by {@code wire()}.
     */
    private static UUID parseCanonicalUuid(String wire) {
        if (wire == null) {
            throw new IllegalArgumentException("typed id wire value must not be null");
        }
        if (wire.length() != 36) {
            throw new IllegalArgumentException(
                    "typed id wire value must be exactly 36 characters, got " + wire.length());
        }
        if (Character.isWhitespace(wire.charAt(0)) || Character.isWhitespace(wire.charAt(35))) {
            throw new IllegalArgumentException(
                    "typed id wire value must not have leading or trailing whitespace");
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(wire);
        } catch (IllegalArgumentException cause) {
            throw new IllegalArgumentException("typed id wire value is not a complete UUID", cause);
        }
        if (!parsed.toString().equalsIgnoreCase(wire)) {
            throw new IllegalArgumentException(
                    "typed id wire value is not a complete, canonical 36 character UUID");
        }
        return parsed;
    }
}
