package net.ptcrys.fpsmatch.editor.core.worldio;

import java.util.Objects;

/** An immutable physical node identity in a provider's namespace. */
public record FileNodeIdentity(String provider, String key) {

    public FileNodeIdentity {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(key, "key");
        if (provider.isEmpty() || provider.length() > 128 || key.isEmpty() || key.length() > 1024) {
            throw new IllegalArgumentException("Invalid filesystem identity length");
        }
    }
}
