package net.ptcrys.fpsmatch.core.persistence.storage;

import com.google.gson.JsonElement;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class ScopedDocumentStore implements DocumentStore {

    private final DocumentStore delegate;
    private final String scope;

    public ScopedDocumentStore(DocumentStore delegate, String scope) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
        this.scope = JsonDocumentTransaction.validate(scope);
    }

    @Override
    public <T> T transact(Function<Transaction, T> operation) {
        return delegate.transact(transaction -> operation.apply(new Transaction() {

            private String namespace(String namespace) {
                return JsonDocumentTransaction.validate(scope + "/" + namespace);
            }

            @Override
            public Optional<JsonElement> get(String namespace, String key) {
                return transaction.get(namespace(namespace), key);
            }

            @Override
            public Map<String, JsonElement> list(String namespace) {
                return transaction.list(namespace(namespace));
            }

            @Override
            public void put(String namespace, String key, JsonElement value) {
                transaction.put(namespace(namespace), key, value);
            }

            @Override
            public void delete(String namespace, String key) {
                transaction.delete(namespace(namespace), key);
            }
        }));
    }
}
