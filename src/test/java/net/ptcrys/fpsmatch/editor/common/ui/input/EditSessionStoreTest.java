package net.ptcrys.fpsmatch.editor.common.ui.input;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EditSessionStoreTest {
    @Test void prepareDoesNotMutateLiveEditorAndUncommittedSessionsDoNotLeak() {
        var store=new EditSessionStore();var editor=store.synchronize("doc","field","seed");
        editor.select(1,false);var before=editor.snapshot();
        var prepared=store.prepare("doc",Map.of("field","external","new","created"));
        assertEquals(before,editor.snapshot());assertSame(editor,store.editor("doc","field").orElseThrow());
        assertTrue(store.editor("doc","new").isEmpty());assertEquals(1,store.size());
        assertEquals("external",prepared.editors().get("field").text());
        prepared.commit();assertEquals("external",store.editor("doc","field").orElseThrow().text());
        assertEquals(before,editor.snapshot());assertEquals(2,store.size());
        assertThrows(IllegalStateException.class,prepared::commit);
    }

    @Test void scopesAndTemporaryUnmountKeepIndependentDraftsAndUndo() {
        var store=new EditSessionStore();var first=store.synchronize("first","name","seed");
        assertTrue(first.insert("!"));first.select(1,false);
        store.prepare("first",Map.of()).commit();
        var second=store.synchronize("second","name","other");assertNotSame(first,second);
        assertSame(first,store.synchronize("first","name","seed"));assertEquals("seed!",first.text());assertEquals(1,first.caret());
        assertTrue(first.undo());assertEquals("seed",first.text());
        store.releaseScope("second");assertTrue(store.editor("second","name").isEmpty());assertSame(first,store.editor("first","name").orElseThrow());
    }

    @Test void unrepresentableExternalValuesAndConflictsNeverReplaceTheDraft() {
        var store=new EditSessionStore();var edit=store.synchronize("doc","field","seed");edit.insert("!");
        var before=edit.snapshot();String large="😀".repeat(2049);
        var oversized=store.prepare("doc",Map.of("field",large));
        assertEquals(EditSessionStore.Problem.EXTERNAL_TOO_LONG,oversized.diagnostics().get("field").code());assertTrue(oversized.editors().isEmpty());
        oversized.commit();assertEquals(large,store.externalValue("doc","field").orElseThrow());assertEquals(before,store.draft("doc","field").orElseThrow());
        var restored=store.prepare("doc",Map.of("field","seed"));restored.commit();assertSame(edit,store.editor("doc","field").orElseThrow());
        var conflict=store.prepare("doc",Map.of("field","external"));conflict.commit();
        assertEquals(EditSessionStore.Problem.EXTERNAL_CONFLICT,store.diagnostic("doc","field").orElseThrow().code());assertEquals(before,edit.snapshot());
        store.prepare("doc",Map.of("field","external")).commit();assertEquals(before,store.draft("doc","field").orElseThrow());
        var invalid=store.prepare("doc",Map.of("multiline","first\nsecond"));invalid.commit();
        assertEquals("first\nsecond",store.externalValue("doc","multiline").orElseThrow());assertEquals(EditSessionStore.Problem.EXTERNAL_NOT_SINGLE_LINE,store.diagnostic("doc","multiline").orElseThrow().code());
    }

    @Test void explicitReleaseInvalidatesAStalePreparation() {
        var store=new EditSessionStore();var candidate=store.prepare("doc",Map.of("field","seed"));
        store.releaseScope("doc");assertThrows(IllegalStateException.class,candidate::commit);assertEquals(0,store.size());
    }
}
