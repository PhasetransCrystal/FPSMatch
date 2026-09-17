package net.ptcrys.fpsmatch.core.persistence;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.core.persistence.datafixer.DataFixer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 用于定义可保存数据的接口。
 * <p>
 * 该接口提供了一种标准化的方式来序列化和反序列化数据，并支持从文件中读取和写入数据。
 * 它使用 {@link Codec} 来实现数据的编解码，并通过 Gson 进行 JSON 操作。
 * 实现该接口的类需要提供一个具体的 {@link Codec} 实例，用于定义数据的编解码逻辑。
 */
public interface ISavePort<T> {

    /** Gson 线程安全，跨读写复用，避免每次构建新实例。 */
    Gson GSON = new Gson();
    Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    Codec<T> codec();   // 获取数据的编解码器

    /**
     * 获取读取数据后的处理逻辑。
     * <p>
     * 默认实现为空操作，具体逻辑需要在实现类中覆盖该方法或通过 {@link SaveHolder} 提供。
     *
     * @apiNote 在数据类中实现该方法无效。需要在 {@link SaveHolder} 中提供处理数据逻辑。
     * @return 一个 Consumer，用于处理解码后的数据
     */
    default Consumer<T> readHandler() {
        return (data) -> {};
    }

    int getVersion();

    Class<?> getHolderClass();

    // 获取数据初始化逻辑
    default Supplier<T> getInitializer() {
        throw new UnsupportedOperationException("Default initializer not provided for " + getHolderClass().getSimpleName());
    }

    /**
     * 从 JSON 元素中解码数据。
     * <p>
     * 使用 {@link Codec} 将 JSON 元素解码为指定的数据类型。
     *
     * @param json JSON 元素
     * @return 解码后的数据
     */
    default T decodeFromJson(JsonElement json) {
        int oldVersion;
        JsonElement rawData;
        if (!json.isJsonObject()) {
            oldVersion = 0;
            rawData = json;
        } else {
            JsonObject wrapper = json.getAsJsonObject();
            oldVersion = wrapper.has("version") ? wrapper.get("version").getAsInt() : 0;
            rawData = wrapper.has("data") ? wrapper.get("data") : json;
        }

        if (oldVersion == getVersion()) return decode(rawData);

        return codec()
                .decode(JsonOps.INSTANCE, DataFixer.getInstance().fixJson(getHolderClass(), rawData, oldVersion, getVersion()))
                .getOrThrow(false, e -> {
                    throw new DataPersistenceException("Failed to decode fixed data", e);
                }).getFirst();
    }

    default T decode(JsonElement jsonElement) {
        return codec().decode(JsonOps.INSTANCE, jsonElement)
                .getOrThrow(false, e -> {
                    throw new DataPersistenceException("Failed to decode fixed data", e);
                })
                .getFirst();
    }

    /**
     * 将数据编码为 JSON 元素。
     * <p>
     * 使用 {@link Codec} 将数据编码为 JSON 元素。
     *
     * @param data 待编码的数据
     * @return 编码后的 JSON 元素
     */
    default JsonElement encodeToJson(T data) {
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("version", getVersion());
        wrapper.add("data", codec().encodeStart(JsonOps.INSTANCE, data).getOrThrow(false, throwable -> {
            throw new DataPersistenceException("Failed to encode data to JSON", throwable);
        }));
        return wrapper;
    }

    /**
     * 从指定文件中读取特定文件名的数据。
     *
     * @param directory 数据目录
     * @param fileName  文件名（不包含扩展名）
     * @return 读取到的数据，如果文件不存在则返回null
     */
    default T readSpecificFile(File directory, String fileName) {
        try {
            File file = new File(directory, PersistenceUtils.fixFileName(fileName) + "." + getFileType());
            if (!file.exists()) return getInitializer().get();

            // 读取并修复数据
            try (FileReader reader = new FileReader(file)) {
                return decodeFromJson(new Gson().fromJson(reader, JsonElement.class));
            }
        } catch (Exception e) {
            return getInitializer().get();
        }
    }

    /**
     * 创建新的数据文件并写入初始数据。
     *
     * @param directory   数据目录
     * @param fileName    文件名（不包含扩展名）
     * @param initialData 初始数据
     * @return 是否创建成功
     */
    default boolean createNewDataFile(File directory, String fileName, T initialData) {
        if (!directory.exists() && !directory.mkdirs()) {
            return false;
        }

        File file = new File(directory, fileName + "." + this.getFileType());
        if (file.exists()) {
            return false;
        }

        try {
            if (!file.createNewFile()) {
                return false;
            }

            Gson gson = PRETTY_GSON;
            String jsonStr = gson.toJson(this.encodeToJson(initialData));

            try (FileWriter writer = new FileWriter(file)) {
                writer.write(jsonStr);
                return true;
            }
        } catch (Exception e) {
            e.fillInStackTrace();
            return false;
        }
    }

