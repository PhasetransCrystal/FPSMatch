package com.ptcrys.fpsmatch.common.command;

import net.minecraft.commands.CommandSourceStack;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.ptcrys.fpsmatch.common.capability.team.ShopCapability;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.capability.FPSMCapabilityManager;
import com.ptcrys.fpsmatch.core.capability.map.MapCapability;
import com.ptcrys.fpsmatch.core.capability.team.TeamCapability;
import com.ptcrys.fpsmatch.core.data.Setting;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import com.ptcrys.fpsmatch.core.shop.FPSMShop;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

public class FPSMCommandSuggests {

    public static final String MAP_NAME_ARG = "map_name";
    public static final String GAME_TYPE_ARG = "game_type";
    public static final String CAPABILITY_ARG = "capability";
    public static final String SHOP_TYPE_ARG = "shop_type";
    public static final String SHOP_SLOT_ARG = "shop_slot";
    public static final String TEAM_NAME_ARG = "team_name";
    public static final String ACTION_ARG = "action";
    public static final String TARGETS_ARG = "targets";
    public static final String SETTING_ARG = "setting";

    public static final FPSMSuggestionProvider GAME_TYPES_SUGGESTION = new FPSMSuggestionProvider((c, b) -> FPSMCommandSuggests.getSuggestions(b, FPSMCore.getInstance().getGameTypes()));
    public static final FPSMSuggestionProvider MAP_NAMES_WITH_GAME_TYPE_SUGGESTION = new FPSMSuggestionProvider((c, b) -> FPSMCommandSuggests.getSuggestions(b, FPSMCore.getInstance().getMapNames(StringArgumentType.getString(c, GAME_TYPE_ARG))));

    public static final FPSMSuggestionProvider TEAM_NAMES_SUGGESTION = new FPSMSuggestionProvider((c, b) -> {
        Optional<BaseMap> map = FPSMCommand.getMap(c);
        Suggestions suggestions = FPSMCommandSuggests.getSuggestions(b, new ArrayList<>());
        if (map.isPresent()) {
            suggestions = FPSMCommandSuggests.getSuggestions(b, map.get().getMapTeams().getNormalTeamsName());
        }
        return suggestions;
    });

    public static final FPSMSuggestionProvider MAP_DEBUG_SUGGESTION = new FPSMSuggestionProvider((c, b) -> FPSMCommandSuggests.getSuggestions(b, List.of("start", "reset", "new_round", "cleanup", "switch")));
    public static final FPSMSuggestionProvider TEAM_ACTION_SUGGESTION = new FPSMSuggestionProvider((c, b) -> FPSMCommandSuggests.getSuggestions(b, List.of("join", "leave")));

    public static final FPSMSuggestionProvider TEAM_CAPABILITIES_ARGS = new FPSMSuggestionProvider((c, b) -> {
        String teamName = StringArgumentType.getString(c, TEAM_NAME_ARG);
        Optional<BaseMap> map = FPSMCommand.getMap(c);
        List<String> capabilities = new ArrayList<>();
        map.flatMap(baseMap -> baseMap.getMapTeams().getTeamByName(teamName)).ifPresent(team -> {
            team.getCapabilityMap().values().forEach(
                    cap -> {
                        FPSMCapabilityManager.getRawFactory(cap.getClass()).ifPresent(
                                factory -> {
                                    TeamCapability.Factory.Command command = factory.command();
                                    if (factory.command() != null) {
                                        capabilities.add(command.getName());
                                    }
                                });
                    });
        });

        return FPSMCommandSuggests.getSuggestions(b, capabilities);
    });

    public static final SuggestionProvider<CommandSourceStack> MAP_SETTINGS_SUGGESTION_PROVIDER = (context, builder) -> FPSMCommand.getMap(context)
            .map(map -> {
                map.settings().stream()
                        .map(Setting::getConfigName)
                        .forEach(builder::suggest);
                return builder.buildFuture();
            })
            .orElse(Suggestions.empty());

    public static final FPSMSuggestionProvider SHOP_ITEM_TYPES_SUGGESTION = new FPSMSuggestionProvider((c, b) -> {
        List<String> typeNames = new ArrayList<>();
        FPSMCommand.getTeamCapability(c, ShopCapability.class).ifPresent(
                cap -> {
                    if (cap.isInitialized()) {
                        for (Enum<?> t : cap.getShop().getEnums()) {
                            typeNames.add(t.name().toLowerCase());
                        }
                    }
                });
        return FPSMCommandSuggests.getSuggestions(b, typeNames);
    });

