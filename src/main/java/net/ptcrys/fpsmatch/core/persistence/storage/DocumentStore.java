package net.ptcrys.fpsmatch.core.persistence.storage;

import com.google.gson.JsonElement;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public interface DocumentStore extends AutoCloseable {

    interface Transaction {

        Optional<JsonElement> get(String namespace, String key);

        Map<String, JsonElement> list(String namespace);

        void put(String namespace, String key, JsonElement value);

        void delete(String namespace, String key);
    }

    <T> T transact(Function<Transaction, T> operation);

    @Override
    default void close() {}
}
