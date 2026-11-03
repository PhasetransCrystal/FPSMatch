package net.ptcrys.fpsmatch.editor.common.ui.input;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Owner-thread editing sessions, independent of mounted controls and their geometry.
 * Scope is a stable document/session identity; revision and mount generation are not identities.
 */
public final class EditSessionStore {

    public enum Problem {
        EXTERNAL_TOO_LONG,
        EXTERNAL_NOT_SINGLE_LINE,
        EXTERNAL_CONFLICT
    }

    public record Diagnostic(Problem code, String externalValue, int maximumLength) {

        public Diagnostic {
            Objects.requireNonNull(code);
            Objects.requireNonNull(externalValue);
        }
    }

    private record Key(String scope, String field) {

        private Key {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(field);
        }
    }

    private record Session(SingleLineText editor, String baseExternal, String observedExternal, Diagnostic diagnostic) {}

    private final Map<Key, Session> sessions = new HashMap<>();
    private long generation;

    public final class Prepared {

        private final long expectedGeneration;
        private final Map<Key, Session> updates;
        private final Map<String, SingleLineText> editors;
        private final Map<String, Diagnostic> diagnostics;
        private boolean committed;

        private Prepared(Map<Key, Session> updates, Map<String, SingleLineText> editors, Map<String, Diagnostic> diagnostics) {
            expectedGeneration = generation;
            this.updates = Map.copyOf(updates);
            this.editors = Map.copyOf(editors);
            this.diagnostics = Map.copyOf(diagnostics);
        }

        public Map<String, SingleLineText> editors() {
            return editors;
        }

        public Map<String, Diagnostic> diagnostics() {
            return diagnostics;
        }

        public Map<String, SingleLineText.Snapshot> snapshots() {
            var result = new HashMap<String, SingleLineText.Snapshot>();
            editors.forEach((id, editor) -> result.put(id, editor.snapshot()));
            return Map.copyOf(result);
        }

        public void validate() {
            if (committed || expectedGeneration != generation) throw new IllegalStateException("stale edit preparation");
        }

        /** Called only after every publication component has prepared successfully. */
        public void commit() {
            validate();
            sessions.putAll(updates);
            generation++;
            committed = true;
        }
    }

    /** No live editor, history or session table is modified during preparation. */
    public Prepared prepare(String scope, Map<String, String> externalValues) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(externalValues);
        var updates = new HashMap<Key, Session>();
        var editors = new HashMap<String, SingleLineText>();
        var diagnostics = new HashMap<String, Diagnostic>();
        externalValues.forEach((id, value) -> {
            Objects.requireNonNull(value);
            var key = new Key(scope, id);
            var old = sessions.get(key);
            Session next = prepareSession(old, value);
            updates.put(key, next);
            if (next.diagnostic() != null) diagnostics.put(id, next.diagnostic());
            else editors.put(id, next.editor());
        });
        return new Prepared(updates, editors, diagnostics);
    }

    private static Session prepareSession(Session old, String external) {
        Problem problem = external.length() > SingleLineText.MAX_LENGTH ? Problem.EXTERNAL_TOO_LONG : !SingleLineText.supportsExternal(external) ? Problem.EXTERNAL_NOT_SINGLE_LINE : null;
        if (problem != null) return new Session(old == null ? null : old.editor(), old == null ? external : old.baseExternal(), external,
                new Diagnostic(problem, external, SingleLineText.MAX_LENGTH));
        if (old == null || old.editor() == null) return new Session(new SingleLineText(external), external, external, null);
        var editor = old.editor();
        if (external.equals(editor.text())) return new Session(editor, external, external, null);
        if (external.equals(old.baseExternal())) return new Session(editor, old.baseExternal(), external, null);
        if (external.equals(old.observedExternal())) return old;
        if (!editor.text().equals(old.baseExternal()))
            return new Session(editor, old.baseExternal(), external, new Diagnostic(Problem.EXTERNAL_CONFLICT, external, SingleLineText.MAX_LENGTH));
        var replacement = editor.copy();
        replacement.set(external);
        return new Session(replacement, external, external, null);
    }

    public Optional<SingleLineText> editor(String scope, String field) {
        var session = sessions.get(new Key(scope, field));
        return session == null || session.diagnostic() != null ? Optional.empty() : Optional.ofNullable(session.editor());
    }

    public Optional<Diagnostic> diagnostic(String scope, String field) {
        var session = sessions.get(new Key(scope, field));
        return session == null ? Optional.empty() : Optional.ofNullable(session.diagnostic());
    }

    public Optional<SingleLineText.Snapshot> draft(String scope, String field) {
        var session = sessions.get(new Key(scope, field));
        return session == null || session.editor() == null ? Optional.empty() : Optional.of(session.editor().snapshot());
    }

    public Optional<String> externalValue(String scope, String field) {
        var session = sessions.get(new Key(scope, field));
        return session == null ? Optional.empty() : Optional.of(session.diagnostic() == null ? session.observedExternal() : session.diagnostic().externalValue());
    }

    /** Explicit compatibility access; ordinary frame publication uses prepare/commit. */
    public SingleLineText synchronize(String scope, String field, String external) {
        var prepared = prepare(scope, Map.of(field, external));
        var editor = prepared.editors().get(field);
        if (editor == null) throw new IllegalArgumentException("external value cannot be edited: " + prepared.diagnostics().get(field).code());
        prepared.commit();
        return editor;
    }

    public void releasePrefix(String scope, String prefix) {
        sessions.keySet().removeIf(key -> key.scope().equals(scope) && key.field().startsWith(prefix));
        generation++;
    }

    public void releaseScope(String scope) {
        sessions.keySet().removeIf(key -> key.scope().equals(scope));
        generation++;
    }

    public void clear() {
        sessions.clear();
        generation++;
    }

    public int size() {
        return sessions.size();
    }
}
