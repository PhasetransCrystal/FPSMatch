package net.ptcrys.fpsmatch.editor.common.host.forge;

import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceContentValidators;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypes;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One-shot Forge lifecycle adapter; the distribution root owns and injects its frozen result. */
public final class ForgeEditorPlatform {

    /** READY publishes metadata and usable implementations, including any availability diagnostics. */
    public enum State {
        NEW,
        COLLECTING,
        READY,
        FAILED
    }

    private final ApiVersion api;
    private final Contribution.Side environment;
    private final Consumer<EditorRegistrationEvent> broadcaster;
    private final Runnable contextCleanup;
    private final BooleanSupplier loadingStateValid;
    private volatile State state = State.NEW;
    private volatile ExtensionRegistry registry;
    private boolean attached;

    /** Uses the physical Forge environment without loading any client implementation. */
    @SuppressWarnings("removal") // Forge 47.4.10's deferred-work context must explicitly be cleared.
    public ForgeEditorPlatform(ApiVersion api) {
        this(api, FMLEnvironment.dist.isClient() ? Contribution.Side.CLIENT : Contribution.Side.COMMON,
                event -> ModLoader.get().postEventWrapContainerInModOrder(event),
                () -> ModLoadingContext.get().setActiveContainer(null),
                ModLoader::isLoadingStateValid);
    }

    ForgeEditorPlatform(ApiVersion api, Contribution.Side environment,
                        Consumer<EditorRegistrationEvent> broadcaster, Runnable contextCleanup,
                        BooleanSupplier loadingStateValid) {
        this.api = Objects.requireNonNull(api, "api");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.broadcaster = Objects.requireNonNull(broadcaster, "broadcaster");
        this.contextCleanup = Objects.requireNonNull(contextCleanup, "contextCleanup");
        this.loadingStateValid = Objects.requireNonNull(loadingStateValid, "loadingStateValid");
    }

    /** Attaches once to the owning mod's bus; collection runs in the load-complete deferred queue. */
    public synchronized void attach(IEventBus ownerModBus) {
        Objects.requireNonNull(ownerModBus, "ownerModBus");
        if (attached || state != State.NEW) {
            throw new IllegalStateException("editor platform can only be attached once while NEW");
        }
        attached = true;
        try {
            ownerModBus.addListener(this::loadComplete);
        } catch (RuntimeException | Error failure) {
            state = State.FAILED;
            throw failure;
        }
    }

    public State state() {
        return state;
    }

    /** Returns the final result only after collection and all scope cleanup succeeded. */
    public ExtensionRegistry registry() {
        if (state != State.READY) {
            throw new IllegalStateException("editor registry is not READY");
        }
        return registry;
    }

    private void loadComplete(FMLLoadCompleteEvent event) {
        event.enqueueWork(this::collect);
    }

    void collect() {
        synchronized (this) {
            if (state != State.NEW) {
                throw new IllegalStateException("editor registration can only collect once");
            }
            state = State.COLLECTING;
        }

        ExtensionRegistryBuilder builder = null;
        EditorRegistrationEvent event = null;
        ExtensionRegistry candidate = null;
        Throwable failure = null;
        // Initialization belongs to this same cleanup boundary, including constructor failures.
        try {
            builder = new ExtensionRegistryBuilder(api, environment);
            requireValidLoadingState();
            builder.define(DocumentTypes.KIND);
            builder.define(net.ptcrys.fpsmatch.editor.core.editing.DocumentEditors.KIND);
            builder.define(net.ptcrys.fpsmatch.editor.core.editing.DocumentEditors.IMPORTERS);
            builder.define(ValueTypes.KIND);
            builder.define(ResourceProducers.KIND);
            builder.define(ResourceContentValidators.KIND);
            event = new EditorRegistrationEvent(builder, environment);
            broadcaster.accept(event);
            requireValidLoadingState();
            candidate = builder.freeze();
        } catch (RuntimeException | Error primary) {
            failure = primary;
        } finally {
            try {
                closeScope(event, builder);
            } catch (RuntimeException | Error cleanupFailure) {
                failure = combine(failure, cleanupFailure);
            }
        }

        if (failure != null) {
            state = State.FAILED;
            rethrow(failure);
        }
        registry = candidate;
        state = State.READY;
    }

    private void requireValidLoadingState() {
        if (!loadingStateValid.getAsBoolean()) {
            throw new IllegalStateException("Forge loading state is invalid during editor registration");
        }
    }

    private void closeScope(EditorRegistrationEvent event, ExtensionRegistryBuilder builder) {
        Throwable failure = null;
        try {
            if (event != null) {
                event.invalidate();
            }
            if (builder != null) {
                builder.discard();
            }
        } catch (RuntimeException | Error closeFailure) {
            failure = closeFailure;
        } finally {
            try {
                contextCleanup.run();
            } catch (RuntimeException | Error cleanupFailure) {
                failure = combine(failure, cleanupFailure);
            }
        }
        if (failure != null) {
            rethrow(failure);
        }
    }

    private static Throwable combine(Throwable primary, Throwable cleanupFailure) {
        if (primary == null) {
            return cleanupFailure;
        }
        if (primary != cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
        }
        return primary;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw (Error) failure;
    }
}
