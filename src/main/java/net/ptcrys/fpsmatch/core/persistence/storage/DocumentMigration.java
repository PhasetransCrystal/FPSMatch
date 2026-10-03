package net.ptcrys.fpsmatch.core.persistence.storage;

import net.ptcrys.fpsmatch.core.persistence.DataPersistenceException;

import com.google.gson.JsonElement;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DocumentMigration {

    private DocumentMigration() {}

    public static int copyIntoEmpty(DocumentStore source, DocumentStore destination, List<String> namespaces) {
        List<String> selected = List.copyOf(namespaces);
        if (selected.stream().distinct().count() != selected.size()) throw new IllegalArgumentException("Duplicate migration namespace");
        Map<String, Map<String, JsonElement>> snapshot = source.transact(transaction -> {
            Map<String, Map<String, JsonElement>> result = new LinkedHashMap<>();
            selected.forEach(namespace -> result.put(namespace, transaction.list(namespace)));
            return result;
        });
        return destination.transact(transaction -> {
            for (String namespace : selected) {
                if (!transaction.list(namespace).isEmpty()) throw new DataPersistenceException("Migration destination is not empty: " + namespace);
            }
            int copied = 0;
            for (var namespace : snapshot.entrySet()) {
                for (var document : namespace.getValue().entrySet()) {
                    transaction.put(namespace.getKey(), document.getKey(), document.getValue());
                    copied++;
                }
            }
            return copied;
        });
    }
}
