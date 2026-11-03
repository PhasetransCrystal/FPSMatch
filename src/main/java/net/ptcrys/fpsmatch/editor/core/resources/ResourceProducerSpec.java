package net.ptcrys.fpsmatch.editor.core.resources;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The support declaration of one trusted Java module for already persisted producer formats.
 *
 * <p>
 * This is a declaration, not an executable importer: it states which stored
 * {@code ResourceVersion.importerVersion} strings the module claims to be able to interpret. The
 * identity of the producer itself is deliberately absent here - both its contribution id and its
 * owner module live in the contribution metadata of the frozen registry, so a spec can never
 * disagree with the registration it is published under.
 * </p>
 *
 * <p>
 * The declared strings are carried verbatim. Nothing is trimmed, nothing is parsed as a version
 * scheme, and no ordering rule picks "the newest" entry: the set is only a sorted lookup keyed by
 * exact string equality, and a stored version outside it stays stored and reported rather than being
 * silently reinterpreted. The bounds below are registration-description budgets of this runtime; they
 * never rewrite a persisted version string, and data that no producer declares support for is
 * retained and diagnosed instead of being discarded.
 * </p>
 *
 * @param readableVersions the exactly supported {@code importerVersion} strings, non-null, 1..256
 *                         entries, copied and immutably ordered by {@link String#compareTo(String)}
 */
public record ResourceProducerSpec(Set<String> readableVersions) {

    /** The most support declarations one producer may carry. */
    static final int MAX_VERSIONS = 256;

    /** The most UTF-16 units one declared version string may use. */
    static final int MAX_VERSION_LENGTH = 4096;

    /** The cumulative UTF-16 unit budget of all declared version strings together. */
    static final int MAX_TOTAL_LENGTH = 65536;

    public ResourceProducerSpec {
        Objects.requireNonNull(readableVersions, "readableVersions");
        // The count is checked first, so an oversized declaration is reported as such even when one
        // of its entries would also fail a per-entry rule: the budgets stay independently readable.
        if (readableVersions.size() > MAX_VERSIONS) {
            throw ResourceSupport.invalid("too many readable producer versions: " + readableVersions.size());
        }
        if (readableVersions.isEmpty()) {
            throw ResourceSupport.invalid("a producer must declare at least one readable version");
        }
        // Every entry is measured against the per-entry bound and against the remaining cumulative
        // budget before anything is copied, so a rejected declaration leaves no partial result.
        long total = 0;
        for (String version : readableVersions) {
            Objects.requireNonNull(version, "readable version");
            if (version.isBlank()) {
                throw ResourceSupport.invalid("readable producer version must not be blank");
            }
            if (version.length() > MAX_VERSION_LENGTH) {
                throw ResourceSupport.invalid("readable producer version exceeds " + MAX_VERSION_LENGTH + " UTF-16 units");
            }
            if (version.length() > MAX_TOTAL_LENGTH - total) {
                throw ResourceSupport.invalid("readable producer versions exceed the total budget of " + MAX_TOTAL_LENGTH + " UTF-16 units");
            }
            total += version.length();
        }
        // Only now is the caller's set copied: the spec owns an immutable, stably ordered snapshot.
        TreeSet<String> copy = new TreeSet<>();
        for (String version : readableVersions) {
            copy.add(Objects.requireNonNull(version, "readable version"));
        }
        readableVersions = Collections.unmodifiableSet(copy);
    }
}
