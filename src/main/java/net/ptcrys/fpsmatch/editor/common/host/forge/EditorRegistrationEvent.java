package net.ptcrys.fpsmatch.editor.common.host.forge;

import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;

import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.fml.event.IModBusEvent;

import java.util.Objects;

/**
 * Synchronous MOD-bus collection of editor extensions into the distribution's one builder.
 * Listeners must register immediately and must not retain or freeze the builder.
 */
public final class EditorRegistrationEvent extends Event implements IModBusEvent {

    private final ExtensionRegistryBuilder registry;
    private final Contribution.Side environment;
    private final Thread owner = Thread.currentThread();
    private volatile boolean active = true;

    /**
     * Framework-only placeholder for EventBus listener discovery without ModLauncher transforms.
     * It is permanently inactive and never grants access to a registration builder or side.
     */
    public EditorRegistrationEvent() {
        this.registry = null;
        this.environment = null;
        this.active = false;
    }

    EditorRegistrationEvent(ExtensionRegistryBuilder registry, Contribution.Side environment) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /** The shared builder, available only during the synchronous registration callback scope. */
    public ExtensionRegistryBuilder registry() {
        requireActive();
        return registry;
    }

    /** CLIENT includes common and client contributions; COMMON is the dedicated-server side. */
    public Contribution.Side environment() {
        requireActive();
        return environment;
    }

    void invalidate() {
        active = false;
    }

    private void requireActive() {
        if (!active || Thread.currentThread() != owner) {
            throw new IllegalStateException("editor registration event is outside its owner scope");
        }
    }
}
