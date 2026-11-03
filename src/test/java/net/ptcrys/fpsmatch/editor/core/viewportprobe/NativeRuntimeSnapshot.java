package net.ptcrys.fpsmatch.editor.core.viewportprobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.ptcrys.fpsmatch.common.entity.MatchDropEntity;
import net.ptcrys.fpsmatch.common.capability.team.StartKitsCapability;
import net.ptcrys.fpsmatch.common.capability.team.SpawnPointCapability;
import net.ptcrys.fpsmatch.common.capability.map.DemolitionModeCapability;
import net.ptcrys.fpsmatch.compat.gun.GunCompatManager;
import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.core.map.BaseRoundMap;
import net.ptcrys.fpsmatch.core.entity.BlastBombEntity;
import net.ptcrys.fpsmatch.core.match.RoundLifecycle;
import net.ptcrys.fpsmatch.core.map.VoteObj;
import net.ptcrys.fpsmatch.core.team.ServerTeam;

import java.util.List;
import java.util.Objects;

/** Read-only server-owner observation of the one explicitly isolated Q82 installation. */
final class NativeRuntimeSnapshot {
    static final String CS_NAME = "Q82_CS_EEAE5ED5";
    private static final String CS_CLASS = "net.ptcrys.blockoffensive.map.CSGameMap";
    private static final String C4_CLASS = "net.ptcrys.blockoffensive.entity.CompositionC4Entity";
    private static final String C4_ITEM_CLASS = "net.ptcrys.blockoffensive.item.CompositionC4";

    private NativeRuntimeSnapshot() {}

