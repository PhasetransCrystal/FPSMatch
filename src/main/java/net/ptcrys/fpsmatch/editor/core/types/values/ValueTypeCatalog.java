package net.ptcrys.fpsmatch.editor.core.types.values;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.RegistrySnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Immutable activated value codecs from one actual frozen extension registry. */
public final class ValueTypeCatalog {

    public static final int MAX_VALUE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_DEPENDENCIES = 8192;

    private final Map<EditorId, ActivatedValueType<?>> entries;
    private final Map<EditorId, RuntimeException> failures;
    private final List<Problem> problems;

    private ValueTypeCatalog(Map<EditorId, ActivatedValueType<?>> entries,
                             Map<EditorId, RuntimeException> failures, List<Problem> problems) {
        this.entries = Collections.unmodifiableMap(new TreeMap<>(entries));
        this.failures = Collections.unmodifiableMap(new TreeMap<>(failures));
        this.problems = List.copyOf(problems);
    }

    /**
     * Activates each registered type once in id order, isolating RuntimeExceptions by type.
     * An undefined kind is an assembly error, and Errors from implementations propagate.
     */
    public static ValueTypeCatalog activate(ExtensionRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        Map<EditorId, ValueType<?>> types = registry.entries(ValueTypes.KIND);
        RegistrySnapshot metadata = registry.metadata();
        Map<EditorId, ActivatedValueType<?>> entries = new TreeMap<>();
        Map<EditorId, RuntimeException> failures = new TreeMap<>();
        List<Problem> problems = new ArrayList<>();
        for (Map.Entry<EditorId, ValueType<?>> entry : new TreeMap<>(types).entrySet()) {
            EditorId id = entry.getKey();
            try {
                ContributionKey key = new ContributionKey(ValueTypes.KIND.id(), id);
                Contribution contribution = Objects.requireNonNull(metadata.contributions().get(key),
                        "registered contribution metadata");
                entries.put(id, activateOne(id, contribution.owner(), entry.getValue()));
            } catch (RuntimeException failure) {
                failures.put(id, failure);
                problems.add(ValueMessages.typeActivationFailed(id));
            }
        }
        return new ValueTypeCatalog(entries, failures, problems);
    }

    private static <T> ActivatedValueType<T> activateOne(EditorId id, EditorId owner,
                                                         ValueType<T> implementation) {
        return new ActivatedValueType<>(id, owner, implementation);
    }

    public Map<EditorId, ActivatedValueType<?>> entries() {
        return entries;
    }

    public Map<EditorId, RuntimeException> activationFailures() {
        return failures;
    }

    public List<Problem> problems() {
        return problems;
    }

    public Optional<ActivatedValueType<?>> find(EditorId id) {
        return Optional.ofNullable(entries.get(Objects.requireNonNull(id, "id")));
    }
}
