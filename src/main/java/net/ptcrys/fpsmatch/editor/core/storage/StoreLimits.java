package net.ptcrys.fpsmatch.editor.core.storage;

/** Operational budgets, independent of any domain's schema. */
public record StoreLimits(int maxManifestBytes, long maxBlobBytes, long maxProjectBytes,
                          int maxEntries, int maxDependencies, int retainedReceipts) {

    public static final StoreLimits DEFAULT = new StoreLimits(
            4 * 1024 * 1024, 512L * 1024 * 1024, 16L * 1024 * 1024 * 1024, 8192, 65536, 64);

    public StoreLimits {
        if (maxManifestBytes < 256 || maxManifestBytes > 64 * 1024 * 1024 || maxBlobBytes < 0 || maxProjectBytes < 0 || maxEntries < 1 || maxEntries > 65536 || maxDependencies < 0 || maxDependencies > 1048576 || retainedReceipts < 1 || retainedReceipts > 256) {
            throw new IllegalArgumentException("invalid storage budgets");
        }
    }
}
