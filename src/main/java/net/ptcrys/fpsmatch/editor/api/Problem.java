package net.ptcrys.fpsmatch.editor.api;

import java.util.Objects;

/** A stable diagnostic with a domain-owned target address and unresolved message. */
public record Problem(Severity severity, EditorId code, String target, TextContent message) {

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    public Problem {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(message, "message");
    }
}