    /**
     * 获取目录读取器。
     * <p>
     * <p>
     * 该方法返回一个 Consumer，用于遍历指定目录中的所有文件，并尝试从中读取数据。
     * <p>
     * 每个文件的内容将被解析为 JSON，然后通过 {@link #decodeFromJson(JsonElement)} 解码为数据对象。
     * <p>
     * 解码后的数据将通过 {@link #readHandler()} 进行处理。
     * <p>
     * 只读取json文件。
     * 
     * @return 目录读取器
     */
    default Consumer<File> getReader() {
        return (directory) -> {
            if (!directory.exists()) {
                if (!directory.mkdirs()) throw new RuntimeException("error : can't create " + directory.getName() + " data folder.");
                return;
            }
            if (directory.exists() && directory.isDirectory()) {
                for (File file : Objects.requireNonNull(directory.listFiles())) {
                    if (file.isFile() && file.getName().endsWith("." + this.getFileType())) {
                        try {
                            FileReader reader = new FileReader(file);
                            JsonElement element = GSON.fromJson(reader, JsonElement.class);
                            T data = this.decodeFromJson(element);
                            this.readHandler().accept(data);
                        } catch (Exception e) {
                            FPSMatch.LOGGER.error("error : {} data file is not a json file or doesn't exist.", file.getName(), e);
                        }
                    }
                }
            } else {
                FPSMatch.LOGGER.error("error : {} data folder is not a directory or doesn't exist.", directory.getName());
            }
        };
    }

    /**
     * 获取文件写入器。
     * <p>
     * 该方法返回一个 Consumer，用于将数据写入指定目录中的文件。
     * 如果目录不存在，则会尝试创建目录。
     * 如果文件不存在，则会尝试创建文件。
     * 数据将通过 {@link #encodeToJson(T)} 编码为 JSON，然后写入文件。
     * 如果文件已存在，则会尝试读取旧数据并与新数据合并，合并逻辑由 {@link #mergeHandler(T, T)} 定义。
     *
     * @param data     待写入的数据
     * @param fileName 文件名（不包含扩展名）
     * @return 文件写入器
     */
    default Consumer<File> getWriter(T data, String fileName, boolean overwrite) {
        return (directory) -> {
            if (!directory.exists()) {
                if (!directory.mkdirs()) throw new RuntimeException("error : can't create " + directory.getName() + " data folder.");
            }
            if (directory.isDirectory()) {
                File file = new File(directory, PersistenceUtils.fixFileName(fileName) + "." + this.getFileType());
                try {
                    if (!file.exists()) {
                        if (!file.createNewFile()) throw new RuntimeException("error : can't create " + fileName + " data file.");
                    }

                    T merged = data;
                    if (!overwrite && file.length() > 0) {
                        try (FileReader reader = new FileReader(file)) {
                            JsonElement element = GSON.fromJson(reader, JsonElement.class);
                            if (element != null) {
                                T old = this.decodeFromJson(element);
                                merged = this.mergeHandler(old, data);
                            }
                        }
                    }

                    Gson gson = PRETTY_GSON;
                    String jsonStr = gson.toJson(this.encodeToJson(merged));
                    try (FileWriter writer = new FileWriter(file)) {
                        writer.write(jsonStr);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                } catch (Exception e) {
                    FPSMatch.LOGGER.error("error : {} data folder is not a directory or doesn't exist.", directory.getName(), e);
                }
            } else {
                FPSMatch.LOGGER.error("error : {} data folder is not a directory or doesn't exist.", directory.getName());
            }
        };
    }

    /**
     * 检查数据是否为全局数据。
     * <p>
     * 默认返回 false，表示数据不是全局数据。
     * 全局数据通常用于存储跨多个实例共享的信息。
     *
     * @return 如果是全局数据，返回 true；否则返回 false
     */
    default boolean isGlobal() {
        return false;
    }

    /**
     * 合并旧数据和新数据的逻辑。
     * <p>
     * 默认实现直接返回新数据，子类可以通过覆盖该方法实现自定义的合并逻辑。
     *
     * @param oldData 旧数据
     * @param newData 新数据
     * @return 合并后的数据
     */
    default T mergeHandler(@Nullable T oldData, T newData) {
        return newData;
    }

    default String getFileType() {
        return "json";
    }

    default boolean createNewDataFile(File directory, String fileName) {
        return createNewDataFile(directory, fileName, getInitializer().get());
    }
}
