package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Pure preparation result; grants no world/install authority, disk pin or resource ownership. */
public final class PreparedRuntime<R> {

    private final LoadedProject source;
    private final UUID documentId;
    private final EditorId adapterId;
    private final R value;
    private final List<Problem> problems;
    private final Map<EditorId, String> runtimeModules;
    private final ExtensionRegistry registry;

    PreparedRuntime(LoadedProject source, UUID documentId, EditorId adapterId, R value,
                    List<Problem> problems, Map<EditorId, String> runtimeModules,
                    ExtensionRegistry registry) {
        this.source = Objects.requireNonNull(source, "source");
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        this.value = Objects.requireNonNull(value, "value");
        this.problems = List.copyOf(problems);
        this.runtimeModules = Collections.unmodifiableMap(new TreeMap<>(runtimeModules));
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public LoadedProject source() {
        return source;
    }

    public UUID documentId() {
        return documentId;
    }

    public EditorId adapterId() {
        return adapterId;
    }

    public R value() {
        return value;
    }

    public List<Problem> problems() {
        return problems;
    }

    public Map<EditorId, String> runtimeModules() {
        return runtimeModules;
    }

    /** Exact preparation provenance; this grants no world, file or installation authority. */
    public boolean preparedWith(ExtensionRegistry candidate) {
        return registry == candidate;
    }
}
