package net.ptcrys.fpsmatch.editor.common.layout;

import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.layout.DockNode.Axis;
import net.ptcrys.fpsmatch.editor.common.layout.DockNode.Panel;
import net.ptcrys.fpsmatch.editor.common.layout.DockNode.Split;
import net.ptcrys.fpsmatch.editor.common.layout.DockNode.Tabs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * R16.1 停靠布局严格 codec（CONTRACTS §8 R1.2 / P16-LAYOUT-CODEC）。
 *
 * <p>
 * 职责边界：只做「冻结的停靠树 ↔ 严格 JSON 文本」的双向转换。不持有文档、选择或相机状态，也不在文件损坏时
 * 从 wire 重建任何非布局对象；未知但不非法的面板 ID 按原样保留，由 UI 注册表决定是否显示为 MissingPanel。
 *
 * <p>
 * wire schema（唯一一种，无版本分支、无默认值、无静默修补）：
 *
 * <pre>
 * 根对象  {"schemaVersion": 1, "root": &lt;node&gt;}
 * panel   {"type": "panel", "id": "&lt;panelId&gt;"}
 * tabs    {"type": "tabs", "activePanelId": "&lt;panelId&gt;", "panels": [&lt;panel&gt;, ...]}
 * split   {"type": "split", "axis": "horizontal"|"vertical", "ratio": &lt;number&gt;,
 *          "first": &lt;node&gt;, "second": &lt;node&gt;}
 * </pre>
 *
 * <p>
 * 严格性来源：
 *
 * <ul>
 * <li>词法与全局预算（UTF-8 字节、JSON 深度、JSON 节点数、字符串/键/数字字面量长度、指数、重复键）全部由冻结的
 * {@link ExactJson} 负责；本类不重复实现，也不放宽。
 * <li>类型判定用 {@code JsonPrimitive.isNumber()/isString()}，不使用 Gson 的宽松 getter，因此 {@code "1"}、
 * {@code true}、{@code null} 不会被当作数字或字符串接受。
 * <li>{@code schemaVersion} 先转成精确 {@link BigDecimal} 再要求恰为整数 1：{@code 1.0}、{@code 1e0} 合法，
 * {@code 2}、{@code 1.5}、{@code 1e999999999} 非法。
 * <li>{@code ratio} 先按精确 {@link BigDecimal} 判定闭区间 [0.05, 0.95]，再取 {@code doubleValue()} 并检查有限
 * 性。先十进制后二进制可避免 {@code 0.049999999999999999999999999} 这类值因二进制舍入而混入合法区间。
 * <li>深度与节点数在「创建并进入任何已知节点之前」按规格账目预检（{@link DockValidation#MAX_DEPTH}/
 * {@link DockValidation#MAX_NODES}），因此超限必然以本类的语义异常出现在越界节点自身的 JSON pointer 上，
 * 不会先构造非法树再依赖 {@link DockLayout} 构造器兜底。JSON 自身的深度/节点预算是另一套账，互不替代。
 * </ul>
 *
 * <p>
 * 异常契约：{@code decode} 的第一个动作是 {@link ExactJson#parse}，其 {@code JsonContractException}
 * （{@link IllegalArgumentException} 子类）原样上抛，不包装；合法 JSON 但 wire 结构、成员类型、ratio 或树约束
 * 不符时抛 {@link IllegalArgumentException}，消息带当前 JSON pointer，并把域构造错误保留为 cause。不新增公开异常
 * 类型，不做默认值填充或修复。
 */
public final class DockLayoutCodec {

    private static final String MEMBER_SCHEMA_VERSION = "schemaVersion";
    private static final String MEMBER_ROOT = "root";
    private static final String MEMBER_TYPE = "type";
    private static final String MEMBER_ID = "id";
    private static final String MEMBER_ACTIVE_PANEL_ID = "activePanelId";
    private static final String MEMBER_PANELS = "panels";
    private static final String MEMBER_AXIS = "axis";
    private static final String MEMBER_RATIO = "ratio";
    private static final String MEMBER_FIRST = "first";
    private static final String MEMBER_SECOND = "second";

    private static final String TYPE_PANEL = "panel";
    private static final String TYPE_TABS = "tabs";
    private static final String TYPE_SPLIT = "split";

    private static final String AXIS_HORIZONTAL = "horizontal";
    private static final String AXIS_VERTICAL = "vertical";

    /** ratio 闭区间端点，按精确十进制判定（与 {@link Split} 的域约束同源）。 */
    private static final BigDecimal RATIO_MIN = new BigDecimal("0.05");
    private static final BigDecimal RATIO_MAX = new BigDecimal("0.95");

    private DockLayoutCodec() {}

    // ------------------------------------------------------------------ 编码

    /**
     * 把冻结的停靠布局编码为规范（canonical）严格 JSON 文本。
     *
     * <p>
     * 文本统一由 {@link ExactJson#freeze} 生成：对象成员按 {@link String#compareTo} 排序，数字走唯一的规范化
     * 通道（零为 {@code 0}，其余为 {@code stripTrailingZeros().toString()}）。本类不手写 JSON，也不格式化 double，
     * 因此 ratio 往返保持精确 double 位模式。
     *
     * @param layout 待编码布局，非空
     * @return 规范化严格 JSON 文本
     * @throws IllegalArgumentException layout 为 null，或树超出 dock 预算
     */
    public static String encode(DockLayout layout) {
        if (layout == null) {
            throw new IllegalArgumentException("layout must not be null");
        }
        JsonObject root = new JsonObject();
        root.add(MEMBER_SCHEMA_VERSION, new JsonPrimitive(BigDecimal.valueOf(layout.schemaVersion())));
        root.add(MEMBER_ROOT, encodeNode(layout.root()));
        return ExactJson.freeze(root).canonical();
    }

    private static JsonObject encodeNode(DockNode node) {
        JsonObject object = new JsonObject();
        if (node instanceof Panel panel) {
            object.addProperty(MEMBER_TYPE, TYPE_PANEL);
            object.addProperty(MEMBER_ID, panel.id());
            return object;
        }
        if (node instanceof Tabs tabs) {
            object.addProperty(MEMBER_TYPE, TYPE_TABS);
            object.addProperty(MEMBER_ACTIVE_PANEL_ID, tabs.activePanelId());
            JsonArray panels = new JsonArray();
            for (Panel panel : tabs.panels()) {
                JsonObject child = new JsonObject();
                child.addProperty(MEMBER_TYPE, TYPE_PANEL);
                child.addProperty(MEMBER_ID, panel.id());
                panels.add(child);
            }
            object.add(MEMBER_PANELS, panels);
            return object;
        }
        Split split = (Split) node;
        object.addProperty(MEMBER_TYPE, TYPE_SPLIT);
        object.addProperty(MEMBER_AXIS, toWireAxis(split.axis()));
        object.add(MEMBER_RATIO, new JsonPrimitive(BigDecimal.valueOf(split.ratio())));
        object.add(MEMBER_FIRST, encodeNode(split.first()));
        object.add(MEMBER_SECOND, encodeNode(split.second()));
        return object;
    }

    // ------------------------------------------------------------------ 解码

    /**
     * 解析严格 JSON 文本并还原为冻结的停靠布局。
     *
     * @param strictJson 严格 JSON 文本，非空
     * @return 已通过全树验证的布局
     * @throws net.ptcrys.fpsmatch.editor.common.codec.JsonContractException JSON 词法、全局预算或重复键不合法
     *                                                                       （原样上抛）
     * @throws IllegalArgumentException                                      合法 JSON 但 wire 结构、成员类型、ratio 或树约束不符
     */
    public static DockLayout decode(String strictJson) {
        JsonElement parsed = ExactJson.parse(strictJson).toJson();
        JsonObject rootObject = requireObject(parsed, "");
        int schemaVersion = requireSchemaVersion(requireMember(rootObject, MEMBER_SCHEMA_VERSION, ""),
                childPointer("", MEMBER_SCHEMA_VERSION));
        String rootPath = childPointer("", MEMBER_ROOT);
        JsonObject nodeObject = requireObject(requireMember(rootObject, MEMBER_ROOT, ""), rootPath);

        // 第一步：只读预检。按规格账目核对深度与节点总数，任何超限都在进入/构造对应节点之前失败，
        // 且失败指针指向越界节点自身。
        checkBudget(nodeObject, rootPath, 1, 0);

        // 第二步：构造。预检已通过，此处不会因预算原因失败。
        DockNode root = decodeNode(nodeObject, rootPath, 1);
        // 树已构造完成，但全树约束（叶子 id 唯一、恰好一个 viewport）只在冻结构造器内校核。
        // 捕获该域异常并补上 /root 指针与真实 cause，不得裸抛。只捕获 IllegalArgumentException，
        // 不捕获 Throwable/Error，也不重包先前的 ExactJson 或预算异常。
        try {
            return new DockLayout(schemaVersion, root);
        } catch (IllegalArgumentException e) {
            throw semantic(rootPath, e);
        }
    }

    /**
     * 只读预算预检：按 P03 账目遍历 wire 树。
     *
     * <p>
     * 节点计账：panel 1；tabs 1 + 面板子节点数（Tabs 本身 1 个节点，其面板子节点各 1 个，深度各 +1）；
     * split 1 + 两个子树。深度：根为 1，每个子节点 +1。
     *
     * @param object 当前节点 JSON 对象
     * @param path   当前节点 JSON pointer
     * @param depth  当前节点 dock 深度（根为 1）
     * @param prior  进入当前节点之前已计入的节点数（祖先与其更早的兄弟分支）
     * @return 以当前节点为根的子树节点数
     */
    private static int checkBudget(JsonObject object, String path, int depth, int prior) {
        if (depth > DockValidation.MAX_DEPTH) {
            throw new IllegalArgumentException("dock node depth " + depth + " exceeds limit " + DockValidation.MAX_DEPTH + " at " + display(path));
        }
        int self = prior + 1;
        if (self > DockValidation.MAX_NODES) {
            throw new IllegalArgumentException("dock node count " + self + " exceeds limit " + DockValidation.MAX_NODES + " at " + display(path));
        }
        JsonElement typeElement = requireMember(object, MEMBER_TYPE, path);
        String type = requireString(typeElement, childPointer(path, MEMBER_TYPE));
        switch (type) {
            case TYPE_PANEL:
                return 1;
            case TYPE_TABS:
                return checkTabsBudget(object, path, depth, self);
            case TYPE_SPLIT:
                return checkSplitBudget(object, path, depth, self);
            default:
                throw new IllegalArgumentException("unknown dock node type '" + type + "' at " + display(childPointer(path, MEMBER_TYPE)));
        }
    }

    private static int checkTabsBudget(JsonObject object, String path, int depth, int used) {
        JsonArray panels = requirePanelsArray(object, path);
        int size = panels.size();
        if (size == 0) {
            throw new IllegalArgumentException(
                    "tabs panels must not be empty at " + display(childPointer(path, MEMBER_PANELS)));
        }
        if (size > DockValidation.MAX_NODES - 1) {
            throw new IllegalArgumentException("tabs panel count " + size + " exceeds limit " + (DockValidation.MAX_NODES - 1) + " at " + display(childPointer(path, MEMBER_PANELS)));
        }
        int total = used + size;
        if (total > DockValidation.MAX_NODES) {
            // 第一个超出全树预算的 panel 的 0-based index。used 已含当前 Tabs 与之前所有已知节点，
            // 故 index = MAX_NODES - used 恰为越界 panel 自身；size > 63 的单组情形已在上方按数组早拒。
            int index = DockValidation.MAX_NODES - used;
            throw new IllegalArgumentException("dock node count " + total + " exceeds limit " + DockValidation.MAX_NODES + " at " + display(indexPointer(path, MEMBER_PANELS, index)));
        }
        int childDepth = depth + 1;
        if (childDepth > DockValidation.MAX_DEPTH) {
            // 数组已确认非空，首个已知 child 即越界节点本身，不指数组、不等 Tabs 构造后晚拒。
            throw new IllegalArgumentException("dock node depth " + childDepth + " exceeds limit " + DockValidation.MAX_DEPTH + " at " + display(indexPointer(path, MEMBER_PANELS, 0)));
        }
        for (int i = 0; i < size; i++) {
            String itemPath = indexPointer(path, MEMBER_PANELS, i);
            JsonObject item = requireObject(panels.get(i), itemPath);
            JsonElement itemTypeElement = requireMember(item, MEMBER_TYPE, itemPath);
            String itemType = requireString(itemTypeElement, childPointer(itemPath, MEMBER_TYPE));
            if (!TYPE_PANEL.equals(itemType)) {
                throw new IllegalArgumentException("tabs child must be type 'panel' but was '" + itemType + "' at " + display(itemPath));
            }
        }
        return total - (used - 1);
    }

    private static int checkSplitBudget(JsonObject object, String path, int depth, int used) {
        requireMember(object, MEMBER_AXIS, path);
        requireMember(object, MEMBER_RATIO, path);
        String firstPath = childPointer(path, MEMBER_FIRST);
        JsonObject first = requireObject(requireMember(object, MEMBER_FIRST, path), firstPath);
        String secondPath = childPointer(path, MEMBER_SECOND);
        JsonObject second = requireObject(requireMember(object, MEMBER_SECOND, path), secondPath);
        int firstCount = checkBudget(first, firstPath, depth + 1, used);
        int secondCount = checkBudget(second, secondPath, depth + 1, used + firstCount);
        return 1 + firstCount + secondCount;
    }

    /** 解码已通过预算预检的节点。 */
    private static DockNode decodeNode(JsonObject object, String path, int depth) {
        String type = requireString(requireMember(object, MEMBER_TYPE, path),
                childPointer(path, MEMBER_TYPE));
        switch (type) {
            case TYPE_PANEL:
                return decodePanel(object, path);
            case TYPE_TABS:
                return decodeTabs(object, path, depth);
            case TYPE_SPLIT:
                return decodeSplit(object, path, depth);
            default:
                // 预检已覆盖，此处仅为穷尽性。
                throw new IllegalArgumentException("unknown dock node type '" + type + "' at " + display(childPointer(path, MEMBER_TYPE)));
        }
    }

    private static Panel decodePanel(JsonObject object, String path) {
        String id = requireString(requireMember(object, MEMBER_ID, path),
                childPointer(path, MEMBER_ID));
        try {
            return new Panel(id);
        } catch (IllegalArgumentException e) {
            throw semantic(path, e);
        }
    }

    private static Tabs decodeTabs(JsonObject object, String path, int depth) {
        String activePanelId = requireString(requireMember(object, MEMBER_ACTIVE_PANEL_ID, path),
                childPointer(path, MEMBER_ACTIVE_PANEL_ID));
        JsonArray panelsArray = requirePanelsArray(object, path);
        int size = panelsArray.size();
        List<Panel> panels = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String itemPath = indexPointer(path, MEMBER_PANELS, i);
            JsonObject item = requireObject(panelsArray.get(i), itemPath);
            String itemType = requireString(requireMember(item, MEMBER_TYPE, itemPath),
                    childPointer(itemPath, MEMBER_TYPE));
            if (!TYPE_PANEL.equals(itemType)) {
                throw new IllegalArgumentException("tabs child must be type 'panel' but was '" + itemType + "' at " + display(itemPath));
            }
            panels.add(decodePanel(item, itemPath));
        }
        try {
            return new Tabs(activePanelId, panels);
        } catch (IllegalArgumentException e) {
            throw semantic(path, e);
        }
    }

    private static Split decodeSplit(JsonObject object, String path, int depth) {
        String axisText = requireString(requireMember(object, MEMBER_AXIS, path),
                childPointer(path, MEMBER_AXIS));
        Axis axis = toDomainAxis(axisText, childPointer(path, MEMBER_AXIS));
        double ratio = requireRatio(requireMember(object, MEMBER_RATIO, path),
                childPointer(path, MEMBER_RATIO));

        String firstPath = childPointer(path, MEMBER_FIRST);
        DockNode first = decodeNode(
                requireObject(requireMember(object, MEMBER_FIRST, path), firstPath), firstPath, depth + 1);
        String secondPath = childPointer(path, MEMBER_SECOND);
        DockNode second = decodeNode(
                requireObject(requireMember(object, MEMBER_SECOND, path), secondPath), secondPath, depth + 1);
        try {
            return new Split(axis, ratio, first, second);
        } catch (IllegalArgumentException e) {
            throw semantic(path, e);
        }
    }

    // -------------------------------------------------------------- 成员读取

    private static JsonArray requirePanelsArray(JsonObject object, String path) {
        JsonElement element = requireMember(object, MEMBER_PANELS, path);
        String pointer = childPointer(path, MEMBER_PANELS);
        if (element == null || !element.isJsonArray()) {
            throw typeMismatch(pointer, "array of panel objects");
        }
        return element.getAsJsonArray();
    }

    private static JsonElement requireMember(JsonObject object, String member, String path) {
        JsonElement value = object.get(member);
        if (value == null || value.isJsonNull()) {
            // 指针精确到缺失成员本身，便于定位。
            throw new IllegalArgumentException("missing required member '" + member + "' at " + display(childPointer(path, member)));
        }
        return value;
    }

    private static JsonObject requireObject(JsonElement element, String path) {
        if (element == null || !element.isJsonObject()) {
            throw typeMismatch(path, "object");
        }
        return element.getAsJsonObject();
    }

    private static String requireString(JsonElement element, String path) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw typeMismatch(path, "string");
        }
        return element.getAsString();
    }

    private static int requireSchemaVersion(JsonElement element, String path) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw typeMismatch(path, "integer number");
        }
        BigDecimal value = toExactDecimal(element, path);
        if (value.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException(
                    "unsupported schemaVersion " + value.toPlainString() + " at " + display(path));
        }
        try {
            return value.intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "unsupported schemaVersion " + value.toPlainString() + " at " + display(path), e);
        }
    }

    private static double requireRatio(JsonElement element, String path) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw typeMismatch(path, "number");
        }
        BigDecimal value = toExactDecimal(element, path);
        // 先按精确十进制判闭区间，再落到 double：避免边界值被二进制舍入拉进合法区间。
        if (value.compareTo(RATIO_MIN) < 0 || value.compareTo(RATIO_MAX) > 0) {
            throw new IllegalArgumentException("split ratio " + value.toPlainString() + " outside closed interval [0.05, 0.95] at " + display(path));
        }
        double ratio = value.doubleValue();
        if (Double.isNaN(ratio) || Double.isInfinite(ratio)) {
            throw new IllegalArgumentException("split ratio is not finite at " + display(path));
        }
        return ratio;
    }

    /** 把数字元素转成精确 {@link BigDecimal}；{@code JsonPrimitive} 保留原文，故无损。 */
    private static BigDecimal toExactDecimal(JsonElement element, String path) {
        String literal = element.getAsJsonPrimitive().getAsString();
        try {
            return new BigDecimal(literal);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "invalid number literal '" + literal + "' at " + display(path), e);
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static String toWireAxis(Axis axis) {
        return axis == Axis.HORIZONTAL ? AXIS_HORIZONTAL : AXIS_VERTICAL;
    }

    private static Axis toDomainAxis(String text, String path) {
        if (AXIS_HORIZONTAL.equals(text)) {
            return Axis.HORIZONTAL;
        }
        if (AXIS_VERTICAL.equals(text)) {
            return Axis.VERTICAL;
        }
        throw new IllegalArgumentException("unknown split axis '" + text + "' at " + display(path));
    }

    private static IllegalArgumentException typeMismatch(String path, String expected) {
        return new IllegalArgumentException("expected " + expected + " at " + display(path));
    }

    private static IllegalArgumentException semantic(String path, IllegalArgumentException cause) {
        return new IllegalArgumentException(
                "invalid dock node at " + display(path) + ": " + cause.getMessage(), cause);
    }

    private static String display(String path) {
        return path.isEmpty() ? "(root)" : path;
    }

    private static String childPointer(String parent, String member) {
        return parent + "/" + escapePointer(member);
    }

    private static String indexPointer(String parent, String member, int index) {
        return childPointer(parent, member) + "/" + index;
    }

    private static String escapePointer(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
