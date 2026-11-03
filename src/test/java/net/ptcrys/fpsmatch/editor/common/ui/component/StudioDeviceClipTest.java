package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;

import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import org.junit.jupiter.api.Test;

class StudioDeviceClipTest {
    @Test void fractionalDensityAndGuiScaleRoundOnlyAtFramebufferEdges(){
        double scale=.75*2;
        assertEquals(new Bounds(1,477,2,2),StudioDeviceClip.pixels(new Bounds(1,1,1,1),scale,480));
        assertEquals(new Bounds(4,459,11,14),StudioDeviceClip.pixels(new Bounds(3,5,7,9),scale,480));
    }

    @Test void negativeCoordinatesRoundOutwardsBeforeFlippingTheVerticalOrigin(){
        assertEquals(new Bounds(-5,483,4,5),StudioDeviceClip.pixels(new Bounds(-3,-5,2,3),1.5,480));
    }

    @Test void zeroAreaRemainsEmptyEvenWhenItsEdgesFallBetweenDevicePixels(){
        var empty=StudioDeviceClip.pixels(new Bounds(1,1,0,0),1.5,480);
        assertEquals(0,empty.width());assertEquals(0,empty.height());
        var zeroWidth=StudioDeviceClip.pixels(new Bounds(1,1,0,3),1.5,480);
        assertEquals(0,zeroWidth.width());assertEquals(5,zeroWidth.height());
        var zeroHeight=StudioDeviceClip.pixels(new Bounds(1,1,3,0),1.5,480);
        assertEquals(5,zeroHeight.width());assertEquals(0,zeroHeight.height());
    }

    @Test void differentDeviceScalesKeepLogicalPositionAndExtentTogether(){
        var logical=new Bounds(2,4,6,8);
        assertEquals(new Bounds(1,94,3,4),StudioDeviceClip.pixels(logical,.5,100));
        assertEquals(new Bounds(2,88,6,8),StudioDeviceClip.pixels(logical,1,100));
        assertEquals(new Bounds(4,73,14,18),StudioDeviceClip.pixels(logical,2.25,100));
    }

    @Test void invalidScalesAreRejected(){
        var logical=new Bounds(0,0,10,10);
        for(double scale:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->StudioDeviceClip.pixels(logical,scale,100));
    }
}
