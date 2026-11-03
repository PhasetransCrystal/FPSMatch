package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import org.junit.jupiter.api.Test;

class StudioComponentsTest {
    @Test void shortCaptionsFitTheirPaintPaddingAndTabsDoNotStretchAcrossTheHost() {
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*9);
        ui.panel("objects","对象层级",new Bounds(0,0,180,100),0,List.of());
        var title=frame.nodes().stream().filter(n->n.id().equals("objects/title")).findFirst().orElseThrow();
        assertTrue(title.bounds().width()-12>=36,"A short Chinese panel name must fit the renderer's padding");
        ui.toolbar("tools",new Bounds(0,110,400,26),0,List.of(new StudioComponents.Action("add","添加",Icon.PLUS,Variant.DEFAULT,true,false,"",()->{})),false);
        var add=frame.nodes().stream().filter(n->n.id().equals("add")).findFirst().orElseThrow();
        assertTrue(add.bounds().width()-12-16>=18,"Icon advance must be included in caption measurement");
        ui.tabs("documents",new Bounds(0,150,800,23),0,List.of(StudioComponents.Action.plain("first","地图",()->{}),StudioComponents.Action.plain("second","商店",()->{})),true,0);
        var first=frame.nodes().stream().filter(n->n.id().equals("first")).findFirst().orElseThrow();
        var second=frame.nodes().stream().filter(n->n.id().equals("second")).findFirst().orElseThrow();
        assertTrue(first.bounds().width()<100);assertEquals(first.bounds().x()+first.bounds().width(),second.bounds().x());
    }
    @Test void overflowRevealsTheSelectedTabAndKeepsNavigationInsideTheStrip() {
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*9);
        var tabs=List.of(StudioComponents.Action.plain("one","First document",()->{}),StudioComponents.Action.plain("two","Second document",()->{}),new StudioComponents.Action("three","Third document",Icon.MAP,Variant.DEFAULT,true,true,"",()->{}));
        var bounds=new Bounds(10,20,160,23);ui.tabs("tabs",bounds,0,tabs,true,0);
        var selected=frame.nodes().stream().filter(n->n.id().equals("three")).findFirst().orElseThrow();assertTrue(selected.visibleBounds().width()>0);
        assertTrue(frame.nodes().stream().allMatch(n->n.visibleBounds().x()+n.visibleBounds().width()<=170));
    }
    @Test void plainActionOverflowTraversesEveryEntryWithoutInvokingBusinessActions() {
        var frame=new StudioFrame();var invoked=new AtomicInteger();var invalidated=new AtomicInteger();
        var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*9,invalidated::incrementAndGet);
        var actions=java.util.stream.IntStream.range(0,5).mapToObj(i->StudioComponents.Action.plain("action/"+i,"Action "+i,invoked::incrementAndGet)).toList();
        var tree=new ControlTree();
        for(int i=0;i<5;i++){
            frame.clear();ui.tabs("strip",new Bounds(10,20,i==2?60:90,23),0,actions,false,0);tree.publish(frame.snapshot().nodes());
            assertEquals(List.of("action/"+i),visibleActions(frame));
            assertEquals(0,invoked.get());
            var next=tree.snapshot().stream().filter(n->n.id().equals("strip/next")).findFirst().orElseThrow();
            assertEquals(i<4,next.enabled());
            if(i<4){assertEquals("strip/next",tree.hit(next.visibleBounds().x(),next.visibleBounds().y()).orElseThrow().id());frame.snapshot().actions().get("strip/next").run();}
        }
        for(int i=3;i>=0;i--){
            frame.snapshot().actions().get("strip/previous").run();frame.clear();ui.tabs("strip",new Bounds(10,20,70,23),0,actions,false,0);tree.publish(frame.snapshot().nodes());
            assertEquals(List.of("action/"+i),visibleActions(frame));assertEquals(0,invoked.get());
        }
        assertEquals(8,invalidated.get());
        frame.snapshot().actions().get("action/0").run();assertEquals(1,invoked.get());
    }
    @Test void titleBarPlainActionsRetainBrowsePositionAcrossRepublicationAndReordering() {
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*9);var calls=new AtomicInteger();
        var actions=java.util.stream.IntStream.range(0,5).mapToObj(i->StudioComponents.Action.plain("action/"+i,"Command "+i,calls::incrementAndGet)).toList();
        for(int i=0;i<4;i++){frame.clear();ui.titleBar("heading","Title",new Bounds(0,0,150,23),0,actions);assertEquals(List.of("action/"+i),visibleActions(frame));if(i<3)frame.snapshot().actions().get("heading/actions/next").run();}
        var reordered=List.of(actions.get(4),actions.get(3),actions.get(2),actions.get(1),actions.get(0));
        frame.clear();ui.titleBar("heading","Title",new Bounds(0,0,130,23),0,reordered);assertEquals(List.of("action/3"),visibleActions(frame));
        frame.snapshot().actions().get("heading/actions/previous").run();frame.clear();ui.titleBar("heading","Title",new Bounds(0,0,130,23),0,reordered);assertEquals(List.of("action/4"),visibleActions(frame));assertEquals(0,calls.get());
    }
    @Test void browsingDoesNotChangeSelectionAndANewSelectionIsRevealed() {
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*9);var calls=new AtomicInteger();
        var selectedLast=java.util.stream.IntStream.range(0,5).mapToObj(i->new StudioComponents.Action("action/"+i,"Document "+i,Icon.NONE,Variant.DEFAULT,true,i==4,"",calls::incrementAndGet)).toList();
        ui.tabs("strip",new Bounds(0,0,100,23),0,selectedLast,true,0);assertEquals(List.of("action/4"),visibleActions(frame));
        frame.snapshot().actions().get("strip/previous").run();frame.clear();ui.tabs("strip",new Bounds(0,0,90,23),0,selectedLast,true,0);assertEquals(List.of("action/3"),visibleActions(frame));assertEquals(0,calls.get());
        var selectedFirst=java.util.stream.IntStream.range(0,5).mapToObj(i->new StudioComponents.Action("action/"+i,"Document "+i,Icon.NONE,Variant.DEFAULT,true,i==0,"",calls::incrementAndGet)).toList();
        frame.clear();ui.tabs("strip",new Bounds(0,0,70,23),0,selectedFirst,true,0);assertEquals(List.of("action/0"),visibleActions(frame));assertTrue(frame.nodes().stream().filter(n->n.id().equals("action/0")).findFirst().orElseThrow().selected());
    }
    @Test void callerCanFeedReturnedOffsetIntoTheNextFrameWithoutResettingNavigation() {
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*9);
        var tabs=java.util.stream.IntStream.range(0,5).mapToObj(i->new StudioComponents.Action("action/"+i,"Document "+i,Icon.NONE,Variant.DEFAULT,true,i==4,"",()->fail("Browsing must not activate a document"))).toList();
        int offset=ui.tabs("documents",new Bounds(0,0,100,23),0,tabs,true,0);assertEquals(4,offset);
        for(int i=3;i>=0;i--){
            frame.snapshot().actions().get("documents/previous").run();frame.clear();offset=ui.tabs("documents",new Bounds(0,0,100,23),0,tabs,true,offset);assertEquals(i,offset);assertEquals(List.of("action/"+i),visibleActions(frame));
            frame.clear();offset=ui.tabs("documents",new Bounds(0,0,100,23),0,tabs,true,offset);assertEquals(i,offset,"An unrelated republish must not return to the selected document");assertEquals(List.of("action/"+i),visibleActions(frame));
        }
        frame.clear();offset=ui.tabs("documents",new Bounds(0,0,60,23),0,tabs,true,offset);assertEquals(0,offset);assertEquals(List.of("action/0"),visibleActions(frame));
    }
    private static List<String> visibleActions(StudioFrame frame){return frame.nodes().stream().filter(n->n.id().startsWith("action/")&&n.visibleBounds().width()>0).map(ControlTree.Node::id).toList();}
    @Test void narrowVectorRowsReflowWithoutLosingDiscardBlurGrouping() {
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);
        var fields=List.of("x","y","z").stream().map(id->new StudioComponents.Field(id,id,"1","1","",StudioComponents.FieldKind.TEXT,0,true,false,value->{},()->{},()->{})).toList();
        var actions=new StudioComponents.FieldActions("position","Apply","Discard",true,()->{},()->{},"");
        int height=ui.measureDocumentFields(fields,actions,150).height();
        assertEquals(height,ui.fieldRow("position",fields,actions,new Bounds(0,0,150,height),0));
        var inputs=frame.nodes().stream().filter(n->n.role()==ControlTree.Role.TEXT_FIELD).toList();assertEquals(3,inputs.size());
        assertEquals(inputs.get(0).bounds().y(),inputs.get(1).bounds().y());
        assertTrue(inputs.get(0).bounds().x()+inputs.get(0).bounds().width()<inputs.get(1).bounds().x());
        assertTrue(inputs.get(1).bounds().y()+inputs.get(1).bounds().height()<inputs.get(2).bounds().y());
        assertEquals("position/",frame.groups().get("z"));
        var discard=frame.nodes().stream().filter(n->n.id().equals("position/discard")).findFirst().orElseThrow();assertTrue(discard.bounds().y()+discard.bounds().height()<=height);
    }
    @Test void wizardReflowsNavigationAndPreservesCallerOwnedDraftOnCancel() {
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);var cancels=new AtomicInteger();var draft=new java.util.HashMap<String,String>();draft.put("name","unfinished");
        var area=ui.wizard("create",new Bounds(0,0,320,300),1,List.of(StudioComponents.Action.plain("new","New",()->{}),StudioComponents.Action.plain("open","Open",()->{})));
        assertTrue(area.content().y()>=area.navigation().y()+area.navigation().height());
        ui.form("fields",List.of(new StudioComponents.Field("name","Name",draft.get("name"),"","",StudioComponents.FieldKind.TEXT,0,true,true,value->draft.put("name",value),()->{},()->{})),area.content(),1,0,ignored->{});
        ui.menu("footer",List.of(StudioComponents.Action.plain("cancel","Cancel",cancels::incrementAndGet)),new Bounds(0,305,320,25),1);
        frame.actions().get("cancel").run();assertEquals(1,cancels.get());assertEquals("unfinished",draft.get("name"));
    }
    @Test void sparseGridRetainsSuppliedIdentityAndDoesNotCreateMissingDomainCells() {
        var frame = new StudioFrame();
        var ui = new StudioComponents(frame, StudioMetrics.compact(), String::length);
        var calls = new AtomicInteger();
        var columns = List.of(new StudioComponents.Column("category-a", "A", 5, null),
                new StudioComponents.Column("category-b", "B", 5, null));
        var slots = List.of(new StudioComponents.Slot("extension/custom-slot", "category-b", 3,
                "Custom item", List.of("price 12", "module 1"), false, true, false, false,
                true, "", calls::incrementAndGet));
        ui.slotGrid("grid", columns, slots, new Bounds(0, 0, 300, 400), 0, 0, 0,
                ignored -> fail("Composition must not navigate"), ignored -> fail("Composition must not scroll"),
                (first, last, total) -> first + "/" + last + "/" + total);
        var cards = frame.nodes().stream().filter(n -> frame.visual(n.id()).kind() == Kind.SLOT).toList();
        assertEquals(1, cards.size());
        var card = cards.get(0);
        assertEquals("extension/custom-slot", card.id());
        assertTrue(card.selected());
        assertEquals(0, calls.get(), "Composing an empty or configured slot must never mutate a document");
        var tree = new ControlTree();
        tree.publish(frame.nodes());
        assertEquals(card.id(), tree.hit(card.bounds().x() + 2, card.bounds().y() + 2).orElseThrow().id());
        frame.actions().get(card.id()).run();
        assertEquals(1, calls.get());
    }
    @Test void gridFitsTheLastCardWithoutDemandingATrailingGapAndUsesThoseBoundsForHits() {
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);
        var activated=new AtomicInteger(-1);var columns=List.of(new StudioComponents.Column("items","Items",5,null));
        var slots=java.util.stream.IntStream.range(0,5).mapToObj(i->new StudioComponents.Slot("slot/"+i,"items",i,"Item "+i,List.of("Price 100","Group 1"),false,false,false,false,true,"",()->activated.set(i))).toList();
        var result=ui.slotGrid("grid",columns,slots,new Bounds(10,20,160,268),0,0,0,ignored->{},ignored->{},(first,last,total)->first+"–"+last+" / "+total);
        assertEquals(5,result.rows().end());assertEquals(0,result.rows().maximum());
        assertFalse(frame.nodes().stream().anyMatch(n->n.id().equals("grid/scroll/track")));
        var tree=new ControlTree();tree.publish(frame.snapshot().nodes());
        for(int i=0;i<5;i++){
            String id="slot/"+i;var card=frame.nodes().stream().filter(n->n.id().equals(id)).findFirst().orElseThrow();
            assertEquals(44,card.bounds().height(),"Text keeps the existing card height");assertEquals(card.bounds(),card.visibleBounds());
            assertEquals(id,tree.hit(card.bounds().x()+2,card.bounds().y()+43).orElseThrow().id());
            frame.actions().get(id).run();assertEquals(i,activated.get());
            assertTrue(tree.hit(card.bounds().x()+2,card.bounds().y()+44).isEmpty(),"The gap must not activate a card");
        }
        assertEquals("1–5 / 5",frame.nodes().stream().filter(n->n.id().equals("grid/range")).findFirst().orElseThrow().name());
        frame.clear();result=ui.slotGrid("grid",columns,slots,new Bounds(10,20,160,266),0,0,0,ignored->{},ignored->{},(first,last,total)->first+"–"+last);assertEquals(5,result.rows().end(),"Five cards fit exactly at 236 logical pixels");
        frame.clear();result=ui.slotGrid("grid",columns,slots,new Bounds(10,20,160,265),0,0,1,ignored->{},ignored->{},(first,last,total)->first+"–"+last);
        assertEquals(1,result.rows().offset());assertEquals(4,result.rows().visible());assertEquals(5,result.rows().end());
        assertTrue(frame.nodes().stream().anyMatch(n->n.id().equals("grid/scroll/track")));
        var last=frame.nodes().stream().filter(n->n.id().equals("slot/4")).findFirst().orElseThrow();assertEquals(last.bounds(),last.visibleBounds());
    }
    @Test void drawerHeaderCommandsRemainDisjointAndIndividuallyReachable() {
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);var selected=new AtomicInteger();
        var actions=List.of(new StudioComponents.Action("menu","Menu",Icon.MORE,Variant.DEFAULT,true,false,"",()->selected.set(1)),new StudioComponents.Action("maximize","Maximize",Icon.MAXIMIZE,Variant.DEFAULT,true,false,"",()->selected.set(2)),new StudioComponents.Action("drawer/close","Close",Icon.CLOSE,Variant.DEFAULT,true,false,"",()->selected.set(3)));
        var body=ui.panel("drawer","Inspector",new Bounds(270,0,300,300),1,actions);var tree=new ControlTree();tree.publish(frame.snapshot().nodes());
        int end=0;
        for(int i=0;i<actions.size();i++){String id=actions.get(i).id();var node=frame.nodes().stream().filter(n->n.id().equals(id)).findFirst().orElseThrow();assertTrue(node.bounds().x()>=end);end=node.bounds().x()+node.bounds().width();assertTrue(node.bounds().y()+node.bounds().height()<=body.y());assertEquals(id,tree.hit(node.bounds().x()+10,node.bounds().y()+10).orElseThrow().id());frame.actions().get(id).run();assertEquals(i+1,selected.get());}
    }
    @Test void pairedFieldsUseOneRowWhenWideAndKeepIndependentInputsWhenNarrow() {
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);var raw=new java.util.HashMap<String,String>();raw.put("first","200");raw.put("second","50");
        var fields=List.of("first","second").stream().map(id->new StudioComponents.Field(id,id,raw.get(id),raw.get(id),"",StudioComponents.FieldKind.TEXT,0,true,false,value->raw.put(id,value),()->{},()->{})).toList();
        for(int width:new int[]{320,130}){
            frame.clear();int height=ui.fieldRowHeight(2,false,false,false,width);ui.formRows("prices",List.of(fields),new Bounds(0,0,width+8,height),0,0,ignored->{});var tree=new ControlTree();tree.publish(frame.snapshot().nodes());
            var first=frame.nodes().stream().filter(n->n.id().equals("first")).findFirst().orElseThrow();var second=frame.nodes().stream().filter(n->n.id().equals("second")).findFirst().orElseThrow();
            if(width==320){assertEquals(first.bounds().y(),second.bounds().y());assertTrue(first.bounds().x()+first.bounds().width()<second.bounds().x());}else assertTrue(first.bounds().y()+first.bounds().height()<second.bounds().y());
            for(var input:List.of(first,second)){assertEquals(input.id(),tree.hit(input.bounds().x()+3,input.bounds().y()+3).orElseThrow().id());assertTrue(input.bounds().y()+input.bounds().height()<=height);}
            frame.snapshot().changes().get("second").accept("75");assertEquals("200",raw.get("first"));assertEquals("75",raw.get("second"));
        }
    }
    @Test void taskDialogKeepsCancelReachableWhileItsBodyScrollsAtBothSizes() {
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*5);var calls=new AtomicInteger();var raw=new java.util.HashMap<String,String>();raw.put("source","unfinished path");
        for(int width:new int[]{240,640}){
            frame.clear();var area=ui.taskDialog("task","Import",new Bounds(0,0,width,width==240?180:430),1,List.of(new StudioComponents.Action("task/step/0","Source",Icon.NONE,Variant.DEFAULT,false,true,"",()->fail("Step labels cannot start work")),new StudioComponents.Action("task/step/1","Preflight",Icon.NONE,Variant.DEFAULT,false,false,"",()->{})),new StudioComponents.Action("task/close","Close",Icon.CLOSE,Variant.DEFAULT,true,false,"",calls::incrementAndGet));
            var fields=java.util.stream.IntStream.range(0,12).mapToObj(i->new StudioComponents.Field("source/"+i,"Source "+i,raw.get("source"),"","",StudioComponents.FieldKind.TEXT,0,true,false,value->raw.put("source",value),()->{},()->{})).toList();
            ui.form("task/fields",fields,area.content(),1,100,ignored->{});ui.toolbar("task/footer",area.footer(),1,List.of(StudioComponents.Action.plain("task/cancel","Cancel",calls::incrementAndGet)),false);
            var tree=new ControlTree();tree.publish(frame.snapshot().nodes());
            for(String id:List.of("task/close","task/cancel")){var button=frame.nodes().stream().filter(n->n.id().equals(id)).findFirst().orElseThrow();assertEquals(id,tree.hit(button.bounds().x()+2,button.bounds().y()+2).orElseThrow().id());frame.actions().get(id).run();}
            for(var input:frame.nodes())if(input.role()==ControlTree.Role.TEXT_FIELD){
                var visible=input.visibleBounds();
                if(visible.width()>0&&visible.height()>0){assertTrue(visible.y()>=area.content().y());assertTrue(visible.y()+visible.height()<=area.content().y()+area.content().height());}
                else assertFalse(input.focusable());
            }
            assertEquals("unfinished path",raw.get("source"));
        }
        assertEquals(4,calls.get());
    }
    @Test void compactCardCommandsStayReachableWithoutLosingTheFullContext() {
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*5);var invoked=new AtomicInteger();
        var commands=List.of(StudioComponents.Action.plain("usage","Used by 2",invoked::incrementAndGet),new StudioComponents.Action("menu","Module actions",Icon.MORE,Variant.DEFAULT,true,false,"",invoked::incrementAndGet),new StudioComponents.Action("detach","Detach",Icon.CLEAR,Variant.DEFAULT,true,false,"",invoked::incrementAndGet),new StudioComponents.Action("up","Move Up",Icon.UP,Variant.DEFAULT,true,false,"",invoked::incrementAndGet),new StudioComponents.Action("down","Move Down",Icon.DOWN,Variant.DEFAULT,true,false,"",invoked::incrementAndGet));
        String context="Priority 1; shared changes affect every reference; reference order does not change execution priority";
        var header=new StudioComponents.CardHeader("Rule",context,true,true,List.of(commands),()->{});int height=ui.cardHeaderHeight(header,200);assertEquals(29+ui.wrap(context,188,TextSize.SMALL).size()*13+26,height);
        ui.detailCard("rule",header,new Bounds(0,0,200,height),0);var tree=new ControlTree();tree.publish(frame.snapshot().nodes());
        var contextLines=frame.nodes().stream().filter(n->n.id().startsWith("rule/context/")).toList();assertTrue(contextLines.size()>1);assertEquals(context,String.join(" ",contextLines.stream().map(ControlTree.Node::name).toList()),"Shared wrapping breaks at words and omits whitespace at line edges");
        for(var line:contextLines){assertEquals(line.bounds(),line.visibleBounds());assertTrue(line.name().length()*5*8d/9<=line.bounds().width());}
        int previousEnd=0;
        for(var command:commands){var node=frame.nodes().stream().filter(n->n.id().equals(command.id())).findFirst().orElseThrow();assertTrue(node.bounds().x()>=previousEnd);previousEnd=node.bounds().x()+node.bounds().width();assertTrue(node.bounds().y()+node.bounds().height()<=height);assertEquals(command.id(),tree.hit(node.bounds().x()+2,node.bounds().y()+2).orElseThrow().id());frame.actions().get(command.id()).run();}
        assertEquals(5,invoked.get());
        var chineseUi=new StudioComponents(new StudioFrame(),StudioMetrics.compact(),text->text.codePointCount(0,text.length())*9);
        assertEquals(2,chineseUi.wrap("优先级 1 · 引用槽位 1；共享修改影响全部引用，引用顺序不改变运行优先级。",198,TextSize.SMALL).size(),"The normal sidebar keeps the complete priority rule in two lines");
    }
    @Test void taskFailureNoticeReservesWrappedReasonAndOneReachableEventAboveCancel() {
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*5);var details=new AtomicInteger();
        String reason="The selected source must be an absolute path to an existing regular file. Correct the source and run preflight again.";
        for(int width:new int[]{240,640}){
            frame.clear();int noticeHeight=ui.noticeHeight(reason,width-16),footerHeight=noticeHeight+40;
            var area=ui.taskDialog("task","Import",new Bounds(0,0,width,300),1,List.of(),new StudioComponents.Action("close","Close",Icon.CLOSE,Variant.DEFAULT,true,false,"",()->{}),footerHeight);
            ui.notice("failure",new StudioComponents.Action("failure/details","Diagnostic 7",Icon.WARNING,Variant.DANGER,true,false,"",details::incrementAndGet),reason,new Bounds(area.footer().x(),area.footer().y()+4,area.footer().width(),noticeHeight),1);
            ui.button(StudioComponents.Action.plain("cancel","Cancel",()->{}),new Bounds(width-100,270,86,20),1,Kind.BUTTON);
            var tree=new ControlTree();tree.publish(frame.snapshot().nodes());var event=frame.nodes().stream().filter(n->n.id().equals("failure/details")).findFirst().orElseThrow();
            assertEquals(1,frame.nodes().stream().filter(n->n.name().equals("Diagnostic 7")).count());assertEquals("failure/details",tree.hit(event.bounds().x()+3,event.bounds().y()+3).orElseThrow().id());frame.actions().get(event.id()).run();
            var lines=frame.nodes().stream().filter(n->n.id().startsWith("failure/reason/")).toList();
            assertEquals(reason,String.join(" ",lines.stream().map(ControlTree.Node::name).toList()),"Shared wrapping replaces spaces at word boundaries with line breaks; every word and punctuation mark must remain");
            if(width==240)assertTrue(lines.size()>1,"The narrow dialog must exercise word wrapping");
            assertEquals(noticeHeight,24+lines.size()*13);
            for(var line:lines){assertEquals(line.bounds(),line.visibleBounds());assertTrue(line.name().length()*5<=line.bounds().width());assertTrue(line.bounds().y()+line.bounds().height()<=270);assertTrue(line.bounds().y()>=area.content().y()+area.content().height());}
            assertEquals("cancel",tree.hit(width-98,272).orElseThrow().id());
        }
        assertEquals(2,details.get());
    }
    @Test void formDocumentKeepsNavigationReachableAboveClippedFieldsAtBothWidths(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*5);
        var backs=new AtomicInteger();var pageChanges=new AtomicInteger();var query=new java.util.concurrent.atomic.AtomicReference<>("");
        for(int width:new int[]{240,640}){
            frame.clear();var bounds=new Bounds(20,30,width,180);
            var body=ui.formDocument("rules",List.of("Project","Map","Map rules"),"Map rules",
                    new StudioComponents.Action("rules/back","Return to scene",Icon.PREVIOUS,Variant.DEFAULT,true,false,"",backs::incrementAndGet),
                    List.of(new StudioComponents.Action("rules/overview","Overview",Icon.NONE,Variant.DEFAULT,true,true,"",pageChanges::incrementAndGet),StudioComponents.Action.plain("rules/game","Game rules",pageChanges::incrementAndGet)),
                    "Search fields","unfinished search",query::set,bounds,0);
            try(var ignored=frame.clipped(body)){
                ui.input("rules/field","Exact field","-",new Bounds(body.x()+6,body.y()+body.height()-8,body.width()-22,20),0,true,true,value->{});
            }
            var tree=new ControlTree();tree.publish(frame.snapshot().nodes());
            for(String id:List.of("rules/back","rules/overview","rules/game","rules/search")){
                var control=frame.nodes().stream().filter(node->node.id().equals(id)).findFirst().orElseThrow();
                assertEquals(control.bounds(),control.visibleBounds());assertTrue(control.bounds().y()+control.bounds().height()<=body.y());
                assertEquals(id,tree.hit(control.bounds().x()+2,control.bounds().y()+2).orElseThrow().id());
            }
            var input=frame.nodes().stream().filter(node->node.id().equals("rules/field")).findFirst().orElseThrow();
            assertEquals("-",input.value());assertEquals(8,input.visibleBounds().height());assertTrue(body.height()>20);
            frame.actions().get("rules/back").run();frame.actions().get("rules/game").run();frame.changes().get("rules/search").accept("duration");
        }
        assertEquals(2,backs.get());assertEquals(2,pageChanges.get());assertEquals("duration",query.get());
    }

    @Test void shortFormDocumentsKeepBackPagesSearchAndScrollableBodyInsideTheirPanel(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*5);
        for(int height:new int[]{80,120,160,240}){
            frame.clear();var bounds=new Bounds(15,20,240,height);
            var body=ui.formDocument("rules",List.of("A long project path that wraps","Map"),"Rules",
                    new StudioComponents.Action("back","Return to scene",Icon.PREVIOUS,Variant.DEFAULT,true,false,"",()->{}),
                    List.of(StudioComponents.Action.plain("overview","Overview",()->{}),StudioComponents.Action.plain("game","Rules",()->{})),
                    "Search fields","",value->{},bounds,0);
            assertTrue(body.height()>=20,"Short forms must retain a field viewport");
            assertTrue(body.y()+body.height()<=bounds.y()+bounds.height());
            var tree=new ControlTree();tree.publish(frame.snapshot().nodes());
            for(String id:List.of("back","overview","game","rules/search")){
                var node=frame.nodes().stream().filter(value->value.id().equals(id)).findFirst().orElseThrow();
                assertEquals(node.bounds(),node.visibleBounds());
                assertTrue(node.bounds().y()+node.bounds().height()<=body.y());
                assertEquals(id,tree.hit(node.bounds().x()+1,node.bounds().y()+1).orElseThrow().id());
            }
        }
    }

    @Test void longDocumentIdentityKeepsNavigationAndBodyUsable(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*5);
        String identity="Long project and document identity ".repeat(30);
        for(int height:List.of(80,120,160,180)){
            frame.clear();var body=ui.formDocument("form",List.of(identity,identity),identity,StudioComponents.Action.plain("back","Back",()->{}),List.of(StudioComponents.Action.plain("page","Rules",()->{})),"Search","",value->{},new Bounds(0,0,140,height),0);
            assertTrue(body.height()>=20);
            for(String id:List.of("back","page","form/search")){var control=frame.nodes().stream().filter(node->node.id().equals(id)).findFirst().orElseThrow();assertEquals(control.bounds(),control.visibleBounds());assertTrue(control.bounds().y()+control.bounds().height()<=body.y());}
            if(height>=170)assertEquals(identity+" › "+identity,frame.details().get("form/path"));
        }
        frame.clear();assertEquals(533,ui.formDocument("form",List.of(),"Rules",StudioComponents.Action.plain("back","Back",()->{}),List.of(),"Search","",value->{},new Bounds(0,0,1000,400),0).width());
    }

    @Test void formColumnsKeepExactFieldIdentityAndSeparateScrollFromHeaderAndOtherForms(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*5);
        var firstScroll=new AtomicInteger();var secondScroll=new AtomicInteger();
        for(int width:new int[]{240,395,396,428,800}){
            firstScroll.set(0);secondScroll.set(0);
            frame.clear();var bounds=new Bounds(20,30,width,140);var columns=ui.formDocumentColumns(bounds,true);
            assertEquals(width<396?1:2,columns.size());
            for(int i=0;i<columns.size();i++){
                var box=columns.get(i);String id="form/map/rules/field/"+i;
                ui.fieldRow(id,List.of(new StudioComponents.Field(id,"Field "+i,"-","-","",StudioComponents.FieldKind.TEXT,0,true,true,value->{},()->{},()->{})),null,box,0);
            }
            ui.pixelScroll("first/scroll",bounds,0,500,0,firstScroll::set);
            ui.pixelScroll("second/scroll",new Bounds(20+width+20,30,100,140),0,500,0,secondScroll::set);
            var tree=new ControlTree();tree.publish(frame.snapshot().nodes());
            for(int i=0;i<columns.size();i++){
                String id="form/map/rules/field/"+i;var node=frame.nodes().stream().filter(value->value.id().equals(id)).findFirst().orElseThrow();
                assertEquals("-",node.value());assertEquals(id,tree.hit(node.bounds().x()+2,node.bounds().y()+2).orElseThrow().id());
            }
            assertEquals(2,frame.scrolls().size());
            assertEquals(bounds,frame.scrolls().get(0).viewport());
            var scrolling=new StudioScrollState();
            assertTrue(scrolling.wheel(frame.scrolls(),bounds.x()+2,bounds.y()+2,-1,0,tree));
            assertEquals(20,firstScroll.get());assertEquals(0,secondScroll.get());
            assertFalse(scrolling.wheel(frame.scrolls(),bounds.x()+2,bounds.y()-1,-1,0,tree));
            assertTrue(scrolling.wheel(frame.scrolls(),bounds.x()+width+22,bounds.y()+2,-1,0,tree));
            assertEquals(20,firstScroll.get());assertEquals(20,secondScroll.get());
        }
    }

    @Test void fieldRevealGeometryMatchesRenderedInputsAfterCompactRowsStack(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*5);
        var kinds=List.of(StudioComponents.FieldKind.TEXT,StudioComponents.FieldKind.TOGGLE,StudioComponents.FieldKind.CHOICE);
        var fields=java.util.stream.IntStream.range(0,kinds.size()).mapToObj(i->new StudioComponents.Field("field/"+i,"A long field label "+i,"raw","raw","unit",kinds.get(i),0,true,false,value->{},()->{},()->{})).toList();
        for(int width:new int[]{150,280}){
            frame.clear();var bounds=new Bounds(10,20,width,180);ui.fieldRow("row",fields,null,bounds,0);
            var measured=ui.measureDocumentFields(fields,null,width);
            for(int i=0;i<kinds.size();i++){
                String id="field/"+i;var actual=frame.nodes().stream().filter(node->node.id().equals(id)).findFirst().orElseThrow();
                var control=measured.fields().get(i).control();
                assertEquals(new Bounds(control.x()+bounds.x(),control.y()+bounds.y(),control.width(),control.height()),actual.bounds());
                assertTrue(frame.details().get(id).contains(fields.get(i).label()));
                assertEquals(fields.get(i).label()+" · "+fields.get(i).hint(),frame.details().get(id));
                if(kinds.get(i)==StudioComponents.FieldKind.CHOICE)assertEquals(fields.get(i).display(),actual.name());
            }
        }
    }

    @Test void host77DefaultWidthFitsTwoReadableColumnsAndCompactHostStaysSingleColumn(){
        var ui=new StudioComponents(new StudioFrame(),StudioMetrics.compact(),text->text.length()*9);
        // The 1280/GUI2/.75 host has about 428 internal units for the central field rows.
        var wide=ui.formDocumentColumns(new Bounds(0,0,428,300),true);
        assertEquals(2,wide.size());assertTrue(wide.stream().allMatch(column->column.width()>=192));
        // At 854 the shell uses drawers: a wider central area must still use its compact mode.
        assertEquals(1,ui.formDocumentColumns(new Bounds(0,0,520,200),false).size());
        assertEquals(1,ui.formDocumentColumns(new Bounds(0,0,330,200),true).size());
    }

    @Test void measuredDocumentFieldsReserveLongLabelsDependenciesActionsAndErrors(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.codePointCount(0,text.length())*9);
        var fields=List.of(new StudioComponents.Field("field","较长的规则字段标签必须根据当前列宽完整换行并保留所有说明文字","abc","abc","启用自动开始比赛后才可编辑；保留当前未完成输入。",StudioComponents.FieldKind.TEXT,0,false,true,value->{},()->{},()->{}));
        var actions=new StudioComponents.FieldActions("row","应用","放弃",false,()->{},()->{},"字段无效；保留原文，请检查类型和值域，并修正全部输入内容后重新提交。");
        for(int width:new int[]{120,202,428}){
            frame.clear();var layout=ui.measureDocumentFields(fields,actions,width);var field=layout.fields().get(0);
            ui.documentFields("row",fields,actions,layout,20,30,0);
            var tree=new ControlTree();tree.publish(frame.snapshot().nodes());
            var control=frame.nodes().stream().filter(node->node.id().equals("field")).findFirst().orElseThrow();
            assertEquals(new Bounds(20+field.control().x(),30+field.control().y(),field.control().width(),field.control().height()),control.bounds());
            assertTrue(field.control().y()>=field.label().y()+field.label().height());
            assertTrue(field.hint().y()>=field.control().y()+field.control().height());
            assertTrue(layout.apply().y()>=field.hint().y()+field.hint().height());
            assertTrue(layout.error().y()>=layout.discard().y()+layout.discard().height());
            assertEquals(layout.height(),layout.error().y()+layout.error().height()+ui.metrics().fieldGap());
            assertEquals("field",tree.hit(control.bounds().x()+1,control.bounds().y()+1).orElseThrow().id());
            assertEquals("abc",control.value());assertTrue(control.readOnly());
            assertTrue(layout.discard().x()>=layout.apply().x()+layout.apply().width()||layout.discard().y()>=layout.apply().y()+layout.apply().height());
            if(width==202){assertTrue(field.labelLines().size()>1);assertTrue(field.hintLines().size()>1);assertTrue(layout.errorLines().size()>1);}
        }
    }

    @Test void longToggleLabelsAndCompactFieldsUseTheirMeasuredControlGeometry(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*9);
        var fields=List.of(new StudioComponents.Field("toggle","允许较长名称的条件生效并保留完整规则说明","true","开启","依赖条件说明",StudioComponents.FieldKind.TOGGLE,0,true,false,value->{},()->{},()->{}),
                new StudioComponents.Field("value","数值","-","-","刻",StudioComponents.FieldKind.TEXT,0,true,true,value->{},()->{},()->{}));
        for(int width:new int[]{202,428}){
            frame.clear();var layout=ui.measureDocumentFields(fields,null,width);ui.documentFields("row",fields,null,layout,0,0,0);
            for(int i=0;i<fields.size();i++){
                var geometry=layout.fields().get(i);String id=fields.get(i).id();
                assertEquals(geometry.control(),frame.nodes().stream().filter(node->node.id().equals(id)).findFirst().orElseThrow().bounds());
                assertTrue(geometry.hint().y()+geometry.hint().height()<=layout.height());
            }
            var toggle=layout.fields().get(0);
            assertTrue(toggle.label().x()+toggle.label().width()<=toggle.control().x()||toggle.label().y()+toggle.label().height()<=toggle.control().y());
        }
    }

    @Test void publishedFormSearchConsumesTextAcrossFramesAndInactiveWindowTicks(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*5);
        var tree=new ControlTree();var input=new StudioComponents.InputBridge(tree);
        var query=new java.util.concurrent.atomic.AtomicReference<>("");
        Runnable publish=()->{
            frame.clear();ui.formDocument("form",List.of("Project","Map"),"Rules",StudioComponents.Action.plain("back","Back",()->{}),List.of(),"Search fields",query.get(),query::set,new Bounds(0,0,428,240),0);
            input.publish(frame.snapshot());
        };
        publish.run();input.windowActive(false);input.focus("form/search");
        for(char character:"no-match-77".toCharArray()){
            publish.run();input.windowActive(false);input.windowActive(false);
            assertTrue(input.insert(String.valueOf(character)));
        }
        assertEquals("no-match-77",query.get());
        publish.run();assertEquals("form/search",tree.focused().orElseThrow().id());assertEquals("no-match-77",input.visibleText().get("form/search").text());
        input.focus("back");assertFalse(input.insert("ignored"));assertEquals("no-match-77",query.get());
    }

    @Test void programmaticProblemFocusSurvivesInactiveTicksAndDiscardNeverAppliesRaw(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*5);
        var tree=new ControlTree();var input=new StudioComponents.InputBridge(tree);var commits=new AtomicInteger();
        String id="field/form/document/rules/page/rules/group/timing/exact-address",row="field-row/form/document/rules/timing";
        var raw=new java.util.concurrent.atomic.AtomicReference<>("abc");
        var fields=List.of(new StudioComponents.Field(id,"Duration",raw.get(),raw.get(),"ticks",StudioComponents.FieldKind.TEXT,0,true,true,raw::set,()->{},commits::incrementAndGet));
        var actions=new StudioComponents.FieldActions(row,"Apply","Discard",true,commits::incrementAndGet,()->raw.set("6001"),"Invalid field");
        var layout=ui.measureDocumentFields(fields,actions,202);ui.documentFields(row,fields,actions,layout,0,0,0);input.publish(frame.snapshot());
        tree.focus(id);input.windowActive(false);input.windowActive(false);input.publish(frame.snapshot());
        assertEquals(id,tree.focused().orElseThrow().id());assertEquals(1,commits.get());assertEquals("abc",raw.get());
        input.focus(row+"/discard");frame.actions().get(row+"/discard").run();
        assertEquals(1,commits.get());assertEquals("6001",raw.get());
    }

    @Test void ordinaryFocusAndModalChangesCommitWithoutConsumingReadOnlyOrUnfocusedText(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),String::length);
        var tree=new ControlTree();var input=new StudioComponents.InputBridge(tree);var commits=new AtomicInteger();
        var raw=new java.util.concurrent.atomic.AtomicReference<>("initial");
        Runnable publish=()->{
            frame.clear();
            ui.input("editable","Editable",raw.get(),new Bounds(0,0,180,20),0,true,false,raw::set);
            frame.blur("editable","row/",commits::incrementAndGet);
            ui.input("readonly","Read only","locked",new Bounds(0,30,180,20),0,false,false,value->fail("Read-only input must not dispatch changes"));
            input.publish(frame.snapshot());
        };
        publish.run();assertFalse(input.insert("ignored"));
        input.focus("editable");assertTrue(input.insert("!"));publish.run();assertEquals(0,commits.get());
        assertEquals("initial!",raw.get());assertEquals("editable",tree.focused().orElseThrow().id());
        input.focus("readonly");assertEquals(1,commits.get());assertFalse(input.insert("ignored"));
        input.focus("readonly");assertEquals(1,commits.get());
        input.focus("editable");input.cycleFocus(false);assertEquals(2,commits.get());
        input.focus("editable");
        ui.surface("modal",new Bounds(0,0,180,80),1,Kind.DIALOG);input.publish(frame.snapshot());
        assertEquals(3,commits.get());assertTrue(tree.focused().isEmpty());assertFalse(input.insert("ignored"));
        input.publish(frame.snapshot());assertEquals(3,commits.get());assertEquals("initial!",raw.get());
    }

    @Test void measuredTaskSourcesKeepEveryInputReachableAndFooterFixedAtHostSizes(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*9);
        var fields=java.util.stream.IntStream.range(0,16).mapToObj(i->new StudioComponents.Field("source/"+i,"适配器声明的较长来源标签必须完整显示，填写来源文件或目录的绝对路径 "+i,"unfinished/path","unfinished/path","",StudioComponents.FieldKind.TEXT,0,true,false,value->{},()->{},()->{})).toList();
        var adapters=List.of(StudioComponents.Action.plain("adapter/first","First adapter",()->{}),StudioComponents.Action.plain("adapter/second","Second adapter",()->{}));
        for(int width:new int[]{660,546,240}){
            int height=width==660?430:width==546?296:180;
            var bounds=new Bounds(10,20,width,height);
            frame.clear();var area=ui.taskDialog("task","Import",bounds,1,List.of(),StudioComponents.Action.plain("close","Close",()->{}));
            var viewport=area.content();int contentWidth=viewport.width()-10;
            var split=ui.wizardArea(new Bounds(0,0,contentWidth,Math.max(34,viewport.height())));
            var layouts=fields.stream().map(field->ui.measureDocumentFields(List.of(field),null,split.content().width())).toList();
            int contentHeight=split.content().y()+layouts.stream().mapToInt(StudioComponents.FieldRowLayout::height).sum();
            for(int target:new int[]{0,15}){
                int targetY=split.content().y();for(int i=0;i<target;i++)targetY+=layouts.get(i).height();
                var control=layouts.get(target).fields().get(0).control();
                int offset=Math.min(contentHeight-viewport.height(),Math.max(0,targetY+control.y()+control.height()-viewport.height()));
                frame.clear();var current=ui.taskDialog("task","Import",bounds,1,List.of(),StudioComponents.Action.plain("close","Close",()->{}));assertEquals(area,current);
                try(var ignored=frame.clipped(viewport)){
                    var sourceBounds=new Bounds(viewport.x(),viewport.y()-offset,contentWidth,contentHeight);
                    var measured=ui.wizardArea(sourceBounds);var painted=ui.wizard("source",sourceBounds,1,adapters);assertEquals(measured,painted);
                    int y=painted.content().y();for(int i=0;i<fields.size();i++){ui.documentFields("source-row/"+i,List.of(fields.get(i)),null,layouts.get(i),painted.content().x(),y,1);y+=layouts.get(i).height();}
                }
                ui.pixelScroll("source/scroll",viewport,offset,contentHeight,1,value->{});
                var footer=new Bounds(area.footer().x()+6,area.footer().y()+area.footer().height()-26,100,20);
                ui.button(StudioComponents.Action.plain("back","Back to sources",()->{}),footer,1,Kind.BUTTON);
                var tree=new ControlTree();tree.publish(frame.snapshot().nodes());String targetId=fields.get(target).id();
                var input=tree.snapshot().stream().filter(node->node.id().equals(targetId)).findFirst().orElseThrow();
                assertEquals(input.bounds(),input.visibleBounds());assertEquals(targetId,tree.hit(input.bounds().x()+1,input.bounds().y()+1).orElseThrow().id());
                assertEquals("back",tree.hit(footer.x()+1,footer.y()+1).orElseThrow().id());assertTrue(input.bounds().y()+input.bounds().height()<=area.footer().y());
            }
        }
    }

    @Test void longTaskDiagnosticAndDetailsScrollWithoutRepeatingReasonOrMovingFooter(){
        var frame=new StudioFrame();var ui=new StudioComponents(frame,StudioMetrics.compact(),text->text.length()*9);var details=new AtomicInteger();
        String reason="找不到来源文件。保留输入路径，请检查文件位置并返回来源修改后重新预检。".repeat(48),summary="Operation: inspecting. Exception category: IOException. Details remain in the local log.";
        for(int width:new int[]{660,546}){
            var bounds=new Bounds(10,20,width,width==660?430:296);
            frame.clear();var area=ui.taskDialog("task","Import",bounds,1,List.of(),StudioComponents.Action.plain("close","Close",()->{}));var viewport=area.content();int contentWidth=viewport.width()-10;
            int notice=ui.noticeHeight(reason,contentWidth),summaryHeight=ui.wrap(summary,contentWidth-12,TextSize.SMALL).size()*13;
            var name=new StudioComponents.Field("name","Document name","retained draft","retained draft","",StudioComponents.FieldKind.TEXT,0,true,false,value->{},()->{},()->{});var layout=ui.measureDocumentFields(List.of(name),null,contentWidth);
            int contentHeight=notice+8+summaryHeight+12+layout.height();
            assertTrue(contentHeight>viewport.height(),"The fixture must overflow the body at both host sizes");
            for(int offset:new int[]{0,contentHeight-viewport.height()}){
                frame.clear();ui.taskDialog("task","Import",bounds,1,List.of(),StudioComponents.Action.plain("close","Close",()->{}));
                try(var ignored=frame.clipped(viewport)){
                    int y=viewport.y()-offset;
                    ui.notice("failure",StudioComponents.Action.plain("event","Diagnostic event 7",details::incrementAndGet),reason,new Bounds(viewport.x(),y,contentWidth,notice),1);y+=notice+8;
                    ui.paragraph("failure/details",summary,new Bounds(viewport.x()+6,y,contentWidth-12,summaryHeight),1,TextSize.SMALL,Variant.MUTED);y+=summaryHeight+12;
                    ui.documentFields("name-row",List.of(name),null,layout,viewport.x(),y,1);
                }
                ui.pixelScroll("report/scroll",viewport,offset,contentHeight,1,value->{});
                var footer=new Bounds(area.footer().x()+6,area.footer().y()+area.footer().height()-26,100,20);ui.button(StudioComponents.Action.plain("back","Back to sources",()->{}),footer,1,Kind.BUTTON);
                assertEquals(reason,frame.nodes().stream().filter(node->node.id().startsWith("failure/reason/")).map(ControlTree.Node::name).collect(java.util.stream.Collectors.joining()));
                var tree=new ControlTree();tree.publish(frame.snapshot().nodes());assertEquals("back",tree.hit(footer.x()+1,footer.y()+1).orElseThrow().id());
                String visibleId=offset==0?"event":"name";var visible=tree.snapshot().stream().filter(node->node.id().equals(visibleId)).findFirst().orElseThrow();assertEquals(visible.bounds(),visible.visibleBounds());assertEquals(visibleId,tree.hit(visible.bounds().x()+1,visible.bounds().y()+1).orElseThrow().id());
                if(offset==0)frame.actions().get("event").run();
            }
        }
        assertEquals(2,details.get());
    }
}
