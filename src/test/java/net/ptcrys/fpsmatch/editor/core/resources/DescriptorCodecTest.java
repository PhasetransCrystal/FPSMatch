package net.ptcrys.fpsmatch.editor.core.resources;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.ptcrys.fpsmatch.editor.api.EditorId;

/** The descriptor codec is deterministic, binary and strict about malformed payloads. */
class DescriptorCodecTest {
    private static final UUID RESOURCE = new UUID(0x5054430000000000L, 1);
    private static final UUID VERSION = new UUID(0x5054430000000000L, 2);
    private static final UUID OTHER_VERSION = new UUID(0x5054430000000000L, 3);
    private static final UUID SOURCE_ENTRY = new UUID(0x5054430000000000L, 4);
    private static final UUID OUTPUT_ENTRY = new UUID(0x5054430000000000L, 5);
    private static final int MAGIC_RESOURCE = 0x45525231;
    private static final int MAGIC_VERSION = 0x45525631;

    private static final EditorId MODEL_TYPE = EditorId.parse("editor:model");
    private static final EditorId MODEL_IMPORTER = EditorId.parse("editor:model-gltf");

    @Test
    void resourceDescriptorRoundTrips() {
        LogicalResource resource = new LogicalResource(RESOURCE, MODEL_TYPE, "lamp.gltf", VERSION);
        byte[] payload = DescriptorCodec.encode(resource);
        LogicalResource decoded = DescriptorCodec.decodeResource(payload);
        assertEquals(resource, decoded);
    }

    @Test
    void versionDescriptorRoundTripsWithAllFields() {
        ResourceVersion version = new ResourceVersion(VERSION, RESOURCE, MODEL_IMPORTER, "1.1.0",
                Map.of("lod", "2", "alpha", "cutout"), SOURCE_ENTRY,
                Set.of(OTHER_VERSION), Set.of(OUTPUT_ENTRY));
        byte[] payload = DescriptorCodec.encode(version);
        ResourceVersion decoded = DescriptorCodec.decodeVersion(payload);
        assertEquals(version, decoded);
        assertEquals(SOURCE_ENTRY, decoded.sourceEntry());
    }

    @Test
    void versionDescriptorWithNoSourceStillRoundTrips() {
        ResourceVersion version = new ResourceVersion(VERSION, RESOURCE, MODEL_IMPORTER, "1.0.0",
                Map.of(), null, Set.of(), Set.of());
        ResourceVersion decoded = DescriptorCodec.decodeVersion(DescriptorCodec.encode(version));
        assertNull(decoded.sourceEntry());
        assertEquals(version, decoded);
    }

    @Test
    void encodingIsDeterministicAcrossInsertionOrders() {
        byte[] first = DescriptorCodec.encode(new ResourceVersion(VERSION, RESOURCE, MODEL_IMPORTER,
                "1.0.0", new java.util.LinkedHashMap<>(Map.of("a", "1", "b", "2")), null,
                new java.util.LinkedHashSet<>(java.util.List.of(OUTPUT_ENTRY, OTHER_VERSION)),
                Set.of(SOURCE_ENTRY)));
        byte[] second = DescriptorCodec.encode(new ResourceVersion(VERSION, RESOURCE, MODEL_IMPORTER,
                "1.0.0", Map.of("b", "2", "a", "1"), null,
                Set.of(OTHER_VERSION, OUTPUT_ENTRY), Set.of(SOURCE_ENTRY)));
        assertArrayEquals(first, second);
        assertEquals(DescriptorCodec.decodeVersion(first), DescriptorCodec.decodeVersion(second));
    }

    @Test
    void foreignMagicIsRejected() throws IOException {
        byte[] payload = new Payload().int32(MAGIC_RESOURCE).uuid(RESOURCE)
                .text(MODEL_TYPE.toString()).text("lamp.gltf").uuid(VERSION).done();
        assertThrows(IllegalArgumentException.class, () -> DescriptorCodec.decodeVersion(payload));
        assertThrows(IllegalArgumentException.class,
                () -> DescriptorCodec.decodeResource(new Payload().int32(0x12345678).uuid(RESOURCE)
                        .text(MODEL_TYPE.toString()).text("lamp.gltf").uuid(VERSION).done()));
    }

