package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import org.junit.jupiter.api.Test;

class StudioConfirmationTest {
    private static final String CANCEL="guard/cancel",DISCARD="guard/discard",SAVE="guard/save",CLOSE="guard/close";
    private static StudioComponents.Confirmation confirmation(List<StudioComponents.ConfirmationText> messages,AtomicInteger cancelled,AtomicInteger discarded,AtomicInteger saved){
        return new StudioComponents.Confirmation("保存文档？",messages,
            new StudioComponents.Action(CLOSE,"取消",Icon.CLOSE,Variant.DEFAULT,true,false,"",cancelled::incrementAndGet),List.of(
                StudioComponents.Action.plain(CANCEL,"取消",cancelled::incrementAndGet),
                StudioComponents.Action.plain(DISCARD,"放弃修改",discarded::incrementAndGet),
                new StudioComponents.Action(SAVE,"保存并关闭",Icon.NONE,Variant.PRIMARY,true,false,"",saved::incrementAndGet)));
    }
    private static List<StudioComponents.ConfirmationText> shortMessages(){return List.of(
        new StudioComponents.ConfirmationText("地图 A 有未保存修改或未完成输入。",Variant.DEFAULT),
        new StudioComponents.ConfirmationText("放弃修改将恢复最近确认保存的内容。",Variant.MUTED));}
    private static ControlTree.Node node(StudioFrame frame,String id){return frame.nodes().stream().filter(value->value.id().equals(id)).findFirst().orElseThrow();}
    private static void assertHit(ControlTree tree,ControlTree.Node node){assertEquals(node.bounds(),node.visibleBounds());assertEquals(node.id(),tree.hit(node.bounds().x()+1,node.bounds().y()+1).orElseThrow().id());}

