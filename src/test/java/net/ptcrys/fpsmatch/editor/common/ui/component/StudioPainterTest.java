package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;
import java.util.List;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.*;
import net.ptcrys.fpsmatch.editor.common.ui.control.WorkbenchTheme;
import org.junit.jupiter.api.Test;

class StudioPainterTest {
    @Test void disconnectedSelectionsKeepTheirGapsAndUseTheSameScrolledOrigin(){
        var bounds=new Bounds(20,30,42,20);var source=new java.util.ArrayList<StudioTextLine.Selection>();
        source.add(new StudioTextLine.Selection(0,2,0,10));source.add(new StudioTextLine.Selection(2,4,20,10));source.add(new StudioTextLine.Selection(4,6,45,10));
        var input=new StudioPainter.Input("mixed text",5,source,14,55,true);source.clear();
        assertEquals(3,input.selections().size());assertThrows(UnsupportedOperationException.class,()->input.selections().clear());
        var painter=new StudioPainter(fractionalText());var node=node("placeholder",Role.TEXT_FIELD,bounds,bounds);
        var picture=painter.compile(node,Visual.of(Kind.INPUT),THEME,false,true,false,input);
        var highlights=quads(picture).stream().filter(quad->quad.color()==THEME.selected()).toList();
        assertEquals(List.of(new StudioCanvas.Quad(new Bounds(26,33,5,14),THEME.selected()),new StudioCanvas.Quad(new Bounds(41,33,10,14),THEME.selected())),highlights);
        assertTrue(picture.commands().indexOf(highlights.get(1))<picture.commands().indexOf(texts(picture).get(0)));
        assertEquals(21,texts(picture).get(0).x());
        var unfocused=painter.compile(node,Visual.of(Kind.INPUT),THEME,false,false,false,input);
        assertTrue(quads(unfocused).stream().noneMatch(quad->quad.color()==THEME.selected()));
        assertTrue(new StudioPainter.Input("text",0,4,4,4,20,false).selections().isEmpty());
    }
    @Test void vectorIconKeepsApprovedBoxAndTextAdvance(){
        var bounds=new Bounds(10,20,90,20);var painter=new StudioPainter(fractionalText());
        var picture=painter.compile(node("Save",Role.BUTTON,bounds,bounds),Visual.of(Kind.BUTTON).icon(Icon.SAVE),THEME,false,false,false,null);
        var icons=picture.commands().stream().filter(StudioCanvas.IconGlyph.class::isInstance).map(StudioCanvas.IconGlyph.class::cast).toList();
        assertEquals(List.of(new StudioCanvas.IconGlyph(Icon.SAVE,new Bounds(16,24,12,12),THEME.text(),new Bounds(16,24,12,12))),icons);
        assertEquals(32,texts(picture).get(0).x());
        assertTrue(picture.commands().indexOf(icons.get(0))<picture.commands().indexOf(texts(picture).get(0)));
        var centered=painter.compile(node("Close",Role.BUTTON,new Bounds(10,20,20,20),new Bounds(10,20,20,20)),Visual.of(Kind.ICON).icon(Icon.CLOSE),THEME,false,false,false,null);
        assertEquals(new Bounds(14,24,12,12),((StudioCanvas.IconGlyph)centered.commands().get(0)).bounds());
    }
    private static final WorkbenchTheme THEME=WorkbenchTheme.dark();
    private static Node node(String name,Role role,Bounds bounds,Bounds clip) {
        return new Node("control",role,name,"",bounds,true,false,false,"",0,clip);
    }
    private static List<StudioCanvas.Text> texts(StudioCanvas.Picture picture) {
        return picture.commands().stream().filter(StudioCanvas.Text.class::isInstance).map(StudioCanvas.Text.class::cast).toList();
    }
    private static List<StudioCanvas.Quad> quads(StudioCanvas.Picture picture) {
        return picture.commands().stream().filter(StudioCanvas.Quad.class::isInstance).map(StudioCanvas.Quad.class::cast).toList();
    }
    private static StudioTextLayout fractionalText() {
        return new StudioTextLayout((value,size)->(int)Math.ceil(value.codePointCount(0,value.length())*4.4));
    }

    @Test void inputPaintUsesTheMeasuredOriginalLineAndOrdersSelectionTextThenCaret() {
        var layout=fractionalText();var line=layout.line("A😀bcd",TextSize.BODY);
        var viewport=line.viewport(1,10);var selection=line.selection(0,4);
        var bounds=new Bounds(20,30,22,20);var node=node("placeholder",Role.TEXT_FIELD,bounds,bounds);
        var input=new StudioPainter.Input(line.text(),viewport.originX(),selection.x(),selection.x()+selection.width(),line.x(4),line.width(),true);
        var picture=new StudioPainter(layout).compile(node,Visual.of(Kind.INPUT),THEME,false,true,false,input);
        var text=texts(picture).get(0);
        assertEquals(new StudioCanvas.Text(line.text(),21,35,THEME.text(),TextSize.BODY,new Bounds(26,32,10,16)),text);
        var selected=new StudioCanvas.Quad(new Bounds(26,33,9,14),THEME.selected());
        var caret=new StudioCanvas.Quad(new Bounds(35,34,1,12),THEME.accent());
        assertTrue(picture.commands().contains(selected));assertEquals(caret,picture.commands().get(picture.commands().size()-1));
        assertTrue(picture.commands().indexOf(selected)<picture.commands().indexOf(text));
        assertTrue(picture.commands().indexOf(text)<picture.commands().indexOf(caret));
        assertEquals(line.x(4),caret.bounds().x()-text.x());
        assertEquals(viewport.selection(0,4).width(),selected.bounds().width());
        assertEquals(4,viewport.hitTest(caret.bounds().x()-26));
        assertTrue(picture.truncated());
    }

