package net.ptcrys.fpsmatch.editor.common.ui.persistence;

import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.FileNodeIdentity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Host-local authoring association, never installation authority or a write inside a project/save. */
public final class ProjectWorldStore {

    private static final int BINDING_LIMIT = 16384;
    private static final int RECORD_LIMIT = 49152;
    private static final int HISTORY_LIMIT = 1024 * 1024;
    private static final int MAX_RELOCATIONS = 64;
    private static final int MAX_DIRECTORY_ENTRIES = 256;
    private static final Set<String> BINDING_KEYS = Set.of("version", "projectId", "projectPath", "projectProvider",
            "projectNode", "savesRoot", "levelId", "label", "worldProvider", "worldNode");
    private static final Set<String> RELOCATION_KEYS = Set.of("version", "revision", "previous", "original", "current");

    public enum Code {
        INVALID,
        CHANGED,
        ALREADY_BOUND,
        MISSING,
        UNKNOWN
    }

    public static final class BindingException extends IOException {

        private static final long serialVersionUID = 1L;
        private final Code code;

        BindingException(Code code, String message) {
            super(message);
            this.code = code;
        }

        BindingException(Code code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }

    public record World(Path savesRoot, String levelId, String label, FileNodeIdentity identity) {

        public World {
            savesRoot = absolute(savesRoot);
            Objects.requireNonNull(identity);
            Objects.requireNonNull(label);
            if (levelId == null || levelId.isBlank() || levelId.equals(".") || levelId.equals("..") || levelId.indexOf('/') >= 0 || levelId.indexOf('\\') >= 0 || levelId.indexOf(':') >= 0 || levelId.indexOf(0) >= 0 || levelId.length() > 255 || label.length() > 4096) {
                throw new IllegalArgumentException("Invalid save identity");
            }
        }

        public Path root() {
            return savesRoot.resolve(levelId);
        }
    }

    public record Binding(UUID projectId, Path project, FileNodeIdentity projectIdentity, World world) {

        public Binding {
            Objects.requireNonNull(projectId);
            project = absolute(project);
            Objects.requireNonNull(projectIdentity);
            Objects.requireNonNull(world);
        }
    }

    /** Archival paths are retained verbatim; read() rebases only the project's current physical location. */
    public record History(Binding original, List<Binding> relocations) {

        public History {
            Objects.requireNonNull(original);
            relocations = List.copyOf(relocations);
        }

        public Binding current() {
            return relocations.isEmpty() ? original : relocations.get(relocations.size() - 1);
        }
    }

    // Only the narrow filesystem fault seams needed to exercise publication and inaccessible paths.
    static class Operations {

        BasicFileAttributes attributes(Path path) throws IOException {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }

        void publish(Path target, Path temporary) throws IOException {
            Files.createLink(target, temporary);
        }

        void delete(Path path) throws IOException {
            Files.delete(path);
        }
    }

    private record Location(Path project, UUID id, FileNodeIdentity projectNode, Path bucket) {}

    private record Stamp(FileNodeIdentity node, long size, FileTime modified, FileTime created) {}

    private record Stored(Path path, Stamp stamp, byte[] bytes) {}

    private record Snapshot(History history, List<Stored> records, FileNodeIdentity storeNode,
                            FileNodeIdentity bucketNode) {

        int revision() {
            return history.relocations().size();
        }

        String digest() {
            return sha256(records.get(records.size() - 1).bytes());
        }
    }

    private final Path directory;
    private final FileIdentityProvider identities;
    private final Operations operations;

    public ProjectWorldStore(Path directory, FileIdentityProvider identities) {
        this(directory, identities, new Operations());
    }

    ProjectWorldStore(Path directory, FileIdentityProvider identities, Operations operations) {
        this.directory = absolute(directory);
        this.identities = Objects.requireNonNull(identities);
        this.operations = Objects.requireNonNull(operations);
    }

    /** Captures an existing save without creating a marker or modifying anything inside it. */
    public World inspect(Path savesRoot, String levelId, String label) throws IOException {
        World candidate = new World(savesRoot, levelId, label, new FileNodeIdentity("pending", "pending"));
        FileNodeIdentity before = directoryIdentity(candidate.root());
        regular(candidate.root().resolve("level.dat"));
        if (!before.equals(directoryIdentity(candidate.root()))) throw changed("Save changed during inspection");
        return new World(candidate.savesRoot(), levelId, label, before);
    }

    public void verify(World world) throws IOException {
        World current = inspect(world.savesRoot(), world.levelId(), world.label());
        if (!world.identity().equals(current.identity())) throw changed("Bound save directory was replaced");
    }