    @Test
    void truncatedAndTrailingPayloadsAreRejected() {
        byte[] resource = DescriptorCodec.encode(
                new LogicalResource(RESOURCE, MODEL_TYPE, "lamp.gltf", VERSION));
        assertThrows(IllegalArgumentException.class,
                () -> DescriptorCodec.decodeResource(java.util.Arrays.copyOf(resource, resource.length - 1)));
        assertThrows(IllegalArgumentException.class,
                () -> DescriptorCodec.decodeResource(java.util.Arrays.copyOf(resource, 8)));
        byte[] padded = java.util.Arrays.copyOf(resource, resource.length + 1);
        assertThrows(IllegalArgumentException.class, () -> DescriptorCodec.decodeResource(padded));
        assertThrows(IllegalArgumentException.class, () -> DescriptorCodec.decodeResource(new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> DescriptorCodec.decodeResource(new byte[ResourceSupport.MAX_DESCRIPTOR_BYTES + 1]));
    }

    @Test
    void malformedUtf8IsRejected() throws IOException {
        byte[] payload = new Payload().int32(MAGIC_RESOURCE).uuid(RESOURCE)
                .rawText(new byte[] {(byte) 0xC3, (byte) 0x28})
                .text("lamp.gltf").uuid(VERSION).done();
        assertThrows(IllegalArgumentException.class, () -> DescriptorCodec.decodeResource(payload));

        byte[] lone = new Payload().int32(MAGIC_RESOURCE).uuid(RESOURCE)
                .rawText(new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80})
                .text("lamp.gltf").uuid(VERSION).done();
        assertThrows(IllegalArgumentException.class, () -> DescriptorCodec.decodeResource(lone));
    }

    @Test
    void duplicateCollectionsAreRejected() throws IOException {
        byte[] duplicateDependency = versionPayload("1.0.0", new String[0][], (byte) 0, null,
                new UUID[] {OTHER_VERSION, OTHER_VERSION}, new UUID[0]);
        assertThrows(IllegalArgumentException.class,
                () -> DescriptorCodec.decodeVersion(duplicateDependency));

        byte[] duplicateOutput = versionPayload("1.0.0", new String[0][], (byte) 0, null,
                new UUID[0], new UUID[] {OUTPUT_ENTRY, OUTPUT_ENTRY});
        assertThrows(IllegalArgumentException.class, () -> DescriptorCodec.decodeVersion(duplicateOutput));

        byte[] duplicateSetting = versionPayload("1.0.0",
                new String[][] {{"lod", "1"}, {"lod", "2"}}, (byte) 0, null, new UUID[0], new UUID[0]);
        assertThrows(IllegalArgumentException.class,
                () -> DescriptorCodec.decodeVersion(duplicateSetting));
    }

    @Test
    void impossibleCountsAreRejected() throws IOException {
        byte[] negativeSettings = new Payload().int32(MAGIC_VERSION).uuid(VERSION).uuid(RESOURCE)
                .text(MODEL_IMPORTER.toString()).text("1.0.0").int32(-1).done();
        assertThrows(IllegalArgumentException.class,
                () -> DescriptorCodec.decodeVersion(negativeSettings));

        byte[] hugeSettings = new Payload().int32(MAGIC_VERSION).uuid(VERSION).uuid(RESOURCE)
                .text(MODEL_IMPORTER.toString()).text("1.0.0")
                .int32(ResourceSupport.MAX_SETTINGS + 1).done();
        assertThrows(IllegalArgumentException.class, () -> DescriptorCodec.decodeVersion(hugeSettings));

        byte[] hugeIdentities = new Payload().int32(MAGIC_VERSION).uuid(VERSION).uuid(RESOURCE)
                .text(MODEL_IMPORTER.toString()).text("1.0.0").int32(0).byteValue(0)
                .int32(ResourceSupport.MAX_COLLECTION + 1).done();
        assertThrows(IllegalArgumentException.class, () -> DescriptorCodec.decodeVersion(hugeIdentities));

        byte[] badFlag = new Payload().int32(MAGIC_VERSION).uuid(VERSION).uuid(RESOURCE)
                .text(MODEL_IMPORTER.toString()).text("1.0.0").int32(0).byteValue(2).done();
        assertThrows(IllegalArgumentException.class, () -> DescriptorCodec.decodeVersion(badFlag));

        byte[] missingSource = new Payload().int32(MAGIC_VERSION).uuid(VERSION).uuid(RESOURCE)
                .text(MODEL_IMPORTER.toString()).text("1.0.0").int32(0).byteValue(1).done();
        assertThrows(IllegalArgumentException.class, () -> DescriptorCodec.decodeVersion(missingSource));
    }

    @Test
    void oversizedStringsAreRejected() {
        String tooLong = "n".repeat(ResourceSupport.MAX_STRING_BYTES + 1);
        assertThrows(IllegalArgumentException.class, () -> DescriptorCodec.encode(
                new LogicalResource(RESOURCE, MODEL_TYPE, tooLong, VERSION)));
        assertThrows(IllegalArgumentException.class, () -> DescriptorCodec.encode(
                new ResourceVersion(VERSION, RESOURCE, MODEL_IMPORTER, tooLong, Map.of(), null,
                        Set.of(), Set.of())));
    }

