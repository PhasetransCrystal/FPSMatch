package net.ptcrys.fpsmatch.editor.common.domain.map;

import com.google.gson.JsonPrimitive;

public record MapTeam(String name) {

    public MapTeam {
        MapCodec.text(new JsonPrimitive(name), "/name");
    }
}