    /** Missing saves remain readable. Only an absent local record returns empty; access failures propagate. */
    public Optional<Binding> read(Path project, UUID projectId) throws IOException {
        Location location = location(project, projectId);
        return capture(location).map(snapshot -> atProject(snapshot.history().current(), location.project()));
    }

    /** Returns the complete authenticated history, including the immutable first binding. */
    public Optional<History> readHistory(Path project, UUID projectId) throws IOException {
        return capture(location(project, projectId)).map(Snapshot::history);
    }

    /** First binding only. An existing same capture is idempotent; relocation requires its explicit API. */
    public Binding bind(Path project, UUID projectId, World world) throws IOException {
        Location location = location(project, projectId);
        requireLocalStorage(location, world);
        verify(world);
        Optional<Snapshot> prior = capture(location);
        if (prior.isPresent()) return existing(location, prior.get(), world);
        return publish(location, null, world);
    }

    /** Updates only the location of the originally bound physical save. Never changes its identity. */
    public Binding relocate(Path project, UUID projectId, World candidate) throws IOException {
        Location location = location(project, projectId);
        requireLocalStorage(location, candidate);
        Snapshot prior = capture(location).orElseThrow(() -> new BindingException(Code.MISSING, "Project has no world binding to locate"));
        if (!prior.history().original().world().identity().equals(candidate.identity())) {
            throw alreadyBound("Selected directory is not the originally bound physical save");
        }
        verify(candidate);
        if (sameWorld(prior.history().current().world(), candidate)) {
            return atProject(prior.history().current(), location.project());
        }
        if (prior.revision() >= MAX_RELOCATIONS) throw invalid("World binding relocation limit reached");
        return publish(location, prior, candidate);
    }

    public Binding locateOriginal(Path project, UUID projectId, Path savesRoot, String levelId, String label) throws IOException {
        return relocate(project, projectId, inspect(savesRoot, levelId, label));
    }

    public static boolean sameWorld(World a, World b) {
        return a.root().equals(b.root()) && a.identity().equals(b.identity());
    }

    private Binding existing(Location location, Snapshot snapshot, World selected) throws IOException {
        Binding current = snapshot.history().current();
        if (!sameWorld(current.world(), selected)) throw alreadyBound("Project already has a permanent world binding; locate the original save");
        verify(current.world());
        return atProject(current, location.project());
    }

