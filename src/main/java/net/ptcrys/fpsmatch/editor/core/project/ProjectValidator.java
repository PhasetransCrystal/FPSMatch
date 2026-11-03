package net.ptcrys.fpsmatch.editor.core.project;

import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;

import java.util.List;

/**
 * Domain validation of a save candidate. The queue owns ordering, budgets and identity; a validator
 * owns everything the domain knows about the candidate's meaning.
 *
 * <p>
 * Contract: the call is synchronous, must not perform I/O, must not publish, and must not call
 * back into the queue that is dispatching it. Returning any {@link Problem.Severity#ERROR} rejects
 * the head without producing an attempt; warnings travel with a valid attempt. A validator must
 * report problems instead of throwing; a thrown runtime exception is reported as
 * {@code editor:save/validator_failed} and rejects the head.
 */
public interface ProjectValidator {

    List<Problem> validate(ProjectState candidate);
}
