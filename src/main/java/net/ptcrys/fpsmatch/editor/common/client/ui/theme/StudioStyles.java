package net.ptcrys.fpsmatch.editor.common.client.ui.theme;

import icyllis.modernui.R;
import icyllis.modernui.core.Core;
import icyllis.modernui.graphics.drawable.ShapeDrawable;
import icyllis.modernui.graphics.drawable.StateListDrawable;
import icyllis.modernui.util.ColorStateList;

/**
 * R17.0 独立 drawable/颜色工厂：把 {@link StudioTokens} 的固定取值落地为每次全新的
 * {@link ShapeDrawable} / {@link StateListDrawable} / {@link ColorStateList}。
 *
 * <p>
 * 实例隔离：本类不持有任何静态 drawable、缓存、View 或 Context，每次调用都返回全新对象，
 * 且每个状态 child、每个 fill/stroke 都使用单独新建的 {@link ColorStateList} 与单独新建的二维
 * state / 颜色数组。{@code ColorStateList(int[][], int[])} 实际直接把传入数组存进字段（零拷贝、
 * 零校验），{@code ShapeDrawable.setColor/setStroke} 也直接存引用，因此
 * {@code ColorStateList.valueOf(int)} 的全局弱引用缓存以及 {@code setColor(int)} /
 * {@code setStroke(int, int)} 重载都不可用——它们会让多个控件通过
 * {@code getStates()/getColors()} 暴露并可改写同一份内部数组。
 *
 * <p>
 * 线程：三个工厂入口都先执行 {@link Core#checkUiThread()}，只有真实 UI 线程才能创建
 * drawable；{@link #pixels(int, float)} 与 {@link #fontPixels(int, float)} 是纯函数，
 * 不访问线程或控件。本类不实现宿主、控件、页面或文案，也不做坐标映射——logical 坐标到物理
 * 像素的权威转换仍属 ScaleTransform。
 */
public final class StudioStyles {

    /** 表面类型：映射到固定的填充、圆角与描边。 */
    public enum Surface {
        CANVAS,
        PANEL,
        RAISED,
        INPUT,
        DIALOG
    }

    /** 交互控件类型：决定状态序里的填充与描边取值。 */
    public enum Control {
        DEFAULT,
        PRIMARY,
        INPUT
    }

    private StudioStyles() {}

    /* ---------- 尺寸单位 helper（纯函数） ---------- */

    /**
     * logical 尺寸转物理像素。
     *
     * <p>
     * {@code logical < 0} 或 density 非有限/非正一律 IAE；先做 double 乘法，乘积与
     * {@code Math.round} 结果越 {@link Integer#MAX_VALUE} 一律 IAE；{@code logical == 0}
     * 恒返回 0，正 logical 返回 {@code max(1, round(logical * density))}。不做 SP/GUI scale 二次缩放。
     */
    public static int pixels(int logical, float density) {
        if (logical < 0) {
            throw new IllegalArgumentException("logical 不能为负: " + logical);
        }
        requireDensity(density);
        double product = (double) logical * (double) density;
        if (!(product <= (double) Integer.MAX_VALUE)) {
            throw new IllegalArgumentException("logical*density 越 Integer.MAX_VALUE: " + product);
        }
        long rounded = Math.round(product);
        if (rounded > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Math.round 结果越 Integer.MAX_VALUE: " + rounded);
        }
        if (logical == 0) {
            return 0;
        }
        return (int) Math.max(1L, rounded);
    }

    /**
     * logical 字号转物理像素字号（调用方以 COMPLEX_UNIT_PX 消费）。
     *
     * <p>
     * {@code logicalFontSize <= 0} 或 density 非有限/非正一律 IAE；double 乘法后拒绝非有限、
     * 超过 {@link Float#MAX_VALUE} 或转 float 为 0 的结果；不做 SP / GUI scale / dp 二次缩放。
     */
    public static float fontPixels(int logicalFontSize, float density) {
        if (logicalFontSize <= 0) {
            throw new IllegalArgumentException("logicalFontSize 必须 > 0: " + logicalFontSize);
        }
        requireDensity(density);
        double product = (double) logicalFontSize * (double) density;
        if (!Double.isFinite(product) || product > (double) Float.MAX_VALUE) {
            throw new IllegalArgumentException("字号缩放越 Float.MAX_VALUE: " + product);
        }
        float scaled = (float) product;
        if (!Float.isFinite(scaled) || scaled == 0.0f) {
            throw new IllegalArgumentException("字号缩放不可表示: " + scaled);
        }
        return scaled;
    }

