package net.ptcrys.fpsmatch.editor.core.session.examples;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.core.project.SaveResult;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers;
import net.ptcrys.fpsmatch.editor.core.session.DocumentHandle;
import net.ptcrys.fpsmatch.editor.core.session.DocumentTypeCatalog;
import net.ptcrys.fpsmatch.editor.core.session.LocalProjectAccess;
import net.ptcrys.fpsmatch.editor.core.session.ProjectSaveDispatch;
import net.ptcrys.fpsmatch.editor.core.session.ProjectSaveJob;
import net.ptcrys.fpsmatch.editor.core.session.ProjectSession;
import net.ptcrys.fpsmatch.editor.core.session.SessionRejection;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import net.ptcrys.fpsmatch.editor.core.types.DocumentType;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;
import net.ptcrys.fpsmatch.editor.core.types.values.ActivatedValueType;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueSnapshot;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueType;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypeCatalog;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypes;

/** Public-JAR consumer; uses only a newly created private temporary project, with no world input. */
public final class NestedCodecSmoke {
    private static final EditorId DOCUMENT = id("sample:bundle");
    private static final EditorId DOCUMENT_CODEC = id("sample:bundle_binary");
    private static final EditorId DOC_OWNER = id("sample:documents");
    private static final EditorId LEFT = id("vendor_a:title");
    private static final EditorId RIGHT = id("vendor_b:marker");
    private static final EditorId LEFT_OWNER = id("vendor_a:module");
    private static final EditorId RIGHT_OWNER = id("vendor_b:module");
    private static final EditorId SUPPORT = id("vendor_a:codec_support");
    private static final EditorId TEXT_CODEC = id("sample:text_utf8");
    private static final Set<ContributionKey> NESTED = Set.of(key(LEFT), key(RIGHT));
    private static final Map<EditorId, String> MODULES = Map.of(DOC_OWNER, "1", LEFT_OWNER, "2",
            RIGHT_OWNER, "3", SUPPORT, "4");

