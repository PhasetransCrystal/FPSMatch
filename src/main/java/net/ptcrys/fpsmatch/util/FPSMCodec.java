package net.ptcrys.fpsmatch.util;

import net.ptcrys.fpsmatch.core.persistence.DataPersistenceException;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

public class FPSMCodec {

    public static <T> JsonElement encodeToJson(Codec<T> codec, T data) {
        return codec.encodeStart(JsonOps.INSTANCE, data).getOrThrow(false, e -> {
            throw new DataPersistenceException(e);
        });
    }

    public static <T> T decodeFromJson(Codec<T> codec, JsonElement json) {
        return codec.decode(JsonOps.INSTANCE, json).getOrThrow(false, e -> {
            throw new DataPersistenceException(e);
        }).getFirst();
    }
}
