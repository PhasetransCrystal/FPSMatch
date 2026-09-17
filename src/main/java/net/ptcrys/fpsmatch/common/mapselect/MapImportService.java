package net.ptcrys.fpsmatch.common.mapselect;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.capability.team.ShopCapability;
import net.ptcrys.fpsmatch.common.capability.team.StartKitsCapability;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapImportSourceInfo;
import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.data.Setting;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.core.shop.FPSMShop;
import net.ptcrys.fpsmatch.core.team.ServerTeam;
import net.ptcrys.fpsmatch.util.FPSMCodec;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLLoader;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** Map-level configuration importer. It deliberately excludes map/region/point data. */
public final class MapImportService {

    private static final Gson GSON = new Gson();
    private static final int MAX_SOURCES = 256;
    private static final long MAX_MAP_FILE_BYTES = 16L * 1024L * 1024L;

    private MapImportService() {}

    public static List<MapImportSourceInfo> listSources(ServerPlayer player, String gameType, String mapName) {
        Optional<BaseMap> target = MapRoomQueryService.findMap(gameType, mapName);
        if (target.isEmpty()) return List.of();
        return discoverSources(target.get()).stream().map(Source::info).toList();
    }

    public static MapRoomActionService.Result importInto(ServerPlayer player, String targetGameType,
                                                         String targetMapName, String sourceId,
                                                         boolean importSettings, boolean importShop,
                                                         boolean importStartKits) {
        if (!MapRoomQueryService.isMapOperator(player)) {
            return MapRoomActionService.Result.failure(Component.translatable(
                    "gui.fpsm.map_select.action.no_permission"));
        }
        if (!importSettings && !importShop && !importStartKits) {
            return failure("gui.fpsm.map_import.nothing_selected");
        }
        Optional<BaseMap> targetOptional = MapRoomQueryService.findMap(targetGameType, targetMapName);
        if (targetOptional.isEmpty()) return failure("gui.fpsm.map_select.action.map_not_found");
        BaseMap target = targetOptional.get();
        Optional<Source> sourceOptional = discoverSources(target).stream()
                .filter(source -> source.info().id().equals(sourceId)).findFirst();
        if (sourceOptional.isEmpty()) return failure("gui.fpsm.map_import.source_missing");
        Source source = sourceOptional.get();
        if (!targetGameType.equals(source.gameType())) return failure("gui.fpsm.map_import.game_type_mismatch");
        if (source.matches(target)) return failure("gui.fpsm.map_import.same_source");

        try {
            List<SettingValue> settings = importSettings ? validateSettings(target, source) : List.of();
            List<TeamImport> teams = validateTeams(target, source, importShop, importStartKits);
            if (importShop && teams.stream().noneMatch(TeamImport::hasShop)) {
                return failure("gui.fpsm.map_import.shop_failed");
            }
            if (importStartKits && teams.stream().noneMatch(TeamImport::hasKits)) {
                return failure("gui.fpsm.map_import.kits_failed");
            }
            if (importSettings) {
                if (!source.hasSettings()) return failure("gui.fpsm.map_import.settings_failed");
                target.configFromJson(source.settings());
            }
            for (TeamImport team : teams) {
                if (team.hasShop()) {
                    if (!team.shopCapability().importConfigurationFrom(team.shop())) {
                        return failure("gui.fpsm.map_import.shop_failed");
                    }
                }
                if (team.hasKits()) {
                    team.targetKits().setTeamKits(new ArrayList<>(team.kits()));
                }
            }
            FPSMCore.getInstance().getFPSMDataManager().saveAllData();
            Component message = Component.translatable("gui.fpsm.map_import.success",
                    source.info().archiveName(), source.info().mapName());
            int shopSkipped = importShop ? (int) teams.stream().filter(team -> !team.hasShop()).count() : 0;
            int kitsSkipped = importStartKits ? (int) teams.stream().filter(team -> !team.hasKits()).count() : 0;
            if (shopSkipped > 0 || kitsSkipped > 0) {
                message = message.copy().append(Component.literal(" ")).append(Component.translatable(
                        "gui.fpsm.map_import.skipped", shopSkipped, kitsSkipped));
            }
            return MapRoomActionService.Result.success(message, target, player);
        } catch (ImportFailure failure) {
            FPSMatch.LOGGER.warn("Map import failed from {}", sourceId, failure);
            return MapRoomActionService.Result.failure(failure.message);
        } catch (RuntimeException failure) {
            FPSMatch.LOGGER.warn("Map import failed from {}", sourceId, failure);
            return failure("gui.fpsm.map_import.failed");
        }
    }

