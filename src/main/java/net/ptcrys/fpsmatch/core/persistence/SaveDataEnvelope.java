package net.ptcrys.fpsmatch.core.persistence;

import net.ptcrys.fpsmatch.core.persistence.datafixer.DataFixer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Shared legacy envelope extraction and version migration for reads and saves. */
record SaveDataEnvelope(int version, JsonElement rawData) {

    static SaveDataEnvelope fromJson(JsonElement json) {
        if (!json.isJsonObject()) {
            return new SaveDataEnvelope(0, json);
        }
        JsonObject wrapper = json.getAsJsonObject();
        int version = wrapper.has("version") ? wrapper.get("version").getAsInt() : 0;
        JsonElement rawData = wrapper.has("data") ? wrapper.get("data") : json;
        return new SaveDataEnvelope(version, rawData);
    }

    JsonElement migrateTo(Class<?> holderClass, int targetVersion) {
        if (version == targetVersion) return rawData;
        return DataFixer.getInstance().fixJson(holderClass, rawData, version, targetVersion);
    }
}
