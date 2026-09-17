package com.ptcrys.fpsmatch.core.persistence;

import com.mojang.serialization.Codec;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class SaveHolder<T> implements ISavePort<T> {

    private final Codec<T> codec;
    private final Consumer<T> readHandler;
    private final Consumer<FPSMDataManager> writeHandler;
    private final boolean isGlobal;
    private final BiFunction<T, T, T> mergeHandler;
    private final String fileType;

    private Class<T> clazz;

    private final int version;
    private final Supplier<T> initializer;

    public static class Builder<T> {

        private final Codec<T> codec;
        private Consumer<T> readHandler = data -> {};
        private Consumer<FPSMDataManager> writeHandler = manager -> {};
        private boolean isGlobal = false;
        private BiFunction<T, T, T> mergeHandler = (old, newData) -> newData;
        private String fileType = "json";
        private int version = 1;
        private Supplier<T> initializer;

        public Builder(Codec<T> codec) {
            this.codec = codec;
            initializer = () -> {
                throw new UnsupportedOperationException("Initializer not set for " + codec);
            };
        }

        public Builder<T> withVersion(int version) {
            this.version = version;
            return this;
        }

        public Builder<T> withInitializer(Supplier<T> initializer) {
            this.initializer = initializer;
            return this;
        }

        public Builder<T> withLoadHandler(Consumer<T> handler) {
            this.readHandler = handler;
            return this;
        }

        public Builder<T> withSaveHandler(Consumer<FPSMDataManager> handler) {
            this.writeHandler = handler;
            return this;
        }

        public Builder<T> isGlobal(boolean global) {
            this.isGlobal = global;
            return this;
        }

        public Builder<T> withMergeHandler(BiFunction<T, T, T> handler) {
            this.mergeHandler = handler;
            return this;
        }

        public Builder<T> withFileType(String type) {
            this.fileType = type;
            return this;
        }

        public SaveHolder<T> build() {
            return new SaveHolder<>(this);
        }
    }

    private SaveHolder(Builder<T> builder) {
        this.codec = builder.codec;
        this.readHandler = builder.readHandler;
        this.writeHandler = builder.writeHandler;
        this.isGlobal = builder.isGlobal;
        this.mergeHandler = builder.mergeHandler;
        this.fileType = builder.fileType;
        this.version = builder.version;
        this.initializer = builder.initializer;
    }

    public void setHolderClass(Class<T> clazz) {
        this.clazz = clazz;
    }

    public Class<?> getHolderClass() {
        return clazz == null ? SaveHolder.class : clazz;
    }

    @Override
    public int getVersion() {
        return version;
    }

    @Override
    public Supplier<T> getInitializer() {
        return initializer;
    }

    @Override
    public Codec<T> codec() {
        return codec;
    }

    @Override
    public Consumer<T> readHandler() {
        return readHandler;
    }

    public Consumer<FPSMDataManager> writeHandler() {
        return writeHandler;
    }

    @Override
    public boolean isGlobal() {
        return isGlobal;
    }

    @Override
    public String getFileType() {
        return fileType;
    }

    @Override
    public T mergeHandler(@Nullable T oldData, T newData) {
        return mergeHandler == null ? ISavePort.super.mergeHandler(oldData, newData) : mergeHandler.apply(oldData, newData);
    }
}
