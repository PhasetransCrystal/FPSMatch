package net.ptcrys.fpsmatch.editor.core.installation;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.core.session.LoadedProject;
import net.ptcrys.fpsmatch.editor.core.session.LocalProjectAccess;
import net.ptcrys.fpsmatch.editor.core.session.PreparedRuntime;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException;
import net.ptcrys.fpsmatch.editor.core.storage.StoreLimits;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.FileNodeIdentity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException.Code.*;

/**
 * Certified saved preparation and the exact complete archive that will be retained by an installation.
 * No world permission is granted. I/O and trusted adapters run on the caller's chosen thread.
 * Files created by a failed call are retained for diagnosis; callers supply fresh scratch destinations.
 */
public final class InstallationBundle<R> implements AutoCloseable {

    private static final long MAX_ARCHIVE_BYTES = 512L * 1024 * 1024;
    private final PreparedRuntime<R> prepared;
    private final ExtensionRegistry registry;
    private final LocalProjectAccess access;
    private final LocalProjectStore ownedStore;
    private final Path archive;
    private final FileIdentityProvider identities;
    private final FileNodeIdentity archiveIdentity;
    private final BlobRef bytes;
    private final UUID projectId;
    private final UUID sourceCommitId;
    private boolean closed;

    /** Exports the exact prepared saved version, even if the source's CURRENT has since advanced. */
    public static <R> InstallationBundle<R> capture(LocalProjectAccess access, PreparedRuntime<R> prepared,
                                                    ExtensionRegistry registry, Path archive, Path verificationDirectory, FileIdentityProvider identities,
                                                    BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(prepared, "prepared");
        if (!prepared.preparedWith(registry)) throw failure(REQUEST_MISMATCH, "Preparation registry differs");
        Objects.requireNonNull(access, "access").exportValidatedSource(prepared.source(), registry, archive, cancelled);
        InstallationBundle<R> result = new InstallationBundle<>(access, prepared, registry, archive, identities, null,
                prepared.source().saved().state().projectId(), prepared.source().saved().commitId());
        var imported = LocalProjectStore.importSnapshot(archive, verificationDirectory, StoreLimits.DEFAULT);
        ProjectState source = prepared.source().saved().state();
        if (imported.state().origin() == null || !imported.state().origin().projectId().equals(source.projectId()) || !imported.state().origin().commitId().equals(prepared.source().saved().commitId()) || !imported.state().withOrigin(source.origin()).equals(source)) {
            throw failure(REQUEST_MISMATCH, "Exported installation archive does not contain the prepared saved version");
        }
        result.verify(cancelled);
        return result;
    }

    /**
     * Reimports the index's actual archive and reruns the registered complete saved preparation.
     * Import generates a local commit; the archive's original project/commit must match its origin.
     * The returned bundle owns that scratch store until close; no persistent binding is restored.
     */
    public static <R> InstallationBundle<R> reopen(LocalInstallationIndex index, InstallationPlan.Key key,
                                                   ExtensionRegistry registry, Class<R> resultType, Path archive, Path projectDirectory,
                                                   FileIdentityProvider identities, BooleanSupplier cancelled) throws IOException {
        return reopen(index, key, registry, resultType, archive, projectDirectory, identities, cancelled, false);
    }

    /** Revalidates the retained previous input; this does not select it for recovery or grant authority. */
    public static <R> InstallationBundle<R> reopenPrevious(LocalInstallationIndex index, InstallationPlan.Key key,
                                                           ExtensionRegistry registry, Class<R> resultType, Path archive, Path projectDirectory,
                                                           FileIdentityProvider identities, BooleanSupplier cancelled) throws IOException {
        return reopen(index, key, registry, resultType, archive, projectDirectory, identities, cancelled, true);
    }

