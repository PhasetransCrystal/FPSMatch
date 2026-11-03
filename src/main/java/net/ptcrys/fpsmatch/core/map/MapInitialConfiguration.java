package net.ptcrys.fpsmatch.core.map;

import net.ptcrys.fpsmatch.core.data.Setting;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable configuration for a map that is still inside its construction scope. */
public final class MapInitialConfiguration {

    private static final int MAX_SETTINGS = 4096;

    private final ExactJson.FrozenJson configuration;

    private MapInitialConfiguration(ExactJson.FrozenJson configuration) {
        this.configuration = configuration;
    }

    /** Captures a complete, bounded JSON object without retaining the caller's tree. */
    public static MapInitialConfiguration capture(JsonElement configuration) {
        Objects.requireNonNull(configuration, "configuration");
        if (!configuration.isJsonObject()) {
            throw new IllegalArgumentException("Initial map configuration must be a JSON object");
        }
        return new MapInitialConfiguration(ExactJson.freeze(configuration));
    }

    /**
     * Validates the entire inventory and all codecs before calling any Setting#fromJson.
     * A failure leaves any applied values in the unpublished candidate; the enclosing scope
     * owns resource cleanup and the caller must discard that candidate.
     */
    public void apply(Collection<Setting<?>> settings) {
        if (!MapConstructionScope.isConstructing()) {
            throw new IllegalStateException("Initial map configuration requires an active construction scope");
        }
        Objects.requireNonNull(settings, "settings");

        List<PlannedSetting> inventory = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Setting<?> setting : settings) {
            if (inventory.size() == MAX_SETTINGS) {
                throw new IllegalArgumentException("Initial map setting limit exceeded");
            }
            Objects.requireNonNull(setting, "setting");
            String name = setting.getConfigName();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Initial map setting name must not be null or blank");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("Duplicate initial map setting: " + name);
            }
            inventory.add(new PlannedSetting(setting, name));
        }

        JsonObject captured = configuration.toJson().getAsJsonObject();
        if (!captured.keySet().equals(names)) {
            throw new IllegalArgumentException("Initial map configuration keys must match the complete setting inventory");
        }

        List<Object> expectedValues = new ArrayList<>(inventory.size());
        for (PlannedSetting item : inventory) {
            DataResult<?> decoded = item.setting().codec().parse(JsonOps.INSTANCE,
                    captured.get(item.name()).deepCopy());
            if (decoded.error().isPresent()) {
                throw new IllegalArgumentException("Invalid initial map setting " + item.name() + ": " + decoded.error().get().message());
            }
            expectedValues.add(decoded.result().orElseThrow(() -> new IllegalArgumentException("Initial map setting decoded to null: " + item.name())));
        }

        for (PlannedSetting item : inventory) {
            item.setting().fromJson(captured.get(item.name()).deepCopy());
        }
        for (int index = 0; index < inventory.size(); index++) {
            PlannedSetting item = inventory.get(index);
            if (!Objects.equals(expectedValues.get(index), item.setting().get())) {
                throw new IllegalStateException("Initial map setting was not adopted: " + item.name());
            }
        }
    }

    private record PlannedSetting(Setting<?> setting, String name) {}
}
