package net.ptcrys.fpsmatch.editor.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class StoreJsonTest {
    private static byte[] bytes(String input) {
        return input.getBytes(StandardCharsets.UTF_8);
    }

    private static String valid() throws Exception {
        return new String(StoreJson.manifest(new SavedProject(UUID.randomUUID(), null,
                LocalProjectStoreTest.empty("A")), 4096), StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{} {}", "{\"a\":1,}", "[1,]", "/*comment*/{}", "{a:1}",
            "{\"a\":01}", "{\"a\":NaN}", "{\"a\":true,\"a\":false}",
            "{\"a\":\"\\uD800\"}", "{\"a\":\"\\uDC00\"}", "\ufeff{}"})
    void malformedMetadataIsRejected(String input) {
        assertThrows(ProjectStorageException.class, () -> StoreJson.readManifest(bytes(input), StoreLimits.DEFAULT));
    }

    @Test void escapedDuplicateFieldIsRejected() throws Exception {
        String input = valid().replace("\"projectName\": \"A\"", "\"projectName\": \"A\", \"project\\u004eame\": \"B\"");
        LocalProjectStoreTest.code(INVALID_FORMAT, () -> StoreJson.readManifest(bytes(input), StoreLimits.DEFAULT));
    }

    @Test void unknownVersionAndUnknownPlatformFieldAreNotSilentlyDiscarded() throws Exception {
        String future = valid().replace("\"formatVersion\": 1", "\"formatVersion\": 2");
        LocalProjectStoreTest.code(UNSUPPORTED_FORMAT, () -> StoreJson.readManifest(bytes(future), StoreLimits.DEFAULT));
        String field = valid().replace("\"formatVersion\": 1", "\"formatVersion\": 1, \"future\": []");
        LocalProjectStoreTest.code(INVALID_FORMAT, () -> StoreJson.readManifest(bytes(field), StoreLimits.DEFAULT));
    }

    @Test void invalidUtf8IsNotReplacedOrRepaired() {
        byte[] input = {(byte) 0xc0, (byte) 0xaf};
        LocalProjectStoreTest.code(INVALID_FORMAT, () -> StoreJson.readManifest(input, StoreLimits.DEFAULT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "Null", "null\u000b", "null\u000c"})
    void nonJsonTokensCannotHideInsideAnOtherwiseValidManifest(String token) throws Exception {
        String input = valid().replace("\"origin\": null", "\"origin\": " + token);
        LocalProjectStoreTest.code(INVALID_FORMAT, () -> StoreJson.readManifest(bytes(input), StoreLimits.DEFAULT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r", "\t", "\u0000", "\u001f"})
    void unescapedControlCharactersInStringsAreRejected(String control) throws Exception {
        String input = valid().replace("\"projectName\": \"A\"", "\"projectName\": \"A" + control + "B\"");
        LocalProjectStoreTest.code(INVALID_FORMAT, () -> StoreJson.readManifest(bytes(input), StoreLimits.DEFAULT));
    }

    @Test void nonCanonicalUuidIsRejected() throws Exception {
        String input = valid().replaceFirst("\"commitId\": \"[^\"]+\"", "\"commitId\": \"1-1-1-1-1\"");
        LocalProjectStoreTest.code(INVALID_FORMAT, () -> StoreJson.readManifest(bytes(input), StoreLimits.DEFAULT));
    }

    @Test void tooLargeSchemaVersionIsStructuredFailure() throws Exception {
        UUID id = UUID.randomUUID();
        BlobRef blob = new BlobRef(BlobRef.Kind.DOCUMENT, "0".repeat(64), 0);
        ProjectState state = new ProjectState(UUID.randomUUID(), "A", Map.of("test:module", "1"),
                Map.of(id, LocalProjectStoreTest.entry(id, "test:map", blob, Set.of(), Set.of())), Map.of(), null);
        String input = new String(StoreJson.manifest(new SavedProject(UUID.randomUUID(), null, state), 8192),
                StandardCharsets.UTF_8).replace("\"schemaVersion\": 1", "\"schemaVersion\": 2147483648");
        LocalProjectStoreTest.code(INVALID_FORMAT, () -> StoreJson.readManifest(bytes(input), StoreLimits.DEFAULT));
    }

    @Test void deepJsonAndOversizedEncodedMetadataFailAtBudgets() throws Exception {
        String deep = "{\"x\":".repeat(20) + "null" + "}".repeat(20);
        LocalProjectStoreTest.code(LIMIT_EXCEEDED, () -> StoreJson.readManifest(bytes(deep), StoreLimits.DEFAULT));
        SavedProject project = new SavedProject(UUID.randomUUID(), null, LocalProjectStoreTest.empty("中".repeat(4096)));
        LocalProjectStoreTest.code(LIMIT_EXCEEDED, () -> StoreJson.manifest(project, 1024));
    }

    @Test void duplicateReceiptsAndWrongLatestReceiptAreRejected() throws Exception {
        UUID project = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        String hash = "1".repeat(64);
        var receipt = new StoreJson.Receipt(first, hash);
        byte[] duplicate = StoreJson.head(new StoreJson.Head(project, first, hash, java.util.List.of(receipt, receipt)));
        LocalProjectStoreTest.code(INVALID_FORMAT, () -> StoreJson.readHead(duplicate, StoreLimits.DEFAULT));
        byte[] different = StoreJson.head(new StoreJson.Head(project, UUID.randomUUID(), hash, java.util.List.of(receipt)));
        LocalProjectStoreTest.code(INVALID_FORMAT, () -> StoreJson.readHead(different, StoreLimits.DEFAULT));
    }
}
