package net.ptcrys.fpsmatch.editor.core.types;

import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;

import java.util.Collections;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The complete set of direct dependencies of one document snapshot.
 *
 * <p>
 * The three components cover the three ways a document can depend on something else:
 * </p>
 * <ul>
 * <li>{@code documents} - stable document references;</li>
 * <li>{@code resources} - resource versions that are explicitly locked: every entry names both the
 * logical resource identity and the exact version the document was written against;</li>
 * <li>{@code contributions} - the complete direct set of third-party objects, values, codecs and
 * migrations embedded in the payload.</li>
 * </ul>
 *
 * <p>
 * The document type's own contribution is counted by the consumer and does not have to be
 * repeated by the author; a dependency set must never consist of only the top-level document owner.
 * Several versions of the same resource are not collapsed, and a locked version is never replaced by
 * a "current" pointer: two {@link ResourceRef}s that share a resource identity but lock different
 * versions are both retained.
 * </p>
 *
 * <p>
 * Construction checks the total size of the three sets against {@value #MAX_DEPENDENCIES} before
 * anything is inserted, then rejects null elements, copies each set and sorts it into a
 * deterministic order - documents and contributions by their natural order, resources by resource
 * identity and then by version. The caller's sets are never modified, and the returned sets are
 * immutable. No dependencies at all is expressed with three empty sets.
 * </p>
 *
 * @param documents     stable document references
 * @param resources     explicitly locked resource identities with versions
 * @param contributions complete direct set of required embedded contributions
 */
public record DocumentDependencies(
                                   Set<UUID> documents,
                                   Set<ResourceRef> resources,
                                   Set<ContributionKey> contributions) {

    /** Hard bound on the total number of direct dependencies of one document. */
    static final int MAX_DEPENDENCIES = 8192;

    /** Locked resources sort by logical identity first, then by the locked version. */
    private static final Comparator<ResourceRef> BY_LOCKED_IDENTITY = Comparator.comparing(ResourceRef::resourceId).thenComparing(ResourceRef::versionId);

    public DocumentDependencies {
        Objects.requireNonNull(documents, "documents");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(contributions, "contributions");
        long total = (long) documents.size() + resources.size() + contributions.size();
        if (total > MAX_DEPENDENCIES) {
            throw new IllegalArgumentException("a document may declare at most " + MAX_DEPENDENCIES + " direct dependencies, got " + total);
        }
        documents = sortedCopy(documents, Comparator.naturalOrder(), "documents");
        resources = sortedCopy(resources, BY_LOCKED_IDENTITY, "resources");
        contributions = sortedCopy(contributions, Comparator.naturalOrder(), "contributions");
    }

    private static <E> Set<E> sortedCopy(Set<E> values, Comparator<E> order, String name) {
        TreeSet<E> copy = new TreeSet<>(order);
        for (E value : values) {
            copy.add(Objects.requireNonNull(value, name + " element"));
        }
        return Collections.unmodifiableSet(copy);
    }
}