    private Binding publish(Location location, Snapshot prior, World world) throws IOException {
        Binding proposed = new Binding(location.id(), location.project(), location.projectNode(), world);
        byte[] bytes = prior == null ? encodeBinding(proposed) : encodeRelocation(prior, proposed);
        int total = bytes.length;
        if (prior != null) for (Stored record : prior.records()) total = Math.addExact(total, record.bytes().length);
        if (total > HISTORY_LIMIT) throw invalid("World binding history exceeds its byte limit");
        ensureDirectory(directory);
        FileNodeIdentity storeNode = directoryIdentity(directory);
        ensureDirectory(location.bucket());
        FileNodeIdentity bucketNode = directoryIdentity(location.bucket());
        Path target = location.bucket().resolve(prior == null ? "binding.json" : relocationName(prior.revision() + 1));
        Path temporary = null;
        FileNodeIdentity temporaryNode = null;
        boolean attempted = false;
        boolean raced = false;
        try {
            // A relocation may only extend the exact history inspected by this caller.
            if (prior != null) checkSnapshot(location, prior);
            temporary = Files.createTempFile(location.bucket(), ".project-world-", ".tmp");
            temporaryNode = regular(temporary).node();
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Stored written = readRecord(temporary, RECORD_LIMIT);
            if (!temporaryNode.equals(written.stamp().node()) || !Arrays.equals(bytes, written.bytes())) {
                throw changed("Temporary binding changed before publication");
            }
            verify(world);
            checkLocation(location, storeNode, bucketNode);
            Stored checked = readRecord(temporary, RECORD_LIMIT);
            if (!written.stamp().equals(checked.stamp()) || !Arrays.equals(bytes, checked.bytes())) {
                throw changed("Temporary binding changed before publication");
            }
            // Hard-link publication never replaces a winner and never exposes a partial record.
            // Even an exception from this call may follow a successful filesystem operation.
            attempted = true;
            try {
                operations.publish(target, temporary);
            } catch (FileAlreadyExistsException concurrent) {
                raced = true;
            }
            Snapshot stored = capture(location).orElseThrow(() -> changed("Published binding cannot be read"));
            Binding current = stored.history().current();
            if (!sameWorld(current.world(), world)) {
                if (raced) throw alreadyBound("Concurrent operation published another binding location");
                throw changed("Published binding no longer matches the requested save");
            }
            if (!raced) {
                Stored published = stored.records().stream().filter(record -> record.path().equals(target))
                        .findFirst().orElseThrow(() -> changed("Published record disappeared"));
                if (!temporaryNode.equals(published.stamp().node()) || !Arrays.equals(bytes, published.bytes())) {
                    throw changed("Published record authentication failed");
                }
            }
            verify(current.world());
            removeTemporary(temporary, temporaryNode);
            temporary = null;
            return atProject(current, location.project());
        } catch (IOException | RuntimeException failure) {
            if (temporary != null && temporaryNode != null) {
                try {
                    removeTemporary(temporary, temporaryNode);
                } catch (IOException | RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            if (attempted && !(raced && failure instanceof BindingException binding && binding.code() == Code.ALREADY_BOUND)) {
                throw new BindingException(Code.UNKNOWN, "World binding may have been published; read the local record to reconcile", failure);
            }
            if (failure instanceof IOException io) throw io;
            throw new IOException("Cannot publish project world binding", failure);
        }
    }

    private Optional<Snapshot> capture(Location location) throws IOException {
        FileNodeIdentity storeNode;
        FileNodeIdentity bucketNode;
        try {
            storeNode = directoryIdentity(directory);
        } catch (NoSuchFileException absent) {
            recheckProject(location);
            return Optional.empty();
        }
        try {
            bucketNode = directoryIdentity(location.bucket());
        } catch (NoSuchFileException absent) {
            recheckProject(location);
            if (!storeNode.equals(directoryIdentity(directory))) throw changed("Binding store changed while reading");
            return Optional.empty();
        }
        List<String> names = recordNames(location.bucket());
        Path first = location.bucket().resolve("binding.json");
        if (!names.contains("binding.json")) {
            if (!names.isEmpty()) throw invalid("World binding history is missing its original record");
            checkLocation(location, storeNode, bucketNode);
            return Optional.empty();
        }
        Stored originalRecord = readRecord(first, BINDING_LIMIT);
        Binding original = decodeBinding(originalRecord.bytes());
        authenticateBinding(location, original);
        var records = new ArrayList<Stored>();
        records.add(originalRecord);
        var relocations = new ArrayList<Binding>();
        int total = originalRecord.bytes().length;
        String previous = sha256(originalRecord.bytes());
        int count = names.size() - 1;
        if (count > MAX_RELOCATIONS) throw invalid("Too many world binding relocations");
        for (int revision = 1; revision <= count; revision++) {
            String name = relocationName(revision);
            if (!names.contains(name)) throw invalid("World binding history has a missing predecessor or branch");
            Stored record = readRecord(location.bucket().resolve(name), RECORD_LIMIT);
            total += record.bytes().length;
            if (total > HISTORY_LIMIT) throw invalid("World binding history exceeds its byte limit");
            Binding current = decodeRelocation(record.bytes(), revision, previous, original);
            authenticateBinding(location, current);
            if (!original.world().identity().equals(current.world().identity())) throw invalid("World binding history changes physical save identity");
            records.add(record);
            relocations.add(current);
            previous = sha256(record.bytes());
        }
        Snapshot result = new Snapshot(new History(original, relocations), List.copyOf(records), storeNode, bucketNode);
        checkSnapshot(location, result);
        return Optional.of(result);
    }

    private void checkSnapshot(Location location, Snapshot snapshot) throws IOException {
        checkLocation(location, snapshot.storeNode(), snapshot.bucketNode());
        List<String> expected = snapshot.records().stream().map(record -> record.path().getFileName().toString()).sorted().toList();
        if (!recordNames(location.bucket()).equals(expected)) throw changed("World binding history changed while reading");
        for (Stored prior : snapshot.records()) {
            Stored current = readRecord(prior.path(), RECORD_LIMIT);
            if (!prior.stamp().equals(current.stamp()) || !Arrays.equals(prior.bytes(), current.bytes())) {
                throw changed("World binding record changed while reading");
            }
        }
        checkLocation(location, snapshot.storeNode(), snapshot.bucketNode());
    }

    private List<String> recordNames(Path bucket) throws IOException {
        var names = new ArrayList<String>();
        int entries = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(bucket)) {
            for (Path entry : stream) {
                if (++entries > MAX_DIRECTORY_ENTRIES) throw invalid("World binding directory exceeds its entry limit");
                String name = entry.getFileName().toString();
                if (name.startsWith(".project-world-") && name.endsWith(".tmp")) continue;
                if (!name.equals("binding.json") && !name.matches("relocation-[0-9]{8}\\.json")) {
                    throw invalid("Unrecognized world binding history entry");
                }
                names.add(name);
                if (names.size() > MAX_RELOCATIONS + 1) throw invalid("Too many world binding records");
            }
        } catch (DirectoryIteratorException failure) {
            throw failure.getCause();
        }
        Collections.sort(names);
        return List.copyOf(names);
    }

    private Stored readRecord(Path path, int limit) throws IOException {
        Stamp before = regular(path);
        if (before.size() > limit) throw invalid("World binding record is too large");
        byte[] bytes;
        try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate(limit + 1);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {}
            if (buffer.position() > limit) throw invalid("World binding record is too large");
            bytes = Arrays.copyOf(buffer.array(), buffer.position());
        }
        Stamp after = regular(path);
        if (!before.equals(after) || bytes.length != after.size()) throw changed("World binding record changed while reading");
        return new Stored(path, after, bytes);
    }

