package net.ptcrys.fpsmatch.core.persistence.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class JsonDocumentTransaction implements DocumentStore.Transaction {

    private final JsonObject documents;
    private boolean dirty;

    JsonDocumentTransaction(JsonObject documents) {
        this.documents = documents;
    }

    static String validate(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        if (identifier.isBlank() || identifier.length() > 191 || identifier.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid document identifier");
        }
        return identifier;
    }

    @Override
    public Optional<JsonElement> get(String namespace, String key) {
        JsonObject collection = documents.getAsJsonObject(validate(namespace));
        validate(key);
        return collection == null ? Optional.empty() : Optional.ofNullable(collection.get(key)).map(JsonElement::deepCopy);
    }

    @Override
    public Map<String, JsonElement> list(String namespace) {
        Map<String, JsonElement> result = new LinkedHashMap<>();
        JsonObject collection = documents.getAsJsonObject(validate(namespace));
        if (collection != null) collection.entrySet().forEach(entry -> result.put(entry.getKey(), entry.getValue().deepCopy()));
        return Map.copyOf(result);
    }

    @Override
    public void put(String namespace, String key, JsonElement value) {
        validate(namespace);
        validate(key);
        Objects.requireNonNull(value, "value");
        JsonObject collection = documents.getAsJsonObject(namespace);
        if (collection == null) {
            collection = new JsonObject();
            documents.add(namespace, collection);
        }
        collection.add(key, value.deepCopy());
        dirty = true;
    }

    @Override
    public void delete(String namespace, String key) {
        validate(key);
        JsonObject collection = documents.getAsJsonObject(validate(namespace));
        if (collection != null && collection.remove(key) != null) dirty = true;
    }

    boolean dirty() {
        return dirty;
    }
}
