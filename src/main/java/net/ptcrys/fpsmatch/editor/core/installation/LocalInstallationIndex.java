package net.ptcrys.fpsmatch.editor.core.installation;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException;
import net.ptcrys.fpsmatch.editor.core.storage.SavedProject;
import net.ptcrys.fpsmatch.editor.core.storage.StoreLimits;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.FileNodeIdentity;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentities;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentitySnapshot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException.Code.*;

/** Durable ownership, not a runtime installer. Every record blocks legacy construction, including ACTIVE. */
public final class LocalInstallationIndex implements AutoCloseable {

    public enum State {
        PREPARED,
        ACTIVE,
        RECOVERY_REQUIRED
    }

    public record Previous(InstallationPlan plan, BlobRef bundle) {

        public Previous {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(bundle, "bundle");
            if (bundle.kind() != BlobRef.Kind.ASSET) throw new IllegalArgumentException("Expected previous snapshot archive asset");
        }
    }

    public record Record(InstallationPlan plan, State state, BlobRef bundle, Previous previous) {

        public Record(InstallationPlan plan, State state, BlobRef bundle) {
            this(plan, state, bundle, null);
        }

        public Record {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(bundle, "bundle");
            if (bundle.kind() != BlobRef.Kind.ASSET) throw new IllegalArgumentException("Expected snapshot archive asset");
            if (previous != null && (!plan.key().equals(previous.plan().key()) || plan.installationId().equals(previous.plan().installationId()))) {
                throw new IllegalArgumentException("Previous input must have the same runtime key and a distinct installation identity");
            }
        }
    }

    private static final long OWNER_MAGIC = 0x4544494e53543031L; // EDINST01
    private static final int RECORD_MAGIC = 0x45444931; // EDI1
    private static final String MODULE = "fpsmatch:runtime_installations";
    private static final String DOCUMENT = "fpsmatch:runtime_installation";
    private static final String BUNDLE = "fpsmatch:installation_bundle";
    private static final String NAME = "Runtime installation ownership";
    private static final Map<String, String> MODULES = Map.of(MODULE, "1");
    private static final Map<String, String> REPLACEMENT_MODULES = Map.of(MODULE, "2");
    private static final LinkOption[] NOFOLLOW = { LinkOption.NOFOLLOW_LINKS };
    private static final StoreLimits LIMITS = new StoreLimits(1024 * 1024,
            512L * 1024 * 1024, 16L * 1024 * 1024 * 1024, 512, 512, 64);
    private final WorldIdentitySnapshot world;
    private final FileIdentityProvider identities;
    private final Path marker;
    private final FileNodeIdentity markerIdentity;
    private final Path directory;
    private final FileNodeIdentity directoryIdentity;
    private final LocalProjectStore store;
    private SavedProject saved;
    private Map<InstallationPlan.Key, Record> records;
    private boolean uncertain;
    private boolean closed;