    private static <R> InstallationBundle<R> reopen(LocalInstallationIndex index, InstallationPlan.Key key,
                                                    ExtensionRegistry registry, Class<R> resultType, Path archive, Path projectDirectory,
                                                    FileIdentityProvider identities, BooleanSupplier cancelled, boolean previous) throws IOException {
        Objects.requireNonNull(index, "index").verify();
        LocalInstallationIndex.Record record = index.records().get(Objects.requireNonNull(key, "key"));
        if (record == null) throw failure(CONFLICT, "Installation record is absent");
        if (previous && record.previous() == null) throw failure(CONFLICT, "Previous installation input is absent");
        InstallationPlan selectedPlan = previous ? record.previous().plan() : record.plan();
        BlobRef selectedBytes = previous ? record.previous().bundle() : record.bundle();
        checkCancellation(cancelled);
        try (OutputStream output = Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            if (previous) index.copyPreviousBundle(key, output);
            else index.copyBundle(key, output);
        }
        try (FileChannel file = FileChannel.open(archive, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            file.force(true);
        }
        // Fix the index's exact bytes before any caller-controlled cancellation or adapter callback.
        ArchiveStamp captured = captureArchive(archive, identities);
        if (!captured.bytes().equals(selectedBytes)) throw failure(CORRUPT_CONTENT, "Installation archive bytes differ");
        checkCancellation(cancelled);
        verifyStamp(archive, identities, captured);
        LocalProjectStore.importSnapshot(archive, projectDirectory, StoreLimits.DEFAULT);
        // Import has no caller callbacks. Certify its input before any validator/adapter can run.
        verifyStamp(archive, identities, captured);
        LocalProjectStore store = LocalProjectStore.open(projectDirectory);
        try {
            LocalProjectAccess access = new LocalProjectAccess(store);
            LoadedProject loaded = access.readValidated(registry, cancelled);
            InstallationPlan plan = selectedPlan;
            ProjectState state = loaded.saved().state();
            if (!state.projectId().equals(plan.projectId()) || state.origin() == null || !state.origin().projectId().equals(plan.projectId()) || !state.origin().commitId().equals(plan.commitId())) {
                throw failure(REQUEST_MISMATCH, "Installation archive provenance differs");
            }
            PreparedRuntime<R> prepared = access.prepareValidatedRuntime(loaded, registry, plan.documentId(),
                    plan.adapterId(), resultType, cancelled);
            InstallationBundle<R> result = new InstallationBundle<>(access, prepared, registry, archive, identities,
                    store, plan.projectId(), plan.commitId());
            if (!result.bytes.equals(selectedBytes) || !result.archiveIdentity.equals(captured.identity())) {
                throw failure(CORRUPT_CONTENT, "Installation archive bytes or identity differ");
            }
            result.requirePlan(plan);
            result.verify(cancelled);
            index.verify();
            if (!record.equals(index.records().get(key))) throw failure(CONFLICT, "Installation changed while reopening");
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                store.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private InstallationBundle(LocalProjectAccess access, PreparedRuntime<R> prepared, ExtensionRegistry registry,
                               Path archive, FileIdentityProvider identities, LocalProjectStore ownedStore,
                               UUID projectId, UUID sourceCommitId) throws IOException {
        this.access = Objects.requireNonNull(access, "access");
        this.prepared = Objects.requireNonNull(prepared, "prepared");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.archive = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        this.identities = Objects.requireNonNull(identities, "identities");
        this.ownedStore = ownedStore;
        this.projectId = projectId;
        this.sourceCommitId = sourceCommitId;
        regularArchive(this.archive);
        archiveIdentity = identities.identity(this.archive);
        bytes = hashArchive(this.archive, () -> false);
        verifyArchive(() -> false);
    }

    public synchronized PreparedRuntime<R> preparation() {
        requireOpen();
        return prepared;
    }

    public synchronized BlobRef bytes() {
        requireOpen();
        return bytes;
    }

    public synchronized InstallationPlan plan(UUID installationId, InstallationPlan.Key key, EditorId factoryId,
                                              String targetVersion, Map<EditorId, EditorId> dimensions) {
        requireOpen();
        return new InstallationPlan(installationId, key, projectId, sourceCommitId, prepared.documentId(),
                prepared.adapterId(), factoryId, targetVersion, prepared.runtimeModules(), dimensions);
    }

    public synchronized void requirePlan(InstallationPlan plan) throws IOException {
        requireOpen();
        Objects.requireNonNull(plan, "plan");
        if (!plan.projectId().equals(projectId) || !plan.commitId().equals(sourceCommitId) || !plan.documentId().equals(prepared.documentId()) || !plan.adapterId().equals(prepared.adapterId()) || !plan.modules().equals(prepared.runtimeModules()) || !prepared.preparedWith(registry)) {
            throw failure(REQUEST_MISMATCH, "Installation plan does not describe this saved preparation");
        }
    }

    public synchronized void verify(BooleanSupplier cancelled) throws IOException {
        requireOpen();
        access.verifyValidatedSource(prepared.source(), registry, cancelled);
        verifyArchive(cancelled);
        checkCancellation(cancelled);
        requireOpen();
        // No caller callback follows the final source and archive certification.
        access.verifyValidatedSource(prepared.source(), registry, () -> false);
        verifyArchive(() -> false);
        requireOpen();
    }

    /** A mismatching stream cannot publish even a PREPARED record. No native resources are allocated. */
    public synchronized LocalInstallationIndex.Record reserve(LocalInstallationIndex index, UUID expectedCommit,
                                                              UUID requestId, InstallationPlan plan, BooleanSupplier cancelled) throws IOException {
        requirePlan(plan);
        verify(cancelled);
        requireOpen();
        try (InputStream input = Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS)) {
            return index.reserve(expectedCommit, requestId, plan, input, bytes);
        }
    }

    /** Certify the fixed new archive before the index retains both inputs in one CAS. */
    public synchronized LocalInstallationIndex.Record beginReplacement(LocalInstallationIndex index, UUID expectedHead,
                                                                       UUID requestId, LocalInstallationIndex.Record expectedRecord, InstallationPlan plan,
                                                                       BooleanSupplier cancelled) throws IOException {
        requirePlan(plan);
        verify(cancelled);
        requireOpen();
        try (InputStream input = Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS)) {
            return index.beginReplacement(expectedHead, requestId, expectedRecord, plan, input, bytes);
        }
    }

    private void verifyArchive(BooleanSupplier cancelled) throws IOException {
        regularArchive(archive);
        if (!archiveIdentity.equals(identities.identity(archive)) || !bytes.equals(hashArchive(archive, cancelled)) || !archiveIdentity.equals(identities.identity(archive))) {
            throw failure(CONFLICT, "Certified installation archive changed");
        }
    }

    private static void regularArchive(Path archive) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(archive, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther() || attributes.size() > MAX_ARCHIVE_BYTES) throw failure(INVALID_PATH, "Invalid installation archive");
    }

