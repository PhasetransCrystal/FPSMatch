package net.ptcrys.fpsmatch.editor.common.backend.tacz.script;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Explicit, immutable primitive constants for a single validation domain. */
public record ScriptConstants(Map<String, String> text, Map<String, Integer> integers) {

    private static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");

    public ScriptConstants {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(integers, "integers");
        if (text.size() > 256 || integers.size() > 256) {
            throw new IllegalArgumentException("Too many script constants");
        }
        Map<String, String> capturedText = new TreeMap<>();
        Map<String, Integer> capturedIntegers = new TreeMap<>();
        int textRemaining = 256;
        for (Map.Entry<String, String> entry : text.entrySet()) {
            if (textRemaining == 0) {
                throw new IllegalArgumentException("Too many text constants");
            }
            textRemaining--;
            String name = entry.getKey();
            checkName(name);
            String value = Objects.requireNonNull(entry.getValue(), "text constant value");
            if (value.length() > 4096) {
                throw new IllegalArgumentException("Text constant exceeds length limit");
            }
            capturedText.put(name, value);
        }
        int integersRemaining = 256;
        for (Map.Entry<String, Integer> entry : integers.entrySet()) {
            if (integersRemaining == 0) {
                throw new IllegalArgumentException("Too many integer constants");
            }
            integersRemaining--;
            String name = entry.getKey();
            checkName(name);
            Integer value = Objects.requireNonNull(entry.getValue(), "integer constant value");
            capturedIntegers.put(name, value);
        }
        for (String name : capturedText.keySet()) {
            if (capturedIntegers.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate script constant");
            }
        }
        text = Collections.unmodifiableMap(capturedText);
        integers = Collections.unmodifiableMap(capturedIntegers);
    }

    private static void checkName(String name) {
        Objects.requireNonNull(name, "constant name");
        if (!NAME.matcher(name).matches() || PinnedScriptValidator.isReserved(name)) {
            throw new IllegalArgumentException("Invalid or reserved script constant name");
        }
    }
}
