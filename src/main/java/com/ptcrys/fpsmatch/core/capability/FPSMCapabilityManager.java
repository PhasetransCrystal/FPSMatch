package com.ptcrys.fpsmatch.core.capability;

import net.minecraft.network.FriendlyByteBuf;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.core.capability.map.MapCapability;
import com.ptcrys.fpsmatch.core.capability.team.TeamCapability;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 能力管理器，支持多种持有者类型的能力注册与实例化
 */
@SuppressWarnings("unchecked")
public class FPSMCapabilityManager {

    public enum CapabilityType {
        TEAM,
        MAP
    }

    // 存储能力类型与对应工厂的映射（键：能力Class，值：工厂实例）
    private static final Map<CapabilityType, Map<Class<? extends FPSMCapability<?>>, FPSMCapability.Factory<?, ?>>> CAPABILITY_FACTORIES = new ConcurrentHashMap<>();

    private static final Map<CapabilityType, List<Class<? extends FPSMCapability<?>>>> ORIGINAL_CAPABILITIES = new ConcurrentHashMap<>();

    /**
     * 注册能力工厂
     * 
     * @param capabilityClass 能力类型
     * @param factory         能力工厂
     * @param <H>             持有者类型
     * @param <T>             能力类型
     * @throws IllegalArgumentException 若能力已注册则抛出异常
     */
    public static <H, T extends FPSMCapability<H>> void register(CapabilityType type, Class<T> capabilityClass, FPSMCapability.Factory<H, T> factory) {
        if (isRegistered(capabilityClass)) {
            throw new IllegalArgumentException("Capability " + capabilityClass.getSimpleName() + " already registered!");
        }
        CAPABILITY_FACTORIES.computeIfAbsent(type, k -> new HashMap<>()).put(capabilityClass, factory);
        if (factory.isOriginal()) {
            ORIGINAL_CAPABILITIES.computeIfAbsent(type, k -> new ArrayList<>()).add(capabilityClass);
        }
    }

    public static List<Class<? extends TeamCapability>> getOriginalTeamCapabilities() {
        return ORIGINAL_CAPABILITIES.getOrDefault(CapabilityType.TEAM, Collections.emptyList()).stream()
                .map(cap -> (Class<? extends TeamCapability>) cap)
                .collect(Collectors.toList());
    }

    public static List<Class<? extends MapCapability>> getOriginalMapCapabilities() {
        return ORIGINAL_CAPABILITIES.getOrDefault(CapabilityType.MAP, Collections.emptyList()).stream()
                .map(cap -> (Class<? extends MapCapability>) cap)
                .collect(Collectors.toList());
    }

    /**
     * 检查能力是否已注册
     */
    public static <T extends FPSMCapability<?>> boolean isRegistered(Class<T> capabilityClass) {
        return CAPABILITY_FACTORIES.values().stream().anyMatch(map -> map.containsKey(capabilityClass));
    }

    /**
     * 获取能力工厂
     */
    @SuppressWarnings("unchecked")
    public static <H, T extends FPSMCapability<H>> Optional<FPSMCapability.Factory<H, T>> getFactory(Class<T> capabilityClass) {
        return Optional.ofNullable((FPSMCapability.Factory<H, T>) CAPABILITY_FACTORIES.values().stream()
                .filter(map -> map.containsKey(capabilityClass))
                .findFirst()
                .map(map -> map.get(capabilityClass))
                .orElse(null));
    }

    /**
     * 获取能力工厂（按类型分组）
     */
    @SuppressWarnings("unchecked")
    public static <H, T extends FPSMCapability<H>> Optional<FPSMCapability.Factory<H, T>> getFactory(CapabilityType type, Class<T> capabilityClass) {
        return Optional.ofNullable((FPSMCapability.Factory<H, T>) CAPABILITY_FACTORIES.get(type).get(capabilityClass));
    }

    @SuppressWarnings("unchecked")
    public static <T extends FPSMCapability<?>> Optional<FPSMCapability.Factory<?, T>> getRawFactory(Class<T> capabilityClass) {
        return Optional.ofNullable((FPSMCapability.Factory<?, T>) CAPABILITY_FACTORIES.values().stream()
                .filter(map -> map.containsKey(capabilityClass))
                .findFirst()
                .map(map -> map.get(capabilityClass))
                .orElse(null));
    }

    /**
     * 为持有者创建能力实例
     * 
     * @param holder          能力持有者
     * @param capabilityClass 能力类型
     * @return 能力实例（若工厂存在则返回）
     */
    public static <H, T extends FPSMCapability<H>> Optional<T> createInstance(H holder, Class<T> capabilityClass) {
        return getFactory(capabilityClass).map(factory -> factory.create(holder));
    }

    /**
     * 获取所有已注册的能力类型
     */
    public static List<Class<? extends FPSMCapability<?>>> getRegisteredCapabilities() {
        return CAPABILITY_FACTORIES.values().stream()
                .flatMap(map -> map.keySet().stream())
                .collect(Collectors.toList());
    }

    /**
     * 根据条件过滤已注册的能力类型
     */
    public static List<Class<? extends FPSMCapability<?>>> getRegisteredCapabilities(CapabilityType type) {
        return new ArrayList<>(CAPABILITY_FACTORIES.getOrDefault(type, new HashMap<>()).keySet());
    }

    /**
     * 根据类名获取已注册的能力类型
     */
    public static Optional<Class<? extends FPSMCapability<?>>> getCapabilityClassByName(String className) {
        return CAPABILITY_FACTORIES.values().stream()
                .flatMap(map -> map.keySet().stream())
                .filter(clazz -> clazz.getSimpleName().equals(className))
                .findFirst();
    }

    public static <T extends FPSMCapability<?>> Optional<Class<T>> getRegisteredCapabilityClassByFormated(String className, Class<T> format) {
        Optional<Class<? extends FPSMCapability<?>>> cap = getCapabilityClassByName(className);
        if (cap.isPresent() && format.isAssignableFrom(cap.get())) {
            return Optional.of((Class<T>) cap.get());
        } else {
            return Optional.empty();
        }
    }

    /**
     * 从网络缓冲区创建同步能力实例
     * 
     * @param holder    能力持有者
     * @param className 能力类名
     * @param buf       网络缓冲区
     * @return 同步能力实例（若类型匹配且工厂存在）
     */
    @SuppressWarnings("unchecked")
    public static <H> Optional<FPSMCapability<H>> createFromNetwork(H holder, String className, FriendlyByteBuf buf) {
        return getCapabilityClassByName(className).flatMap(capabilityClass -> {
            // 获取工厂（使用Z础能力类型）
            Optional<FPSMCapability.Factory<H, FPSMCapability<H>>> factory = getFactory((Class<FPSMCapability<H>>) capabilityClass);

            return factory.flatMap(f -> {
                try {
                    // 先创建基础实例，再检查是否为同步类型
                    FPSMCapability<H> instance = f.create(holder);
                    if (instance instanceof FPSMCapability.CapabilitySynchronizable syncedInstance) {
                        syncedInstance.readFromBuf(buf);
                        return Optional.of(instance);
                    }
                    return Optional.empty();
                } catch (Exception e) {
                    FPSMatch.debug("Failed to create sync capability from network: " + className + ", error: " + e.getMessage());
                    return Optional.empty();
                }
            });
        });
    }
}