    public static final FPSMSuggestionProvider SHOP_TYPE_SUGGESTION = new FPSMSuggestionProvider((c, b) -> FPSMCommandSuggests.getSuggestions(b, FPSMShop.getRegisteredShopTypes()));
    public static final FPSMSuggestionProvider SHOP_SET_SLOT_ACTION_SUGGESTION = new FPSMSuggestionProvider((c, b) -> FPSMCommandSuggests.getSuggestions(b, List.of("1", "2", "3", "4", "5")));
    public static final FPSMSuggestionProvider SHOP_SLOT_ADD_LISTENER_MODULES_SUGGESTION = new FPSMSuggestionProvider((c, b) -> {
        String shopType = StringArgumentType.getString(c, SHOP_TYPE_ARG).toUpperCase(Locale.ROOT);
        int slotNum = IntegerArgumentType.getInteger(c, SHOP_SLOT_ARG) - 1;
        return FPSMCommand.getTeamCapability(c, ShopCapability.class).map(
                cap -> {
                    if (cap.isInitialized()) {
                        List<String> stringList = FPSMCore.getInstance().getListenerModuleManager().getListenerModules();
                        stringList.removeAll(cap.getShop().getDefaultShopSlotListByType(shopType).get(slotNum).getListenerNames());
                        return FPSMCommandSuggests.getSuggestions(b, stringList);
                    }
                    return FPSMCommandSuggests.getSuggestions(b, List.of());
                }).orElse(FPSMCommandSuggests.getSuggestions(b, List.of()));
    });

    public static final FPSMSuggestionProvider SHOP_SLOT_REMOVE_LISTENER_MODULES_SUGGESTION = new FPSMSuggestionProvider((c, b) -> {
        String shopType = StringArgumentType.getString(c, SHOP_TYPE_ARG).toUpperCase(Locale.ROOT);
        int slotNum = IntegerArgumentType.getInteger(c, SHOP_SLOT_ARG) - 1;
        return FPSMCommand.getTeamCapability(c, ShopCapability.class).map(
                cap -> {
                    if (cap.isInitialized()) {
                        List<String> stringList = cap.getShop().getDefaultShopSlotListByType(shopType).get(slotNum).getListenerNames();
                        return getSuggestions(b, stringList);
                    }
                    return FPSMCommandSuggests.getSuggestions(b, List.of());
                }).orElse(FPSMCommandSuggests.getSuggestions(b, List.of()));
    });
    public static final FPSMSuggestionProvider MAP_CAPABILITIES_SUGGESTION = new FPSMSuggestionProvider((c, b) -> FPSMCommand.getMapCapabilities(c)
            .map(capMap -> FPSMCommandSuggests.getSuggestions(b, capMap.capabilitiesString()))
            .orElse(FPSMCommandSuggests.getSuggestions(b, List.of())));

    public static final FPSMSuggestionProvider MAP_ADD_CAPABILITIES_SUGGESTION = new FPSMSuggestionProvider((c, b) -> FPSMCommand.getMapCapabilities(c)
            .map(capMap -> FPSMCommandSuggests.getSuggestions(b, FPSMCapabilityManager.getRegisteredCapabilities(FPSMCapabilityManager.CapabilityType.MAP)
                    .stream()
                    .filter(s -> !capMap.contains((Class<? extends MapCapability>) s))
                    .map(Class::getSimpleName).toList()))
            .orElse(FPSMCommandSuggests.getSuggestions(b, List.of())));

    public static final FPSMSuggestionProvider TEAM_CAPABILITIES_SUGGESTION = new FPSMSuggestionProvider((c, b) -> FPSMCommand.getTeamCapabilities(c)
            .map(capMap -> FPSMCommandSuggests.getSuggestions(b, capMap.capabilitiesString()))
            .orElse(FPSMCommandSuggests.getSuggestions(b, List.of())));

    public static final FPSMSuggestionProvider TEAM_ADD_CAPABILITIES_SUGGESTION = new FPSMSuggestionProvider((c, b) -> FPSMCommand.getTeamCapabilities(c)
            .map(capMap -> FPSMCommandSuggests.getSuggestions(b, FPSMCapabilityManager.getRegisteredCapabilities(FPSMCapabilityManager.CapabilityType.TEAM)
                    .stream()
                    .filter(s -> !capMap.contains((Class<? extends TeamCapability>) s))
                    .map(Class::getSimpleName).toList()))
            .orElse(FPSMCommandSuggests.getSuggestions(b, List.of())));

    public record FPSMSuggestionProvider(BiFunction<CommandContext<CommandSourceStack>, SuggestionsBuilder, Suggestions> suggestions) implements SuggestionProvider<CommandSourceStack> {

        @Override
        public CompletableFuture<Suggestions> getSuggestions(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
            return CompletableFuture.supplyAsync(() -> this.suggestions.apply(context, builder));
        }
    }

    @NotNull
    public static Suggestions getSuggestions(SuggestionsBuilder builder, Collection<String> suggests) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);

        for (String suggest : suggests) {
            if (suggest.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(suggest);
            }
        }

        return builder.build();
    }
}