    /** Absent only when both independent ownership nodes are confirmed absent. Does not register a world. */
    public static Optional<LocalInstallationIndex> openIfPresent(Path root, FileIdentityProvider identities) throws IOException {
        Path absolute = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        Objects.requireNonNull(identities, "identities");
        directory(absolute, identities);
        Path editor = absolute.resolve(".editor-platform");
        if (attributes(editor) == null) return Optional.empty();
        directory(editor, identities);
        Path marker = editor.resolve("installations.owner");
        Path index = editor.resolve("installations");
        boolean hasMarker = attributes(marker) != null;
        boolean hasIndex = attributes(index) != null;
        if (!hasMarker && !hasIndex) return Optional.empty();
        if (!hasMarker || !hasIndex) throw failure(CORRUPT_CONTENT, "Installation ownership is incomplete; preserve it for recovery");
        WorldIdentitySnapshot world = WorldIdentities.read(absolute, identities, () -> false);
        readMarker(marker, world.worldId(), identities);
        directory(index, identities);
        LocalProjectStore store = LocalProjectStore.open(index, LIMITS);
        try {
            return Optional.of(new LocalInstallationIndex(world, identities, store));
        } catch (IOException | RuntimeException | Error failure) {
            try {
                store.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Explicit first installation setup. Failures leave the witness and partial index protected, never erase them. */
    public static LocalInstallationIndex initialize(Path root, FileIdentityProvider identities) throws IOException {
        WorldIdentitySnapshot world = WorldIdentities.read(root, identities, () -> false);
        Path editor = world.root().resolve(".editor-platform");
        Path marker = editor.resolve("installations.owner");
        Path index = editor.resolve("installations");
        if (attributes(marker) != null || attributes(index) != null) {
            throw failure(CONFLICT, "Installation ownership is already present or requires recovery");
        }
        WorldIdentities.verify(world, () -> false);
        byte[] markerBytes = markerBytes(world.worldId());
        try (FileChannel output = FileChannel.open(marker, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = ByteBuffer.wrap(markerBytes);
            while (bytes.hasRemaining()) output.write(bytes);
            output.force(true);
        }
        WorldIdentities.verify(world, () -> false);
        readMarker(marker, world.worldId(), identities);
        LocalProjectStore store = LocalProjectStore.create(index, LIMITS);
        try {
            store.commit(null, UUID.randomUUID(), state(world.worldId(), Map.of()));
            return new LocalInstallationIndex(world, identities, store);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                store.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private LocalInstallationIndex(WorldIdentitySnapshot world, FileIdentityProvider identities, LocalProjectStore store) throws IOException {
        this.world = world;
        this.identities = identities;
        this.store = store;
        marker = world.root().resolve(".editor-platform/installations.owner");
        directory = world.root().resolve(".editor-platform/installations");
        markerIdentity = readMarker(marker, world.worldId(), identities);
        directoryIdentity = directory(directory, identities);
        saved = store.load();
        records = decode(saved);
        verify();
    }

    public UUID worldId() {
        return world.worldId();
    }

    public synchronized UUID currentCommitId() {
        requireUsable();
        return saved.commitId();
    }

    public synchronized Map<InstallationPlan.Key, Record> records() {
        requireUsable();
        return records;
    }

    /** Checks the physical authority and captured head only; bundle bytes are revalidated when consumed. */
    public synchronized void verify() throws IOException {
        requireUsable();
        verifyAuthorityNodes();
        store.verifyCurrent();
    }

    private void verifyAuthorityNodes() throws IOException {
        WorldIdentities.verify(world, () -> false);
        if (!markerIdentity.equals(readMarker(marker, world.worldId(), identities)) || !directoryIdentity.equals(directory(directory, identities))) {
            throw failure(CONFLICT, "Installation ownership nodes changed");
        }
    }

    /** Holds exact archive bytes. This does not certify ZIP content, install a map or grant construction authority. */
    public synchronized Record reserve(UUID expectedCommitId, UUID requestId, InstallationPlan plan, InputStream bundle) throws IOException {
        return reserve(expectedCommitId, requestId, plan, bundle, null);
    }

    /** Expected bytes are checked before any installation record is published. */
    public synchronized Record reserve(UUID expectedCommitId, UUID requestId, InstallationPlan plan,
                                       InputStream bundle, BlobRef expectedBundle) throws IOException {
        verify();
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(bundle, "bundle");
        if (records.containsKey(plan.key()) || identityOccupied(plan.installationId())) {
            throw failure(CONFLICT, "Installation identity or runtime name is already reserved");
        }
        requireEntryBudget(records, 2);
        BlobRef archive = store.stage(BlobRef.Kind.ASSET, bundle);
        if (expectedBundle != null && !archive.equals(expectedBundle)) {
            throw failure(CORRUPT_CONTENT, "Installation archive differs from its certified saved source");
        }
        Record record = new Record(plan, State.PREPARED, archive);
        Map<InstallationPlan.Key, Record> next = new LinkedHashMap<>(records);
        next.put(plan.key(), record);
        publish(expectedCommitId, requestId, next);
        return record;
    }

    /** A coordinator's durable stage update, never evidence of a currently running map. No destructive erase API. */
    public synchronized Record transition(UUID expectedCommitId, UUID requestId, InstallationPlan.Key key, State nextState) throws IOException {
        verify();
        Record old = records.get(Objects.requireNonNull(key, "key"));
        Objects.requireNonNull(nextState, "nextState");
        if (old == null || old.state() == State.RECOVERY_REQUIRED || nextState == State.PREPARED || old.state() == nextState) throw failure(CONFLICT, "Invalid installation stage transition");
        Record nextRecord = new Record(old.plan(), nextState, old.bundle(), old.previous());
        Map<InstallationPlan.Key, Record> next = new LinkedHashMap<>(records);
        next.put(key, nextRecord);
        publish(expectedCommitId, requestId, next);
        return nextRecord;
    }

    /** Explicit recovery CAS; reuses the exact retained archive and never changes installation identity. */
    public synchronized Record beginRecovery(UUID expectedHead, UUID requestId, Record expectedRecord,
                                             BlobRef certifiedBundle) throws IOException {
        verify();
        Objects.requireNonNull(expectedRecord, "expectedRecord");
        Objects.requireNonNull(certifiedBundle, "certifiedBundle");
        Objects.requireNonNull(requestId, "requestId");
        if (!saved.commitId().equals(expectedHead) || !expectedRecord.equals(records.get(expectedRecord.plan().key()))) {
            throw failure(CONFLICT, "Installation recovery baseline differs");
        }
        if (!expectedRecord.bundle().equals(certifiedBundle)) {
            throw failure(CORRUPT_CONTENT, "Installation recovery archive differs");
        }
        store.copyContent(certifiedBundle, java.io.OutputStream.nullOutputStream());
        Record recovered = new Record(expectedRecord.plan(), State.PREPARED, expectedRecord.bundle(), expectedRecord.previous());
        Map<InstallationPlan.Key, Record> next = new LinkedHashMap<>(records);
        next.put(recovered.plan().key(), recovered);
        publish(expectedHead, requestId, next);
        return recovered;
    }

    /** Retain both fixed inputs before the coordinator retires the exact old runtime instance. */
    public synchronized Record beginReplacement(UUID expectedHead, UUID requestId, Record expectedRecord,
                                                InstallationPlan newPlan, InputStream newArchive, BlobRef certifiedBlob) throws IOException {
        verify();
        requireBaseline(expectedHead, requestId, expectedRecord);
        Objects.requireNonNull(newPlan, "newPlan");
        Objects.requireNonNull(newArchive, "newArchive");
        Objects.requireNonNull(certifiedBlob, "certifiedBlob");
        if (expectedRecord.state() != State.ACTIVE || !expectedRecord.plan().key().equals(newPlan.key()) || identityOccupied(newPlan.installationId())) {
            throw failure(CONFLICT, "Installation replacement requires an exact ACTIVE input and a fresh same-key identity");
        }
        requireEntryBudget(records, expectedRecord.previous() == null ? 1 : 0);
        store.copyContent(expectedRecord.bundle(), java.io.OutputStream.nullOutputStream());
        BlobRef archive = store.stage(BlobRef.Kind.ASSET, newArchive);
        if (!archive.equals(certifiedBlob)) throw failure(CORRUPT_CONTENT, "Replacement archive differs from its certified saved source");
        Record replacement = new Record(newPlan, State.PREPARED, archive,
                new Previous(expectedRecord.plan(), expectedRecord.bundle()));
        Map<InstallationPlan.Key, Record> next = new LinkedHashMap<>(records);
        next.put(newPlan.key(), replacement);
        publish(expectedHead, requestId, next);
        return replacement;
    }

    /** Explicitly select the retained old input; both inputs remain available after activation. */
    public synchronized Record beginPreviousRecovery(UUID expectedHead, UUID requestId, Record expectedRecord,
                                                     BlobRef certifiedPreviousBlob) throws IOException {
        verify();
        requireBaseline(expectedHead, requestId, expectedRecord);
        Objects.requireNonNull(certifiedPreviousBlob, "certifiedPreviousBlob");
        Previous previous = expectedRecord.previous();
        if (previous == null) throw failure(CONFLICT, "No previous installation input is retained");
        if (!previous.bundle().equals(certifiedPreviousBlob)) throw failure(CORRUPT_CONTENT, "Previous recovery archive differs");
        store.copyContent(previous.bundle(), java.io.OutputStream.nullOutputStream());
        store.copyContent(expectedRecord.bundle(), java.io.OutputStream.nullOutputStream());
        Record recovered = new Record(previous.plan(), State.PREPARED, previous.bundle(),
                new Previous(expectedRecord.plan(), expectedRecord.bundle()));
        Map<InstallationPlan.Key, Record> next = new LinkedHashMap<>(records);
        next.put(recovered.plan().key(), recovered);
        publish(expectedHead, requestId, next);
        return recovered;
    }

    private void requireBaseline(UUID expectedHead, UUID requestId, Record expectedRecord) throws IOException {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(expectedRecord, "expectedRecord");
        if (!saved.commitId().equals(expectedHead) || !expectedRecord.equals(records.get(expectedRecord.plan().key()))) {
            throw failure(CONFLICT, "Installation replacement/recovery baseline differs");
        }
    }

    private boolean identityOccupied(UUID id) {
        return records.values().stream().anyMatch(record -> record.plan().installationId().equals(id) || record.previous() != null && record.previous().plan().installationId().equals(id));
    }

    private static void requireEntryBudget(Map<InstallationPlan.Key, Record> records, int additional) throws IOException {
        long entries = records.values().stream().mapToLong(record -> record.previous() == null ? 2 : 3).sum();
        if (entries + additional > LIMITS.maxEntries()) throw failure(LIMIT_EXCEEDED, "Installation entry budget exceeded");
    }

    /** UNKNOWN remains unknown. Querying cannot clear the guard or adopt a changed baseline. */
    public synchronized LocalProjectStore.RequestResult lookup(UUID requestId) throws IOException {
        if (closed) throw failure(CLOSED, "Installation index is closed");
        verifyAuthorityNodes();
        LocalProjectStore.RequestResult result = store.lookup(requestId);
        if (result.saved() != null) decode(result.saved());
        verifyAuthorityNodes();
        return result;
    }

    public synchronized void copyBundle(InstallationPlan.Key key, java.io.OutputStream output) throws IOException {
        verify();
        Record record = records.get(Objects.requireNonNull(key, "key"));
        if (record == null) throw failure(CONFLICT, "Installation record is absent");
        store.copyContent(record.bundle(), Objects.requireNonNull(output, "output"));
        verify();
    }

    public synchronized void copyPreviousBundle(InstallationPlan.Key key, java.io.OutputStream output) throws IOException {
        verify();
        Record record = records.get(Objects.requireNonNull(key, "key"));
        if (record == null || record.previous() == null) throw failure(CONFLICT, "Previous installation input is absent");
        store.copyContent(record.previous().bundle(), Objects.requireNonNull(output, "output"));
        verify();
    }

    private void publish(UUID expected, UUID request, Map<InstallationPlan.Key, Record> next) throws IOException {
        Map<InstallationPlan.Key, Record> fixed = Collections.unmodifiableMap(new LinkedHashMap<>(next));
        requireEntryBudget(fixed, 0);
        Map<UUID, ProjectState.Entry> documents = new TreeMap<>(), assets = new TreeMap<>();
        for (Record record : fixed.values()) {
            UUID id = record.plan().installationId();
            BlobRef content = store.stage(BlobRef.Kind.DOCUMENT, encode(record));
            Set<UUID> dependencies = record.previous() == null ? Set.of(id) : Set.of(id, record.previous().plan().installationId());
            if (documents.put(id, entry(id, DOCUMENT, content, dependencies)) != null || assets.put(id, entry(id, BUNDLE, record.bundle(), Set.of())) != null) {
                throw failure(INVALID_FORMAT, "Duplicate installation identity");
            }
            if (record.previous() != null) {
                UUID previousId = record.previous().plan().installationId();
                if (assets.put(previousId, entry(previousId, BUNDLE, record.previous().bundle(), Set.of())) != null) {
                    throw failure(INVALID_FORMAT, "Duplicate retained installation identity");
                }
            }
        }
        ProjectState state = new ProjectState(world.worldId(), NAME, modules(fixed), documents, assets, null);
        verify();
        // No allocation or caller callback after publication may leave a stale permissive snapshot.
        uncertain = true;
        LocalProjectStore.CommitResult result = store.commit(expected, request, state);
        saved = result.saved();
        records = fixed;
        uncertain = false;
    }

    private Map<InstallationPlan.Key, Record> decode(SavedProject source) throws IOException {
        ProjectState state = source.state();
        if (!state.projectId().equals(world.worldId()) || !state.name().equals(NAME) || !(state.modules().equals(MODULES) || state.modules().equals(REPLACEMENT_MODULES)) || state.origin() != null || state.documents().size() > 256 || (long) state.assets().size() + state.documents().size() > LIMITS.maxEntries()) {
            throw failure(INVALID_FORMAT, "Invalid installation index identity or version");
        }
        Map<InstallationPlan.Key, Record> result = new LinkedHashMap<>();
        Set<UUID> identities = new HashSet<>();
        for (var document : state.documents().values()) {
            UUID id = document.id();
            ProjectState.Entry asset = state.assets().get(id);
            requireEntry(asset, BUNDLE, Set.of());
            if (document.content().size() > 65536) throw failure(LIMIT_EXCEEDED, "Installation record exceeds budget");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            store.copyContent(document.content(), bytes);
            Record record = decode(bytes.toByteArray(), asset.content(), state.assets());
            Set<UUID> dependencies = record.previous() == null ? Set.of(id) : Set.of(id, record.previous().plan().installationId());
            requireEntry(document, DOCUMENT, dependencies);
            if (!record.plan().installationId().equals(id) || !identities.add(id) || record.previous() != null && !identities.add(record.previous().plan().installationId()) || result.put(record.plan().key(), record) != null) {
                throw failure(INVALID_FORMAT, "Duplicate installation runtime name or record identity");
            }
        }
        if (!identities.equals(state.assets().keySet()) || !state.modules().equals(modules(result))) {
            throw failure(INVALID_FORMAT, "Installation version or complete archive asset set differs");
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, String> modules(Map<InstallationPlan.Key, Record> records) {
        return records.values().stream().anyMatch(record -> record.previous() != null) ? REPLACEMENT_MODULES : MODULES;
    }

    private static ProjectState state(UUID world, Map<UUID, ProjectState.Entry> documents) {
        return new ProjectState(world, NAME, MODULES, documents, Map.of(), null);
    }

    private static ProjectState.Entry entry(UUID id, String type, BlobRef content, Set<UUID> assets) {
        return new ProjectState.Entry(id, MODULE, type, type, 1, type, content, Set.of(), assets);
    }

    private static void requireEntry(ProjectState.Entry entry, String type, Set<UUID> assets) throws IOException {
        if (entry == null || !entry.ownerModule().equals(MODULE) || !entry.typeId().equals(type) || !entry.codecId().equals(type) || entry.schemaVersion() != 1 || !entry.displayName().equals(type) || !entry.documentDependencies().isEmpty() || !entry.assetDependencies().equals(assets)) {
            throw failure(INVALID_FORMAT, "Invalid installation entry schema");
        }
    }

    private static byte[] encode(Record record) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(RECORD_MAGIC);
            out.writeInt(record.previous() == null ? 1 : 2);
            out.writeUTF(record.state().name());
            writePlan(out, record.plan());
            if (record.previous() != null) writePlan(out, record.previous().plan());
        }
        if (bytes.size() > 65536) throw failure(LIMIT_EXCEEDED, "Installation record exceeds budget");
        return bytes.toByteArray();
    }

    private static void writePlan(DataOutputStream out, InstallationPlan plan) throws IOException {
        uuid(out, plan.installationId());
        out.writeUTF(plan.key().type());
        out.writeUTF(plan.key().name());
        uuid(out, plan.projectId());
        uuid(out, plan.commitId());
        uuid(out, plan.documentId());
        out.writeUTF(plan.adapterId().toString());
        out.writeUTF(plan.factoryId().toString());
        out.writeUTF(plan.targetVersion());
        out.writeInt(plan.modules().size());
        for (var module : plan.modules().entrySet()) {
            out.writeUTF(module.getKey().toString());
            out.writeUTF(module.getValue());
        }
        out.writeInt(plan.dimensions().size());
        for (var dimension : plan.dimensions().entrySet()) {
            out.writeUTF(dimension.getKey().toString());
            out.writeUTF(dimension.getValue().toString());
        }
    }

    private static Record decode(byte[] bytes, BlobRef archive, Map<UUID, ProjectState.Entry> assets) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != RECORD_MAGIC) throw failure(UNSUPPORTED_FORMAT, "Unknown installation record magic");
            int binaryVersion = in.readInt();
            if (binaryVersion != 1 && binaryVersion != 2) throw failure(UNSUPPORTED_FORMAT, "Unknown installation record version");
            State state = State.valueOf(in.readUTF());
            InstallationPlan plan = readPlan(in);
            Previous previous = null;
            if (binaryVersion == 2) {
                InstallationPlan oldPlan = readPlan(in);
                ProjectState.Entry oldAsset = assets.get(oldPlan.installationId());
                requireEntry(oldAsset, BUNDLE, Set.of());
                previous = new Previous(oldPlan, oldAsset.content());
            }
            if (in.read() != -1) throw failure(INVALID_FORMAT, "Trailing installation record data");
            return new Record(plan, state, archive, previous);
        } catch (IllegalArgumentException | java.io.EOFException | java.io.UTFDataFormatException invalid) {
            throw new ProjectStorageException(INVALID_FORMAT, "Invalid installation record", invalid);
        }
    }

    private static InstallationPlan readPlan(DataInputStream in) throws IOException {
        UUID id = uuid(in);
        InstallationPlan.Key key = new InstallationPlan.Key(in.readUTF(), in.readUTF());
        UUID project = uuid(in), commit = uuid(in), document = uuid(in);
        EditorId adapter = EditorId.parse(in.readUTF()), factory = EditorId.parse(in.readUTF());
        String version = in.readUTF();
        Map<EditorId, String> modules = new TreeMap<>();
        int moduleCount = in.readInt();
        if (moduleCount < 1 || moduleCount > 256) throw failure(INVALID_FORMAT, "Invalid module count");
        for (int i = 0; i < moduleCount; i++) if (modules.put(EditorId.parse(in.readUTF()), in.readUTF()) != null) throw failure(INVALID_FORMAT, "Duplicate module");
        Map<EditorId, EditorId> dimensions = new TreeMap<>();
        int dimensionCount = in.readInt();
        if (dimensionCount < 1 || dimensionCount > 64) throw failure(INVALID_FORMAT, "Invalid dimension count");
        for (int i = 0; i < dimensionCount; i++) if (dimensions.put(EditorId.parse(in.readUTF()), EditorId.parse(in.readUTF())) != null) throw failure(INVALID_FORMAT, "Duplicate dimension");
        return new InstallationPlan(id, key, project, commit, document, adapter, factory, version, modules, dimensions);
    }

    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID uuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
        } catch (NoSuchFileException absent) {
            return null;
        }
    }

    private static FileNodeIdentity directory(Path path, FileIdentityProvider identities) throws IOException {
        BasicFileAttributes attrs = attributes(path);
        if (attrs == null || !attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) throw failure(INVALID_PATH, "Invalid installation directory");
        return identities.identity(path);
    }

    private static byte[] markerBytes(UUID world) {
        return ByteBuffer.allocate(24).putLong(OWNER_MAGIC).putLong(world.getMostSignificantBits()).putLong(world.getLeastSignificantBits()).array();
    }

    private static FileNodeIdentity readMarker(Path marker, UUID world, FileIdentityProvider identities) throws IOException {
        BasicFileAttributes attrs = attributes(marker);
        if (attrs == null || !attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther() || attrs.size() != 24) throw failure(CORRUPT_CONTENT, "Invalid installation ownership witness");
        FileNodeIdentity identity = identities.identity(marker);
        byte[] bytes;
        try (InputStream input = Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(25);
        }
        if (!Arrays.equals(bytes, markerBytes(world)) || !identity.equals(identities.identity(marker))) throw failure(CORRUPT_CONTENT, "Installation witness identity differs");
        return identity;
    }

    private void requireUsable() {
        if (closed || uncertain) throw new IllegalStateException(closed ? "Installation index is closed" : "Installation result is unknown; reopen and recover");
    }

    private static ProjectStorageException failure(ProjectStorageException.Code code, String message) {
        return new ProjectStorageException(code, message);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        store.close();
    }
}
