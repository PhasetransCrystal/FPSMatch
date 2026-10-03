package net.ptcrys.fpsmatch.core.persistence.storage;

import net.ptcrys.fpsmatch.core.persistence.DataPersistenceException;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class JdbcDocumentStore implements DocumentStore {

    @FunctionalInterface
    public interface Connections {

        Connection open() throws SQLException;
    }

    private final Connections connections;
    private boolean binaryKeys;

    public JdbcDocumentStore(Connections connections) {
        this.connections = connections;
        initialize();
    }

    private void initialize() {
        try (Connection connection = connections.open(); var statement = connection.createStatement()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT);
            if (!connection.getMetaData().supportsTransactions()) throw new SQLException("Transactional storage is required");
            binaryKeys = product.contains("mysql") || product.contains("mariadb");
            String payloadType = binaryKeys ? "LONGTEXT CHARACTER SET utf8mb4" : "TEXT";
            String keyType = binaryKeys ? "VARBINARY(764)" : "VARCHAR(191)";
            String engine = binaryKeys ? " ENGINE=InnoDB" : "";
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS fpsm_documents (namespace " + keyType + " NOT NULL, document_key " + keyType + " NOT NULL, payload " + payloadType + " NOT NULL, PRIMARY KEY (namespace, document_key))" + engine);
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS fpsm_document_lock (id INTEGER PRIMARY KEY, revision BIGINT NOT NULL)" + engine);
            if (binaryKeys) verifyMySqlTables(connection);
            try (var query = statement.executeQuery("SELECT id FROM fpsm_document_lock WHERE id = 1")) {
                if (query.next()) return;
            }
            try {
                statement.executeUpdate("INSERT INTO fpsm_document_lock (id, revision) VALUES (1, 0)");
            } catch (SQLException collision) {
                try (var query = statement.executeQuery("SELECT id FROM fpsm_document_lock WHERE id = 1")) {
                    if (!query.next()) throw collision;
                }
            }
        } catch (SQLException failure) {
            throw new DataPersistenceException("Cannot initialize JDBC document storage", failure);
        }
    }

    private void verifyMySqlTables(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT TABLE_NAME, ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('fpsm_documents', 'fpsm_document_lock')")) {
            int count = 0;
            while (result.next()) {
                if (!"InnoDB".equalsIgnoreCase(result.getString(2))) throw new SQLException("FPSMatch storage requires InnoDB tables");
                count++;
            }
            if (count != 2) throw new SQLException("Cannot verify FPSMatch storage engines");
        }
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fpsm_documents' AND COLUMN_NAME IN ('namespace', 'document_key')")) {
            int count = 0;
            while (result.next()) {
                if (!"varbinary".equalsIgnoreCase(result.getString(2)) || result.getLong(3) < 764) {
                    throw new SQLException("FPSMatch storage requires binary document keys; migrate the existing table before startup");
                }
                count++;
            }
            if (count != 2) throw new SQLException("Cannot verify FPSMatch document keys");
        }
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT CHARACTER_SET_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fpsm_documents' AND COLUMN_NAME = 'payload'")) {
            if (!result.next() || !"utf8mb4".equalsIgnoreCase(result.getString(1))) {
                throw new SQLException("FPSMatch storage requires utf8mb4 payloads; migrate the existing table before startup");
            }
        }
    }

    @Override
    public <T> T transact(Function<Transaction, T> operation) {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                try (var lock = connection.prepareStatement("UPDATE fpsm_document_lock SET revision = revision + 1 WHERE id = 1")) {
                    if (lock.executeUpdate() != 1) throw new SQLException("Missing transaction lock");
                }
                T result = operation.apply(new JdbcTransaction(connection, binaryKeys));
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new DataPersistenceException("JDBC document transaction failed", failure);
        }
    }

    private record JdbcTransaction(Connection connection, boolean binaryKeys) implements Transaction {

        private void bind(java.sql.PreparedStatement statement, int position, String key) throws SQLException {
            if (binaryKeys) statement.setBytes(position, key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            else statement.setString(position, key);
        }

        @Override
        public Optional<JsonElement> get(String namespace, String key) {
            JsonDocumentTransaction.validate(namespace);
            JsonDocumentTransaction.validate(key);
            try (var statement = connection.prepareStatement("SELECT payload FROM fpsm_documents WHERE namespace = ? AND document_key = ?")) {
                bind(statement, 1, namespace);
                bind(statement, 2, key);
                try (var result = statement.executeQuery()) {
                    return result.next() ? Optional.of(JsonParser.parseString(result.getString(1))) : Optional.empty();
                }
            } catch (SQLException failure) {
                throw new DataPersistenceException("Cannot read document", failure);
            }
        }

        @Override
        public Map<String, JsonElement> list(String namespace) {
            JsonDocumentTransaction.validate(namespace);
            try (var statement = connection.prepareStatement("SELECT document_key, payload FROM fpsm_documents WHERE namespace = ?")) {
                bind(statement, 1, namespace);
                Map<String, JsonElement> documents = new LinkedHashMap<>();
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        String key = binaryKeys ? new String(result.getBytes(1), java.nio.charset.StandardCharsets.UTF_8) : result.getString(1);
                        documents.put(key, JsonParser.parseString(result.getString(2)));
                    }
                }
                return Map.copyOf(documents);
            } catch (SQLException failure) {
                throw new DataPersistenceException("Cannot list documents", failure);
            }
        }

        @Override
        public void put(String namespace, String key, JsonElement value) {
            JsonDocumentTransaction.validate(namespace);
            JsonDocumentTransaction.validate(key);
            java.util.Objects.requireNonNull(value, "value");
            delete(namespace, key);
            try (var statement = connection.prepareStatement("INSERT INTO fpsm_documents (namespace, document_key, payload) VALUES (?, ?, ?)")) {
                bind(statement, 1, namespace);
                bind(statement, 2, key);
                statement.setString(3, value.toString());
                statement.executeUpdate();
            } catch (SQLException failure) {
                throw new DataPersistenceException("Cannot write document", failure);
            }
        }

        @Override
        public void delete(String namespace, String key) {
            JsonDocumentTransaction.validate(namespace);
            JsonDocumentTransaction.validate(key);
            try (var statement = connection.prepareStatement("DELETE FROM fpsm_documents WHERE namespace = ? AND document_key = ?")) {
                bind(statement, 1, namespace);
                bind(statement, 2, key);
                statement.executeUpdate();
            } catch (SQLException failure) {
                throw new DataPersistenceException("Cannot delete document", failure);
            }
        }
    }
}
