package net.ptcrys.fpsmatch.editor.common.text.java2d;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.ptcrys.fpsmatch.editor.api.EditorId;

/** Behaviour tests for {@link Raster}, {@link FontFaceId} and the AWT-free public surface. */
class RasterTest {

    @Test
    void samplesAreCopiedOnTheWayInAndOut() {
        int[] source = {0x11223344, 0x55667788, 0x99AABBCC, 0xDDEEFF00};
        Raster raster = new Raster(2, 2, source, 1f, 3f);
        source[0] = 0;
        assertEquals(0x11223344, raster.argb()[0], "constructor copies the caller array");
        int[] first = raster.argb();
        int[] second = raster.argb();
        assertNotSame(first, second, "every accessor call hands out a fresh array");
        first[1] = 0;
        assertEquals(0x55667788, raster.argb()[1], "mutating a handed-out array cannot reach the raster");
    }

    @Test
    void equalityIsValueBasedOverSamples() {
        int[] samples = {0x11223344, 0x55667788, 0x99AABBCC, 0xDDEEFF00};
        Raster one = new Raster(2, 2, samples, 1f, 3f);
        Raster same = new Raster(2, 2, samples.clone(), 1f, 3f);
        Raster otherSamples = new Raster(2, 2, new int[] {0, 0, 0, 0}, 1f, 3f);
        Raster otherAnchor = new Raster(2, 2, samples.clone(), 2f, 3f);
        assertEquals(one, same);
        assertEquals(one.hashCode(), same.hashCode());
        assertNotEquals(one, otherSamples);
        assertNotEquals(one, otherAnchor);
        assertNotEquals(one, null);
        assertNotEquals(one, "raster");
        assertTrue(one.toString().contains("2"));
    }

    @Test
    void dimensionsAndSamplesAreValidated() {
        int[] four = new int[4];
        assertThrows(IllegalArgumentException.class, () -> new Raster(0, 2, four, 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> new Raster(2, 0, four, 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> new Raster(-1, 2, four, 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> new Raster(2, 2, new int[3], 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> new Raster(2, 2, new int[5], 0f, 0f));
        assertThrows(NullPointerException.class, () -> new Raster(2, 2, null, 0f, 0f));
        assertThrows(IllegalArgumentException.class, () -> new Raster(2, 2, four, Float.NaN, 0f));
        assertThrows(IllegalArgumentException.class, () -> new Raster(2, 2, four, 0f, Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> new Raster(Raster.MAX_SIDE + 1, 1, new int[Raster.MAX_SIDE + 1], 0f, 0f));
        // MAX_SIDE itself is valid; the pixel ceiling is what rejects oversized squares.
        Raster tall = new Raster(Raster.MAX_SIDE, 1, new int[Raster.MAX_SIDE], 0f, 0f);
        assertEquals(Raster.MAX_SIDE, tall.width());
        int tooManyPixels = Raster.MAX_PIXELS / 2 + 1;
        assertThrows(IllegalArgumentException.class,
                () -> new Raster(2, tooManyPixels, new int[2 * tooManyPixels], 0f, 0f));
    }

    @Test
    void fontFaceIdentityFollowsEditorId() {
        EditorId id = EditorId.parse("test:family/face-0");
        FontFaceId face = new FontFaceId(id);
        assertEquals(id, face.id());
        assertEquals("test:family/face-0", face.toString());
        assertEquals(new FontFaceId(id), face);
        assertEquals(new FontFaceId(id).hashCode(), face.hashCode());
        assertThrows(NullPointerException.class, () -> new FontFaceId(null));
    }

    @Test
    void publicSurfaceNeverExposesAwtOrSwingTypes() {
        List<Class<?>> exposed = new ArrayList<>();
        for (Class<?> type : List.of(Java2dTextBackend.class, TextLine.class, Raster.class,
                FontFaceId.class, InstalledFont.class)) {
            for (Method method : type.getMethods()) {
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }
                collect(exposed, method.getReturnType(), type, method.getName());
                for (Class<?> parameter : method.getParameterTypes()) {
                    collect(exposed, parameter, type, method.getName());
                }
            }
            for (Constructor<?> constructor : type.getConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    collect(exposed, parameter, type, "<init>");
                }
            }
            for (Class<?> nested : type.getDeclaredClasses()) {
                assertFalse(Modifier.isPublic(nested.getModifiers()),
                        "nested type must stay internal: " + nested);
            }
        }
        assertTrue(exposed.isEmpty(), "public API leaks desktop types: " + exposed);
    }

    private static void collect(List<Class<?>> exposed, Class<?> type, Class<?> owner, String member) {
        Class<?> current = type;
        while (current.isArray()) {
            current = current.getComponentType();
        }
        String name = current.getName();
        if (name.startsWith("java.awt") || name.startsWith("javax.swing")
                || name.startsWith("sun.awt") || name.startsWith("sun.java2d")) {
            exposed.add(current);
        }
        assertFalse(current == owner, "self reference is fine only when internal: " + member);
    }
}
