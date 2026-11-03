package net.ptcrys.fpsmatch.editor.core.fontprobe;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;
import org.lwjgl.opengl.GL11;

/** Deliberately a short pipeline smoke, never a formal A/B benchmark. */
final class FontProbeScreen extends Screen {
    private static final long WARMUP_NS = 1_000_000_000L;
    private static final long MEASURE_NS = 2_000_000_000L;
    private static final int ROWS = 16;
    private static final int MAX_SAMPLES = 20000;
    private static final int MIN_SAMPLES = 20;
    private static final long MAX_FRAME_GAP_NS = 1_000_000_000L;
    private static final String[] SCENES = {"empty_probe", "short_labels", "cjk_formatted", "dynamic_unique"};
    private static final int FULL_BRIGHT = 15728880;

    private final List<Samples> scenes = new ArrayList<>();
    private final Map<String, Object> environment = new LinkedHashMap<>();
    private final String[] strings = new String[ROWS];
    private final Path output = Path.of(System.getProperty("editorFontSmoke.output"));
    private long phaseStart;
    private long previousFrame;
    private long frameNumber;
    private long checksum;
    private int sceneIndex;
    private boolean finished;
    private int expectedWidth;
    private int expectedHeight;
    private long resourceGeneration;

    FontProbeScreen() {
        super(Component.literal("Development font smoke — NOT A BASELINE"));
        if (Files.exists(output)) {
            throw new IllegalStateException("Font probe refuses an existing results directory");
        }
        for (String scene : SCENES) {
            scenes.add(new Samples(scene));
        }
    }

    @Override protected void init() {
        if (expectedWidth != 0 && (expectedWidth != width || expectedHeight != height)) {
            finish("INCOMPLETE", "screen_resized");
            return;
        }
        expectedWidth = width;
        expectedHeight = height;
        if (phaseStart != 0 || finished) {
            return;
        }
        if (height < 250 || width < 360) {
            finish("INCOMPLETE", "window_too_small_for_visible_rows");
            return;
        }
        try {
            Class<?> modernUi = Class.forName("icyllis.modernui.mc.ModernUIMod");
            Object enabled = modernUi.getMethod("isTextEngineEnabled").invoke(null);
            environment.put("modernuiTextEngineEnabled", enabled);
            Map<String, String> bootstrap = new LinkedHashMap<>();
            for (String flag : List.of("disableTextEngine", "disableEnhancedTextField", "disableSmoothScrolling")) {
                Object value = modernUi.getMethod("getBootstrapProperty", String.class)
                        .invoke(null, "modernui_mc_" + flag);
                bootstrap.put(flag, String.valueOf(value));
            }
            environment.put("modernuiBootstrap", bootstrap);
            // 3.12.0.1 checks smooth scrolling first and returns before the enhanced-text branch.
            if (!"true".equals(bootstrap.get("disableEnhancedTextField"))
                    || !"false".equals(bootstrap.get("disableSmoothScrolling"))) {
                finish("CONTAMINATED", "modernui_bootstrap_branch_conflict");
                return;
            }
            environment.put("splitterClass", font.getSplitter().getClass().getName());
            if (!Boolean.FALSE.equals(enabled) || font.getSplitter().getClass() != StringSplitter.class) {
                finish("CONTAMINATED", "font_takeover_not_disabled");
                return;
            }
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                | InvocationTargetException failure) {
            finish("INCOMPLETE", "takeover_verification_failed:" + failure.getClass().getSimpleName());
            return;
        }
        environment.put("mods", ModList.get().getMods().stream()
                .map(mod -> Map.of("id", mod.getModId(), "version", mod.getVersion().toString())).toList());
        if (!ModList.get().getModContainerById("modernui").orElseThrow()
                .getModInfo().getVersion().toString().equals("3.12.0.1")) {
            finish("INCOMPLETE", "unverified_modernui_bootstrap_version");
            return;
        }
        environment.put("java", System.getProperty("java.version"));
        environment.put("os", System.getProperty("os.name"));
        environment.put("arch", System.getProperty("os.arch"));
        environment.put("processors", Runtime.getRuntime().availableProcessors());
        environment.put("gpu", GL11.glGetString(GL11.GL_RENDERER));
        environment.put("driver", GL11.glGetString(GL11.GL_VERSION));
        environment.put("windowWidth", minecraft.getWindow().getWidth());
        environment.put("windowHeight", minecraft.getWindow().getHeight());
        environment.put("guiScale", minecraft.getWindow().getGuiScale());
        environment.put("guiWidth", width);
        environment.put("guiHeight", height);
        environment.put("language", minecraft.options.languageCode);
        environment.put("vsync", minecraft.options.enableVsync().get());
        environment.put("fpsLimit", minecraft.options.framerateLimit().get());
        resourceGeneration = FontProbe.RESOURCE_GENERATION.get();
        environment.put("resourceGeneration", resourceGeneration);
        phaseStart = System.nanoTime();
        previousFrame = phaseStart;
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (finished) {
            return;
        }
        if (minecraft.getOverlay() != null || FontProbe.RESOURCE_GENERATION.get() != resourceGeneration) {
            finish("INCOMPLETE", "resource_environment_changed");
            return;
        }
        long frameStart = System.nanoTime();
        if (previousFrame != 0 && frameStart - previousFrame > MAX_FRAME_GAP_NS) {
            finish("INCOMPLETE", "render_interrupted_over_one_second");
            return;
        }
        graphics.fill(0, 0, width, height, 0xff17202b);
        graphics.drawString(font, title, 12, 10, 0xffffffff, false);
        graphics.drawString(font, "SMOKE_ONLY / " + SCENES[sceneIndex], 12, 24, 0xffb0c0d0, false);
        graphics.flush();

        long inputStart = System.nanoTime();
        prepareInputs();
        long widthStart = System.nanoTime();
        if (sceneIndex != 0) {
            for (String text : strings) {
                checksum += font.width(text);
            }
        }
        long drawStart = System.nanoTime();
        if (sceneIndex != 0) {
            for (int row = 0; row < ROWS; row++) {
                checksum += font.drawInBatch(strings[row], 12, 46 + row * 12, 0xffeeeeee,
                        true, graphics.pose().last().pose(), graphics.bufferSource(),
                        Font.DisplayMode.NORMAL, 0, FULL_BRIGHT, font.isBidirectional());
            }
        }
        long flushStart = System.nanoTime();
        graphics.flush();
        long end = System.nanoTime();
        if (end - frameStart > MAX_FRAME_GAP_NS) {
            finish("INCOMPLETE", "render_batch_interrupted_over_one_second");
            return;
        }
        long elapsed = frameStart - phaseStart;
        if (elapsed >= WARMUP_NS && elapsed < WARMUP_NS + MEASURE_NS) {
            Samples samples = scenes.get(sceneIndex);
            if (!samples.add(widthStart - inputStart, drawStart - widthStart,
                    flushStart - drawStart, end - flushStart,
                    previousFrame == 0 ? 0 : frameStart - previousFrame)) {
                finish("INCOMPLETE", "sample_capacity_exceeded");
                return;
            }
        }
        previousFrame = frameStart;
        frameNumber++;
        if (elapsed >= WARMUP_NS + MEASURE_NS) {
            if (scenes.get(sceneIndex).size < MIN_SAMPLES) {
                finish("INCOMPLETE", "insufficient_samples");
                return;
            }
            sceneIndex++;
            if (sceneIndex == SCENES.length) {
                finish("SMOKE_ONLY", "pipeline_completed_not_a_baseline");
                minecraft.setScreen(new TitleScreen());
            } else {
                phaseStart = System.nanoTime();
            }
        }
    }

