package net.ptcrys.fpsmatch.editor.core.editing;

import net.ptcrys.fpsmatch.editor.api.Problem;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.util.List;

/** Import failure with untranslated product diagnostics; getMessage is a technical locator only. */
public final class ResourceImportException extends IOException {

    private static final long serialVersionUID = 1L;
    private final transient List<Problem> problems;

    public ResourceImportException(List<Problem> problems) {
        super("resource import rejected");
        this.problems = List.copyOf(problems);
        if (this.problems.isEmpty() || this.problems.stream().noneMatch(p -> p.severity() == Problem.Severity.ERROR))
            throw new IllegalArgumentException("an import failure requires an error");
    }

    public List<Problem> problems() {
        return problems;
    }

    private void readObject(ObjectInputStream input) throws InvalidObjectException {
        throw new InvalidObjectException("Import diagnostics cannot be deserialized");
    }
}