    @Test void inputSelectionTextAndCaretRespectThePublishedParentClip() {
        var layout=fractionalText();var bounds=new Bounds(20,30,22,20);var clip=new Bounds(30,33,5,10);
        var input=new StudioPainter.Input("A😀bcd",5,0,14,14,22,true);
        var picture=new StudioPainter(layout).compile(node("placeholder",Role.TEXT_FIELD,bounds,clip),Visual.of(Kind.INPUT),THEME,false,true,false,input);
        assertEquals(clip,texts(picture).get(0).clip());
        assertTrue(quads(picture).contains(new StudioCanvas.Quad(clip,THEME.selected())));
        for(var quad:quads(picture))assertEquals(quad.bounds(),quad.bounds().intersect(clip));
        assertFalse(quads(picture).contains(new StudioCanvas.Quad(new Bounds(35,34,1,12),THEME.accent())));
    }

    @Test void unfocusedInputHidesSelectionAndCaretWithoutDiscardingRawText() {
        var layout=fractionalText();var bounds=new Bounds(20,30,22,20);
        var input=new StudioPainter.Input("A😀bcd",5,0,14,14,22,true);
        var picture=new StudioPainter(layout).compile(node("placeholder",Role.TEXT_FIELD,bounds,bounds),Visual.of(Kind.INPUT),THEME,false,false,false,input);
        assertEquals(input.text(),texts(picture).get(0).value());
        assertEquals(21,texts(picture).get(0).x());
        assertTrue(quads(picture).stream().noneMatch(q->q.color()==THEME.selected()||q.color()==THEME.accent()));
        assertInstanceOf(StudioCanvas.Text.class,picture.commands().get(picture.commands().size()-1));
    }

    @Test void wrappedLabelPaintUsesTheMeasurementServiceLineHeightAndLines() {
        var layout=new StudioTextLayout(new StudioTextLayout.Metrics() {
            @Override public int width(String value,TextSize size) { return value.length()*5; }
            @Override public int lineHeight(TextSize size) { return 17; }
        });
        var wrapped=layout.wrap("alpha beta gamma",30,TextSize.BODY);
        var bounds=new Bounds(10,20,30,wrapped.height());
        var picture=new StudioPainter(layout).compile(node("",Role.LABEL,bounds,bounds),Visual.of(Kind.LABEL).lines(wrapped.lines()),THEME,false,false,false,null);
        var commands=texts(picture);assertEquals(wrapped.lines(),commands.stream().map(StudioCanvas.Text::value).toList());
        for(int index=0;index<commands.size();index++){
            assertEquals(10,commands.get(index).x());assertEquals(20+index*wrapped.lineHeight(),commands.get(index).y());
            assertEquals(bounds,commands.get(index).clip());
        }
        assertFalse(picture.truncated());
    }

    @Test void labelEllipsisAndTruncationUseTheSameMeasuredWidth() {
        var layout=fractionalText();var bounds=new Bounds(10,20,15,20);String name="A😀bcd";
        var picture=new StudioPainter(layout).compile(node(name,Role.LABEL,bounds,bounds),Visual.of(Kind.LABEL),THEME,false,false,false,null);
        var text=texts(picture).get(0);assertEquals(layout.ellipsize(name,15,TextSize.BODY),text.value());
        assertTrue(layout.width(text.value(),TextSize.BODY)<=15);assertTrue(picture.truncated());
        assertEquals(10,text.x());assertEquals(25,text.y());
    }

    @Test void roundedControlsAndTextAreClippedToTheSameVisibleGeometry() {
        var bounds=new Bounds(10,20,70,24);var clip=new Bounds(25,25,20,12);
        var picture=new StudioPainter(fractionalText()).compile(node("button",Role.BUTTON,bounds,clip),Visual.of(Kind.BUTTON).variant(Variant.OUTLINED),THEME,true,true,true,null);
        assertFalse(quads(picture).isEmpty());
        for(var quad:quads(picture))assertEquals(quad.bounds(),quad.bounds().intersect(clip));
        assertEquals(clip,texts(picture).get(0).clip());
        assertTrue(quads(picture).stream().anyMatch(q->q.color()==THEME.selected()));
    }
}
