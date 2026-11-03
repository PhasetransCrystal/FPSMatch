package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.*;
import net.ptcrys.fpsmatch.editor.api.registry.*;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;

import java.util.Objects;
import java.util.Set;

/** Registered categories and recipes; no prototype serialization or hardcoded host categories. */
public final class ProjectTemplates {

    public record Category(TextContent label) {

        public Category {
            Objects.requireNonNull(label);
        }
    }

    public static final ExtensionKind<Category> CATEGORIES = new ExtensionKind<>(EditorId.parse("editor:project_category"), Category.class);
    public static final ExtensionKind<ProjectTemplate<?>> TEMPLATES = kind();

    private ProjectTemplates() {}

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static ExtensionKind<ProjectTemplate<?>> kind() {
        return (ExtensionKind) new ExtensionKind<>(EditorId.parse("editor:project_template"), ProjectTemplate.class);
    }

    public static void registerCategory(ExtensionRegistryBuilder builder, EditorId id, EditorId owner, TextContent label) {
        builder.define(CATEGORIES);
        builder.add(CATEGORIES, new Contribution(new ContributionKey(CATEGORIES.id(), id), owner, Contribution.Side.COMMON, Set.of()), new Category(label));
    }

    public static void register(ExtensionRegistryBuilder builder, EditorId id, EditorId owner, ProjectTemplate<?> template) {
        builder.define(TEMPLATES);
        builder.add(TEMPLATES, new Contribution(new ContributionKey(TEMPLATES.id(), id), owner, Contribution.Side.COMMON,
                Set.of(new ContributionKey(CATEGORIES.id(), template.category()), new ContributionKey(DocumentTypes.KIND.id(), template.documentType()))), template);
    }
}
