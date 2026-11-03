package net.ptcrys.fpsmatch.editor.common.client.workbench;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A client-owned lifetime for the existing Minecraft/LWJGL Windows path dialogs.
 * Construct and call the public methods on the client thread, passing Minecraft::execute.
 * Native calls run on a shared daemon; neither construction nor request loads the native library.
 * Call cancelPending whenever the target field, document, page, or screen changes.
 * This discards the callback immediately, including a result already queued on the client thread.
 * It cannot dismiss a Windows dialog: that dialog retains the shared busy slot until it returns.
 * A reusable screen should cancelPending on removal; close permanently disposes this instance.
 * The shared daemon belongs to the client process and does not keep it alive at shutdown.
 */
public final class WorkbenchPathPicker implements AutoCloseable {

    public enum Mode {
        FOLDER,
        OPEN_FILE,
        SAVE_FILE
    }

    public enum Start {
        STARTED,
        BUSY,
        CLOSED
    }

    public enum Status {
        SELECTED,
        CANCELLED,
        FAILED
    }

    /** Only SELECTED has a path; only FAILED has a cause. No files are written by this adapter. */
    public record Result(Status status, Path path, Throwable failure) {

        public Result {
            Objects.requireNonNull(status, "status");
            if ((status == Status.SELECTED) != (path != null) || (status == Status.FAILED) != (failure != null)) {
                throw new IllegalArgumentException("Result payload does not match its status");
            }
        }

        public static Result selected(Path path) {
            return new Result(Status.SELECTED, Objects.requireNonNull(path), null);
        }

        public static Result cancelled() {
            return new Result(Status.CANCELLED, null, null);
        }

        public static Result failed(Throwable failure) {
            return new Result(Status.FAILED, null, Objects.requireNonNull(failure));
        }
    }