    private static List<SettingValue> validateSettings(BaseMap target, Source source) throws ImportFailure {
        if (!source.hasSettings()) throw new ImportFailure("gui.fpsm.map_import.settings_failed");
        List<SettingValue> values = new ArrayList<>();
        for (Setting<?> targetSetting : target.settings()) {
            JsonElement value = source.settings().get(targetSetting.getConfigName());
            if (value == null) throw new ImportFailure("gui.fpsm.map_import.settings_failed");
            try {
                values.add(new SettingValue(targetSetting, decodeSetting(targetSetting, value)));
            } catch (RuntimeException failure) {
                throw new ImportFailure("gui.fpsm.map_import.settings_failed");
            }
        }
        return values;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Object decodeSetting(Setting<?> setting, JsonElement value) {
        com.mojang.serialization.DataResult result = ((com.mojang.serialization.Codec) setting.codec())
                .decode(JsonOps.INSTANCE, value);
        com.mojang.datafixers.util.Pair pair = (com.mojang.datafixers.util.Pair) result.getOrThrow(false, error -> error.toString());
        return pair.getFirst();
    }

    private static List<TeamImport> validateTeams(BaseMap target, Source source, boolean shop, boolean kits)
                                                                                                             throws ImportFailure {
        List<TeamImport> result = new ArrayList<>();
        for (ServerTeam targetTeam : target.getMapTeams().getNormalTeams()) {
            JsonObject sourceTeam = source.teams().get(targetTeam.getName());
            ShopCapability targetShopCapability = targetTeam.getCapabilityMap().get(ShopCapability.class).orElse(null);
            StartKitsCapability targetKitsCapability = targetTeam.getCapabilityMap().get(StartKitsCapability.class).orElse(null);
            FPSMShop<?> importedShop = null;
            List<ItemStack> importedKits = null;
            if (shop && targetShopCapability != null && sourceTeam != null) {
                JsonElement raw = capability(sourceTeam, ShopCapability.class);
                if (raw != null && targetShopCapability.getShopSafe().isPresent()) {
                    try {
                        importedShop = decodeShop(targetShopCapability.getShopSafe().orElseThrow(), raw);
                        if (!targetShopCapability.canImportConfigurationFrom(importedShop)) {
                            throw new IllegalArgumentException("Incompatible shop configuration");
                        }
                    } catch (RuntimeException failure) {
                        throw new ImportFailure("gui.fpsm.map_import.shop_failed");
                    }
                }
            }
            if (kits && targetKitsCapability != null && sourceTeam != null) {
                JsonElement raw = capability(sourceTeam, StartKitsCapability.class);
                if (raw != null) {
                    try {
                        importedKits = decodeKits(raw);
                    } catch (RuntimeException failure) {
                        throw new ImportFailure("gui.fpsm.map_import.kits_failed");
                    }
                }
            }
            result.add(new TeamImport(targetShopCapability, targetKitsCapability, importedShop, importedKits));
        }
        return result;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static FPSMShop<?> decodeShop(FPSMShop<?> target, JsonElement json) {
        return (FPSMShop<?>) FPSMCodec.decodeFromJson((com.mojang.serialization.Codec) target.getCodec(), json);
    }

    private static List<ItemStack> decodeKits(JsonElement json) {
        return ItemStack.CODEC.listOf().decode(JsonOps.INSTANCE, json)
                .getOrThrow(false, IllegalArgumentException::new).getFirst()
                .stream().map(ItemStack::copy).toList();
    }

    private static JsonElement capability(JsonObject team, Class<?> capability) {
        JsonObject capabilities = team.has("capabilities") && team.get("capabilities").isJsonObject() ? team.getAsJsonObject("capabilities") : null;
        return capabilities == null ? null : capabilities.get(capability.getSimpleName());
    }

    private static List<Source> discoverSources(BaseMap target) {
        List<Source> sources = new ArrayList<>();
        addLoadedSources(target, sources);
        addArchivedSources(target, sources);
        sources.sort(Comparator.comparing((Source source) -> !source.info().currentArchive())
                .thenComparing(source -> source.info().archiveName()).thenComparing(source -> source.info().mapName()));
        List<Source> usable = sources.stream()
                .filter(source -> source.info().hasSettings() || source.info().hasShop() || source.info().hasStartKits())
                .toList();
        return usable.size() <= MAX_SOURCES ? List.copyOf(usable) : List.copyOf(usable.subList(0, MAX_SOURCES));
    }

    private static void addLoadedSources(BaseMap target, List<Source> sources) {
        FPSMCore core = FPSMCore.getInstance();
        core.getAllMaps().forEach((gameType, maps) -> maps.forEach(map -> {
            if (!gameType.equals(target.getGameType()) || map.equals(target)) return;
            Map<String, JsonObject> teams = new LinkedHashMap<>();
            map.getMapTeams().getNormalTeams().forEach(team -> teams.put(team.getName(), teamData(team)));
            MapImportSourceInfo info = sourceInfo("loaded|" + gameType + "|" + map.getMapName(),
                    core.archiveName, gameType, map.getMapName(), true, target, teams, true);
            sources.add(new Source(info, gameType, map.getMapName(), map.configToJson().getAsJsonObject(), teams));
        }));
    }

    private static JsonObject teamData(ServerTeam team) {
        return team.getCapabilityMap().getData().encode().getAsJsonObject();
    }

    private static void addArchivedSources(BaseMap target, List<Source> sources) {
        Path root = FMLLoader.getGamePath().resolve("fpsmatch").toAbsolutePath().normalize();
        Path current = FPSMCore.getInstance().getFPSMDataManager().getLevelDataPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> archives = Files.list(root)) {
            archives.filter(Files::isDirectory).filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !path.toAbsolutePath().normalize().equals(current))
                    .filter(path -> !path.getFileName().toString().equalsIgnoreCase("global"))
                    .forEach(archive -> scanArchive(root, archive, target, sources));
        } catch (Exception failure) {
            FPSMatch.LOGGER.warn("Failed to scan FPSMatch archives", failure);
        }
    }

