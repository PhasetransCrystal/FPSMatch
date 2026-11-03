package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.editing.DocumentEditor;
import net.ptcrys.fpsmatch.editor.core.editing.StudioDocument;
import org.junit.jupiter.api.Test;

class StudioFormDocumentTest {
    private static final TextContent LABEL=new TextContent.Literal("Label");
    private static final java.util.function.Function<TextContent,String> TEXT=value->((TextContent.Literal)value).text();

    @Test void contributedPageOrderAndExactFieldObjectsAreRetained(){
        var first=field("name","overview","general","Name");
        var rule=field("duration","rules","timing","Duration");
        var excluded=field("x","boundary","position","X");
        var form=new StudioDocument.Form("custom-form",LABEL,"root",List.of("rules","overview"));
        var pages=StudioFormDocument.pages(form,List.of(first,excluded,rule));
        assertEquals(List.of("rules","overview"),pages.stream().map(InspectorPresentation.Page::id).toList());
        assertSame(rule,pages.get(0).groups().get(0).rows().get(0).fields().get(0));
        assertSame(first,pages.get(1).groups().get(0).rows().get(0).fields().get(0));
    }

    @Test void problemNavigationRevealsTheExactPageAndGroupDespiteFilterAndCollapsedState(){
        var form=new StudioDocument.Form("custom-form",LABEL,"root",List.of("overview","rules"));
        var pages=StudioFormDocument.pages(form,List.of(field("name","overview","general","Name"),field("duration","rules","timing","Duration")));
        var view=new StudioFormDocument();view.selectPage("rules");view.toggle("timing");view.offset(180);
        view.selectPage("overview");view.query("no match");
        assertTrue(view.groups(pages.get(0),TEXT).isEmpty());
        assertTrue(view.reveal(pages,"duration",true));
        assertEquals("rules",view.page(pages));assertEquals("",view.query());assertFalse(view.collapsed("timing"));
        assertEquals("duration",view.groups(pages.get(1),TEXT).get(0).rows().get(0).fields().get(0).property());
        assertFalse(view.reveal(pages,"absent",true));assertEquals("rules",view.page(pages));
    }

    @Test void searchPreservesWholeCompactRowsAndDoesNotMutateCollapsePreference(){
        var x=field("x","rules","position","X coordinate");
        var y=new DocumentEditor.Field("y",new TextContent.Literal("Y coordinate"),DocumentEditor.Kind.INTEGER,"-",LABEL).presented(x.presentation().orElseThrow());
        var page=InspectorPresentation.pages(List.of(x,y)).get(0);
        var view=new StudioFormDocument();view.selectPage("rules");view.toggle("position");view.query("x coordinate");
        var rows=view.groups(page,TEXT).get(0).rows();assertEquals(List.of(x,y),rows.get(0).fields());assertFalse(view.collapsed("position"));
        view.query("");assertTrue(view.collapsed("position"));assertEquals("-",rows.get(0).fields().get(1).raw());
    }

    @Test void viewIdentityAndScrollStaySeparateAcrossDocumentsFormsAndPages(){
        UUID document=UUID.randomUUID();var first=new StudioFormDocument.Key(document,"rules");
        assertNotEquals(first,new StudioFormDocument.Key(UUID.randomUUID(),"rules"));assertNotEquals(first,new StudioFormDocument.Key(document,"other"));
        assertNotEquals(first.controlId(),new StudioFormDocument.Key(UUID.randomUUID(),"rules").controlId());
        var view=new StudioFormDocument();view.selectPage("overview");view.offset(31);view.selectPage("rules");view.offset(72);view.selectPage("overview");assertEquals(31,view.offset());
        assertEquals(90,StudioFormDocument.revealOffset(0,120,50,80,300));
        assertEquals(20,StudioFormDocument.revealOffset(90,20,30,80,300));
        assertEquals(120,StudioFormDocument.revealOffset(0,120,130,80,300));
        assertEquals(0,StudioFormDocument.revealOffset(200,0,20,80,20));
    }

    @Test void filteredGroupsCanCollapseWithoutChangingUnfilteredPreferences(){
        var view=new StudioFormDocument();view.selectPage("rules");view.toggle("timing");view.query("duration");
        assertFalse(view.collapsed("timing"));view.toggle("timing");assertTrue(view.collapsed("timing"));
        view.toggle("other");assertTrue(view.collapsed("other"));
        view.selectPage("overview");assertFalse(view.collapsed("timing"));
        view.selectPage("rules");view.query("other search");assertFalse(view.collapsed("timing"));
        view.query("");assertTrue(view.collapsed("timing"));assertFalse(view.collapsed("other"));
    }

    @Test void repeatedProblemRevealDoesNotResetOtherPagesScrollWhenSearchIsAlreadyClear(){
        var form=new StudioDocument.Form("rules",LABEL,"root",List.of("overview","rules"));
        var pages=StudioFormDocument.pages(form,List.of(field("name","overview","general","Name"),field("duration","rules","timing","Duration")));
        var view=new StudioFormDocument();view.selectPage("overview");view.offset(44);view.selectPage("rules");view.offset(120);
        assertTrue(view.reveal(pages,"duration",true));assertEquals(120,view.offset());
        view.selectPage("overview");assertEquals(44,view.offset());
        view.query("duration");assertEquals(0,view.offset());
        view.offset(18);view.query("duration");assertEquals(18,view.offset());
    }

    private static DocumentEditor.Field field(String property,String page,String group,String label){
        return new DocumentEditor.Field(property,new TextContent.Literal(label),DocumentEditor.Kind.TEXT,"raw",LABEL)
                .presented(new DocumentEditor.Presentation(page,new TextContent.Literal(page),group,new TextContent.Literal(group),"row",LABEL,DocumentEditor.Accent.NONE));
    }
}