    /* ---------- 表面 ---------- */

    /**
     * 表面矩形 drawable：CANVAS/PANEL/RAISED 无圆角无描边，INPUT 半径 4 + STROKE 宽 1，
     * DIALOG 半径 8 + STROKE_STRONG 宽 1。每次返回全新实例且 fill/描边各有独立 ColorStateList。
     */
    public static ShapeDrawable surface(Surface surface, float density) {
        Core.checkUiThread();
        if (surface == null) {
            throw new IllegalArgumentException("surface == null");
        }
        requireDensity(density);

        int fill;
        int strokeColor = 0;
        int strokeWidth = 0;
        float radius = 0.0f;
        switch (surface) {
            case CANVAS:
                fill = StudioTokens.BG_CANVAS;
                break;
            case PANEL:
                fill = StudioTokens.BG_PANEL;
                break;
            case RAISED:
                fill = StudioTokens.BG_RAISED;
                break;
            case INPUT:
                fill = StudioTokens.BG_INPUT;
                radius = scaledRadius(StudioTokens.CONTROL_RADIUS, density);
                strokeColor = StudioTokens.STROKE;
                strokeWidth = pixels(StudioTokens.STROKE_WIDTH, density);
                break;
            case DIALOG:
                fill = StudioTokens.BG_RAISED;
                radius = scaledRadius(StudioTokens.DIALOG_RADIUS, density);
                strokeColor = StudioTokens.STROKE_STRONG;
                strokeWidth = pixels(StudioTokens.STROKE_WIDTH, density);
                break;
            default:
                throw new IllegalArgumentException("未知 surface: " + surface);
        }

        ShapeDrawable drawable = new ShapeDrawable();
        drawable.setShape(ShapeDrawable.RECTANGLE);
        drawable.setColor(freshSolid(fill));
        if (radius > 0.0f) {
            drawable.setCornerRadius(radius);
        }
        if (strokeWidth > 0) {
            drawable.setStroke(strokeWidth, freshSolid(strokeColor));
        }
        return drawable;
    }

    /* ---------- 控件 ---------- */

    /**
     * 控件状态序 drawable，优先级固定为
     * disabled(-state_enabled) → focused(enabled,focused) → pressed(enabled,pressed) →
     * selected(enabled,selected) → hovered(enabled,hovered) → default(空数组)。
     *
     * <p>
     * 所有半径 4 logical；普通描边宽 {@code pixels(STROKE_WIDTH)}，focus 环宽
     * {@code pixels(FOCUS_WIDTH)}（PRIMARY 的 focus 描边用 TEXT_PRIMARY，避免与 ACCENT 填充同色不可见）。
     * 每个状态 child 都是全新 ShapeDrawable，其 fill 与 stroke 各有独立新建的 ColorStateList。
     */
    public static StateListDrawable control(Control control, float density) {
        Core.checkUiThread();
        if (control == null) {
            throw new IllegalArgumentException("control == null");
        }
        requireDensity(density);

        int normalFill;
        int normalStroke;
        switch (control) {
            case DEFAULT:
                normalFill = StudioTokens.BG_RAISED;
                normalStroke = StudioTokens.STROKE;
                break;
            case INPUT:
                normalFill = StudioTokens.BG_INPUT;
                normalStroke = StudioTokens.STROKE;
                break;
            case PRIMARY:
                normalFill = StudioTokens.ACCENT;
                normalStroke = StudioTokens.ACCENT;
                break;
            default:
                throw new IllegalArgumentException("未知 control: " + control);
        }

        final boolean primary = control == Control.PRIMARY;
        int focusStroke = primary ? StudioTokens.TEXT_PRIMARY : StudioTokens.ACCENT;
        int activeFill = primary ? StudioTokens.ACCENT : StudioTokens.BG_SELECTED;
        int hoverFill = primary ? StudioTokens.ACCENT : StudioTokens.BG_HOVER;
        int activeStroke = primary ? StudioTokens.TEXT_PRIMARY : StudioTokens.STROKE_STRONG;

        int strokeNormal = pixels(StudioTokens.STROKE_WIDTH, density);
        int strokeFocus = pixels(StudioTokens.FOCUS_WIDTH, density);
        float radius = scaledRadius(StudioTokens.CONTROL_RADIUS, density);

        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(
                new int[] { -R.attr.state_enabled },
                controlChild(StudioTokens.BG_PANEL, StudioTokens.STROKE, strokeNormal, radius));
        drawable.addState(
                new int[] { R.attr.state_enabled, R.attr.state_focused },
                controlChild(normalFill, focusStroke, strokeFocus, radius));
        drawable.addState(
                new int[] { R.attr.state_enabled, R.attr.state_pressed },
                controlChild(activeFill, activeStroke, strokeNormal, radius));
        drawable.addState(
                new int[] { R.attr.state_enabled, R.attr.state_selected },
                controlChild(activeFill, activeStroke, strokeNormal, radius));
        drawable.addState(
                new int[] { R.attr.state_enabled, R.attr.state_hovered },
                controlChild(hoverFill, activeStroke, strokeNormal, radius));
        drawable.addState(
                new int[0],
                controlChild(normalFill, normalStroke, strokeNormal, radius));
        return drawable;
    }

