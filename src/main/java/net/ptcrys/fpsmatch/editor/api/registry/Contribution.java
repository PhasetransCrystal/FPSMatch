package net.ptcrys.fpsmatch.editor.api.registry;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.util.Objects;
import java.util.Set;

/** A registration offered by a module owner. */
public record Contribution(
                           ContributionKey key,
                           EditorId owner,
                           Side side,
                           Set<ContributionKey> requiredContributions) {

    /** Publication side of a contribution. */
    public enum Side {
        COMMON,
        CLIENT
    }

    public Contribution {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(requiredContributions, "requiredContributions");
        if (requiredContributions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("requiredContributions must not contain null");
        }
        requiredContributions = Set.copyOf(requiredContributions);
    }
}
