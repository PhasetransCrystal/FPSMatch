package net.ptcrys.fpsmatch.core.persistence;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class SaveHolder<T> implements ISavePort<T> {

    private final Codec<T> codec;
    private final Consumer<T> readHandler;
    private final Consumer<FPSMDataManager> writeHandler;
    private final boolean isGlobal;
    private final BiFunction<T, T, T> mergeHandler;
    private final Codec<?> validationCodec;
    private final String fileType;

    private Class<T> clazz;

    private final int version;
    private final int loadPriority;
    private final Supplier<T> initializer;

    public static class Builder<T> {

        private final Codec<T> codec;
        private Consumer<T> readHandler = data -> {};
        private Consumer<FPSMDataManager> writeHandler = manager -> {};
        private boolean isGlobal = false;
        private BiFunction<T, T, T> mergeHandler;
        private Codec<?> validationCodec;
        private String fileType = "json";
        private int version = 1;
        private int loadPriority;
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

        public Builder<T> withLoadPriority(int priority) {
            this.loadPriority = priority;
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

        /**
         * Replaces existing data after validating its migrated payload with a pure codec.
         * The validator must not construct runtime holders or change host state. Explicit
         * reads still use the primary codec. Cannot be combined with a non-null merge handler.
         */
        public Builder<T> withValidatedReplacement(Codec<?> validationCodec) {
            this.validationCodec = Objects.requireNonNull(validationCodec, "validationCodec");
            return this;
        }

        public Builder<T> withFileType(String type) {
            this.fileType = type;
            return this;
        }

        public SaveHolder<T> build() {
            if (validationCodec != null && mergeHandler != null) {
                throw new IllegalStateException("Validated replacement cannot be combined with a custom merge handler");
            }
            return new SaveHolder<>(this);
        }
    }

    private SaveHolder(Builder<T> builder) {
        this.codec = builder.codec;
        this.readHandler = builder.readHandler;
        this.writeHandler = builder.writeHandler;
        this.isGlobal = builder.isGlobal;
        this.mergeHandler = builder.mergeHandler;
        this.validationCodec = builder.validationCodec;
        this.fileType = builder.fileType;
        this.version = builder.version;
        this.loadPriority = builder.loadPriority;
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

    public int getLoadPriority() {
        return loadPriority;
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

    @Override
    public T mergeForSave(JsonElement existing, T current) {
        if (validationCodec == null) return ISavePort.super.mergeForSave(existing, current);
        SaveDataEnvelope envelope = SaveDataEnvelope.fromJson(existing);
        validationCodec.decode(JsonOps.INSTANCE, envelope.migrateTo(getHolderClass(), getVersion()))
                .getOrThrow(false, error -> {
                    throw new DataPersistenceException("Failed to decode fixed data", error);
                });
        return current;
    }

    /** Preserves the legacy database Optional chain's null-decoded-value short circuit. */
    T mergeForDatabaseSave(JsonElement existing, T current) {
        if (validationCodec != null) return mergeForSave(existing, current);
        T old = decodeFromJson(existing);
        return old == null ? current : mergeHandler(old, current);
    }
}
