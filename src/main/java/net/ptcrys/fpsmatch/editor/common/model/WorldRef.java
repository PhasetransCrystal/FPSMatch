package net.ptcrys.fpsmatch.editor.common.model;

/**
 * Reference to a persistent world plus the explicit dimension inside that world.
 *
 * <p>
 * The dimension is always written as {@code namespace:path}; the namespace is never filled in
 * implicitly (no implicit {@code minecraft:}) and no Minecraft registry lookup happens here. The
 * platform decides whether the world and the dimension actually exist.
 */
public record WorldRef(Ids.WorldId worldId, String dimensionId) {

    /** Maximum accepted length of {@link #dimensionId()}, in ASCII characters. */
    private static final int MAX_DIMENSION_ID_LENGTH = 191;
    private static final int MAX_NAMESPACE_LENGTH = MAX_DIMENSION_ID_LENGTH - 1;
    private static final int MAX_PATH_LENGTH = MAX_DIMENSION_ID_LENGTH - 2;

    public WorldRef {
        if (worldId == null) {
            throw new IllegalArgumentException("worldId must not be null");
        }
        dimensionId = validateDimensionId(dimensionId);
    }

    /**
     * Validates a wire dimension id: explicit {@code namespace:path}, ASCII only, at most
     * {@value #MAX_DIMENSION_ID_LENGTH} characters, namespace {@code [a-z0-9_.-]+} and path
     * {@code [a-z0-9_./-]+}. The value is used exactly as given: it is never trimmed and never
     * completed with a default namespace.
     */
    private static String validateDimensionId(String dimensionId) {
        if (dimensionId == null) {
            throw new IllegalArgumentException("dimensionId must not be null");
        }
        int length = dimensionId.length();
        if (length == 0) {
            throw new IllegalArgumentException("dimensionId must not be empty");
        }
        if (length > MAX_DIMENSION_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "dimensionId must be at most " + MAX_DIMENSION_ID_LENGTH + " ASCII characters, got " + length);
        }
        int colon = dimensionId.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException(
                    "dimensionId must be explicit 'namespace:path'; the namespace is never defaulted");
        }
        if (dimensionId.indexOf(':', colon + 1) >= 0) {
            throw new IllegalArgumentException(
                    "dimensionId must contain exactly one ':' separator");
        }
        if (colon == 0) {
            throw new IllegalArgumentException("dimensionId namespace must not be empty");
        }
        if (colon == length - 1) {
            throw new IllegalArgumentException("dimensionId path must not be empty");
        }
        if (colon > MAX_NAMESPACE_LENGTH) {
            throw new IllegalArgumentException("dimensionId namespace is too long");
        }
        if (length - colon - 1 > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("dimensionId path is too long");
        }
        for (int i = 0; i < colon; i++) {
            if (!isNamespaceCharacter(dimensionId.charAt(i))) {
                throw new IllegalArgumentException(
                        "dimensionId namespace must match [a-z0-9_.-]+ at index " + i);
            }
        }
        for (int i = colon + 1; i < length; i++) {
            if (!isPathCharacter(dimensionId.charAt(i))) {
                throw new IllegalArgumentException(
                        "dimensionId path must match [a-z0-9_./-]+ at index " + i);
            }
        }
        return dimensionId;
    }

    private static boolean isNamespaceCharacter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
    }

    private static boolean isPathCharacter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '/' || c == '-';
    }
}
