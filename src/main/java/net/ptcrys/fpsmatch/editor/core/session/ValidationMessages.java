package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceVersion;

import java.util.List;
import java.util.UUID;

/** Stable diagnostics for the content validation gate. */
final class ValidationMessages {

    private ValidationMessages() {}

    static SessionRejection unavailable(ResourceVersion version) {
        return rejection("resource_content_validator_unavailable", version.id(), version.importer().toString());
    }

    static SessionRejection ownerMismatch(ResourceVersion version) {
        return rejection("resource_content_validator_owner_mismatch", version.id(), version.importer().toString());
    }

    static SessionRejection required(UUID project) {
        return rejection("resource_content_validation_required", project, project.toString());
    }

    static SessionRejection savedSourceRequired(UUID project) {
        return rejection("saved_source_validation_required", project, project.toString());
    }

    private static SessionRejection rejection(String name, UUID target, String argument) {
        return new SessionRejection(List.of(new Problem(Problem.Severity.ERROR,
                new EditorId("editor", "session/" + name), target.toString(),
                new TextContent.Message("editor.session." + name,
                        List.of(new TextContent.LiteralArgument(argument))))));
    }
}