    private static void scanArchive(Path root, Path archive, BaseMap target, List<Source> sources) {
        try (Stream<Path> files = Files.walk(archive, 2)) {
            files.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> path.getParent() != null && !path.getParent().equals(archive))
                    .limit(MAX_SOURCES).forEach(path -> readArchivedMap(root, archive, path, target, sources));
        } catch (Exception failure) {
            FPSMatch.LOGGER.warn("Failed to scan FPSMatch archive {}", archive, failure);
        }
    }

    private static void readArchivedMap(Path root, Path archive, Path file, BaseMap target, List<Source> sources) {
        try {
            Path normalized = file.toAbsolutePath().normalize();
            if (!normalized.startsWith(root) || Files.size(normalized) > MAX_MAP_FILE_BYTES) return;
            JsonObject wrapper;
            try (Reader reader = Files.newBufferedReader(normalized, StandardCharsets.UTF_8)) {
                wrapper = GSON.fromJson(reader, JsonObject.class);
            }
            if (wrapper == null || !wrapper.has("data") || !wrapper.get("data").isJsonObject()) return;
            JsonObject data = wrapper.getAsJsonObject("data");
            if (!data.has("teams") || !data.get("teams").isJsonObject()) return;
            String group = normalized.getParent().getFileName().toString();
            String targetGroup = FPSMCore.getInstance().getFPSMDataManager().getSaveFolder(target).getName();
            if (!group.equals(targetGroup)) return;
            String mapName = stringOr(data, "mapName", stripExtension(normalized.getFileName().toString()));
            Map<String, JsonObject> teams = new LinkedHashMap<>();
            data.getAsJsonObject("teams").entrySet().forEach(entry -> {
                if (entry.getValue().isJsonObject()) teams.put(entry.getKey(), entry.getValue().getAsJsonObject());
            });
            JsonObject settings = new JsonObject();
            Path cfg = normalized.getParent().resolve(mapName + ".cfg");
            if (Files.isRegularFile(cfg)) {
                try (Reader reader = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
                    JsonElement parsed = GSON.fromJson(reader, JsonElement.class);
                    if (parsed != null && parsed.isJsonObject()) settings = parsed.getAsJsonObject();
                }
            }
            MapImportSourceInfo info = sourceInfo("file|" + archive.getFileName() + "|" + archive.relativize(normalized),
                    archive.getFileName().toString(), group, mapName, false, target, teams, !settings.entrySet().isEmpty());
            sources.add(new Source(info, target.getGameType(), mapName, settings, teams));
        } catch (Exception ignored) {
            // Non-map JSON files and obsolete/corrupt map files are skipped.
        }
    }

    private static MapImportSourceInfo sourceInfo(String fingerprint, String archive, String group, String map,
                                                  boolean current, BaseMap target, Map<String, JsonObject> teams,
                                                  boolean hasSettings) {
        int mapped = 0;
        boolean shop = false;
        boolean kits = false;
        for (ServerTeam team : target.getMapTeams().getNormalTeams()) {
            JsonObject source = teams.get(team.getName());
            if (source == null) continue;
            mapped++;
            shop |= capability(source, ShopCapability.class) != null && team.getCapabilityMap().get(ShopCapability.class).isPresent();
            kits |= capability(source, StartKitsCapability.class) != null && team.getCapabilityMap().get(StartKitsCapability.class).isPresent();
        }
        return new MapImportSourceInfo(UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8)).toString(),
                label(archive), label(group), label(map), current, hasSettings, shop, kits, mapped);
    }

    private static String stringOr(JsonObject object, String key, String fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String label(String value) {
        return value == null ? "" : value.length() <= 128 ? value : value.substring(0, 128);
    }

    private static MapRoomActionService.Result failure(String key) {
        return MapRoomActionService.Result.failure(Component.translatable(key));
    }

    private record Source(MapImportSourceInfo info, String gameType, String mapName,
                          JsonObject settings, Map<String, JsonObject> teams) {

        boolean hasSettings() {
            return info.hasSettings();
        }

        boolean matches(BaseMap target) {
            return info.currentArchive() && gameType.equals(target.getGameType()) && mapName.equals(target.getMapName());
        }
    }

    private record SettingValue(Setting<?> setting, Object value) {}

    private record TeamImport(ShopCapability shopCapability, StartKitsCapability kitsCapability,
                              FPSMShop<?> shop, List<ItemStack> kits) {

        boolean hasShop() {
            return shop != null && shopCapability != null;
        }

        boolean hasKits() {
            return kitsCapability != null && kits != null;
        }

        StartKitsCapability targetKits() {
            return kitsCapability;
        }
    }

    private static final class ImportFailure extends Exception {

        private final Component message;

        private ImportFailure(String key) {
            this.message = Component.translatable(key);
        }
    }
}