    @Test
    void stringsAreBoundedInCharsBeforeEncodingAndInBytesAfter() {
        // The char count is checked before UTF-8 encoding, so an over long string never reaches the
        // encoder's buffer.
        assertThrows(IllegalArgumentException.class, () -> DescriptorCodec.encode(
                new LogicalResource(RESOURCE, MODEL_TYPE,
                        "n".repeat(ResourceSupport.MAX_STRING_BYTES + 1), VERSION)));
        // Forty thousand three byte characters are only forty thousand chars but a hundred and twenty
        // thousand bytes: the byte bound decides after the strict encoding.
        String wide = "\u4e2d".repeat(40000);
        assertEquals(40000, wide.length());
        assertThrows(IllegalArgumentException.class, () -> DescriptorCodec.encode(
                new LogicalResource(RESOURCE, MODEL_TYPE, wide, VERSION)));
        // Exactly at either bound is still accepted.
        LogicalResource atCharBound = new LogicalResource(RESOURCE, MODEL_TYPE,
                "n".repeat(ResourceSupport.MAX_STRING_BYTES), VERSION);
        assertEquals(atCharBound, DescriptorCodec.decodeResource(DescriptorCodec.encode(atCharBound)));
        LogicalResource atByteBound = new LogicalResource(RESOURCE, MODEL_TYPE,
                "\u4e2d".repeat(21845), VERSION);
        assertEquals(atByteBound, DescriptorCodec.decodeResource(DescriptorCodec.encode(atByteBound)));
    }

    @Test
    void encodedSizeMatchesTheEncodedPayloadAndBoundsTheOutput() {
        LogicalResource resource = new LogicalResource(RESOURCE, MODEL_TYPE, "lamp.gltf", VERSION);
        assertEquals(DescriptorCodec.encode(resource).length, DescriptorCodec.encodedSize(resource));
        ResourceVersion version = new ResourceVersion(VERSION, RESOURCE, MODEL_IMPORTER, "1.1.0",
                Map.of("lod", "2"), SOURCE_ENTRY, Set.of(OTHER_VERSION), Set.of(OUTPUT_ENTRY));
        assertEquals(DescriptorCodec.encode(version).length, DescriptorCodec.encodedSize(version));
        // Every string stays under the string bound, so the descriptor bound alone is what refuses the
        // measurement; encode refuses it just as early instead of building a full payload first.
        Map<String, String> bulky = new java.util.LinkedHashMap<>();
        for (int index = 0; index < 20; index++) {
            bulky.put("k" + index, "a".repeat(60000));
        }
        ResourceVersion oversized = new ResourceVersion(VERSION, RESOURCE, MODEL_IMPORTER, "1.0.0",
                bulky, null, Set.of(), Set.of());
        assertThrows(IllegalArgumentException.class, () -> DescriptorCodec.encodedSize(oversized));
        assertThrows(IllegalArgumentException.class, () -> DescriptorCodec.encode(oversized));
    }

    @Test
    void oversizedPayloadsAreRejectedOnDecode() {
        assertThrows(IllegalArgumentException.class,
                () -> DescriptorCodec.decodeVersion(new byte[ResourceSupport.MAX_DESCRIPTOR_BYTES + 1]));
        assertThrows(IllegalArgumentException.class,
                () -> DescriptorCodec.decodeResource(new byte[ResourceSupport.MAX_DESCRIPTOR_BYTES + 1]));
    }

    @Test
    void blankImporterVersionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceVersion(VERSION, RESOURCE,
                MODEL_IMPORTER, "  ", Map.of(), null, Set.of(), Set.of()));
    }

    private static byte[] versionPayload(String importerVersion, String[][] settings, byte sourceFlag,
                                         UUID sourceEntry, UUID[] dependencies, UUID[] outputs)
            throws IOException {
        Payload payload = new Payload().int32(MAGIC_VERSION).uuid(VERSION).uuid(RESOURCE)
                .text(MODEL_IMPORTER.toString()).text(importerVersion).int32(settings.length);
        for (String[] setting : settings) {
            payload.text(setting[0]).text(setting[1]);
        }
        payload.byteValue(sourceFlag);
        if (sourceFlag == 1) {
            payload.uuid(sourceEntry);
        }
        payload.int32(dependencies.length);
        for (UUID dependency : dependencies) {
            payload.uuid(dependency);
        }
        payload.int32(outputs.length);
        for (UUID output : outputs) {
            payload.uuid(output);
        }
        return payload.done();
    }

    /** Raw little-endian-free writer mirroring the frozen descriptor layout. */
    private static final class Payload {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final DataOutputStream out = new DataOutputStream(buffer);

        Payload int32(int value) throws IOException {
            out.writeInt(value);
            return this;
        }

        Payload byteValue(int value) throws IOException {
            out.writeByte(value);
            return this;
        }

        Payload uuid(UUID value) throws IOException {
            out.writeLong(value.getMostSignificantBits());
            out.writeLong(value.getLeastSignificantBits());
            return this;
        }

        Payload text(String value) throws IOException {
            return rawText(value.getBytes(StandardCharsets.UTF_8));
        }

        Payload rawText(byte[] value) throws IOException {
            out.writeInt(value.length);
            out.write(value);
            return this;
        }

        byte[] done() throws IOException {
            out.flush();
            return buffer.toByteArray();
        }
    }
}