    /**
     * 控件文本颜色：disabled(-state_enabled) → TEXT_DISABLED，default(空数组) →
     * PRIMARY 用 ACCENT_TEXT、其余用 TEXT_PRIMARY。每次返回新建 ColorStateList 与新建 state 数组。
     */
    public static ColorStateList textColor(Control control) {
        Core.checkUiThread();
        if (control == null) {
            throw new IllegalArgumentException("control == null");
        }
        int normalColor = control == Control.PRIMARY ? StudioTokens.ACCENT_TEXT : StudioTokens.TEXT_PRIMARY;
        return new ColorStateList(
                new int[][] { new int[] { -R.attr.state_enabled }, new int[0] },
                new int[] { StudioTokens.TEXT_DISABLED, normalColor });
    }

    /* ---------- 内部 helper：每次全新对象与全新数组 ---------- */

    /**
     * 单色 {@link ColorStateList}：state 与颜色数组都是本次新建，绝不与任何其它 drawable 共享，
     * 也不用 {@code ColorStateList.valueOf} 的全局缓存。
     */
    private static ColorStateList freshSolid(int color) {
        return new ColorStateList(new int[][] { new int[0] }, new int[] { color });
    }

    /** 控件状态 child：全新 ShapeDrawable + 独立 fill/stroke ColorStateList。 */
    private static ShapeDrawable controlChild(int fill, int strokeColor, int strokeWidth, float radius) {
        ShapeDrawable child = new ShapeDrawable();
        child.setShape(ShapeDrawable.RECTANGLE);
        child.setColor(freshSolid(fill));
        child.setCornerRadius(radius);
        child.setStroke(strokeWidth, freshSolid(strokeColor));
        return child;
    }

    private static void requireDensity(float density) {
        if (!Float.isFinite(density) || density <= 0.0f) {
            throw new IllegalArgumentException("density 必须有限且 > 0: " + density);
        }
    }

    /** logical 半径转 float 物理半径，double 乘法后做有限性与量级检查。 */
    private static float scaledRadius(int logicalRadius, float density) {
        if (logicalRadius <= 0) {
            throw new IllegalArgumentException("logicalRadius 必须 > 0: " + logicalRadius);
        }
        requireDensity(density);
        double product = (double) logicalRadius * (double) density;
        if (!Double.isFinite(product) || product > (double) Float.MAX_VALUE) {
            throw new IllegalArgumentException("半径缩放越界: " + product);
        }
        float scaled = (float) product;
        if (!Float.isFinite(scaled) || scaled <= 0.0f) {
            throw new IllegalArgumentException("半径缩放不可表示: " + scaled);
        }
        return scaled;
    }
}
