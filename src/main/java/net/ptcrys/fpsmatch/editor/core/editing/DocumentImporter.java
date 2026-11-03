package net.ptcrys.fpsmatch.editor.core.editing;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Explicit read-only format conversion. Inputs are bounded private bytes, never filesystem paths. */
public interface DocumentImporter<T> {

    record Input(String id, TextContent label, boolean directory, boolean required) {

        public Input {
            if (!Objects.requireNonNull(id).matches("[a-z][a-z0-9_-]{0,63}"))
                throw new IllegalArgumentException("invalid import input id");
            Objects.requireNonNull(label);
        }
    }

    record Result<T>(Optional<T> value, List<Problem> problems) {

        public Result {
            Objects.requireNonNull(value);
            problems = List.copyOf(problems);
            if (value.isPresent() && problems.stream().anyMatch(p -> p.severity() == Problem.Severity.ERROR))
                throw new IllegalArgumentException("import candidate cannot carry errors");
            if (value.isEmpty() && problems.stream().noneMatch(p -> p.severity() == Problem.Severity.ERROR))
                throw new IllegalArgumentException("rejected import requires a reason");
        }
    }

    EditorId documentType();

    Class<T> valueType();

    TextContent label();

    /** File input uses id; flat directory entries use id/filename. At most 16 inputs. */
    List<Input> inputs();

    Result<T> convert(ExtensionRegistry registry, Map<String, byte[]> sources, UUID importSeed);
}
