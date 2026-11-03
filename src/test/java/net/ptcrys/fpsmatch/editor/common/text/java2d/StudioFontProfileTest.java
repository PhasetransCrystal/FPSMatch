package net.ptcrys.fpsmatch.editor.common.text.java2d;

import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.TextSize.BODY;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class StudioFontProfileTest {
    @Test void preferredPhysicalFamilyFallsBackByAvailabilityAndKeepsDialogForMissingGlyphs() {
        Font preferred=TestFonts.inspect(TestFonts.latin()),second=TestFonts.inspect(TestFonts.wide());
        String firstName=preferred.getFamily(Locale.ROOT),secondName=second.getFamily(Locale.ROOT);
        assertNotEquals(firstName,secondName);var names=List.of(firstName,secondName);
        for(var available:List.of(List.of(preferred,second),List.of(second),List.<Font>of())){
            var backend=new Java2dTextBackend(()->available);
            try(var adapter=new StudioJava2dText(backend,StudioJava2dText.Budget.defaults(),names)){
                var profile=adapter.fontProfile();
                String expected=available.isEmpty()?"Dialog":available.get(0).getFamily(Locale.ROOT);
                assertEquals(expected,profile.preferredFamily());
                assertEquals(available.isEmpty()?StudioJava2dText.FontSource.JDK_LOGICAL:StudioJava2dText.FontSource.INSTALLED_SYSTEM,profile.source());
                assertEquals(available.isEmpty()?List.of():List.of("Dialog"),profile.fallbackFamilies());
                var before=adapter.line("Sample",BODY);adapter.clear();
                assertSame(profile,adapter.fontProfile());assertNotEquals(before.identity(),adapter.line("Sample",BODY).identity());
                assertThrows(UnsupportedOperationException.class,()->profile.fallbackFamilies().add("other"));
                if(available.size()==2){
                    char fallbackGlyph=TestFonts.findUncoveredBy(new Font("Dialog",Font.PLAIN,1),preferred);
                    var fallback=adapter.line(String.valueOf(fallbackGlyph),BODY);
                    assertEquals(List.of(new FontFaceId(new net.ptcrys.fpsmatch.editor.api.EditorId("studio","dialog"))),fallback.usedFonts());
                }
            }
        }
    }
    @Test void defaultProfileReportsTheFirstActuallyInstalledYaHeiFamilyOrLogicalDialog() {
        var available=Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT));
        String expected="Dialog";
        for(String candidate:List.of("Microsoft YaHei UI","Microsoft YaHei")){
            var match=available.stream().filter(name->name.equalsIgnoreCase(candidate)).findFirst();
            if(match.isPresent()){expected=match.orElseThrow();break;}
        }
        try(var adapter=new StudioJava2dText()){
            assertEquals(expected,adapter.fontProfile().preferredFamily());
            assertEquals(expected.equals("Dialog")?StudioJava2dText.FontSource.JDK_LOGICAL:StudioJava2dText.FontSource.INSTALLED_SYSTEM,adapter.fontProfile().source());
            var line=adapter.line("工程 Project",BODY);assertTrue(line.advance()>0);
        }
    }
}
