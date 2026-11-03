package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.*;
import org.junit.jupiter.api.Test;

class StudioTooltipTest {
    @Test void visibleLabelsAndBlankHintsDoNotProduceTooltips(){
        assertEquals("",StudioTooltip.content(node(Role.TEXT_FIELD,"Maximum purchases","2",""),"",false,false));
        assertEquals("",StudioTooltip.content(node(Role.LABEL,"Maximum purchases","",""),"Maximum purchases",false,false));
    }
    @Test void editingSuppressesEvenTruncatedValuesAndHints(){
        assertEquals("",StudioTooltip.content(node(Role.TEXT_FIELD,"Count","123456789","Invalid value"),"Choose a count",true,true));
        assertEquals("123456789",StudioTooltip.content(node(Role.TEXT_FIELD,"Count","123456789",""),"",true,false));
    }
    @Test void repeatedReasonAndDetailAppearOnlyOnceAndLongContentKeepsLineBreaks(){
        var hint="Stage first, then apply; empty item removes content";
        assertEquals("Apple\n"+hint,StudioTooltip.content(node(Role.BUTTON,"Apple","",hint),hint,true,false));
        assertEquals(hint,StudioTooltip.content(node(Role.BUTTON,"Apple","",hint),hint,false,false));
        assertEquals("Apple · "+hint,StudioTooltip.content(node(Role.BUTTON,"Apple · "+hint,"",hint),hint,true,false));
    }
    private static Node node(Role role,String name,String value,String reason){return new Node("field",role,name,value,new Bounds(0,0,120,20),true,false,false,reason,0);}
}