    private NestedCodecSmoke() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 0) throw new IllegalArgumentException("No input paths are accepted");
        Path directory = Files.createTempDirectory("nested-codec-consumer-").resolve("project");
        UUID projectId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID importedId = UUID.randomUUID();
        byte[] persisted;
        UUID commit;
        try (LocalProjectStore store = LocalProjectStore.create(directory)) {
            // Explicit host bootstrap, not an end-user new-project implementation.
            store.commit(null, UUID.randomUUID(), new ProjectState(projectId, "Nested codec example",
                    Map.of(), Map.of(), Map.of(), null));
            LocalProjectAccess access = new LocalProjectAccess(store);
            try (ProjectSession session = ProjectSession.open(access, access.read(), registry(true), 8)) {
                require(!session.protectedMode(), "new isolated project must open");
                DocumentHandle<Bundle> document = session.create(documentId, DOCUMENT, Bundle.class, "Bundle");
                require(document.view().content().title().equals("new"), "bound create must supply the default");
                document.edit(document.view().token(), new TextContent.Message("sample.bundle.edit", List.of()),
                        value -> Bundle.draft("已编辑 title", value.marker()));
                document.undo(document.view().token());
                require(document.view().content().title().equals("new"), "undo restores the default");
                document.redo(document.view().token());
                require(document.view().content().title().equals("已编辑 title"), "redo restores edited content");
                var existingToken = document.view().token();
                boolean importRejected = false;
                try { session.createWithValue(importedId, DOCUMENT, Bundle.class, "Imported bundle",
                        Bundle.draft("\uD800", "marker")); }
                catch (DocumentCodecException rejection) { importRejected = true; }
                require(importRejected && !session.documents().containsKey(importedId), "failed import leaves no document or reserved id");
                require(document.view().token().equals(existingToken), "failed import leaves the existing draft untouched");
                DocumentHandle<Bundle> imported = session.createWithValue(importedId, DOCUMENT, Bundle.class,
                        "Imported bundle", Bundle.draft("converted", "marker"));
                require(imported.hasUnpublishedChanges() && !imported.view().canUndo(), "converted value is an unpublished initial baseline");
                imported.edit(imported.view().token(), new TextContent.Message("sample.bundle.edit", List.of()),
                        value -> Bundle.draft("converted edited", value.marker()));
                imported.undo(imported.view().token());
                require(imported.view().content().title().equals("converted"), "undo returns to the complete converted value");
                imported.redo(imported.view().token());
                var capture = session.captureSave(Set.of(documentId, importedId));
                var prepared = access.prepare(capture);
                commit = session.enqueueSave(prepared);
                ProjectSaveDispatch dispatch = session.dispatchSave().orElseThrow();
                require(dispatch.problems().isEmpty(), "complete nested closure passes preflight");
                ProjectSaveJob job = dispatch.job().orElseThrow();
                SaveResult result = access.publish(job);
                require(result.outcome() == SaveResult.Outcome.CONFIRMED, "publication is confirmed");
                require(session.completeSave(job, result), "owner accepts its exact save result");
                require(!document.hasUnpublishedChanges(), "saved document becomes clean");
                require(store.load().commitId().equals(commit), "CURRENT points to the confirmed save");
                require(store.load().state().modules().equals(stringModules()),
                        "both real nested owners and the required support module are persisted");
            }
            // Reopen bytes through a new access lease, after the writing session is closed.
            LocalProjectAccess verify = new LocalProjectAccess(store);
            persisted = verify.read().documentBytes(documentId);
        }

        // A damaged nested UTF-8 sequence must reject instead of being silently re-encoded.
        byte[] damaged = persisted.clone();
        damaged[damaged.length - 1] = (byte) 0x80;
        byte[] beforeDecode = damaged.clone();
        boolean damagedRejected = false;
        try { DocumentTypeCatalog.activate(registry(true)).find(DOCUMENT).orElseThrow()
                .decode(DOCUMENT_CODEC, 1, damaged); }
        catch (DocumentCodecException rejection) {
            damagedRejected = rejection.problems().get(0).code().equals(id("sample:invalid_bundle"));
        }
        require(damagedRejected && Arrays.equals(beforeDecode, damaged), "damaged nested UTF-8 rejects and preserves caller bytes");
        boolean malformedTextRejected = false;
        try { new BundleType().bind(registry(true)).snapshot(Bundle.draft("\uD800", "marker")); }
        catch (DocumentCodecException rejection) { malformedTextRejected = true; }
        require(malformedTextRejected, "unpaired surrogate input must not be silently replaced");

        try (LocalProjectStore store = LocalProjectStore.open(directory)) {
            LocalProjectAccess access = new LocalProjectAccess(store);
            try (ProjectSession fresh = ProjectSession.open(access, access.read(), registry(true), 8)) {
                require(!fresh.protectedMode(), "fresh registry decodes all nested values");
                Bundle content = fresh.find(documentId, Bundle.class).orElseThrow().view().content();
                require(content.title().equals("已编辑 title") && content.marker().equals("marker"),
                        "both nested values survive disk reopen");
                require(fresh.find(importedId, Bundle.class).orElseThrow().view().content().title().equals("converted edited"),
                        "converted value survives fresh public-JAR reopen");
                require(content.left().dependencies().contributions().equals(Set.of(key(LEFT)))
                                && content.right().dependencies().contributions().equals(Set.of(key(RIGHT))),
                        "each decoded value retains its actual contribution");
                require(Arrays.equals(persisted, fresh.source().documentBytes(documentId)), "bytes are unchanged on open");
            }
            // All modules remain installed; remove one actual nested contribution only.
            try (ProjectSession missing = ProjectSession.open(access, access.read(), registry(false), 8)) {
                require(missing.protectedMode(), "missing nested codec protects the whole project");
                require(missing.unavailableDocuments().equals(Set.of(documentId, importedId)), "both whole documents are unavailable");
                require(missing.find(documentId, Bundle.class).isEmpty(), "no partial editable placeholder is issued");
                require(Arrays.equals(persisted, missing.source().documentBytes(documentId)), "original bytes remain accessible");
                boolean refused = false;
                try { missing.captureSave(Set.of(documentId)); }
                catch (SessionRejection rejection) {
                    refused = rejection.problems().stream().anyMatch(problem ->
                            problem.code().equals(id("editor:runtime_save/protected_project")));
                }
                require(refused, "protected publication reports its typed rejection");
                require(store.load().commitId().equals(commit), "failed publication cannot change CURRENT");
            }
            // Restoring the contribution recovers the exact persisted document without rewriting it.
            try (ProjectSession restored = ProjectSession.open(access, access.read(), registry(true), 8)) {
                require(!restored.protectedMode(), "restoring modules recovers read/edit availability");
                require(restored.find(documentId, Bundle.class).orElseThrow().view().content().title().equals("已编辑 title"),
                        "recovery retains the edited title");
                require(Arrays.equals(persisted, restored.source().documentBytes(documentId)), "recovery preserves original bytes");
            }
        }
        System.out.println("NESTED_CODEC_CONSUMER_PASS: registered values -> document bind -> create/edit/undo/redo/save -> fresh reopen -> missing contribution protection -> restore");
        System.out.println("INITIAL_VALUE_CONSUMER_PASS: invalid supplied value leaves no draft -> same id retry -> complete initial baseline/history -> save -> fresh reopen");
        System.out.println("Scope: public nested codec runtime, not BO conversion, UI, resource sharing or gameplay acceptance.");
        System.out.println("Temporary project retained: " + directory);
    }

    public record Bundle(String title, String marker, ValueSnapshot<?> left, ValueSnapshot<?> right) {
        static Bundle draft(String title, String marker) { return new Bundle(title, marker, null, null); }
    }

    private static final class BundleType implements DocumentType<Bundle> {
        private final ActivatedValueType<?> left;
        private final ActivatedValueType<?> right;
        BundleType() { this(null, null); }
        BundleType(ActivatedValueType<?> left, ActivatedValueType<?> right) { this.left = left; this.right = right; }
        @Override public DocumentType<Bundle> bind(ExtensionRegistry registry) {
            ValueTypeCatalog values = ValueTypeCatalog.activate(registry);
            return new BundleType(requireValue(values, LEFT), requireValue(values, RIGHT));
        }
        private static ActivatedValueType<?> requireValue(ValueTypeCatalog catalog, EditorId id) {
            ActivatedValueType<?> type = catalog.find(id).orElseThrow(() -> invalid(id));
            if (type.valueType() != String.class) throw invalid(id);
            return type;
        }
        @Override public Class<Bundle> valueType() { return Bundle.class; }
        @Override public EditorId codecId() { return DOCUMENT_CODEC; }
        @Override public int schemaVersion() { return 1; }
        @Override public Set<Integer> readableSchemas() { return Set.of(1); }
        @Override public Bundle create() { return snapshot(Bundle.draft("new", "marker")); }
        @Override public Bundle snapshot(Bundle value) {
            // Same-registry codecs validate and issue immutable nested snapshots, without erasure casts.
            ValueSnapshot<?> a = left.decode(TEXT_CODEC, 1, utf8(value.title()));
            ValueSnapshot<?> b = right.decode(TEXT_CODEC, 1, utf8(value.marker()));
            return new Bundle(String.class.cast(a.content()), String.class.cast(b.content()), a, b);
        }
        @Override public byte[] encode(Bundle value) {
            requireOwned(value);
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (DataOutputStream data = new DataOutputStream(output)) {
                    write(data, value.left()); write(data, value.right());
                }
                return output.toByteArray();
            } catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        private static void write(DataOutputStream data, ValueSnapshot<?> value) throws IOException {
            data.writeUTF(value.typeId().toString()); data.writeUTF(value.codecId().toString());
            data.writeInt(value.schemaVersion());
            byte[] bytes = value.encoding(); data.writeInt(bytes.length); data.write(bytes);
        }
        @Override public Bundle decode(int schema, byte[] bytes) {
            try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(bytes))) {
                ValueSnapshot<?> a = read(data, left); ValueSnapshot<?> b = read(data, right);
                if (data.available() != 0) throw invalid(DOCUMENT);
                return new Bundle(String.class.cast(a.content()), String.class.cast(b.content()), a, b);
            } catch (IOException failure) { throw invalid(DOCUMENT); }
        }
        private static ValueSnapshot<?> read(DataInputStream data, ActivatedValueType<?> type) throws IOException {
            if (!id(data.readUTF()).equals(type.id())) throw invalid(type.id());
            EditorId codec = id(data.readUTF()); int schema = data.readInt(); int length = data.readInt();
            if (length < 0 || length > ValueTypeCatalog.MAX_VALUE_BYTES || length > data.available()) throw invalid(type.id());
            return type.decode(codec, schema, data.readNBytes(length));
        }
        @Override public List<Problem> validate(Bundle value) { requireOwned(value); return List.of(); }
        private void requireOwned(Bundle value) {
            if (!left.owns(value.left()) || !right.owns(value.right())) throw invalid(DOCUMENT);
        }
        @Override public DocumentDependencies dependencies(Bundle value) {
            requireOwned(value);
            Set<ContributionKey> keys = new TreeSet<>(value.left().dependencies().contributions());
            keys.addAll(value.right().dependencies().contributions());
            require(keys.equals(NESTED), "complete nested dependency enumeration");
            return new DocumentDependencies(Set.of(), Set.of(), keys);
        }
    }

    private static final class TextType implements ValueType<String> {
        @Override public Class<String> valueType() { return String.class; }
        @Override public EditorId codecId() { return TEXT_CODEC; }
        @Override public int schemaVersion() { return 1; }
        @Override public Set<Integer> readableSchemas() { return Set.of(1); }
        @Override public String decode(int schema, byte[] bytes) {
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException failure) { throw invalid(TEXT_CODEC); }
        }
        @Override public String snapshot(String value) { return value; }
        @Override public byte[] encode(String value) { return utf8(value); }
        @Override public List<Problem> validate(String value) { return List.of(); }
        @Override public DocumentDependencies dependencies(String value) { return new DocumentDependencies(Set.of(), Set.of(), Set.of()); }
    }

    private static ExtensionRegistry registry(boolean includeRight) {
        ApiVersion api = new ApiVersion(1, 0);
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(api, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND); builder.define(ValueTypes.KIND); builder.define(ResourceProducers.KIND);
        MODULES.forEach((owner, build) -> builder.addModule(new ModuleSpec(owner, build, api,
                owner.equals(LEFT_OWNER) ? Set.of(SUPPORT) : Set.of(), Set.of())));
        builder.add(DocumentTypes.KIND, contribution(DocumentTypes.KIND.id(), DOCUMENT, DOC_OWNER), new BundleType());
        builder.add(ValueTypes.KIND, contribution(ValueTypes.KIND.id(), LEFT, LEFT_OWNER), new TextType());
        if (includeRight) builder.add(ValueTypes.KIND, contribution(ValueTypes.KIND.id(), RIGHT, RIGHT_OWNER), new TextType());
        return builder.freeze();
    }
    private static Contribution contribution(EditorId kind, EditorId type, EditorId owner) {
        return new Contribution(new ContributionKey(kind, type), owner, Contribution.Side.COMMON, Set.of());
    }
    private static Map<String, String> stringModules() {
        Map<String, String> result = new java.util.TreeMap<>(); MODULES.forEach((id, build) -> result.put(id.toString(), build));
        return result;
    }
    private static DocumentCodecException invalid(EditorId type) {
        return new DocumentCodecException(List.of(new Problem(Problem.Severity.ERROR, id("sample:invalid_bundle"),
                type.toString(), new TextContent.Message("sample.bundle.invalid", List.of(new TextContent.LiteralArgument(type.toString()))))));
    }
    private static ContributionKey key(EditorId type) { return new ContributionKey(ValueTypes.KIND.id(), type); }
    private static byte[] utf8(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            byte[] result = new byte[encoded.remaining()]; encoded.get(result); return result;
        } catch (CharacterCodingException failure) { throw invalid(TEXT_CODEC); }
    }
    private static EditorId id(String value) { return EditorId.parse(value); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
