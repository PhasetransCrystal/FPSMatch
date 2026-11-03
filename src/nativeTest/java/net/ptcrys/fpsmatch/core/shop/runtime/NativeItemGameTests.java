package net.ptcrys.fpsmatch.core.shop.runtime;

import com.google.gson.GsonBuilder;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** A required synchronous GameTest. Jupiter is entered only after the real Forge server starts. */
@GameTestHolder("fpsmatch")
@PrefixGameTestTemplate(false)
public final class NativeItemGameTests {
    private static final String SCHEMA = "Q69_NATIVE_ITEM_TESTS_V1";

    @GameTest(template = "native_item_empty", timeoutTicks = 1200, required = true, attempts = 1)
    public static void nativeRuntimeItems(GameTestHelper helper) {
        Thread owner = Thread.currentThread();
        ClassLoader previous = owner.getContextClassLoader();
        Map<String, Object> result = identity(owner);
        result.put("status", "FAIL");
        result.put("expectedTests", List.of());
        result.put("tests", List.of());
        result.put("containers", List.of());
        result.put("discoveredTests", 0);
        result.put("succeededTests", 0);
        result.put("failedTests", 0);
        result.put("abortedTests", 0);
        result.put("skippedTests", 0);
        result.put("javaVersion", System.getProperty("java.version"));
        Throwable failure = null;
        Path attempt = null;
        try {
            Path game = validateGame();
            attempt = game.getParent();
            writeNew(attempt.resolve("native-started.json"), identity(owner));
            if (Runtime.version().feature() != 17) throw new IllegalStateException("Native tests require Java 17");
            if (owner.isInterrupted()) throw new InterruptedException("Native owner interrupted before launch");
            owner.setContextClassLoader(NativeItemGameTests.class.getClassLoader());
            // Kept in a separate class so linkage/engine/discovery failures occur after the start record.
            NativeJupiterGate.execute(owner, result);
            if (owner.isInterrupted()) throw new InterruptedException("Native owner interrupted during launch");
        } catch (Throwable thrown) {
            failure = thrown;
        } finally {
            try {
                owner.setContextClassLoader(previous);
                if (Thread.currentThread() != owner) throw new IllegalStateException("Native owner thread changed");
            } catch (Throwable cleanup) {
                if (failure == null) failure = cleanup;
                else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
        }
        result.put("endedAt", Instant.now().toString());
        result.put("tcclRestored", owner.getContextClassLoader() == previous);
        if (failure != null) result.put("error", describe(failure));
        result.put("status", failure == null ? "PASS" : "FAIL");
        try {
            if (attempt == null) throw new IllegalStateException("Unvalidated native game path");
            regularDirectories(attempt);
            writeNew(attempt.resolve("native-results.json"), result);
        } catch (Throwable reportFailure) {
            if (failure == null) failure = reportFailure;
            else if (failure != reportFailure) failure.addSuppressed(reportFailure);
        }
        if (failure != null) {
            failure.printStackTrace(System.err);
            helper.fail("Q69 native Jupiter gate failed: " + failure);
        } else {
            helper.succeed();
        }
    }

    private static Map<String, Object> identity(Thread owner) {
        ProcessHandle process = ProcessHandle.current();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("schema", SCHEMA);
        fields.put("processId", process.pid());
        fields.put("processStartedAt", process.info().startInstant().orElseThrow().toString());
        fields.put("ownerThreadId", owner.getId());
        fields.put("ownerThreadName", owner.getName());
        fields.put("at", Instant.now().toString());
        return fields;
    }

    private static Path validateGame() throws Exception {
        String supplied = System.getProperty("q69.native.game");
        if (supplied == null) throw new IllegalStateException("Missing native game path");
        Path suppliedPath = Path.of(supplied);
        Path game = suppliedPath.toAbsolutePath().normalize();
        Path fmlGame = FMLLoader.getGamePath().toAbsolutePath().normalize();
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (!suppliedPath.isAbsolute() || !suppliedPath.equals(game)
                || !game.getFileName().toString().equals("game")
                || !game.getParent().getParent().getFileName().toString().equals("native-item-tests")
                || !game.getParent().getParent().getParent().getFileName().toString().equals("build")
                || !UUID.fromString(game.getParent().getFileName().toString()).toString()
                        .equals(game.getParent().getFileName().toString())
                || !fmlGame.equals(game)
                || !cwd.equals(game)) {
            throw new IllegalStateException("Unexpected native game path: supplied=" + supplied
                    + ", normalized=" + game + ", FML=" + fmlGame + ", cwd=" + cwd);
        }
        regularDirectories(game);
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            if (System.getenv(variable) != null && !System.getenv(variable).isBlank()) {
                throw new IllegalStateException("JVM injection refused: " + variable);
            }
        }
        if (System.getProperties().keySet().stream().anyMatch(k -> k.toString().startsWith("fpsmatch.storage.jdbc."))) {
            throw new IllegalStateException("JDBC injection refused");
        }
        return game;
    }

    private static void regularDirectories(Path path) throws Exception {
        Path cursor = path.getRoot();
        for (Path component : path) {
            cursor = cursor.resolve(component);
            BasicFileAttributes attrs = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()
                    || !cursor.toRealPath().equals(cursor.toAbsolutePath().normalize())) {
                throw new IllegalStateException("Linked/reparsed native directory refused: " + cursor);
            }
        }
    }

    private static void writeNew(Path file, Map<String, Object> report) throws Exception {
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(report),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    static String describe(Throwable failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        return text.toString();
    }
}