    static JsonObject capture(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("server_owner_required");
        String name = "Q82_UI46_DM_TYPED";
        JsonObject result = new JsonObject();
        result.addProperty("server_thread", true);
        result.add("round_observation", NativeCsRoundObservation.status(server));
        JsonArray players=new JsonArray();
        for(var player:server.getPlayerList().getPlayers()){
            JsonObject entry=new JsonObject();
            entry.addProperty("uuid",player.getUUID().toString());
            entry.addProperty("x",player.getX());entry.addProperty("y",player.getY());entry.addProperty("z",player.getZ());
            entry.addProperty("dimension",player.level().dimension().location().toString());
            entry.addProperty("game_mode",player.gameMode.getGameModeForPlayer().getName());
            entry.addProperty("below",player.level().getBlockState(player.blockPosition().below()).getBlock().toString());
            entry.addProperty("feet",player.level().getBlockState(player.blockPosition()).getBlock().toString());
            entry.addProperty("head",player.level().getBlockState(player.blockPosition().above()).getBlock().toString());
            entry.addProperty("health",player.getHealth());
            entry.addProperty("selected_slot",player.getInventory().selected);
            JsonArray inventory=new JsonArray();
            for(int slot=0;slot<player.getInventory().getContainerSize();slot++){
                ItemStack stack=player.getInventory().getItem(slot);
                if(!stack.isEmpty()){
                    JsonObject observed=item(stack);observed.addProperty("slot",slot);inventory.add(observed);
                }
            }
            entry.add("inventory",inventory);
            players.add(entry);
        }
        result.add("players",players);
        JsonArray runtimes=new JsonArray();
        FPSMCore.getInstance().getAllMaps().forEach((type,maps)->maps.forEach(runtime->{
            JsonObject entry=new JsonObject();entry.addProperty("type",type);entry.addProperty("name",runtime.getMapName());
            entry.addProperty("map_class",runtime.getClass().getName());
            entry.addProperty("runtime_owned",runtime.hasRuntimeOwnership());
            entry.addProperty("is_start",runtime.isStart());entry.addProperty("reset_generation",runtime.resetGeneration());
            entry.addProperty("participants",runtime.getMapTeams().getJoinedPlayersWithSpec().size());
            JsonArray roster=new JsonArray();
            for(ServerTeam team:runtime.getMapTeams().getNormalTeams())for(var playerId:team.getPlayers().keySet()){
                JsonObject participant=new JsonObject();participant.addProperty("uuid",playerId.toString());
                participant.addProperty("team",team.getName());roster.add(participant);
            }
            entry.add("roster",roster);
            JsonObject settings = new JsonObject();
            for (var setting : runtime.settings()) {
                Object value = setting.get();
                if (value instanceof Number number) settings.addProperty(setting.getConfigName(), number);
                else if (value instanceof Boolean flag) settings.addProperty(setting.getConfigName(), flag);
                else settings.addProperty(setting.getConfigName(), String.valueOf(value));
            }
            entry.add("settings", settings);
            JsonArray nativeTeams = new JsonArray();
            for (ServerTeam team : runtime.getMapTeams().getTeamsWithSpectator()) {
                JsonObject observed = new JsonObject();
                observed.addProperty("name",team.getName()); observed.addProperty("fixed_name",team.getFixedName());
                observed.addProperty("player_limit",team.getPlayerLimit()); observed.addProperty("rounds_enabled",team.isRoundsEnabled());
                JsonArray capabilities = new JsonArray();
                team.getCapabilityMap().values().forEach(cap -> capabilities.add(cap.getClass().getSimpleName()));
                observed.add("capabilities",capabilities);
                JsonArray spawns = new JsonArray();
                team.getCapabilityMap().get(SpawnPointCapability.class).ifPresent(cap -> cap.getSpawnPointsData().forEach(point -> {
                    JsonObject spawn = new JsonObject(); spawn.addProperty("dimension",point.getDimension().location().toString());
                    spawn.addProperty("x",point.getX());spawn.addProperty("y",point.getY());spawn.addProperty("z",point.getZ());
                    spawns.add(spawn);
                }));
                observed.add("spawns",spawns);
                JsonArray deliveries = new JsonArray();
                team.getCapabilityMap().get(StartKitsCapability.class).flatMap(StartKitsCapability::runtimeKits).ifPresent(kits -> {
                    for (int i = 0; i < kits.templates().size(); i++) {
                        ItemStack delivery = kits.createDelivery(i);
                        try { deliveries.add(item(delivery)); }
                        finally { if (delivery != ItemStack.EMPTY) delivery.invalidateCaps(); }
                    }
                });
                observed.add("independent_kit_deliveries",deliveries); nativeTeams.add(observed);
            }
            entry.add("teams",nativeTeams);
            runtime.getCapabilityMap().get(DemolitionModeCapability.class).ifPresent(cap -> {
                JsonObject demolition = new JsonObject(); demolition.addProperty("blast_team",cap.getDemolitionTeam());
                JsonArray bombAreas = new JsonArray();
                cap.getBombAreaData().forEach(area -> {
                    JsonObject bomb = new JsonObject(); bomb.addProperty("first",area.pos1().toShortString());
                    bomb.addProperty("second",area.pos2().toShortString()); bombAreas.add(bomb);
                });
                demolition.add("bomb_areas",bombAreas); entry.add("demolition",demolition);
            });
            if ("cs".equals(type) && CS_NAME.equals(runtime.getMapName()))
                entry.add("cs_round", captureCsRound(server, runtime));
            runtimes.add(entry);
        }));
        result.add("runtimes",runtimes);
        result.addProperty("map_name", name);
        var found = FPSMCore.getInstance().getMapByTypeWithName("csdm", name);
        result.addProperty("map_present", found.isPresent());
        if (found.isEmpty()) return result;
        BaseMap map = found.orElseThrow();
        result.addProperty("map_class", map.getClass().getName());
        result.addProperty("runtime_owned", map.hasRuntimeOwnership());
        JsonArray teams = new JsonArray();
        for (String teamName : List.of("ct", "t")) {
            ServerTeam team = map.getMapTeams().getTeamByName(teamName).orElseThrow();
            var kits = team.getCapabilityMap().get(StartKitsCapability.class).orElseThrow()
                    .runtimeKits().orElseThrow();
            JsonObject entry = new JsonObject();
            entry.addProperty("name", teamName);
            JsonArray items = new JsonArray();
            entry.addProperty("observation", "independent_delivery_copy");
            for (int index = 0; index < kits.templates().size(); index++) {
                ItemStack delivery = kits.createDelivery(index);
                try { items.add(item(delivery)); }
                finally { if (delivery != ItemStack.EMPTY) delivery.invalidateCaps(); }
            }
            entry.add("kits", items);
            teams.add(entry);
        }
        result.add("teams", teams);
        return result;
    }

