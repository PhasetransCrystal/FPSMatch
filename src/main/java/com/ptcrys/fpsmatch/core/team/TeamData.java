package com.ptcrys.fpsmatch.core.team;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.capability.team.SpawnPointCapability;
import com.ptcrys.fpsmatch.core.capability.FPSMCapabilityManager;
import com.ptcrys.fpsmatch.core.capability.team.TeamCapability;

import java.util.ArrayList;
import java.util.List;

public record TeamData(String name, int limit, List<String> capabilities) {

    public static final Codec<TeamData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("name").forGetter(TeamData::name),
            Codec.INT.fieldOf("limit").forGetter(TeamData::limit),
            Codec.STRING.listOf().fieldOf("capabilities").forGetter(TeamData::capabilities)).apply(instance, instance.stable(TeamData::new)));

    public TeamData(String name, int limit) {
        this(name, limit, new ArrayList<>());
    }

    public static TeamData of(String name, int limit) {
        List<String> caps = new ArrayList<>();
        caps.add(SpawnPointCapability.class.getSimpleName());
        return new TeamData(name, limit, caps);
    }

    public static TeamData of(String name, int limit, List<Class<? extends TeamCapability>> capabilities) {
        List<String> caps = new ArrayList<>();
        for (Class<? extends TeamCapability> cap : capabilities) {
            caps.add(cap.getSimpleName());
        }
        return new TeamData(name, limit, caps);
    }

    public static TeamData of(ServerTeam team) {
        return new TeamData(team.name, team.getPlayerLimit(), team.getCapabilityMap().synchronizableCapabilitiesString());
    }

    public static TeamData of(ClientTeam team) {
        return new TeamData(team.name, -1, team.getCapabilityMap().capabilitiesString());
    }

    public List<Class<? extends TeamCapability>> getCapabilities() {
        List<Class<? extends TeamCapability>> caps = new ArrayList<>();
        for (String cap : capabilities) {
            FPSMCapabilityManager.getRegisteredCapabilityClassByFormated(cap, TeamCapability.class).ifPresentOrElse(caps::add, () -> FPSMatch.LOGGER.error("Could not find team capability class: {}", cap));
        }
        return caps;
    }
}