    private void removeTemporary(Path path, FileNodeIdentity owned) throws IOException {
        try {
            if (!owned.equals(regular(path).node())) throw changed("Temporary binding is no longer owned");
            operations.delete(path);
        } catch (NoSuchFileException alreadyRemoved) { /* This temporary link is already absent. */ }
    }

    private Location location(Path project, UUID projectId) throws IOException {
        Path root = absolute(project);
        Objects.requireNonNull(projectId);
        FileNodeIdentity node = directoryIdentity(root);
        // Physical identity, rather than the current pathname, preserves binding across a project move.
        String key = node.provider().length() + ":" + node.provider() + node.key();
        Path bucket = directory.resolve(projectId + "-" + sha256(key.getBytes(StandardCharsets.UTF_8)));
        return new Location(root, projectId, node, bucket);
    }

    private void requireLocalStorage(Location location, World world) throws BindingException {
        if (directory.startsWith(location.project()) || directory.startsWith(world.root()) || location.bucket().startsWith(location.project()) || location.bucket().startsWith(world.root())) {
            throw invalid("Local binding storage must be outside the project and save directories");
        }
    }

    private void authenticateBinding(Location location, Binding binding) throws BindingException {
        if (!binding.projectId().equals(location.id()) || !binding.projectIdentity().equals(location.projectNode())) {
            throw changed("Project identity no longer matches its world binding");
        }
    }

    private void recheckProject(Location location) throws IOException {
        if (!location.projectNode().equals(directoryIdentity(location.project()))) throw changed("Project directory changed");
    }

    private void checkLocation(Location location, FileNodeIdentity storeNode, FileNodeIdentity bucketNode) throws IOException {
        recheckProject(location);
        if (!storeNode.equals(directoryIdentity(directory)) || !bucketNode.equals(directoryIdentity(location.bucket()))) {
            throw changed("Local binding storage changed");
        }
    }

    private FileNodeIdentity directoryIdentity(Path path) throws IOException {
        FileNodeIdentity node = null;
        Path part = path.getRoot();
        if (part == null || path.getNameCount() > 256) throw invalid("Invalid directory path");
        node = directoryNode(part);
        for (Path segment : path) {
            part = part.resolve(segment);
            node = directoryNode(part);
        }
        return node;
    }

    private FileNodeIdentity directoryNode(Path path) throws IOException {
        BasicFileAttributes attrs = operations.attributes(path);
        if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) throw invalid("Binding path contains a non-directory or link");
        return Objects.requireNonNull(identities.identity(path), "Identity provider returned null");
    }

