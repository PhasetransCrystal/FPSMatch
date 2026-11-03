package net.ptcrys.fpsmatch.core.team;

import net.ptcrys.fpsmatch.common.capability.team.SpawnPointCapability;
import net.ptcrys.fpsmatch.core.capability.FPSMCapabilityManager;
import net.ptcrys.fpsmatch.core.capability.team.TeamCapability;
import net.ptcrys.fpsmatch.core.map.MapConstructionScope;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public record TeamData(String name, int limit, List<String> capabilities) {

    private static final Logger LOGGER = LoggerFactory.getLogger(TeamData.class);

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
        if (MapConstructionScope.isConstructing()) {
            HashSet<String> requested = new HashSet<>();
            for (String capability : capabilities) {
                if (!requested.add(capability)) {
                    throw new IllegalStateException("Duplicate required team capability: " + capability);
                }
            }
        }
        List<Class<? extends TeamCapability>> caps = new ArrayList<>();
        for (String cap : capabilities) {
            FPSMCapabilityManager.getRegisteredCapabilityClassByFormated(cap, TeamCapability.class).ifPresentOrElse(caps::add, () -> LOGGER.error("Could not find team capability class: {}", cap));
        }
        return caps;
    }
}
