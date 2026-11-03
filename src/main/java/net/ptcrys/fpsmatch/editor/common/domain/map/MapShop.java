package net.ptcrys.fpsmatch.editor.common.domain.map;

import com.google.gson.JsonPrimitive;

public record MapShop(String sourceName, int defaultMoney) {

    public MapShop {
        MapCodec.text(new JsonPrimitive(sourceName), "/sourceName");
    }
}