    private Stamp regular(Path path) throws IOException {
        directoryIdentity(path.getParent());
        BasicFileAttributes attrs = operations.attributes(path);
        if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) throw invalid("Expected a regular save or binding file");
        FileNodeIdentity node = Objects.requireNonNull(identities.identity(path), "Identity provider returned null");
        return new Stamp(node, attrs.size(), attrs.lastModifiedTime(), attrs.creationTime());
    }

    private void ensureDirectory(Path path) throws IOException {
        Path part = path.getRoot();
        if (part == null || path.getNameCount() > 256) throw invalid("Invalid local binding directory");
        directoryNode(part);
        for (Path segment : path) {
            part = part.resolve(segment);
            try {
                directoryNode(part);
            } catch (NoSuchFileException absent) {
                try {
                    Files.createDirectory(part);
                } catch (FileAlreadyExistsException concurrent) { /* Authenticate the winner below. */ }
                directoryNode(part);
            }
        }
    }

    private static Binding atProject(Binding binding, Path project) {
        return new Binding(binding.projectId(), project, binding.projectIdentity(), binding.world());
    }

    private static String relocationName(int revision) {
        return String.format(Locale.ROOT, "relocation-%08d.json", revision);
    }

    private static Path absolute(Path path) {
        Objects.requireNonNull(path);
        if (!path.isAbsolute()) throw new IllegalArgumentException("Absolute path required");
        return path.normalize();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static JsonObject bindingJson(Binding binding) {
        var json = new JsonObject();
        json.addProperty("version", 1);
        json.addProperty("projectId", binding.projectId().toString());
        json.addProperty("projectPath", binding.project().toString());
        json.addProperty("projectProvider", binding.projectIdentity().provider());
        json.addProperty("projectNode", binding.projectIdentity().key());
        json.addProperty("savesRoot", binding.world().savesRoot().toString());
        json.addProperty("levelId", binding.world().levelId());
        json.addProperty("label", binding.world().label());
        json.addProperty("worldProvider", binding.world().identity().provider());
        json.addProperty("worldNode", binding.world().identity().key());
        return json;
    }

    private static byte[] encodeBinding(Binding binding) throws BindingException {
        return bounded(bindingJson(binding), BINDING_LIMIT);
    }

    private static byte[] encodeRelocation(Snapshot prior, Binding current) throws BindingException {
        // Validate individual complete bindings too, so every entry remains independently recoverable.
        encodeBinding(current);
        var json = new JsonObject();
        json.addProperty("version", 2);
        json.addProperty("revision", prior.revision() + 1);
        json.addProperty("previous", prior.digest());
        json.add("original", bindingJson(prior.history().original()));
        json.add("current", bindingJson(current));
        return bounded(json, RECORD_LIMIT);
    }

    private static byte[] bounded(JsonObject json, int limit) throws BindingException {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) throw invalid("Project world binding is too large");
        return bytes;
    }

    private static Binding decodeBinding(byte[] bytes) throws BindingException {
        try {
            return decodeBinding(JsonParser.parseString(utf8(bytes)).getAsJsonObject());
        } catch (RuntimeException failure) {
            throw new BindingException(Code.INVALID, "Malformed project world binding", failure);
        }
    }

    private static Binding decodeBinding(JsonObject json) throws BindingException {
        if (!json.keySet().equals(BINDING_KEYS) || !number(json, "version", "1")) throw invalid("Unsupported project world binding");
        return new Binding(UUID.fromString(string(json, "projectId")), Path.of(string(json, "projectPath")),
                new FileNodeIdentity(string(json, "projectProvider"), string(json, "projectNode")),
                new World(Path.of(string(json, "savesRoot")), string(json, "levelId"), string(json, "label"),
                        new FileNodeIdentity(string(json, "worldProvider"), string(json, "worldNode"))));
    }

    private static Binding decodeRelocation(byte[] bytes, int revision, String previous, Binding original) throws BindingException {
        try {
            var json = JsonParser.parseString(utf8(bytes)).getAsJsonObject();
            if (!json.keySet().equals(RELOCATION_KEYS) || !number(json, "version", "2") || !number(json, "revision", Integer.toString(revision)) || !previous.equals(string(json, "previous"))) {
                throw invalid("World binding history has an invalid predecessor or revision");
            }
            if (!original.equals(decodeBinding(json.getAsJsonObject("original")))) throw invalid("World binding history changes its original binding");
            return decodeBinding(json.getAsJsonObject("current"));
        } catch (RuntimeException failure) {
            throw new BindingException(Code.INVALID, "Malformed world binding relocation", failure);
        }
    }

    private static boolean number(JsonObject json, String key, String expected) {
        var value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && expected.equals(value.getAsString());
    }

    private static String utf8(byte[] bytes) throws BindingException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new BindingException(Code.INVALID, "World binding contains malformed UTF-8", failure);
        }
    }

    private static String string(JsonObject json, String key) throws BindingException {
        var value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid("Invalid binding field");
        return value.getAsString();
    }

    private static BindingException invalid(String message) {
        return new BindingException(Code.INVALID, message);
    }

    private static BindingException changed(String message) {
        return new BindingException(Code.CHANGED, message);
    }

    private static BindingException alreadyBound(String message) {
        return new BindingException(Code.ALREADY_BOUND, message);
    }
}
