package net.ptcrys.fpsmatch.editor.common.ui.component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;
import net.ptcrys.fpsmatch.editor.common.ui.control.WorkbenchTheme;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Style;
import org.junit.jupiter.api.Test;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;
import static org.junit.jupiter.api.Assertions.*;

class StudioImageTest {
    private static StudioView view(){return new StudioView(StudioTextLayout.scaled(value->value.length()*5,9),StudioMetrics.compact());}

    @Test void imageKeepsFixedGeometryAndItsEntireAreaHitsTheSingleRowAction(){
        for(int width:List.of(224,680)){
            var view=view();var calls=new AtomicInteger();
            var row=view.actionRow(StudioComponents.Action.plain("row","View room",calls::incrementAndGet),Style.defaults().gap(12),List.of(
                    view.image("row/image","fpsmatch:textures/map.png",Icon.MAP,64,40),view.paragraph("row/title","Map title")));
            var layout=view.layoutContent(row,10,20,width);var frame=new StudioFrame();layout.emit(frame,0);
            var box=layout.bounds("row/image");assertEquals(64,box.width());assertEquals(40,box.height());
            var image=frame.nodes().stream().filter(node->node.id().equals("row/image")).findFirst().orElseThrow();
            assertEquals(Role.LABEL,image.role());assertFalse(image.focusable());assertFalse(image.pointerBarrier());
            assertEquals(Set.of("row"),frame.actions().keySet());
            var tree=new ControlTree();tree.publish(frame.nodes());
            assertEquals(List.of("row"),tree.focusOrder().stream().map(ControlTree.Node::id).toList());
            assertEquals("row",tree.hit(box.x()+1,box.y()+1).orElseThrow().id());
            assertEquals("row",tree.hit(box.x()+box.width()-1,box.y()+box.height()-1).orElseThrow().id());
            assertEquals(0,calls.get());frame.actions().get("row").run();assertEquals(1,calls.get());
        }
    }

    @Test void painterRetainsFullSamplingBoundsAndUsesThePublishedClip(){
        var bounds=new Bounds(10,20,64,40);var clip=new Bounds(30,25,5,6);
        var node=new ControlTree.Node("image",Role.LABEL,"","",bounds,true,false,false,"",0,clip);
        var visual=Visual.of(Kind.IMAGE).image(new Image("fpsmatch:textures/map.png",Icon.MAP));
        for(var theme:List.of(WorkbenchTheme.dark(),WorkbenchTheme.light())){
            var picture=new StudioPainter(view().textLayout()).compile(node,visual,theme,false,false,false,null);
            var images=picture.commands().stream().filter(StudioCanvas.Image.class::isInstance).map(StudioCanvas.Image.class::cast).toList();
            assertEquals(List.of(new StudioCanvas.Image("fpsmatch:textures/map.png",Icon.MAP,new Bounds(11,21,62,38),theme.muted(),clip)),images);
            assertTrue(picture.commands().stream().noneMatch(StudioCanvas.Text.class::isInstance));assertFalse(picture.truncated());
        }
    }

    @Test void changingOnlyTheResourceRefreshesPaintWithoutChangingLayout(){
        var view=view();var cache=new StudioPaintCache(new StudioPainter(view.textLayout()));var theme=WorkbenchTheme.dark();
        StudioCanvas.Picture first=null;Bounds previous=null;
        for(String resource:List.of("fpsmatch:a.png","fpsmatch:b.png","")){
            var frame=new StudioFrame();var layout=view.layoutContent(view.image("image",resource,Icon.MAP,64,40),0,0,64);layout.emit(frame,0);
            if(previous!=null)assertEquals(previous,layout.bounds("image"));previous=layout.bounds("image");
            var node=frame.nodes().get(0);var picture=cache.picture(node,frame.visual("image"),theme,false,false,false,null,0);
            var image=(StudioCanvas.Image)picture.commands().stream().filter(StudioCanvas.Image.class::isInstance).findFirst().orElseThrow();
            assertEquals(resource,image.resource());if(first!=null)assertNotSame(first,picture);first=picture;
        }
        assertEquals(3,cache.stats().compilations());
        assertThrows(IllegalArgumentException.class,()->view.image("bad","",Icon.MAP,0,40));
    }

    @Test void addingImageSupportDoesNotAdmitNestedInteractiveControls(){
        var view=view();var action=StudioComponents.Action.plain("row","Row",()->{});
        var row=view.actionRow(action,Style.defaults(),List.of(view.image("image","",Icon.MAP,64,40),
                view.button(StudioComponents.Action.plain("nested","Nested",()->{}))));
        assertThrows(IllegalArgumentException.class,()->view.layoutContent(row,0,0,200));
    }
}
