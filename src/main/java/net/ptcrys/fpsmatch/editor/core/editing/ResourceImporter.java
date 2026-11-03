package net.ptcrys.fpsmatch.editor.core.editing;

import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Explicit new-resource import. No project writer, native object or original source path is exposed. */
public interface ResourceImporter {

    long MAX_INPUT_BYTES = 128L * 1024 * 1024;

    record Input(String id, TextContent label, long maxBytes) {

        public Input {
            if (!Objects.requireNonNull(id).matches("[a-z][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("input id");
            Objects.requireNonNull(label);
            if (maxBytes < 1 || maxBytes > MAX_INPUT_BYTES) throw new IllegalArgumentException("input budget");
        }
    }

    /** Immutable privately captured bytes. Each stream belongs to the caller and must be closed. */
    interface Source {

        BlobRef content();

        InputStream open() throws IOException;
    }

    /** Owns its generated snapshots until close; candidate/summary are not content-validation authority. */
    interface Prepared extends AutoCloseable {

        ResourceCatalog candidate();

        /** Exactly the new byte-entry identities in candidate, each matching its declared BlobRef. */
        Map<UUID, Source> sources();

        List<TextContent> summary();

        @Override
        void close() throws IOException;
    }

    TextContent label();

    /** Presentation only for resources produced by the same owner. Empty preserves the actual resource name. */
    default Optional<TextContent> resourceLabel(ResourceCatalog catalog, UUID resource) {
        return Optional.empty();
    }

    List<Input> inputs();

    /** Called on an I/O worker. Must retain original bytes, observe cancellation and release failed preparation. */
    Prepared inspect(ExtensionRegistry registry, Map<String, Source> inputs, ResourceCatalog baseline,
                     UUID seed, BooleanSupplier cancelled) throws IOException;
}
