package net.ptcrys.fpsmatch.editor.common.text.java2d;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InstalledFontTest {
    @Test void missingAndLogicalFamiliesNeverSilentlyBecomeAnInstalledDialog() {
        var loads=new AtomicInteger();
        var backend=new Java2dTextBackend(()->{loads.incrementAndGet();return List.of(new Font("Dialog",Font.PLAIN,1));});
        var id=TestFonts.id("absent");
        assertTrue(backend.registerInstalled(id,"Definitely absent family").isEmpty());
        assertTrue(backend.registerInstalled(id,"Dialog").isEmpty());
        assertTrue(backend.registerInstalled(id,"C:/Windows/Fonts/not-a-family.ttf").isEmpty());
        assertEquals(1,loads.get());
        assertThrows(IllegalArgumentException.class,()->backend.layout("text",List.of(new FontFaceId(id)),12f,1f));
        assertEquals(id,backend.registerLogical(id,"Dialog").id(),"Failed installed lookup must leave the identity available");
    }
    @Test void installedSelectionUsesTheActualPhysicalFamilyAndPreservesItsMetrics() {
        Font font=TestFonts.inspect(TestFonts.latin());String family=font.getFamily(Locale.ROOT);
        var backend=new Java2dTextBackend(()->List.of(font));var id=TestFonts.id("physical");
        var installed=backend.registerInstalled(id,family.toLowerCase(Locale.ROOT)).orElseThrow();
        assertEquals(family,installed.family());
        String text="Measured installed face";var line=backend.layout(text,List.of(installed.face()),15f,1f);
        var oracle=new TextLayout(text,font.deriveFont(Font.PLAIN,15f),new FontRenderContext(null,true,true));
        assertEquals(oracle.getAdvance(),line.advance(),0.001f);
        assertEquals(List.of(installed.face()),line.usedFonts());
        assertThrows(IllegalArgumentException.class,()->backend.registerInstalled(id,"absent"));
        assertEquals(line.advance(),backend.layout(text,List.of(installed.face()),15f,1f).advance());
        assertThrows(IllegalArgumentException.class,()->backend.registerInstalled(TestFonts.id("blank")," "));
    }
}