    private static BlobRef hashArchive(Path archive, BooleanSupplier cancelled) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        long count = 0;
        try (InputStream input = Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                checkCancellation(cancelled);
                count += read;
                if (count > MAX_ARCHIVE_BYTES) throw failure(LIMIT_EXCEEDED, "Installation archive exceeds budget");
                digest.update(buffer, 0, read);
            }
        }
        checkCancellation(cancelled);
        return new BlobRef(BlobRef.Kind.ASSET, HexFormat.of().formatHex(digest.digest()), count);
    }

    private record ArchiveStamp(FileNodeIdentity identity, BlobRef bytes) {}

    private static ArchiveStamp captureArchive(Path archive, FileIdentityProvider identities) throws IOException {
        regularArchive(archive);
        FileNodeIdentity identity = identities.identity(archive);
        ArchiveStamp captured = new ArchiveStamp(identity, hashArchive(archive, () -> false));
        verifyStamp(archive, identities, captured);
        return captured;
    }

    private static void verifyStamp(Path archive, FileIdentityProvider identities, ArchiveStamp expected) throws IOException {
        regularArchive(archive);
        if (!expected.identity().equals(identities.identity(archive)) || !expected.bytes().equals(hashArchive(archive, () -> false)) || !expected.identity().equals(identities.identity(archive))) {
            throw failure(CONFLICT, "Installation archive changed while importing");
        }
    }

    private static void checkCancellation(BooleanSupplier cancelled) throws net.ptcrys.fpsmatch.editor.core.resources.ResourceValidationCancelledException {
        if (Objects.requireNonNull(cancelled, "cancelled").getAsBoolean()) {
            throw new net.ptcrys.fpsmatch.editor.core.resources.ResourceValidationCancelledException();
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Installation bundle is closed");
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        if (ownedStore != null) ownedStore.close();
    }

    private static ProjectStorageException failure(ProjectStorageException.Code code, String message) {
        return new ProjectStorageException(code, message);
    }
}
