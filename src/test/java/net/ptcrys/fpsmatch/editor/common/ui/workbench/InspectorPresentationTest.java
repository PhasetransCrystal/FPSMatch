package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.editing.DocumentEditor;
import org.junit.jupiter.api.Test;

class InspectorPresentationTest {
    private static final TextContent LABEL = new TextContent.Literal("Label");

    @Test void legacyContributorsKeepIndependentScalarRowsWithoutLayoutMetadata() {
        var fields = List.of(field("name"), field("dimension"));
        var pages = InspectorPresentation.pages(fields);
        assertEquals(List.of("overview"), pages.stream().map(InspectorPresentation.Page::id).toList());
        assertEquals(List.of("general"), pages.get(0).groups().stream().map(InspectorPresentation.Group::id).toList());
        var rows = pages.get(0).groups().get(0).rows();
        assertEquals(List.of("name", "dimension"), rows.stream().map(InspectorPresentation.Row::id).toList());
        assertEquals(List.of(fields.get(0)), rows.get(0).fields());
        assertEquals(List.of(), InspectorPresentation.pages(List.of()));
    }

    @Test void repeatedRowNamesStayScopedToTheirPageAndGroupAndRetainDeclaredAxisOrder() {
        var x = placed("x", "boundary", "first", "position");
        var y = placed("y", "boundary", "first", "position");
        var z = placed("z", "boundary", "first", "position");
        var name = placed("name", "overview", "identity", "position");
        var other = placed("other", "boundary", "second", "position");
        var fields = new java.util.ArrayList<>(List.of(x, name, y, other, z));
        var pages = InspectorPresentation.pages(fields); fields.clear();
        assertEquals(List.of("boundary", "overview"), pages.stream().map(InspectorPresentation.Page::id).toList());
        var groups = pages.get(0).groups();
        assertEquals(List.of("first", "second"), groups.stream().map(InspectorPresentation.Group::id).toList());
        assertEquals(List.of(x, y, z), groups.get(0).rows().get(0).fields());
        assertEquals(List.of(other), groups.get(1).rows().get(0).fields());
        assertEquals(List.of(name), pages.get(1).groups().get(0).rows().get(0).fields());
        assertThrows(UnsupportedOperationException.class, () -> groups.get(0).rows().get(0).fields().clear());
    }

    @Test void malformedContributionsFailBeforeAnyPartialInspectorIsPublished() {
        var first = placed("x", "overview", "position", "position");
        assertThrows(IllegalArgumentException.class, () -> InspectorPresentation.pages(List.of(first, first)));
        var differentLabel = field("y").presented(new DocumentEditor.Presentation("overview",
                new TextContent.Literal("Conflicting page"), "position", LABEL, "position", LABEL, DocumentEditor.Accent.NONE));
        assertThrows(IllegalArgumentException.class, () -> InspectorPresentation.pages(List.of(first, differentLabel)));
        var groupConflict = field("y").presented(new DocumentEditor.Presentation("overview", LABEL,
                "position", new TextContent.Literal("Conflicting group"), "position", LABEL, DocumentEditor.Accent.NONE));
        assertThrows(IllegalArgumentException.class, () -> InspectorPresentation.pages(List.of(first, groupConflict)));
        assertThrows(IllegalArgumentException.class, () -> InspectorPresentation.pages(List.of(first,
                placed("y", "overview", "position", "position"), placed("z", "overview", "position", "position"),
                placed("fourth", "overview", "position", "position"))));
        for (String dependency : List.of("missing", "x")) {
            var invalid = field("x").presented(new DocumentEditor.Presentation("overview", LABEL, "position", LABEL,
                    "position", LABEL, DocumentEditor.Accent.NONE,
                    Optional.of(new DocumentEditor.Dependency(dependency, "true", LABEL))));
            assertThrows(IllegalArgumentException.class, () -> InspectorPresentation.pages(List.of(invalid)));
        }
    }

    private static DocumentEditor.Field field(String property) {
        return new DocumentEditor.Field(property, LABEL, DocumentEditor.Kind.TEXT, "value", LABEL);
    }

    private static DocumentEditor.Field placed(String property, String page, String group, String row) {
        return field(property).presented(new DocumentEditor.Presentation(page, LABEL, group, LABEL, row, LABEL, DocumentEditor.Accent.NONE));
    }
}
