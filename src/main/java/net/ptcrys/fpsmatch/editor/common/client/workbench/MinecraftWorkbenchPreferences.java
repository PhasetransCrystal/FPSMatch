package net.ptcrys.fpsmatch.editor.common.client.workbench;

import net.ptcrys.fpsmatch.editor.api.TextContent;

import net.minecraft.client.Minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Small host-local visual preferences; never writes project data or a world. */
final class MinecraftWorkbenchPreferences {

    private static String selected;
    private static TextContent readDiagnostic;

    private MinecraftWorkbenchPreferences() {}

    private static Path path() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config/fpsmatch-editor-ui.json");
    }

    static String theme() {
        if (selected != null) return selected;
        selected = "light";
        Path file = path();
        try {
            if (!Files.exists(file)) return selected;
            if (!Files.isRegularFile(file)) throw new IOException("invalid visual preferences");
            byte[] bytes;
            try (var input = Files.newInputStream(file)) {
                bytes = input.readNBytes(4097);
            }
            if (bytes.length > 4096) throw new IOException("invalid visual preferences");
            var json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!json.keySet().equals(Set.of("formatVersion", "theme")) || !json.get("formatVersion").isJsonPrimitive() || !json.getAsJsonPrimitive("formatVersion").isNumber() || !json.get("formatVersion").getAsString().equals("1") || !json.get("theme").isJsonPrimitive() || !json.getAsJsonPrimitive("theme").isString()) throw new IOException("unsupported visual preferences");
            String value = json.get("theme").getAsString();
            if (!Set.of("light", "dark").contains(value)) throw new IOException("unsupported theme");
            selected = value;
        } catch (IOException | RuntimeException failure) {
            readDiagnostic = new TextContent.Message("editor.workbench.studio_preferences_invalid", List.of());
        }
        return selected;
    }

    static Optional<TextContent> diagnostic() {
        theme();
        return Optional.ofNullable(readDiagnostic);
    }

    static void saveTheme(String value) {
        if (!Set.of("light", "dark").contains(value)) throw new IllegalArgumentException("unknown theme");
        Path file = path(), temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "fpsmatch-editor-ui-", ".tmp");
            JsonObject json = new JsonObject();
            json.addProperty("formatVersion", 1);
            json.addProperty("theme", value);
            Files.writeString(temporary, json + "\n", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            selected = value;
            readDiagnostic = null;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not save editor visual preferences", failure);
        } finally {
            if (temporary != null) try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {}
        }
    }
}