    /** A compact owner-thread observation, without creating kit copies or touching gameplay state. */
    static JsonObject captureCsRound(MinecraftServer server, BaseMap map) {
        requireOwner(server);
        if (requireCsTarget(server) != map) throw new IllegalStateException("cs_runtime_identity_changed");
        JsonObject result = new JsonObject();
        result.addProperty("server_tick", server.getTickCount());
        result.addProperty("name", map.getMapName());
        result.addProperty("runtime_identity", identity(map));
        result.addProperty("runtime_owned", map.hasRuntimeOwnership());
        result.addProperty("is_start", map.isStart());
        result.addProperty("reset_generation", map.resetGeneration());
        result.addProperty("is_debug", map.isDebug());
        result.addProperty("dimension", map.getServerLevel().dimension().location().toString());
        result.add("map_bounds", bounds(map.getMapArea().aabb()));
        RoundLifecycle<?, ?> lifecycle = csLifecycle(map);
        result.addProperty("lifecycle_present", lifecycle != null);
        if (lifecycle == null) result.add("lifecycle", JsonNull.INSTANCE);
        else {
            JsonObject round = new JsonObject();
            round.addProperty("identity", identity(lifecycle));
            round.addProperty("phase", lifecycle.phase().name());
            round.addProperty("phase_elapsed_ticks", lifecycle.phaseElapsedTicks());
            round.addProperty("round_elapsed_ticks", lifecycle.roundElapsedTicks());
            round.addProperty("paused", lifecycle.isPaused());
            round.addProperty("round_started", lifecycle.hasRoundStarted());
            round.addProperty("next_round_requested", lifecycle.isNextRoundRequested());
            round.add("last_result", JsonNull.INSTANCE);
            lifecycle.lastResult().ifPresent(value -> {
                JsonObject settlement = new JsonObject();
                settlement.addProperty("winner", String.valueOf(value.winner()));
                settlement.addProperty("reason", String.valueOf(value.reason()));
                round.add("last_result", settlement);
            });
            result.add("lifecycle", round);
        }
        JsonObject pause = new JsonObject();
        for (String method : List.of("isPause", "isWaiting", "isWaitingWinner", "isKnifeSelectingVote"))
            pause.addProperty(method, (Boolean) invoke(map, method));
        for (String field : List.of("isWarmTime", "isOvertime", "isWaitingOverTimeVote"))
            pause.addProperty(field, (Boolean) readField(map.getClass(), map, field));
        pause.addProperty("currentPauseTime", (Number) readField(map.getClass(), map, "currentPauseTime"));
        VoteObj vote = (VoteObj) invoke(map, "getVote");
        if (vote == null) pause.add("vote", JsonNull.INSTANCE);
        else {
            JsonObject observed = new JsonObject();
            observed.addProperty("title", vote.getVoteTitle());
            observed.addProperty("status", vote.getStatus().name());
            observed.addProperty("remaining_seconds", vote.getRemainingTime());
            pause.add("vote", observed);
        }
        result.add("pause_and_waiting", pause);
        JsonArray teams = new JsonArray();
        for (ServerTeam team : map.getMapTeams().getTeamsWithSpectator()) {
            JsonObject observed = new JsonObject();
            observed.addProperty("name", team.getName());
            observed.addProperty("fixed_name", team.getFixedName());
            observed.addProperty("normal", team.isNormal());
            observed.addProperty("score", team.getScores());
            JsonArray roster = new JsonArray();
            for (var data : team.getPlayersData()) {
                JsonObject player = new JsonObject();
                player.addProperty("uuid", data.getOwner().toString());
                player.addProperty("living", data.isLiving());
                player.addProperty("online", data.isOnline());
                player.addProperty("living_on_server", data.isLivingOnServer());
                roster.add(player);
            }
            observed.add("roster", roster);
            teams.add(observed);
        }
        result.add("teams", teams);
        Class<?> bombType = loadClass(map, C4_CLASS);
        Class<?> itemType = loadClass(map, C4_ITEM_CLASS);
        var capability = map.getCapabilityMap().get(DemolitionModeCapability.class).orElseThrow();
        BlastBombEntity reference = (BlastBombEntity) readField(DemolitionModeCapability.class, capability, "c4");
        JsonObject demolition = new JsonObject();
        demolition.addProperty("blast_team", capability.getDemolitionTeam());
        demolition.addProperty("state", capability.blastState().name());
        demolition.addProperty("reference_null", reference == null);
        demolition.add("reference", reference == null ? JsonNull.INSTANCE : bomb(reference, map, bombType, reference));
        result.add("demolition", demolition);
        JsonArray bombs = new JsonArray();
        JsonArray drops = new JsonArray();
        JsonArray dimensions = new JsonArray();
        for (var level : server.getAllLevels()) {
            dimensions.add(level.dimension().location().toString());
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof BlastBombEntity blast) bombs.add(bomb(blast, map, bombType, reference));
                ItemStack stack = entity instanceof ItemEntity item ? item.getItem()
                        : entity instanceof MatchDropEntity drop ? drop.getItem() : ItemStack.EMPTY;
                if (!stack.isEmpty() && itemType.isInstance(stack.getItem())) {
                    JsonObject drop = entity(entity, map);
                    drop.addProperty("count", stack.getCount());
                    drop.addProperty("association", "unknown_no_runtime_owner_field");
                    if (entity instanceof MatchDropEntity match) drop.addProperty("drop_type", match.getDropType().name());
                    drops.add(drop);
                }
            }
        }
        result.addProperty("entity_scope", "all_server_levels_loaded_entities_no_distance_or_map_filter");
        result.add("enumerated_dimensions", dimensions);
        result.add("bomb_entities", bombs);
        result.add("c4_drop_entities", drops);
        JsonArray inventories = new JsonArray();
        for (var player : server.getPlayerList().getPlayers()) {
            JsonObject inventory = new JsonObject();
            inventory.addProperty("uuid", player.getUUID().toString());
            inventory.addProperty("target_participant", map.getMapTeams().getTeamByPlayer(player).isPresent());
            inventory.addProperty("health", player.getHealth());
            inventory.addProperty("game_mode", player.gameMode.getGameModeForPlayer().getName());
            inventory.addProperty("dimension", player.level().dimension().location().toString());
            inventory.addProperty("x", player.getX()); inventory.addProperty("y", player.getY()); inventory.addProperty("z", player.getZ());
            JsonArray slots = new JsonArray();
            int count = 0;
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (stack.isEmpty() || !itemType.isInstance(stack.getItem())) continue;
                JsonObject observed = new JsonObject();
                observed.addProperty("slot", slot); observed.addProperty("count", stack.getCount());
                slots.add(observed); count += stack.getCount();
            }
            inventory.addProperty("inventory_c4_count", count);
            inventory.add("c4_slots", slots);
            inventories.add(inventory);
        }
        result.add("player_c4_inventories", inventories);
        return result;
    }

    static BaseMap requireCsTarget(MinecraftServer server) {
        requireOwner(server);
        if (FPSMCore.getInstance().getServer() != server) throw new IllegalStateException("cs_server_identity_changed");
        BaseMap map = FPSMCore.getInstance().getMapByTypeWithName("cs", CS_NAME).orElseThrow();
        if (!CS_CLASS.equals(map.getClass().getName())) throw new IllegalStateException("concrete_cs_runtime_required");
        return map;
    }

    static RoundLifecycle<?, ?> csLifecycle(BaseMap map) {
        // No public accessor exists; read the declaring FPSMatch class, never invoke lifecycle methods that advance it.
        return (RoundLifecycle<?, ?>) readField(BaseRoundMap.class, map, "roundLifecycle");
    }

    static String identity(Object value) { return Integer.toHexString(System.identityHashCode(value)); }

    private static void requireOwner(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("server_owner_required");
    }

    private static Class<?> loadClass(BaseMap map, String name) {
        try { return Class.forName(name, false, map.getClass().getClassLoader()); }
        catch (ClassNotFoundException failure) { throw new IllegalStateException("cs_observation_type_missing: " + name, failure); }
    }

    private static Object readField(Class<?> owner, Object target, String name) {
        try {
            var field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cs_observation_field_unavailable: " + owner.getName() + "." + name, failure);
        }
    }

    private static Object invoke(Object target, String method) {
        // BO is deliberately not a compile-time dependency of this FPSMatch test source.
        try { return target.getClass().getMethod(method).invoke(target); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("cs_observation_method_unavailable: " + method, failure); }
    }

    private static JsonObject entity(Entity entity, BaseMap map) {
        JsonObject result = new JsonObject();
        result.addProperty("uuid", entity.getUUID().toString());
        result.addProperty("entity_id", entity.getId());
        result.addProperty("class", entity.getClass().getName());
        result.addProperty("dimension", entity.level().dimension().location().toString());
        result.addProperty("x", entity.getX()); result.addProperty("y", entity.getY()); result.addProperty("z", entity.getZ());
        result.addProperty("removed", entity.isRemoved());
        result.addProperty("intersects_target_area", entity.level() == map.getServerLevel()
                && entity.getBoundingBox().intersects(map.getMapArea().aabb()));
        return result;
    }

    private static JsonObject bomb(BlastBombEntity entity, BaseMap map, Class<?> bombType, BlastBombEntity reference) {
        JsonObject result = entity(entity, map);
        result.addProperty("is_demolition_reference", entity == reference);
        result.addProperty("state", entity.getState().name());
        result.addProperty("deleting", entity.isDeleting());
        var owner = entity.getOwner();
        var demolisher = entity.getDemolisher();
        result.addProperty("owner_uuid", owner == null ? null : owner.getUUID().toString());
        result.addProperty("demolisher_uuid", demolisher == null ? null : demolisher.getUUID().toString());
        if (bombType.isInstance(entity)) {
            Object associated = readField(bombType, entity, "map");
            result.addProperty("association", associated == map ? "target_runtime" : associated == null ? "unassigned" : "other_runtime");
            result.addProperty("associated_runtime_identity", associated == null ? null : identity(associated));
            if (associated instanceof BaseMap other) result.addProperty("associated_runtime_name", other.getMapName());
            result.addProperty("fuse", (Number) invoke(entity, "getFuse"));
            result.addProperty("delete_time", (Number) invoke(entity, "getDeleteTime"));
            result.addProperty("demolition_progress", (Number) invoke(entity, "getDemolitionProgress"));
        } else result.addProperty("association", entity == reference ? "target_reference_unknown_bomb_type" : "unknown_bomb_type");
        return result;
    }

    private static JsonArray bounds(AABB box) {
        JsonArray result = new JsonArray();
        for (double coordinate : new double[]{box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ}) result.add(coordinate);
        return result;
    }

    private static JsonObject item(ItemStack stack) {
        CompoundTag before = stack.save(new CompoundTag()).copy();
        CompoundTag tag = stack.getTag() == null ? null : stack.getTag().copy();
        JsonObject result = new JsonObject();
        result.addProperty("count", stack.getCount());
        result.addProperty("empty", stack.isEmpty());
        result.addProperty("saved_snbt", before.toString());
        JsonObject fields = new JsonObject();
        if (tag != null) for (String key : List.of("GunCurrentAmmoCount", "DummyAmmo", "MaxDummyAmmo", "Damage")) {
            JsonObject field = new JsonObject();
            field.addProperty("present", tag.contains(key));
            if (tag.contains(key)) {
                field.addProperty("tag_id", tag.get(key).getId());
                field.addProperty("value", tag.get(key).toString());
            }
            fields.add(key, field);
        }
        result.add("typed_fields", fields);
        var provider = GunCompatManager.findProvider(stack);
        result.addProperty("gun_provider", provider.getClass().getName());
        if (provider.isAvailable()) {
            result.addProperty("current_ammo", provider.getCurrentAmmo(stack));
            result.addProperty("dummy_ammo", provider.getDummyAmmo(stack));
            result.addProperty("max_dummy_ammo", provider.getMaxDummyAmmo(stack));
        }
        if (!before.equals(stack.save(new CompoundTag())) || !Objects.equals(tag, stack.getTag()))
            throw new AssertionError("snapshot_getters_changed_template");
        return result;
    }
}
