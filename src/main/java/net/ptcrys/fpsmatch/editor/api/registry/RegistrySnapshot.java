package net.ptcrys.fpsmatch.editor.api.registry;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The frozen result of a {@link RegistryBuilder}. Every collection is immutable and only usable
 * entries are present: a rejected module or contribution never appears in either map, and each
 * rejection is explained by at least one {@link Problem} in {@link #problems()}.
 *
 * <p>
 * Both maps are sorted by their natural key order and {@code problems} is sorted by
 * (severity, code, target, message key, typed arguments) — the same total order
 * {@code Messages.order()} imposes — so a snapshot does not depend on the order in which modules
 * and contributions were added. The arguments are compared as typed content (literal, number and
 * nested argument), so two diagnostics sharing severity, code, target and message key still have
 * one fixed order.
 * </p>
 */
public record RegistrySnapshot(
                               Map<EditorId, ModuleSpec> modules,
                               Map<ContributionKey, Contribution> contributions,
                               List<Problem> problems) {

    public RegistrySnapshot {
        modules = Collections.unmodifiableMap(new TreeMap<>(modules));
        contributions = Collections.unmodifiableMap(new TreeMap<>(contributions));
        problems = List.copyOf(problems);
    }
}
