package net.ptcrys.fpsmatch.editor.common.backend.tacz.examples;

import net.ptcrys.fpsmatch.editor.common.backend.tacz.script.PinnedScriptValidator;
import net.ptcrys.fpsmatch.editor.common.backend.tacz.script.ScriptConstants;
import net.ptcrys.fpsmatch.editor.common.backend.tacz.script.ScriptValidationException;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiled against only the public backend JAR, with no LuaJ on javac's classpath. */
public final class PinnedScriptValidationSmoke {
    private PinnedScriptValidationSmoke() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--missing-runtime")) {
            try {
                PinnedScriptValidator.validate(Map.of("test_module", "return {}"),
                        new ScriptConstants(Map.of(), Map.of()), () -> false, 2_000);
                throw new AssertionError("Incomplete backend runtime accepted");
            } catch (ScriptValidationException expected) {
                if (expected.reason() != ScriptValidationException.Reason.SCRIPT_ERROR) throw expected;
                System.out.println("PINNED_SCRIPT_MISSING_RUNTIME_PASS");
                return;
            }
        }
        if (args.length != 2) throw new IllegalArgumentException("fixture directory and output required");
        Path root = Path.of(args[0]);
        List<String> report = new ArrayList<>();
        for (String domain : List.of("tacz-client", "tacz-common", "lr-client")) {
            Map<String, String> text = new LinkedHashMap<>();
            Map<String, Integer> integers = new LinkedHashMap<>();
            for (String row : Files.readAllLines(root.resolve(domain + ".constants.tsv"))) {
                String[] fields = row.split("\t", -1);
                if (fields.length != 3) throw new IllegalArgumentException("constant row");
                if (fields[0].equals("text")) text.put(fields[1], fields[2]);
                else if (fields[0].equals("integer")) integers.put(fields[1], Integer.valueOf(fields[2]));
                else throw new IllegalArgumentException("constant kind");
            }
            ScriptConstants constants = new ScriptConstants(text, integers);
            Map<String, String> sources = new LinkedHashMap<>();
            for (String row : Files.readAllLines(root.resolve(domain + ".sources.tsv"))) {
                String[] fields = row.split("\t", -1);
                if (fields.length != 3) throw new IllegalArgumentException("source row");
                Path source = root.resolve(fields[2]).normalize();
                if (!source.startsWith(root)) throw new IllegalArgumentException("source escapes fixture");
                byte[] bytes = Files.readAllBytes(source);
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (!hash.equals(fields[1])) throw new AssertionError("Source authentication failed");
                String decoded = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
                if (sources.put(fields[0], decoded) != null) {
                    throw new AssertionError("module mapping collision");
                }
                report.add("source\t" + domain + "\t" + fields[0] + "\t" + hash + "\t" + bytes.length);
            }
            long started = System.nanoTime();
            PinnedScriptValidator.Validation validation =
                    PinnedScriptValidator.validate(sources, constants, () -> false, 2_000_000);
            long elapsed = System.nanoTime() - started;
            List<String> names = new ArrayList<>(sources.keySet());
            Collections.reverse(names);
            Map<String, String> reverse = new LinkedHashMap<>();
            for (String name : names) reverse.put(name, sources.get(name));
            if (!validation.equals(PinnedScriptValidator.validate(reverse, constants, () -> false, 2_000_000))) {
                throw new AssertionError("Independent invocation/order changed results");
            }
            report.add("stats\t" + domain + "\t" + validation.modules().size() + "\t"
                    + validation.instructions() + "\t" + elapsed);
            for (String name : validation.modules()) {
                report.add("module\t" + domain + "\t" + name);
                for (String required : validation.requires().get(name)) {
                    report.add("edge\t" + domain + "\t" + name + "\t" + required);
                }
            }
            if (domain.equals("tacz-client")) {
                reverse.remove("tacz_default_state_machine");
                try {
                    PinnedScriptValidator.validate(reverse, constants, () -> false, 2_000_000);
                    throw new AssertionError("Missing real dependency accepted");
                } catch (ScriptValidationException expected) {
                    if (expected.reason() != ScriptValidationException.Reason.UNKNOWN_MODULE) throw expected;
                    report.add("missing\t" + domain + "\t" + expected.reason());
                }
            }
        }
        Files.write(Path.of(args[1]), report, StandardCharsets.UTF_8);
        System.out.println("PINNED_SCRIPT_REAL_SOURCES_PASS");
    }
}
