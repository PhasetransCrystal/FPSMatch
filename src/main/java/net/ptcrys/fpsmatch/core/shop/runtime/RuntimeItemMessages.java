package net.ptcrys.fpsmatch.core.shop.runtime;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

import java.util.List;

final class RuntimeItemMessages {

    private RuntimeItemMessages() {}

    static RuntimeItemException error(String reason) {
        return new RuntimeItemException(new Problem(Problem.Severity.ERROR,
                new EditorId("editor", "runtime_items/" + reason), "runtime_items",
                new TextContent.Message("editor.runtime_items." + reason, List.of())));
    }
}
