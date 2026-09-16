package com.ptcrys.fpsmatch.common.client.screen.modernui;

import icyllis.modernui.core.Core;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.mc.MuiScreen;
import icyllis.modernui.mc.ScreenCallback;
import icyllis.modernui.mc.UIManager;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.*;
import icyllis.modernui.widget.*;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Consumer;

/** Native Modern UI host. Controllers and packets stay on the Minecraft thread;
 * immutable view descriptions cross to the UI thread. Keyed views retain focus and drafts. */
public abstract class ModernScreen extends Screen implements MuiScreen {
    protected final Screen parent;
    private final PageFragment fragment = new PageFragment(this);
    private volatile List<Node> pending = List.of();
    private volatile boolean attached;
    private final Map<String, net.minecraft.world.item.ItemStack> items = new HashMap<>();
    private volatile List<ItemBounds> itemBounds = List.of();
    private record ItemBounds(String id, int x, int y, int width, int height, int left, int top, int right, int bottom) {}
    private final ScreenCallback callback = new ScreenCallback() {
        @Override public boolean shouldClose() { ModernScreen.this.onClose(); return false; }
        @Override public boolean isPauseScreen() { return false; }
        @Override public boolean hasDefaultBackground() { return false; }
        @Override public boolean shouldBlurBackground() { return false; }
    };