    @Test void shortConfirmationUsesThePrototypeCapAndContentHeightAtBothHostSizes(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.codePointCount(0,text.length())*9);
        var cancelled=new AtomicInteger();var discarded=new AtomicInteger();var saved=new AtomicInteger();
        var confirmation=confirmation(shortMessages(),cancelled,discarded,saved);
        for(var viewport:List.of(new Bounds(0,0,854,480),new Bounds(0,0,570,320))){
            frame.clear();var layout=ui.confirmationDialog("guard",confirmation,viewport,1,0,ignored->{});
            assertEquals(433,layout.bounds().width());assertTrue(layout.bounds().height()<180,"Two short paragraphs must not leave a large empty modal");
            assertEquals(0,layout.maximumScroll());assertEquals(layout.contentHeight(),layout.body().height());assertEquals(layout,ui.measureConfirmation(confirmation,viewport));
            assertEquals(Kind.DIALOG,frame.visual("guard").kind());
            assertEquals(Kind.SEPARATOR,frame.visual("guard/header-line").kind());assertEquals(Kind.SEPARATOR,frame.visual("guard/footer-line").kind());
            assertTrue(node(frame,"guard/header-line").bounds().y()<layout.body().y());assertTrue(layout.body().y()+layout.body().height()<node(frame,"guard/footer-line").bounds().y());
            var cancel=node(frame,CANCEL);var discard=node(frame,DISCARD);var save=node(frame,SAVE);
            assertTrue(cancel.bounds().x()<discard.bounds().x());assertTrue(discard.bounds().x()<save.bounds().x());assertEquals(cancel.bounds().y(),save.bounds().y());
            assertEquals(ui.captionWidth("取消",Icon.NONE),cancel.bounds().width());assertEquals(ui.captionWidth("保存并关闭",Icon.NONE),save.bounds().width());
            assertEquals(layout.bounds().x()+layout.bounds().width()-11,save.bounds().x()+save.bounds().width());assertEquals(Variant.PRIMARY,frame.visual(SAVE).variant());
            var tree=new ControlTree();tree.publish(frame.snapshot().nodes());for(String id:List.of(CLOSE,CANCEL,DISCARD,SAVE))assertHit(tree,node(frame,id));
        }
        assertEquals(0,cancelled.get());assertEquals(0,discarded.get());assertEquals(0,saved.get());
    }

    @Test void longOwnerAndMultilineReasonsAreMeasuredAndPublishedWithoutTruncation(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.codePointCount(0,text.length())*9);
        String owner="团队制作的超长文档名称🗺".repeat(12),reason="第一段原因：保留当前输入。\n第二段原因：确认保存结果后再关闭。".repeat(10);
        var messages=List.of(new StudioComponents.ConfirmationText(owner+" 有未保存修改。",Variant.DEFAULT),new StudioComponents.ConfirmationText(reason,Variant.DANGER),new StudioComponents.ConfirmationText("其他文档及其输入保持不变。",Variant.MUTED));
        var confirmation=confirmation(messages,new AtomicInteger(),new AtomicInteger(),new AtomicInteger());
        var layout=ui.confirmationDialog("guard",confirmation,new Bounds(0,0,570,320),1,0,ignored->{});
        assertTrue(layout.maximumScroll()>0);assertEquals(messages.size(),layout.paragraphs().size());
        for(int p=0;p<messages.size();p++){
            int index=p;var lines=frame.nodes().stream().filter(value->value.id().startsWith("guard/message/"+index+"/")).toList();
            assertEquals(messages.get(p).text().replace("\n",""),String.join("",lines.stream().map(ControlTree.Node::name).toList()));
            for(var line:lines){assertEquals(messages.get(p).variant(),frame.visual(line.id()).variant());assertTrue(line.name().codePointCount(0,line.name().length())*9<=line.bounds().width());}
            if(p>0){var previous=layout.paragraphs().get(p-1);assertTrue(layout.paragraphs().get(p).y()>=previous.y()+previous.lines().size()*13+8);}
        }
    }

    @Test void shortWindowScrollsOnlyTheBodyAndKeepsEveryFooterActionReachable(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*9);
        var cancelled=new AtomicInteger();var discarded=new AtomicInteger();var saved=new AtomicInteger();
        var messages=List.of(new StudioComponents.ConfirmationText("A long reason that must remain readable. ".repeat(90),Variant.DANGER),new StudioComponents.ConfirmationText("Last line remains reachable.",Variant.MUTED));
        var confirmation=confirmation(messages,cancelled,discarded,saved);var viewport=new Bounds(10,20,570,180);var offset=new AtomicInteger();
        var first=ui.confirmationDialog("guard",confirmation,viewport,1,0,offset::set);var footer=first.actions();
        assertTrue(first.maximumScroll()>0);assertTrue(first.body().height()>0);assertTrue(first.bounds().y()+first.bounds().height()<=viewport.y()+viewport.height());
        var tree=new ControlTree();tree.publish(frame.snapshot().nodes());var scroll=new StudioScrollState();
        assertTrue(scroll.wheel(frame.scrolls(),first.body().x()+2,first.body().y()+2,-1,tree.topLayer(),tree));assertTrue(offset.get()>0);
        var region=frame.scrolls().get(0);assertTrue(scroll.press(frame.scrolls(),region.track().x()+2,region.track().y()+1,tree.topLayer(),tree));
        assertTrue(scroll.release(region.track().y()+region.track().height()));assertEquals(first.maximumScroll(),offset.get());
        frame.clear();var last=ui.confirmationDialog("guard",confirmation,viewport,1,offset.get(),offset::set);tree.publish(frame.snapshot().nodes());
        assertEquals(first.bounds(),last.bounds());assertEquals(footer,last.actions());assertEquals(first.footer(),last.footer());
        var finalLine=node(frame,"guard/message/1/0");assertEquals(finalLine.bounds(),finalLine.visibleBounds());
        for(var line:frame.nodes())if(line.id().startsWith("guard/message/")&&line.visibleBounds().height()>0){assertTrue(line.visibleBounds().y()>=last.body().y());assertTrue(line.visibleBounds().y()+line.visibleBounds().height()<=last.body().y()+last.body().height());}
        for(String id:List.of(CLOSE,CANCEL,DISCARD,SAVE))assertHit(tree,node(frame,id));
        assertFalse(scroll.wheel(frame.scrolls(),node(frame,SAVE).bounds().x()+1,node(frame,SAVE).bounds().y()+1,-1,tree.topLayer(),tree));
        assertEquals(0,cancelled.get());assertEquals(0,discarded.get());assertEquals(0,saved.get());
    }

    @Test void narrowWindowWrapsCompactActionsWithoutOverlappingOrClippingThem(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*9);
        var confirmation=confirmation(shortMessages(),new AtomicInteger(),new AtomicInteger(),new AtomicInteger());
        var viewport=new Bounds(0,0,160,200);var layout=ui.confirmationDialog("guard",confirmation,viewport,1,0,ignored->{});
        assertTrue(confirmation.actions().stream().mapToInt(action->ui.captionWidth(action.label(),action.icon())).sum()+10>layout.bounds().width()-22,"The fixture must require more than one action row");
        assertTrue(node(frame,SAVE).bounds().y()>node(frame,CANCEL).bounds().y());assertTrue(layout.body().height()>0);
        var tree=new ControlTree();tree.publish(frame.snapshot().nodes());
        for(String id:List.of(CLOSE,CANCEL,DISCARD,SAVE)){var button=node(frame,id);assertHit(tree,button);assertTrue(button.bounds().x()>=layout.bounds().x());assertTrue(button.bounds().x()+button.bounds().width()<=layout.bounds().x()+layout.bounds().width());assertTrue(button.bounds().y()+button.bounds().height()<=viewport.height());}
        for(int i=0;i<layout.actions().size();i++)for(int j=i+1;j<layout.actions().size();j++){var intersection=layout.actions().get(i).intersect(layout.actions().get(j));assertTrue(intersection.width()==0||intersection.height()==0);}
    }

    @Test void cancelAndCloseStayIndependentOfSaveAndFocusRemainsWithinTheModal(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*9);
        var cancelled=new AtomicInteger();var discarded=new AtomicInteger();var saved=new AtomicInteger();var tree=new ControlTree();
        ui.button(StudioComponents.Action.plain("origin","Close document tab",()->{}),new Bounds(0,0,120,20),0,Kind.BUTTON);tree.publish(frame.snapshot().nodes());tree.focus("origin");String returnFocus=tree.focused().orElseThrow().id();
        var layout=ui.confirmationDialog("guard",confirmation(shortMessages(),cancelled,discarded,saved),new Bounds(0,0,570,320),1,0,ignored->{});tree.publish(frame.snapshot().nodes());
        assertTrue(tree.focused().isEmpty());tree.focus(layout.initialFocus());assertEquals(CANCEL,tree.focused().orElseThrow().id());
        for(int i=0;i<8;i++){tree.cycleFocus(false);assertEquals(1,tree.focused().orElseThrow().layer());assertNotEquals("origin",tree.focused().orElseThrow().id());}
        tree.focus(CANCEL);tree.cycleFocus(true);assertEquals(CLOSE,tree.focused().orElseThrow().id());
        frame.actions().get(CANCEL).run();frame.actions().get(CLOSE).run();assertEquals(2,cancelled.get());assertEquals(0,discarded.get());assertEquals(0,saved.get());
        frame.clear();ui.button(StudioComponents.Action.plain("origin","Close document tab",()->{}),new Bounds(0,0,120,20),0,Kind.BUTTON);tree.publish(frame.snapshot().nodes());tree.focus(returnFocus);assertEquals("origin",tree.focused().orElseThrow().id());
    }

    @Test void removingLongFailureTextReclaimsHeightAndClampsTheOldScrollPosition(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*9);var viewport=new Bounds(0,0,570,320);
        var messages=new ArrayList<>(shortMessages());messages.add(new StudioComponents.ConfirmationText("Failure details. ".repeat(100),Variant.DANGER));
        var longLayout=ui.confirmationDialog("guard",confirmation(messages,new AtomicInteger(),new AtomicInteger(),new AtomicInteger()),viewport,1,Integer.MAX_VALUE,ignored->{});
        assertTrue(longLayout.maximumScroll()>0);frame.clear();
        var shortLayout=ui.confirmationDialog("guard",confirmation(shortMessages(),new AtomicInteger(),new AtomicInteger(),new AtomicInteger()),viewport,1,longLayout.maximumScroll(),ignored->{});
        assertTrue(shortLayout.bounds().height()<longLayout.bounds().height());assertEquals(0,shortLayout.clampScroll(longLayout.maximumScroll()));assertTrue(frame.scrolls().isEmpty());
        assertEquals(node(frame,"guard/message/0/0").bounds(),node(frame,"guard/message/0/0").visibleBounds());
    }
}
