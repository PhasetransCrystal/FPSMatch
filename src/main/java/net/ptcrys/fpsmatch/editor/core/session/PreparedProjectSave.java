package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.core.project.ProjectDelta;

import java.util.List;

/** Fixed staged data, issued only by the same physical access that owns its capture. */
public final class PreparedProjectSave {

    final ProjectSaveCapture capture;
    final ProjectDelta delta;
    private final List<Problem> problems;

    PreparedProjectSave(ProjectSaveCapture capture, ProjectDelta delta) {
        this(capture, delta, List.of());
    }

    PreparedProjectSave(ProjectSaveCapture capture, ProjectDelta delta, List<Problem> problems) {
        this.problems = List.copyOf(problems);
        this.capture = capture;
        this.delta = delta;
    }

    /** Unresolved content validation diagnostics for this fixed preparation, including warnings. */
    public List<Problem> problems() {
        return problems;
    }

    public ProjectSaveCapture capture() {
        return capture;
    }
}