    /**
     * initialPath may be empty or contain the user's unfinished input; it is only a starting hint.
     * Patterns are native globs such as *.json. Empty patterns permit any file.
     * Supply already-translated title and filterDescription strings from the owning screen.
     */
    public record Request(Mode mode, String title, String initialPath, List<String> patterns,
                          String filterDescription) {

        public Request {
            Objects.requireNonNull(mode, "mode");
            title = nativeText(title, "title");
            initialPath = Objects.requireNonNull(initialPath, "initialPath");
            patterns = List.copyOf(patterns);
            for (String pattern : patterns) {
                if (nativeText(pattern, "pattern").isBlank()) throw new IllegalArgumentException("Empty file pattern");
            }
            filterDescription = nativeText(filterDescription, "filterDescription");
            if (mode == Mode.FOLDER && !patterns.isEmpty()) throw new IllegalArgumentException("Folders do not have file filters");
        }

        public static Request folder(String title, String initialPath) {
            return new Request(Mode.FOLDER, title, initialPath, List.of(), "");
        }

        public static Request openFile(String title, String initialPath, String description, String... patterns) {
            return new Request(Mode.OPEN_FILE, title, initialPath, List.of(patterns), description);
        }

        public static Request saveFile(String title, String initialPath, String description, String... patterns) {
            return new Request(Mode.SAVE_FILE, title, initialPath, List.of(patterns), description);
        }

        private static String nativeText(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.indexOf(0) >= 0) throw new IllegalArgumentException(name + " contains NUL");
            return value;
        }
    }

    private static final class Shared {

        private static final Coordinator DIALOGS = new Coordinator(Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "fpsmatch-editor-path-dialog");
            thread.setDaemon(true);
            return thread;
        }), WorkbenchPathPicker::showNativeDialog);
    }

    // The injected coordinator keeps lifetime/concurrency tests independent of native initialization.
    @FunctionalInterface
    interface Dialog {

        Result show(Request request);
    }

    static final class Coordinator {

        final Executor worker;
        final Dialog dialog;
        final AtomicBoolean busy = new AtomicBoolean();

        Coordinator(Executor worker, Dialog dialog) {
            this.worker = Objects.requireNonNull(worker);
            this.dialog = Objects.requireNonNull(dialog);
        }
    }

    private static final class Pending {

        final AtomicReference<Consumer<Result>> callback;

        Pending(Consumer<Result> callback) {
            this.callback = new AtomicReference<>(callback);
        }
    }

    private final Thread clientThread = Thread.currentThread();
    private final Executor clientExecutor;
    private final Coordinator coordinator;
    private Pending pending;
    private boolean closed;

    public WorkbenchPathPicker(Executor clientExecutor) {
        this(clientExecutor, Shared.DIALOGS);
    }

    WorkbenchPathPicker(Executor clientExecutor, Coordinator coordinator) {
        this.clientExecutor = Objects.requireNonNull(clientExecutor, "clientExecutor");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    /**
     * BUSY/CLOSED do not enqueue anything or call completion. STARTED completes on the client
     * thread unless cancelled/closed or that executor stops accepting work during shutdown.
     * A rejected worker submission reports FAILED immediately on the calling client thread.
     */
    public Start request(Request request, Consumer<Result> completion) {
        requireClientThread();
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(completion, "completion");
        Pending ticket;
        synchronized (this) {
            if (closed) return Start.CLOSED;
            if (pending != null || !coordinator.busy.compareAndSet(false, true)) return Start.BUSY;
            ticket = new Pending(completion);
            pending = ticket;
        }
        try {
            coordinator.worker.execute(() -> runDialog(request, ticket));
        } catch (RuntimeException failure) {
            coordinator.busy.set(false);
            deliver(ticket, Result.failed(failure));
        }
        return Start.STARTED;
    }

    /** Includes another picker instance's live dialog, even when its owner has cancelled. */
    public boolean isBusy() {
        requireClientThread();
        synchronized (this) {
            return pending != null || coordinator.busy.get();
        }
    }

    /** Invalidates the field binding without invoking completion or interrupting native code. */
    public void cancelPending() {
        requireClientThread();
        synchronized (this) {
            if (pending != null) pending.callback.set(null);
            pending = null;
        }
    }

    @Override
    public void close() {
        requireClientThread();
        cancelPending();
        synchronized (this) {
            closed = true;
        }
    }

    private void runDialog(Request request, Pending ticket) {
        Result result;
        try {
            if (ticket.callback.get() == null) return;
            result = Objects.requireNonNull(coordinator.dialog.show(request), "dialog result");
        } catch (RuntimeException | LinkageError failure) {
            result = Result.failed(failure);
        } finally {
            coordinator.busy.set(false);
        }
        if (ticket.callback.get() == null) return;
        Result completed = result;
        try {
            clientExecutor.execute(() -> deliver(ticket, completed));
        } catch (RuntimeException failure) {
            // A stopped executor has no safe thread on which to notify UI. Release the owner.
            synchronized (this) {
                ticket.callback.set(null);
                if (pending == ticket) pending = null;
            }
            System.getLogger(WorkbenchPathPicker.class.getName()).log(System.Logger.Level.WARNING,
                    "Path dialog completion could not reach the client thread", failure);
        }
    }

    private void deliver(Pending ticket, Result result) {
        requireClientThread();
        Consumer<Result> completion;
        synchronized (this) {
            if (closed || pending != ticket) return;
            pending = null;
            completion = ticket.callback.getAndSet(null);
        }
        if (completion != null) completion.accept(result);
    }

    private void requireClientThread() {
        if (Thread.currentThread() != clientThread) throw new IllegalStateException("Path picker must be used on its owning client thread");
    }

    private static Result showNativeDialog(Request request) {
        // The current Studio release targets Windows. Do not invoke TinyFD's Unix script fallbacks.
        if (Platform.get() != Platform.WINDOWS) throw new UnsupportedOperationException("Studio path dialogs currently support Windows");
        String initial = initialPath(request);
        String selected;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = null;
            if (!request.patterns().isEmpty()) {
                filters = stack.mallocPointer(request.patterns().size());
                for (String pattern : request.patterns()) filters.put(stack.UTF8(pattern));
                filters.flip();
            }
            String description = request.filterDescription().isEmpty() ? null : request.filterDescription();
            selected = switch (request.mode()) {
                case FOLDER -> TinyFileDialogs.tinyfd_selectFolderDialog(request.title(), initial);
                case OPEN_FILE -> TinyFileDialogs.tinyfd_openFileDialog(request.title(), initial, filters, description, false);
                case SAVE_FILE -> TinyFileDialogs.tinyfd_saveFileDialog(request.title(), initial, filters, description);
            };
        }
        // TinyFD exposes no separate OS error code for a null result; its contract calls this cancel.
        if (selected == null) return Result.cancelled();
        if (selected.isEmpty()) return Result.failed(new IllegalStateException("Native path dialog returned an empty selection"));
        return Result.selected(Path.of(selected).toAbsolutePath().normalize());
    }

    private static String initialPath(Request request) {
        if (request.initialPath().isBlank()) return "";
        try {
            Path hint = Path.of(request.initialPath()).toAbsolutePath().normalize();
            if (request.mode() != Mode.FOLDER && !Files.isDirectory(hint) && hint.getParent() != null && Files.isDirectory(hint.getParent())) return hint.toString();
            while (hint != null && !Files.isDirectory(hint)) hint = hint.getParent();
            if (hint == null) return "";
            String directory = hint.toString();
            return request.mode() == Mode.FOLDER || directory.endsWith(hint.getFileSystem().getSeparator()) ? directory : directory + hint.getFileSystem().getSeparator();
        } catch (InvalidPathException | SecurityException ignored) {
            // Choosing a path must remain available to repair an invalid typed value.
            return "";
        }
    }
}
