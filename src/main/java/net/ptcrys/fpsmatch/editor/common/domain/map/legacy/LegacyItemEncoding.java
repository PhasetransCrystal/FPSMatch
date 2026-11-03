package net.ptcrys.fpsmatch.editor.common.domain.map.legacy;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

import java.util.List;
import java.util.Objects;

public final class LegacyItemEncoding {

    public static final int MAX_NOTICES = 256;
    private final EditorId typeId, codecId;
    private final int schema;
    private final byte[] bytes;
    private final List<Problem> notices;

    public LegacyItemEncoding(EditorId typeId, EditorId codecId, int schema, byte[] bytes) {
        this(typeId, codecId, schema, bytes, List.of());
    }

    /** Non-error typed migration notices; the converter collects only a uniquely accepted item. */
    public LegacyItemEncoding(EditorId typeId, EditorId codecId, int schema, byte[] bytes,
                              List<Problem> notices) {
        Objects.requireNonNull(notices, "notices");
        int size = notices.size();
        if (size < 0 || size > MAX_NOTICES) throw new IllegalArgumentException("notices size");
        for (Problem notice : notices) {
            Objects.requireNonNull(notice, "notice");
            if (notice.severity() == Problem.Severity.ERROR || !(notice.message() instanceof TextContent.Message)) {
                throw new IllegalArgumentException("notices require non-error typed messages");
            }
        }
        this.typeId = Objects.requireNonNull(typeId);
        this.codecId = Objects.requireNonNull(codecId);
        if (schema <= 0) throw new IllegalArgumentException("schema");
        Objects.requireNonNull(bytes);
        if (bytes.length > 8 * 1024 * 1024) throw new IllegalArgumentException("bytes");
        this.schema = schema;
        this.bytes = bytes.clone();
        this.notices = List.copyOf(notices);
    }

    public EditorId typeId() {
        return typeId;
    }

    public EditorId codecId() {
        return codecId;
    }

    public int schema() {
        return schema;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public List<Problem> notices() {
        return notices;
    }
}
