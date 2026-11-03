package net.ptcrys.fpsmatch.common.mapselect;

import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.ptcrys.fpsmatch.core.RuntimeMapLeaseException;
import net.ptcrys.fpsmatch.core.data.Setting;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production service boundary with real Setting values and observable side effects. */
class MapRoomSettingWriteTest {
    private static final class Harness {
        boolean operator = true, running, owned, scopeOpen, startOnSecondCheck;
        int starts, opened, closed, inventories, parses, saves, restored, valueAtClose;
        int parseMode;
        String name = "rounds";
        Runnable persistence = () -> {};
        Supplier<MapRoomActionService.Result> success = () -> new MapRoomActionService.Result(true, Component.literal("ok"), Optional.empty());
        final Setting<Integer> setting = new Setting<>("rounds", Codec.INT, 7, Integer::parseInt) {
            @Override public boolean parse(String raw) {
                assertTrue(scopeOpen); parses++;
                if (parseMode > 0) {
                    super.set(99);
                    if (parseMode == 2) throw new IllegalArgumentException("parser");
                    if (parseMode == 3) throw new AssertionError("parser");
                    return false;
                }
                return super.parse(raw);
            }
            @Override public Integer set(Integer value) {
                assertTrue(scopeOpen, "rollback must finish before the ownership scope closes");
                restored++; return super.set(value);
            }
        };
        MapRoomActionService.Result run() {
            return MapRoomActionService.writeSetting(operator, () -> running || startOnSecondCheck && ++starts == 2, () -> {
                opened++;
                if (owned) throw new RuntimeMapLeaseException(RuntimeMapLeaseException.Reason.OWNED_DEFINITION);
                scopeOpen = true;
                return () -> { valueAtClose = setting.get(); scopeOpen = false; closed++; };
            }, () -> { assertTrue(scopeOpen); inventories++; return List.of(setting); }, name, "9",
                    () -> { assertTrue(scopeOpen); saves++; persistence.run(); },
                    () -> { assertTrue(scopeOpen); return success.get(); });
        }
    }
    private static void failure(MapRoomActionService.Result result, String key) {
        assertFalse(result.success());
        assertEquals(key, ((TranslatableContents) result.message().getContents()).getKey());
    }
    private static void unchangedBeforeParse(Harness h) {
        assertEquals(7, h.setting.get()); assertEquals(0, h.parses);
        assertEquals(0, h.inventories); assertEquals(0, h.saves);
    }
    @Test void nonOperatorRefusesBeforeOpeningPersistenceOrReadingSettings() {
        Harness h = new Harness(); h.operator = false;
        failure(h.run(), "gui.fpsm.map_select.action.no_permission");
        unchangedBeforeParse(h); assertEquals(0, h.opened);
    }
    @Test void runningMapRefusesBeforeOpeningPersistenceOrReadingSettings() {
        Harness h = new Harness(); h.running = true;
        failure(h.run(), "gui.fpsm.map_select.action.setting.in_progress");
        unchangedBeforeParse(h); assertEquals(0, h.opened);
    }
    @Test void runtimeOwnershipRefusesBeforeParsingAndPersistence() {
        Harness h = new Harness(); h.owned = true;
        failure(h.run(), "gui.fpsm.map_select.action.setting.runtime_owned");
        unchangedBeforeParse(h); assertEquals(1, h.opened); assertEquals(0, h.closed);
    }
    @Test void runningStateIsRecheckedInsideTheOwnershipScope() {
        Harness h = new Harness(); h.startOnSecondCheck = true;
        failure(h.run(), "gui.fpsm.map_select.action.setting.in_progress");
        unchangedBeforeParse(h); assertEquals(1, h.closed);
    }
    @Test void stoppedUnmanagedMapMutatesAndPersistsWhileScopeRemainsOpen() {
        Harness h = new Harness();
        h.persistence = () -> { assertTrue(h.scopeOpen); assertEquals(9, h.setting.get()); };
        assertTrue(h.run().success());
        assertEquals(9, h.setting.get()); assertEquals(9, h.valueAtClose);
        assertEquals(1, h.saves); assertEquals(1, h.closed); assertEquals(0, h.restored);
    }
    @Test void missingSettingNeverParsesOrSaves() {
        Harness h = new Harness(); h.name = "missing";
        failure(h.run(), "gui.fpsm.map_select.action.setting.not_found");
        assertEquals(0, h.parses); assertEquals(0, h.saves); assertEquals(1, h.closed);
    }
    @Test void aParserThatMutatesThenRejectsIsRestoredBeforeScopeClose() {
        Harness h = new Harness(); h.parseMode = 1;
        failure(h.run(), "gui.fpsm.map_select.action.setting.invalid");
        assertEquals(7, h.setting.get()); assertEquals(7, h.valueAtClose); assertEquals(0, h.saves);
    }
    @Test void parsingExceptionRestoresTheOriginalValue() {
        Harness h = new Harness(); h.parseMode = 2;
        failure(h.run(), "gui.fpsm.map_select.action.setting.save_failed");
        assertEquals(7, h.setting.get()); assertEquals(7, h.valueAtClose); assertEquals(0, h.saves);
    }
    @Test void persistenceFailureRestoresTheOriginalValueBeforeScopeClose() {
        Harness h = new Harness(); h.persistence = () -> { throw new IllegalStateException("disk"); };
        failure(h.run(), "gui.fpsm.map_select.action.setting.save_failed");
        assertEquals(7, h.setting.get()); assertEquals(7, h.valueAtClose); assertEquals(1, h.saves);
    }
    @Test void resultConstructionFailureCannotLeaveTheNewValueInMemory() {
        Harness h = new Harness(); h.success = () -> { throw new IllegalStateException("detail"); };
        failure(h.run(), "gui.fpsm.map_select.action.setting.save_failed");
        assertEquals(7, h.setting.get()); assertEquals(7, h.valueAtClose);
    }
    @Test void fatalParserAndPersistenceErrorsRestoreBeforePropagating() {
        Harness parser = new Harness(); parser.parseMode = 3;
        assertThrows(AssertionError.class, parser::run);
        assertEquals(7, parser.setting.get()); assertEquals(7, parser.valueAtClose);
        Harness disk = new Harness();
        disk.persistence = () -> { throw new AssertionError("disk"); };
        assertThrows(AssertionError.class, disk::run);
        assertEquals(7, disk.setting.get()); assertEquals(7, disk.valueAtClose);
    }
}