    protected ModernScreen(Component title, Screen parent) { super(title); this.parent = parent; }
    protected abstract List<Node> content();
    protected float nativeTextSize() { return 9; }
    protected int designWidth() { return width; }
    protected int designHeight() { return height; }
    /** Optional page-specific presentation, applied after the shared native view defaults. */
    protected void styleView(View view, Node node, float scale, boolean changed) {}
    /** Return true when a page renders its own item artwork inside the native view bounds. */
    protected boolean renderItemArtwork(GuiGraphics graphics, String key, net.minecraft.world.item.ItemStack stack,
                                        String texture, float x, float y, float width, float height) { return false; }
    private volatile float unitScale = 1;
    private volatile int viewportWidth, viewportHeight;
    private final Map<String, net.minecraft.resources.ResourceLocation> avatars = new HashMap<>();
    private final Map<String, String> itemTextures = new HashMap<>();
    private final Set<String> decoratedItems = new HashSet<>();
    protected final void refresh() {
        items.clear(); itemTextures.clear(); avatars.clear(); decoratedItems.clear();
        viewportWidth = Math.max(1, designWidth()); viewportHeight = Math.max(1, designHeight());
        unitScale = Math.min(Minecraft.getInstance().getWindow().getWidth() / (float) viewportWidth,
                Minecraft.getInstance().getWindow().getHeight() / (float) viewportHeight);
        pending = List.copyOf(content());
        if (attached) Core.getUiHandler().post(fragment::update);
    }
    @Override protected void init() {
        attached = true;
        refresh();
        UIManager.getInstance().initScreen(this);
    }
    @Override public void removed() {
        attached = false;
        itemBounds = List.of();
        UIManager.getInstance().removed(this);
        super.removed();
    }
    @Override public void render(GuiGraphics graphics, int x, int y, float partial) {
        renderBackground(graphics);
        graphics.flush();
        UIManager.getInstance().render(graphics, x, y, partial);
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        for (ItemBounds bounds : itemBounds) {
            var item = items.get(bounds.id());
            var skin = avatars.get(bounds.id());
            if (skin == null && (item == null || item.isEmpty())) continue;
            graphics.enableScissor((int) (bounds.left() / scale), (int) (bounds.top() / scale),
                    (int) Math.ceil(bounds.right() / scale), (int) Math.ceil(bounds.bottom() / scale));
            if (skin != null) {
                net.minecraft.client.gui.components.PlayerFaceRenderer.draw(graphics,skin,(int)(bounds.x()/scale),(int)(bounds.y()/scale),
                        Math.max(1,(int)(Math.min(bounds.width(),bounds.height())/scale)));
                graphics.disableScissor(); continue;
            }
            String texture = itemTextures.get(bounds.id());
            if (renderItemArtwork(graphics, bounds.id(), item, texture,
                    (float)(bounds.x()/scale), (float)(bounds.y()/scale),
                    (float)(bounds.width()/scale), (float)(bounds.height()/scale))) {
                graphics.disableScissor(); continue;
            }
            if (texture != null && !texture.isBlank()) {
                var resource = net.minecraft.resources.ResourceLocation.tryParse(texture);
                if (resource != null && Minecraft.getInstance().getResourceManager().getResource(resource).isPresent()) {
                    int bx = (int) (bounds.x() / scale), by = (int) (bounds.y() / scale);
                    int bw = (int) (bounds.width() / scale), bh = (int) (bounds.height() / scale);
                    graphics.blit(resource, bx, by, 0, 0, bw, bh, bw, bh);
                    graphics.disableScissor(); continue;
                }
            }
            float size = (float) (Math.min(bounds.width(), bounds.height()) / scale);
            graphics.pose().pushPose();
            graphics.pose().translate((bounds.x() + bounds.width() / 2.0) / scale - size / 2,
                    (bounds.y() + bounds.height() / 2.0) / scale - size / 2, 0);
            graphics.pose().scale(size / 16, size / 16, 1);
            graphics.renderItem(item, 0, 0);
            if (decoratedItems.contains(bounds.id())) graphics.renderItemDecorations(font, item, 0, 0);
            graphics.pose().popPose();
            graphics.disableScissor();
        }
    }
    @Override public void renderBackground(GuiGraphics graphics) {
        graphics.fill(0, 0, width, height, 0xEE141618);
    }
    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public Screen self() { return this; }
    @Override public Fragment getFragment() { return fragment; }
    @Override public ScreenCallback getCallback() { return callback; }
    @Override public Screen getPreviousScreen() { return parent; }
    @Override public boolean isMenuScreen() { return false; }
    @Override public void onBackPressed() { UIManager.getInstance().getOnBackPressedDispatcher().onBackPressed(); }
    @Override public void mouseMoved(double x, double y) { UIManager.getInstance().onHoverMove(true); }
    @Override public boolean mouseClicked(double x, double y, int button) { return false; }
    @Override public boolean mouseReleased(double x, double y, int button) { return false; }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) { return true; }
    @Override public boolean mouseScrolled(double x, double y, double delta) { return true; }
    @Override public boolean keyPressed(int key, int scan, int mods) { UIManager.getInstance().onKeyPress(key, scan, mods); return false; }
    @Override public boolean keyReleased(int key, int scan, int mods) { UIManager.getInstance().onKeyRelease(key, scan, mods); return false; }
    @Override public boolean charTyped(char ch, int mods) { return UIManager.getInstance().onCharTyped(ch); }

    public static String tr(String key, Object... args) { return Component.translatable(key, args).getString(); }
    public enum Kind { TEXT, TITLE, MUTED, ACCENT, BUTTON, ICON, CARD, INPUT, TOGGLE, IMAGE, ITEM, COLUMN, ROW, SCROLL, FRAME, ACTION_FRAME, SELECT, SLIDER }
    public record Geometry(float x, float y, float width, float height, float weight, boolean placed) {
        static final Geometry AUTO = new Geometry(0, 0, -1, -2, 0, false);
    }
    public record Node(String key, Kind kind, String text, boolean enabled, boolean selected,
                       Consumer<String> action, List<Node> children, Geometry geometry, boolean panel, String hint) {
        public Node { children = List.copyOf(children); }
        public Node(String key, Kind kind, String text, boolean enabled, boolean selected,
                    Consumer<String> action, List<Node> children) {
            this(key, kind, text, enabled, selected, action, children, Geometry.AUTO, false, "");
        }
        public Node at(float x, float y, float w, float h) {
            return new Node(key, kind, text, enabled, selected, action, children, new Geometry(x,y,w,h,0,true),panel,hint);
        }
        public Node size(float w, float h) {
            return new Node(key, kind, text, enabled, selected, action, children, new Geometry(0,0,w,h,0,false),panel,hint);
        }
        public Node fill() { return new Node(key,kind,text,enabled,selected,action,children,new Geometry(0,0,-1,0,1,false),panel,hint); }
        public Node surface() { return new Node(key,kind,text,enabled,selected,action,children,geometry,true,hint); }
        public Node selected(boolean value) { return new Node(key,kind,text,enabled,value,action,children,geometry,panel,hint); }
        public Node enabled(boolean value) { return new Node(key,kind,text,value,selected,action,children,geometry,panel,hint); }
        public Node hint(String value) { return new Node(key,kind,text,enabled,selected,action,children,geometry,panel,value); }
    }
    public static Node canvas(String key, List<Node> children) { return new Node(key,Kind.FRAME,"",true,false,null,children); }
    public static Node actionCanvas(String key, String label, boolean enabled, Consumer<String> action, List<Node> children) {
        return new Node(key,Kind.ACTION_FRAME,label,enabled,false,action,children);
    }
    public static Node field(String key, String value, boolean enabled, Consumer<String> action) { return leaf(key,Kind.INPUT,value,enabled,false,action); }
    public static Node select(String key, String selected, List<Node> options, Consumer<String> action) {
        return new Node(key,Kind.SELECT,selected,true,false,action,options);
    }
    public static Node slider(String key, int progress, int steps, boolean enabled, Consumer<String> action) {
        return new Node(key,Kind.SLIDER,Integer.toString(progress),enabled,false,action,List.of(text("max",Integer.toString(steps))));
    }
    protected final Node avatar(String key, UUID player, String name) {
        avatars.put(key, com.ptcrys.fpsmatch.util.RenderUtil.fetchSkin(player,name));
        return leaf(key,Kind.ITEM,key,true,false,null);
    }
    protected final List<Node> dialog(String key, List<Node> children, int preferredWidth, int preferredHeight) {
        int w=Math.min(preferredWidth,Math.max(1,width-16)),h=Math.min(preferredHeight,Math.max(1,height-16));
        return List.of(canvas(key,List.of(group("dialog.body",false,children).surface().at((width-w)/2f,(height-h)/2f,w,h))).fill());
    }
    protected final Node item(String key, net.minecraft.world.item.ItemStack stack, String texture) {
        itemTextures.put(key, texture); return item(key, stack);
    }
    public static Node text(String key, String value) { return leaf(key, Kind.TEXT, value, true, false, null); }
    public static Node muted(String key, String value) { return leaf(key, Kind.MUTED, value, true, false, null); }
    public static Node accent(String key, String value) { return leaf(key, Kind.ACCENT, value, true, false, null); }
    public static Node iconButton(String key, String icon, String label, boolean enabled, Runnable action) {
        return leaf(key, Kind.ICON, "fpsmatch:textures/gui/competitive/" + icon + ".png", enabled, false, v -> action.run()).hint(label);
    }
    public static Node title(String key, String value) { return leaf(key, Kind.TITLE, value, true, false, null); }
    public static Node image(String key, String resource) { return leaf(key, Kind.IMAGE, resource, true, false, null); }
    protected final Node item(String key, net.minecraft.world.item.ItemStack stack) {
        items.put(key, stack.copy());
        return leaf(key, Kind.ITEM, key, true, false, null);
    }
    protected final Node inventoryItem(String key, net.minecraft.world.item.ItemStack stack) {
        decoratedItems.add(key);
        return item(key, stack);
    }
    public static Node card(String key, String label, boolean enabled, Consumer<String> action) {
        return leaf(key, Kind.CARD, label, enabled, false, action);
    }
    public static Node button(String key, String label, boolean enabled, Runnable action) {
        return leaf(key, Kind.BUTTON, label, enabled, false, v -> action.run());
    }
    public static Node input(String key, String label, String value, boolean enabled, Consumer<String> action) {
        return column(key + ".field", text(key + ".label", label), leaf(key, Kind.INPUT, value, enabled, false, action));
    }
    public static Node toggle(String key, String label, boolean selected, boolean enabled, Consumer<Boolean> action) {
        return leaf(key, Kind.TOGGLE, label, enabled, selected, v -> action.accept(Boolean.parseBoolean(v)));
    }
    private static Node leaf(String key, Kind kind, String text, boolean enabled, boolean selected, Consumer<String> action) {
        return new Node(key, kind, text, enabled, selected, action, List.of());
    }
    public static Node column(String key, Node... nodes) { return group(key, false, List.of(nodes)); }
    public static Node row(String key, Node... nodes) { return group(key, true, List.of(nodes)); }
    public static Node group(String key, boolean horizontal, List<Node> nodes) {
        return new Node(key, horizontal ? Kind.ROW : Kind.COLUMN, "", true, false, null, nodes);
    }
    public static Node scroll(String key, List<Node> nodes) {
        return new Node(key, Kind.SCROLL, "", true, false, null, nodes);
    }

    public static final class PageFragment extends Fragment {
        private final ModernScreen owner;
        public PageFragment(ModernScreen owner) { this.owner = Objects.requireNonNull(owner); }
        private LinearLayout root;
        private final Map<View, Node> specs = new IdentityHashMap<>();
        private boolean binding;
        private float appliedScale;
        private boolean scaleChanged;

        @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, DataSet state) {
            specs.clear();
            root = new LinearLayout(getContext());
            root.setOrientation(LinearLayout.VERTICAL);
            int padding = 0;
            root.setPadding(padding, padding, padding, padding);
            root.getViewTreeObserver().addOnPreDrawListener(() -> {
                List<ItemBounds> bounds = new ArrayList<>();
                for (var entry : specs.entrySet()) if (entry.getValue().kind() == Kind.ITEM) {
                    View view = entry.getKey();
                    icyllis.modernui.graphics.Rect clip = new icyllis.modernui.graphics.Rect();
                    if (!view.getGlobalVisibleRect(clip)) continue;
                    int[] location = new int[2]; view.getLocationInWindow(location);
                    bounds.add(new ItemBounds(entry.getValue().text(), location[0], location[1], view.getWidth(), view.getHeight(), clip.left, clip.top, clip.right, clip.bottom));
                }
                owner.itemBounds = List.copyOf(bounds);
                return true;
            });
            FrameLayout frame = new FrameLayout(getContext()) {
                @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                    super.onSizeChanged(w, h, oldw, oldh);
                    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                            Math.round(owner.viewportWidth * owner.unitScale), Math.round(owner.viewportHeight * owner.unitScale), Gravity.CENTER);
                    root.setLayoutParams(params);
                }
            };
            frame.addView(root, new FrameLayout.LayoutParams(Math.round(owner.viewportWidth * owner.unitScale), Math.round(owner.viewportHeight * owner.unitScale), Gravity.CENTER));
            frame.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
            update();
            return frame;
        }
        @Override public void onDestroyView() { root = null; specs.clear(); super.onDestroyView(); }
        void update() {
            if (root == null || !owner.attached) return;
            scaleChanged = appliedScale != owner.unitScale;
            appliedScale = owner.unitScale;
            if (scaleChanged) root.setLayoutParams(new FrameLayout.LayoutParams(
                    px(owner.viewportWidth), px(owner.viewportHeight), Gravity.CENTER));
            binding = true;
            try { reconcile(root, owner.pending); } finally { binding = false; }
        }
        private void dispatch(View view, String value) {
            if (binding) return;
            Node node = specs.get(view);
            if (node == null || !node.enabled() && !value.startsWith("hover") || node.action() == null) return;
            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().screen == owner) {
                    node.action().accept(value);
                    owner.refresh();
                }
            });
        }
        private void forget(View view) {
            specs.remove(view);
            if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) forget(group.getChildAt(i));
        }
        private void reconcile(ViewGroup group, List<Node> nodes) {
            Set<String> keys = new HashSet<>();
            for (Node n : nodes) if (!keys.add(n.key())) throw new IllegalArgumentException("Duplicate UI key: " + n.key());
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View view = group.getChildAt(i);
                Node old = specs.get(view);
                if (old == null || !keys.contains(old.key())) { group.removeViewAt(i); forget(view); }
            }
            for (int i = 0; i < nodes.size(); i++) {
                Node node = nodes.get(i);
                View view = null;
                for (int j = i; j < group.getChildCount(); j++) {
                    View candidate = group.getChildAt(j);
                    Node old = specs.get(candidate);
                    if (old != null && old.key().equals(node.key()) && old.kind() == node.kind()) {
                        view = candidate;
                        if (j != i) { group.removeViewAt(j); group.addView(view, i); }
                        break;
                    }
                }
                if (view == null) {
                    view = create(node);
                    group.addView(view, i);
                }
                Node old = specs.put(view, node);
                if (old == null || scaleChanged || !old.geometry().equals(node.geometry())) applyGeometry(group, view, node);
                view.setEnabled(node.enabled());
                view.setSelected(node.selected());
                // Modern UI hides and reschedules a visible tooltip on every setter call.
                // Tick-driven pages must leave an unchanged tooltip attached while hovering.
                if (old == null || !old.hint().equals(node.hint()))
                    view.setTooltipText(node.hint().isBlank() ? null : node.hint());
                if (view instanceof EditText edit) { edit.setHint(node.hint()); edit.setHintTextColor(0xFF91A0B2); }
                if (node.kind() == Kind.ACTION_FRAME) view.setContentDescription(node.text());
                if (node.panel() && (old == null || !old.panel())) view.setBackground(surface(view, 0xEE242729, 0xFF414649));
                view.setAlpha(node.enabled() ? 1 : .45f);
                if (view instanceof Spinner spinner) {
                    if (old == null || !old.children().equals(node.children())) {
                        spinner.setAdapter(new ArrayAdapter<String>(getContext(), node.children().stream().map(Node::text).toList()) {
                            @Override public View getView(int position, View convertView, ViewGroup parent) { return option(super.getView(position,convertView,parent)); }
                            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) { return option(super.getDropDownView(position,convertView,parent)); }
                            private View option(View v) {
                                TextView label = (TextView) v;
                                label.setTextSize(icyllis.modernui.resources.TypedValue.COMPLEX_UNIT_PX, owner.nativeTextSize()*owner.unitScale);
                                label.setTextColor(0xFFE6EDF3); label.setPadding(px(6),px(3),px(6),px(3));
                                label.setMinHeight(px(22)); return label;
                            }
                        });
                    }
                    for (int index=0; index<node.children().size(); index++) if (node.children().get(index).key().equals(node.text())) spinner.setSelection(index);
                }
                else if (view instanceof SeekBar slider) {
                    slider.setMax(Integer.parseInt(node.children().get(0).text()));
                    slider.setProgress(Integer.parseInt(node.text()));
                }
                else if (view instanceof ScrollView scroller) reconcile((LinearLayout) scroller.getChildAt(0), node.children());
                else if (view instanceof LinearLayout || view instanceof FrameLayout) reconcile((ViewGroup)view, node.children());
                else if (view instanceof ImageView image && (old == null || !old.text().equals(node.text()))) {
                    String[] path = node.text().split(":", 2);
                    image.setImage(path.length == 2 ? icyllis.modernui.graphics.Image.create(path[0],
                            path[1].replaceFirst("^textures/", "")) : null);
                }
                else if (view instanceof TextView label) {
                    if (scaleChanged) label.setTextSize(icyllis.modernui.resources.TypedValue.COMPLEX_UNIT_PX,
                            (node.kind() == Kind.TITLE ? owner.nativeTextSize() * 1.4f : owner.nativeTextSize()) * owner.unitScale);
                    label.setTextColor(node.selected() || node.kind() == Kind.ACCENT ? 0xFFA9DBC5
                            : node.kind() == Kind.MUTED ? 0xFF949B9E : 0xFFE6EBE9);
                    // Input text changes only for an explicit controller update, never for a new callback.
                    if (old == null || !old.text().equals(node.text())) {
                        if (!(view instanceof EditText && view.isFocused())
                                && !label.getText().toString().equals(node.text())) label.setText(node.text());
                    }
                    if (view instanceof CheckBox check) check.setChecked(node.selected());
                }
                owner.styleView(view, node, owner.unitScale, old == null || scaleChanged
                        || old.kind() != node.kind() || old.selected() != node.selected() || old.enabled() != node.enabled());
            }
            while (group.getChildCount() > nodes.size()) {
                View view = group.getChildAt(group.getChildCount() - 1);
                group.removeViewAt(group.getChildCount() - 1); forget(view);
            }
        }
        private int px(float value) { return Math.round(value * owner.unitScale); }
        private int dimension(float value) { return value < 0 ? (int)value : px(value); }
        private void applyGeometry(ViewGroup parent, View view, Node node) {
            Geometry g = node.geometry();
            if (parent instanceof FrameLayout) {
                FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dimension(g.width()),dimension(g.height()));
                p.leftMargin=px(g.x()); p.topMargin=px(g.y()); view.setLayoutParams(p);
            } else {
                boolean row = parent instanceof LinearLayout linear && linear.getOrientation()==LinearLayout.HORIZONTAL;
                boolean auto = g.equals(Geometry.AUTO);
                int w=dimension(g.width()), h=dimension(g.height()); float weight=g.weight();
                if (auto && row) { w=0; weight=1; }
                if (auto && (node.kind()==Kind.SCROLL || node.kind()==Kind.FRAME)) { h=0; weight=1; }
                if (row && weight>0) { w=0; h=-1; }
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h,weight);
                if (node.kind()!=Kind.FRAME) p.setMargins(0,0,row?px(4):0,px(3));
                view.setLayoutParams(p);
            }
        }
        private View create(Node node) {
            if (node.kind()==Kind.FRAME || node.kind()==Kind.ACTION_FRAME) {
                FrameLayout frame = new FrameLayout(getContext());
                if (node.kind()==Kind.ACTION_FRAME) { decorateAction(frame); frame.setFocusable(true); }
                return frame;
            }
            if (node.kind()==Kind.SELECT) {
                Spinner spinner=new Spinner(getContext());
                spinner.setBackground(surface(spinner,0xFF2A2E30,0xFF485053));
                spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        Node spec=specs.get(spinner);
                        if(spec!=null && position>=0 && position<spec.children().size()
                                && !spec.text().equals(spec.children().get(position).key())) dispatch(spinner,spec.children().get(position).key());
                    }
                    @Override public void onNothingSelected(AdapterView<?> parent) {}
                });
                return spinner;
            }
            if (node.kind()==Kind.SLIDER) {
                SeekBar slider=new SeekBar(getContext());
                var accent = icyllis.modernui.util.ColorStateList.valueOf(0xFFA9DBC5);
                slider.setThumbTintList(accent);
                slider.setProgressTintList(accent);
                slider.setProgressBackgroundTintList(icyllis.modernui.util.ColorStateList.valueOf(0xFF485053));
                slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar bar,int progress,boolean user) { if(user) dispatch(bar,Integer.toString(progress)); }
                }); return slider;
            }
            if (node.kind() == Kind.SCROLL) {
                ScrollView scroller = new ScrollView(getContext());
                LinearLayout content = new LinearLayout(getContext());
                content.setOrientation(LinearLayout.VERTICAL);
                scroller.addView(content, new ViewGroup.LayoutParams(-1, -2));
                return scroller;
            }
            if (node.kind() == Kind.ITEM) {
                View item = new View(getContext()); item.setMinimumHeight(px(20)); return item;
            }
            if (node.kind() == Kind.IMAGE || node.kind() == Kind.ICON) {
                ImageView image = node.kind() == Kind.ICON ? new ImageButton(getContext()) : new ImageView(getContext());
                if (node.kind() == Kind.ICON) {
                    image.setPadding(px(5), px(5), px(5), px(5));
                    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    image.setBackground(surface(image, 0xFF2A2E30, 0xFF485053));
                    image.setOnClickListener(v -> dispatch(v, ""));
                    image.setContentDescription(node.hint());
                } else {
                    image.setMinimumHeight(px(16));
                    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                }
                return image;
            }
            if (node.kind() == Kind.COLUMN || node.kind() == Kind.ROW) {
                LinearLayout group = new LinearLayout(getContext());
                group.setOrientation(node.kind() == Kind.ROW ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                return group;
            }
            TextView view = switch (node.kind()) {
                case BUTTON, CARD -> new Button(getContext(), null);
                case INPUT -> new EditText(getContext(), null);
                case TOGGLE -> new CheckBox(getContext(), null);
                default -> new TextView(getContext());
            };
            view.setTextColor(0xFFE6EDF3);
            view.setTextSize(icyllis.modernui.resources.TypedValue.COMPLEX_UNIT_PX,
                    (node.kind() == Kind.TITLE ? owner.nativeTextSize() * 1.4f : owner.nativeTextSize()) * owner.unitScale);
            view.setMinHeight(0); view.setMinimumHeight(0); view.setMinWidth(0); view.setMinimumWidth(0);
            view.setGravity(Gravity.CENTER_VERTICAL | (node.kind()==Kind.BUTTON ? Gravity.CENTER_HORIZONTAL : Gravity.LEFT));
            if (node.kind()!=Kind.TEXT) { view.setSingleLine(true); view.setEllipsize(icyllis.modernui.text.TextUtils.TruncateAt.END); }
            boolean label = node.kind() == Kind.TEXT || node.kind() == Kind.MUTED
                    || node.kind() == Kind.ACCENT || node.kind() == Kind.TITLE;
            view.setPadding(px(5), label ? 0 : px(2), px(5), label ? 0 : px(2));
            if (node.kind() == Kind.TITLE) view.setTextColor(0xFF8FCBF0);
            if (node.kind() == Kind.BUTTON || node.kind() == Kind.CARD || node.kind() == Kind.INPUT) {
                // Explicit game palette: the user's system accent can be very light, so pairing
                // it with white text would lose contrast. These remain native state drawables.
                var states = new icyllis.modernui.graphics.drawable.StateListDrawable();
                states.addState(new int[]{icyllis.modernui.R.attr.state_pressed}, surface(view, 0xFF3C5550, 0xFFA9DBC5));
                states.addState(new int[]{icyllis.modernui.R.attr.state_selected}, surface(view, 0xFF30413C, 0xFFA9DBC5));
                states.addState(new int[]{icyllis.modernui.R.attr.state_focused}, surface(view, 0xFF293C35, 0xFFA9DBC5));
                states.addState(new int[]{icyllis.modernui.R.attr.state_hovered}, surface(view, 0xFF393F40, 0xFF839591));
                states.addState(new int[]{}, surface(view, node.kind() == Kind.INPUT ? 0xFF191D1E : 0xFF2A2E30, 0xFF485053));
                view.setBackground(states);
            }
            if (view instanceof EditText edit) {
                edit.setSingleLine(true);
                edit.setOnFocusChangeListener((v, focused) -> {
                    Node latest = specs.get(edit);
                    if (!focused && latest != null && !edit.getText().toString().equals(latest.text())) {
                        binding = true;
                        try { edit.setText(latest.text()); } finally { binding = false; }
                    }
                });
                edit.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) { dispatch(edit, s.toString()); }
                    @Override public void afterTextChanged(Editable s) {}
                });
            } else if (view instanceof CheckBox check) {
                check.setButtonTintList(new icyllis.modernui.util.ColorStateList(
                        new int[][]{{icyllis.modernui.R.attr.state_checked}, {}},
                        new int[]{0xFFA9DBC5, 0xFF949B9E}));
                check.setOnCheckedChangeListener((button, checked) -> dispatch(check, Boolean.toString(checked)));
            } else if (node.kind() == Kind.BUTTON || node.kind() == Kind.CARD) view.setOnClickListener(v -> dispatch(v, ""));
            if (node.kind() == Kind.CARD) {
                view.setOnContextClickListener(v -> { dispatch(v, "secondary"); return true; });
                view.setOnHoverListener((v, event) -> {
                    if (event.getAction() == MotionEvent.ACTION_HOVER_ENTER) dispatch(v, "hover");
                    else if (event.getAction() == MotionEvent.ACTION_HOVER_EXIT) dispatch(v, "hoverExit");
                    return false;
                });
            }
            return view;
        }
        private void decorateAction(View view) {
            var states=new icyllis.modernui.graphics.drawable.StateListDrawable();
            states.addState(new int[]{icyllis.modernui.R.attr.state_pressed},surface(view,0xFF3C5550,0xFFA9DBC5));
            states.addState(new int[]{icyllis.modernui.R.attr.state_focused},surface(view,0xFF30413C,0xFFA9DBC5));
            states.addState(new int[]{icyllis.modernui.R.attr.state_selected},surface(view,0xFF30413C,0xFFA9DBC5));
            states.addState(new int[]{icyllis.modernui.R.attr.state_hovered},surface(view,0xFF393F40,0xFF839591));
            states.addState(new int[]{},surface(view,0xEE2A2E30,0xFF485053));
            view.setBackground(states);
            view.setOnClickListener(v->dispatch(v,""));
            view.setOnContextClickListener(v->{dispatch(v,"secondary");return true;});
            view.setOnHoverListener((v,event)->{
                if(event.getAction()==MotionEvent.ACTION_HOVER_ENTER) dispatch(v,"hover");
                else if(event.getAction()==MotionEvent.ACTION_HOVER_EXIT) dispatch(v,"hoverExit");
                return false;
            });
        }
        private icyllis.modernui.graphics.drawable.ShapeDrawable surface(View view, int color, int border) {
            var shape = new icyllis.modernui.graphics.drawable.ShapeDrawable();
            shape.setColor(color); shape.setCornerRadius(Math.min(view.dp(8), px(4))); shape.setStroke(Math.max(1, px(.5f)), border);
            return shape;
        }
    }
}
