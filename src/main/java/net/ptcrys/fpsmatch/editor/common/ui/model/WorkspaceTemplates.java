package net.ptcrys.fpsmatch.editor.common.ui.model;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.*;
import net.ptcrys.fpsmatch.editor.api.registry.*;

import java.util.Set;

/** Workspace templates are real frozen module contributions, independent of document selection. */
public final class WorkspaceTemplates {

    public static final ExtensionKind<WorkspaceTemplate> KIND = new ExtensionKind<>(EditorId.parse("editor:workspace_template"), WorkspaceTemplate.class);
    public static final EditorId OBJECTS = EditorId.parse("editor:objects");
    public static final EditorId PROJECT = EditorId.parse("editor:project_browser");
    public static final EditorId SUMMARY = EditorId.parse("editor:document_summary");
    public static final EditorId VIEWPORT = EditorId.parse("editor:viewport");
    public static final EditorId RESOURCES = EditorId.parse("editor:resources");
    public static final EditorId TASKS = EditorId.parse("editor:tasks");
    public static final EditorId PROBLEMS = EditorId.parse("editor:problems");
    public static final EditorId INSPECTOR = EditorId.parse("editor:inspector");

    private WorkspaceTemplates() {}

    public static void register(ExtensionRegistryBuilder builder, EditorId owner, WorkspaceTemplate template, Set<ContributionKey> dependencies) {
        builder.define(KIND);
        builder.add(KIND, new Contribution(new ContributionKey(KIND.id(), template.id()), owner, Contribution.Side.COMMON, dependencies), template);
    }
}
