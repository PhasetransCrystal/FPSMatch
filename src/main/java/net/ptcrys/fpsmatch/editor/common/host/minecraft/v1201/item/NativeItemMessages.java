package net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.item;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

import java.util.List;

final class NativeItemMessages {

    private NativeItemMessages() {}

    static NativeItemException error(String reason) {
        return error(reason, null, false);
    }

    static NativeItemException error(String reason, EditorId typeId) {
        return error(reason, typeId, true);
    }

    static NativeItemException caused(String reason, EditorId typeId, RuntimeException cause) {
        NativeItemException failure = error(reason, typeId);
        failure.initCause(cause);
        return failure;
    }

    private static NativeItemException error(String reason, EditorId typeId, boolean argument) {
        String target = typeId == null ? "native_items" : typeId.toString();
        List<TextContent.Argument> arguments = argument ? List.of(new TextContent.LiteralArgument(target)) : List.of();
        return new NativeItemException(new Problem(Problem.Severity.ERROR,
                new EditorId("editor", "native_items/" + reason), target,
                new TextContent.Message("editor.native_items." + reason, arguments)));
    }
}