    private void prepareInputs() {
        for (int row = 0; row < ROWS; row++) {
            strings[row] = switch (sceneIndex) {
                case 2 -> "区域 " + row + " §aAlpha§r / 世界编辑器 §l入口§r 123";
                case 3 -> "frame=" + frameNumber + " value=" + (frameNumber * ROWS + row);
                default -> SHORT_LABELS[row];
            };
        }
    }

    @Override public void removed() {
        if (!finished) {
            finish("INCOMPLETE", "screen_removed");
        }
    }

    @Override public void onClose() {
        finish("INCOMPLETE", "operator_closed_screen");
        minecraft.setScreen(new TitleScreen());
    }

    @Override public boolean isPauseScreen() { return false; }

    private void finish(String status, String reason) {
        if (finished) {
            return;
        }
        finished = true;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", status);
        report.put("reason", reason);
        report.put("formalBaseline", false);
        report.put("timing", "CPU wall time per batch; flush is NOT GPU execution time");
        report.put("environment", environment);
        report.put("warmupNs", WARMUP_NS);
        report.put("measureNs", MEASURE_NS);
        report.put("rows", ROWS);
        report.put("checksum", checksum);
        report.put("scenes", scenes.stream().map(Samples::summary).toList());
        try {
            Files.createDirectory(output);
            try (BufferedWriter writer = Files.newBufferedWriter(output.resolve("samples.csv"),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                writer.write("scene,index,input_ns,width_ns,draw_ns,flush_cpu_ns,frame_interval_ns\n");
                for (Samples samples : scenes) {
                    samples.write(writer);
                }
            }
            Files.writeString(output.resolve("summary.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(report),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (IOException failure) {
            throw new IllegalStateException("Font smoke result could not be written", failure);
        }
    }

    private static final String[] SHORT_LABELS = {"Play", "Options", "Done", "Cancel", "Back", "Search",
            "World", "Objects", "Tools", "Project", "Save", "Open", "Close", "Undo", "Redo", "Help"};

    private static final class Samples {
        private final String name;
        private final long[][] values = new long[5][MAX_SAMPLES];
        private int size;

        Samples(String name) { this.name = name; }

        boolean add(long input, long width, long draw, long flush, long interval) {
            if (size == MAX_SAMPLES) {
                return false;
            }
            values[0][size] = input;
            values[1][size] = width;
            values[2][size] = draw;
            values[3][size] = flush;
            values[4][size] = interval;
            size++;
            return true;
        }

        Map<String, Object> summary() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            result.put("samples", size);
            String[] labels = {"input", "width", "draw", "flushCpu", "frameInterval"};
            for (int i = 0; i < values.length; i++) {
                long[] sorted = Arrays.copyOf(values[i], size);
                Arrays.sort(sorted);
                result.put(labels[i] + "Ns", Map.of("p50", percentile(sorted, 0.50),
                        "p95", percentile(sorted, 0.95), "p99", percentile(sorted, 0.99)));
            }
            return result;
        }

        private static long percentile(long[] sorted, double rank) {
            return sorted.length == 0 ? 0 : sorted[(int) Math.ceil(rank * sorted.length) - 1];
        }

        void write(BufferedWriter writer) throws IOException {
            for (int i = 0; i < size; i++) {
                writer.write(name + "," + i);
                for (long[] field : values) {
                    writer.write("," + field[i]);
                }
                writer.newLine();
            }
        }
    }
}
