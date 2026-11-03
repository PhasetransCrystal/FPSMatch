package net.ptcrys.fpsmatch.editor.common.codec;

import net.ptcrys.fpsmatch.editor.common.model.FieldPath;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Wire 预算累加器：在真正编码之前，按 canonical UTF-8 语义核对一次编码会产生的节点数、深度与
 * 字节数，任一超限即拒绝，且失败后不再允许继续累积。
 *
 * <p>
 * 它只读取已经由 {@link ExactJson} 认证过的 canonical 文本，不解析 JSON、不复制树、不构造
 * {@code StringBuilder}/{@code JsonElement} 副本，也不缓存跨请求状态：每次编码新建一个实例，
 * 实例内只有计数器和失败标志。它不定义第二套数字或字符串规范——数字一律经
 * {@link ExactJsonNumber#parse} 认证，字符串一律按 {@link ExactJson} 的 canonical 转义规则计宽。
 * </p>
 *
 * <p>
 * 预算对象是局部可变对象，不对外公开，也不在领域层或客户端持有。
 * </p>
 */
final class PayloadWireBudget {

    /** 单次操作的累计字节上限，引用冻结的公共常量。 */
    private static final long MAX_BYTES = ExactJson.MAX_UTF8_BYTES;

    /** 单次操作的累计节点上限，引用冻结的公共常量。 */
    private static final long MAX_NODES = ExactJson.MAX_NODES;

    /** wire 深度上限，引用冻结的公共常量。 */
    private static final int MAX_DEPTH = ExactJson.MAX_DEPTH;

    /** {@code addMembers} 的 ownerPath 段数上限：保证真实碰撞可安全追加第 65 段。 */
    private static final int MAX_OWNER_SEGMENTS = ExactJson.MAX_DEPTH;

    /**
     * 短控制字符的 2 字节转义：b、t、n、f、r，即码点 0x08、0x09、0x0A、0x0C、0x0D。
     * 其余控制字符写出反斜杠加 u 加四位十六进制，占 6 字节。
     */
    private static boolean isShortEscape(char c) {
        return c == 0x08 || c == 0x09 || c == 0x0A || c == 0x0C || c == 0x0D;
    }

    private long bytes;

    private int nodes;

    private boolean poisoned;

    PayloadWireBudget() {
        // 无参构造：package-private，无 public API。
    }

    // ------------------------------------------------------------------
    // 对象与数组骨架
    // ------------------------------------------------------------------

    /**
     * 记一个对象开始：一个 node 与两个字节（{@code '{'} 与 {@code '}'}）。
     * 不需要 close 方法。
     */
    void addObject(int depth, FieldPath path) {
        requireUsable();
        requireDepth(depth, path);
        final long nodeDelta = 1;
        final long byteDelta = 2;
        checkDepthAgainstProgress(depth, nodeDelta, byteDelta, path);
        this.nodes += (int) nodeDelta;
        this.bytes += byteDelta;
    }

    /** 记一个数组开始：一个 node 与两个字节（{@code '['} 与 {@code ']'}）。 */
    void addArray(int depth, FieldPath path) {
        requireUsable();
        requireDepth(depth, path);
        final long nodeDelta = 1;
        final long byteDelta = 2;
        checkDepthAgainstProgress(depth, nodeDelta, byteDelta, path);
        this.nodes += (int) nodeDelta;
        this.bytes += byteDelta;
    }

    /**
     * 记一个对象的成员：完整转义 key 的引号、冒号，以及非首成员的逗号。
     * key 本身不算 node。
     *
     * @param zeroBasedMemberIndex 该对象内的成员序号，0 表示首成员，无逗号
     */
    void addMember(String key, int zeroBasedMemberIndex, FieldPath childPath) {
        requireUsable();
        if (key == null) {
            poison(new IllegalArgumentException("member key must not be null"));
        }
        if (childPath == null) {
            poison(new IllegalArgumentException("childPath must not be null"));
        }
        if (zeroBasedMemberIndex < 0) {
            poison(new IllegalArgumentException("zeroBasedMemberIndex must not be negative: " + zeroBasedMemberIndex));
        }
        requireKeyText(key, childPath);

        final long keyBytes = quotedEscapedKeyUtf8Bytes(key);
        final long byteDelta = keyBytes + 1 /* ':' */ + (zeroBasedMemberIndex == 0 ? 0 : 1 /* ',' */);
        checkBytesAndNodes(0, byteDelta, childPath);
        this.bytes += byteDelta;
    }

    /**
     * 记一个数组元素分隔符：index 0 贡献 0 字节，其余贡献 1 字节，不算 node。
     *
     * @param zeroBasedElementIndex 该数组内的元素序号
     */
    void addArraySeparator(int zeroBasedElementIndex, FieldPath elementPath) {
        requireUsable();
        if (elementPath == null) {
            poison(new IllegalArgumentException("elementPath must not be null"));
        }
        if (zeroBasedElementIndex < 0) {
            poison(new IllegalArgumentException("zeroBasedElementIndex must not be negative: " + zeroBasedElementIndex));
        }
        if (zeroBasedElementIndex == 0) {
            return;
        }
        checkBytesAndNodes(0, 1, elementPath);
        this.bytes += 1;
    }

    // ------------------------------------------------------------------
    // 标量
    // ------------------------------------------------------------------

    /** 记一个字符串标量：一个 node 与 canonical quoted UTF-8 宽度。 */
    void addString(String value, int depth, FieldPath path) {
        requireUsable();
        requireDepth(depth, path);
        if (value == null) {
            poison(new IllegalArgumentException("value must not be null"));
        }
        requireValidUnicode(value, path, false);

        final long utf8 = decodedUtf8Bytes(value);
        if (utf8 > ExactJson.MAX_STRING_UTF8_BYTES) {
            poison(new SchemaException(SchemaException.CODE_LIMIT_EXCEEDED, path, "a string holds at most " + ExactJson.MAX_STRING_UTF8_BYTES + " UTF-8 bytes, this one holds " + utf8));
        }
        checkDepthAgainstProgress(depth, 1, quotedStringUtf8Bytes(value), path);
        this.nodes += 1;
        this.bytes += quotedStringUtf8Bytes(value);
    }

    /**
     * 记一个数字标量：一个 node，字节按 canonical ASCII 文本的 {@link String#length()} 计。
     * 输入必须已经是 canonical：非 canonical 是调用方错误（IAE），不做隐式规范化。
     */
    void addNumber(String canonicalNumber, int depth, FieldPath path) {
        requireUsable();
        requireDepth(depth, path);
        if (canonicalNumber == null) {
            poison(new IllegalArgumentException("canonicalNumber must not be null"));
        }
        final ExactJsonNumber.ParsedNumber parsed;
        try {
            parsed = ExactJsonNumber.parse(canonicalNumber, path.pointer());
        } catch (JsonContractException failure) {
            poison(new SchemaException(failure.code(), path, failure.getMessage(), failure));
            return;
        }
        if (!canonicalNumber.equals(parsed.canonical())) {
            poison(new IllegalArgumentException("the number must already be canonical: expected " + parsed.canonical() + " but was " + canonicalNumber));
        }
        checkDepthAgainstProgress(depth, 1, canonicalNumber.length(), path);
        this.nodes += 1;
        this.bytes += canonicalNumber.length();
    }

    // ------------------------------------------------------------------
    // 认证片段的整树计入
    // ------------------------------------------------------------------

    /**
     * 把一段已经认证的 canonical 片段整体计入：读取其全部字节宽度，用有界扫描求出相对节点数与
     * 最大相对深度，再从插入位置推断真实深度，最后一次性更新计数。
     *
     * <p>
     * 本方法不做任何 reserved 比较，因此扫描时对全部 key（含根 key）一律只跳过 quoted 内容：
     * 不解码、不建 {@code StringBuilder}、不 substring，profile 也不保存任何 key 列表或输出文本。
     * 内存只与片段深度（≤64 层递归）有关，与片段内 key 数量无关。
     * </p>
     *
     * <p>
     * 片段根的未知后代一律不编造路径：预算失败的 FieldPath 固定为该片段的插入位置。
     * </p>
     */
    void addValue(ExactJson.FrozenJson value, int depth, FieldPath path) {
        requireUsable();
        requireDepth(depth, path);
        if (value == null) {
            poison(new IllegalArgumentException("value must not be null"));
        }

        final int fragmentBytes = value.utf8Size();
        // 扫描之前先核累计字节，使多个大片段立即被拒而不必逐个遍历。
        checkBytesAndNodes(0, fragmentBytes, path);

        final FragmentProfile profile = scanFragment(value.canonical(), null, null);
        final long realDepth = (long) depth + profile.maxRelativeDepth;
        if (realDepth > MAX_DEPTH) {
            poison(new SchemaException(SchemaException.CODE_LIMIT_EXCEEDED, path, "the real wire depth would be " + realDepth + ", the contract allows at most " + MAX_DEPTH));
        }
        checkBytesAndNodes(profile.nodes, 0, path);
        this.nodes += profile.nodes;
        this.bytes += fragmentBytes;
    }

    /**
     * 计入一个 object-only 片段的成员：先按累计字节整体核 body，再做一次 profile 扫描（根 key
     * 遇到时当场与 reserved 比较并立即释放该局部 key），全部通过后一次性更新计数，
     * 并返回该片段根的实际成员数。
     *
     * @param ownerPath        所属对象的业务定位路径，最多 64 段
     * @param ownerDepth       该对象真实的 wire 深度，不是 extras 虚拟包装层
     * @param knownMemberCount 调用方已知的成员数，非负且不超过节点上限
     */
    int addMembers(ExactJson.FrozenJson members, Set<String> reserved,
                   int ownerDepth, int knownMemberCount, FieldPath ownerPath) {
        requireUsable();
        if (ownerPath == null) {
            poison(new IllegalArgumentException("ownerPath must not be null"));
        }
        // 真实碰撞需要追加第 65 段完整 key，因此 ownerPath 自身最多 64 段；在扫描与预算更新前拒绝。
        if (ownerPath.segments().size() > MAX_OWNER_SEGMENTS) {
            poison(new IllegalArgumentException("ownerPath holds at most " + MAX_OWNER_SEGMENTS + " segments, this one holds " + ownerPath.segments().size()));
        }
        requireDepth(ownerDepth, ownerPath);
        if (members == null) {
            poison(new IllegalArgumentException("members must not be null"));
        }
        if (reserved == null) {
            poison(new IllegalArgumentException("reserved must not be null"));
        }
        if (knownMemberCount < 0 || knownMemberCount > MAX_NODES) {
            poison(new IllegalArgumentException("knownMemberCount must be between 0 and " + MAX_NODES + ": " + knownMemberCount));
        }
        final List<String> reservedKeys = snapshotReserved(reserved, ownerPath);

        // object-only：从已认证 canonical 的首字符当场确认，非 object 片段是调用方错误。
        final String canonical = members.canonical();
        if (canonical.isEmpty() || canonical.charAt(0) != '{') {
            poison(new SchemaException(SchemaException.CODE_WRONG_TYPE, ownerPath,
                    "addMembers accepts an object only, the fragment root is not an object"));
        }

        // 虚拟 {} 的 body 宽度：去掉根括号。空 object 贡献 0，不因 ownerDepth==64 而多算一层。
        // 关键：这一步在任何 profile 扫描之前完成，使超预算的 extras 被早拒而不必先遍历。
        final long bodyBytes = members.utf8Size() - 2;
        // 非空由 canonical.length() > 2 判断，与 rootMemberCount 无关，故无需先扫描。
        final long connector = canonical.length() > 2 && knownMemberCount > 0 ? 1 : 0;
        checkBytesAndNodes(0, bodyBytes + connector, ownerPath);

        // 到这里参数与字节预算都已通过，再做唯一一次 profile 扫描；根 key 当场比较、当场释放。
        // scanner 在扫描中抛出的 RuntimeException（含根 reserved 碰撞）必须让本 budget 进入
        // poison 态：不 catch 后正常继续，也不写任何部分计数。
        final FragmentProfile profile;
        try {
            profile = scanFragment(canonical, reservedKeys, ownerPath);
        } catch (RuntimeException failure) {
            poison(failure);
            throw failure; // 不可达：poison 总是抛出，仅为满足编译器控制流。
        }
        if (!profile.objectRoot) {
            poison(new SchemaException(SchemaException.CODE_WRONG_TYPE, ownerPath,
                    "addMembers accepts an object only, the fragment root is not an object"));
        }

        final long realDepth = (long) ownerDepth + profile.maxRelativeDepth;
        if (realDepth > MAX_DEPTH) {
            poison(new SchemaException(SchemaException.CODE_LIMIT_EXCEEDED, ownerPath, "the real wire depth would be " + realDepth + ", the contract allows at most " + MAX_DEPTH));
        }
        checkBytesAndNodes(profile.nodes - 1, bodyBytes + connector, ownerPath);

        this.nodes += profile.nodes - 1;
        this.bytes += bodyBytes + connector;
        return profile.rootMemberCount;
    }

    // ------------------------------------------------------------------
    // 只读计数
    // ------------------------------------------------------------------

    /** 当前累计字节数；失败后调用抛 {@link IllegalStateException}。 */
    long bytes() {
        requireUsable();
        return this.bytes;
    }

    /** 当前累计节点数；失败后调用抛 {@link IllegalStateException}。 */
    int nodes() {
        requireUsable();
        return this.nodes;
    }

    // ------------------------------------------------------------------
    // 参数与预算检查（全部先检查、后记账）
    // ------------------------------------------------------------------

    private void requireUsable() {
        if (this.poisoned) {
            throw new IllegalStateException("this payload wire budget has already failed and cannot " + "accept or report anything further");
        }
    }

    private void poison(RuntimeException failure) {
        this.poisoned = true;
        throw failure;
    }

    private void requireDepth(int depth, FieldPath path) {
        if (path == null) {
            poison(new IllegalArgumentException("path must not be null"));
        }
        if (depth < 0) {
            poison(new IllegalArgumentException("depth must not be negative: " + depth));
        }
        if (depth > MAX_DEPTH) {
            poison(new SchemaException(SchemaException.CODE_LIMIT_EXCEEDED, path, "depth " + depth + " exceeds the contract limit " + MAX_DEPTH));
        }
    }

    /** 标量或骨架操作：先核本次字节与节点，再核真实 depth 是否越过上限。 */
    private void checkDepthAgainstProgress(int depth, long nodeDelta, long byteDelta, FieldPath path) {
        checkBytesAndNodes(nodeDelta, byteDelta, path);
        final long realDepth = depth;
        if (realDepth > MAX_DEPTH) {
            poison(new SchemaException(SchemaException.CODE_LIMIT_EXCEEDED, path, "depth " + realDepth + " exceeds the contract limit " + MAX_DEPTH));
        }
    }

    private void checkBytesAndNodes(long nodeDelta, long byteDelta, FieldPath path) {
        if (nodeDelta < 0 || byteDelta < 0) {
            poison(new IllegalStateException("internal accounting error: negative delta"));
        }
        final long nextBytes = this.bytes + byteDelta;
        if (nextBytes > MAX_BYTES) {
            poison(new SchemaException(SchemaException.CODE_LIMIT_EXCEEDED, path, "the payload would hold " + nextBytes + " bytes, the contract allows at most " + MAX_BYTES));
        }
        final long nextNodes = (long) this.nodes + nodeDelta;
        if (nextNodes > MAX_NODES) {
            poison(new SchemaException(SchemaException.CODE_LIMIT_EXCEEDED, path, "the payload would hold " + nextNodes + " nodes, the contract allows at most " + MAX_NODES));
        }
    }

    /** key 与字符串共同的前置：合法 Unicode，且 decoded UTF-8 不超过各自预算。 */
    private void requireKeyText(String key, FieldPath path) {
        requireValidUnicode(key, path, true);
        final long utf8 = decodedUtf8Bytes(key);
        if (utf8 > ExactJson.MAX_KEY_UTF8_BYTES) {
            poison(new SchemaException(SchemaException.CODE_LIMIT_EXCEEDED, path, "a member key holds at most " + ExactJson.MAX_KEY_UTF8_BYTES + " UTF-8 bytes, this one holds " + utf8));
        }
    }

    /** 逐个原字符核对合法 Unicode：不分配转义或 UTF-8 副本。 */
    private void requireValidUnicode(String text, FieldPath path, boolean asKey) {
        final int length = text.length();
        for (int i = 0; i < length; i++) {
            final char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= length || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    poison(new SchemaException(SchemaException.CODE_INVALID_UNICODE, path,
                            "a lone high surrogate is not a character in " + (asKey ? "a member key" : "a string")));
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                poison(new SchemaException(SchemaException.CODE_INVALID_UNICODE, path,
                        "a lone low surrogate is not a character in " + (asKey ? "a member key" : "a string")));
            }
        }
    }

    /** decoded UTF-8 字节数：逐字符累加，不调用 getBytes、不分配数组。 */
    private static long decodedUtf8Bytes(String text) {
        long total = 0;
        final int length = text.length();
        for (int i = 0; i < length; i++) {
            final char c = text.charAt(i);
            if (c < 0x80) {
                total += 1;
            } else if (c < 0x800) {
                total += 2;
            } else if (Character.isHighSurrogate(c)) {
                total += 4;
                i++;
            } else {
                total += 3;
            }
        }
        return total;
    }

    /** canonical quoted 字符串的 UTF-8 宽度：两枚引号加各字符的 canonical 转义宽度。 */
    private static long quotedStringUtf8Bytes(String text) {
        long total = 2;
        final int length = text.length();
        for (int i = 0; i < length; i++) {
            final char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                total += 2;
            } else if (c < 0x20) {
                total += isShortEscape(c) ? 2 : 6;
            } else if (c < 0x80) {
                total += 1;
            } else if (c < 0x800) {
                total += 2;
            } else if (Character.isHighSurrogate(c)) {
                total += 4;
                i++;
            } else {
                total += 3;
            }
        }
        return total;
    }

    /** member key 的 canonical quoted 宽度：与字符串同一套转义规则。 */
    private static long quotedEscapedKeyUtf8Bytes(String key) {
        return quotedStringUtf8Bytes(key);
    }

    /** reserved 集合的防御性 snapshot：先检查 size，再至多 64 次 iterator 读取。 */
    private List<String> snapshotReserved(Set<String> reserved, FieldPath ownerPath) {
        final int reported = reserved.size();
        if (reported < 0 || reported > MAX_OWNER_SEGMENTS) {
            poison(new IllegalArgumentException("reserved holds at most " + MAX_OWNER_SEGMENTS + " keys, its size() reported " + reported));
        }
        final java.util.ArrayList<String> snapshot = new java.util.ArrayList<>(reported);
        final Iterator<String> iterator = reserved.iterator();
        int seen = 0;
        while (iterator.hasNext()) {
            if (seen == MAX_OWNER_SEGMENTS) {
                poison(new IllegalArgumentException("reserved holds at most " + MAX_OWNER_SEGMENTS + " keys, more were supplied"));
            }
            final String key = iterator.next();
            if (key == null) {
                poison(new IllegalArgumentException("reserved must not hold a null key"));
            }
            requireValidUnicode(key, ownerPath, true);
            if (decodedUtf8Bytes(key) > ExactJson.MAX_KEY_UTF8_BYTES) {
                poison(new SchemaException(SchemaException.CODE_LIMIT_EXCEEDED, ownerPath, "a reserved key holds at most " + ExactJson.MAX_KEY_UTF8_BYTES + " UTF-8 bytes"));
            }
            snapshot.add(key);
            seen++;
        }
        return snapshot;
    }

    /** reserved 比较：完整原文身份，不做转义等价归一。 */
    private static boolean containsIdentity(List<String> reservedKeys, String memberKey) {
        for (String key : reservedKeys) {
            if (key.equals(memberKey)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // canonical 片段的有界扫描（无树 profile、无根 key 列表）
    // ------------------------------------------------------------------

    /**
     * 片段的 profile：只有固定标量。
     *
     * <p>
     * 不保存任何 key/value 列表，也不保存完整输出文本；内存与片段内 key 数量无关。
     * </p>
     */
    private static final class FragmentProfile {

        private int nodes;

        private int maxRelativeDepth;

        private boolean objectRoot;

        private int rootMemberCount;
    }

    /**
     * 扫描已认证 canonical 文本，仅提取固定标量 profile：不建 JsonElement、不 clone 树、
     * 不 substring 整个值，也不保存任何 key。只用 cursor 与有界递归（片段最多 64 层）。
     *
     * <p>
     * 无 reserved 比较模式（{@code reservedKeys == null}，即 {@link #addValue}）下，
     * 全部 key 一律只 {@code skipString}：不解码、不建 {@code StringBuilder}、不 substring。
     * </p>
     *
     * <p>
     * 有 reserved 比较模式（{@link #addMembers}）下，只有当前片段根的 key 会被解码，
     * 当场与至多 64 个 reserved 做完整 {@code equals}，比较后立即释放该局部 key，
     * 不保存供第二轮扫描；嵌套 key 始终只 skip。碰撞当场抛
     * {@link SchemaException} {@code RESERVED_COLLISION(ownerPath.child(actualKey))}。
     * </p>
     *
     * <p>
     * 无法完整消费已认证文本属内部错误，抛 {@link IllegalStateException}。
     * </p>
     */
    private static FragmentProfile scanFragment(String canonical, List<String> reservedKeys, FieldPath ownerPath) {
        final Scanner scanner = new Scanner(canonical, reservedKeys, ownerPath);
        final FragmentProfile profile = new FragmentProfile();
        final int[] slots = new int[2]; // 0: nodes, 1: maxRelativeDepth
        scanner.scanValue(0, slots, true, profile);
        if (scanner.cursor != canonical.length()) {
            throw new IllegalStateException("the frozen canonical text was not fully consumed: stopped at " + scanner.cursor + " of " + canonical.length());
        }
        profile.nodes = slots[0];
        profile.maxRelativeDepth = slots[1];
        return profile;
    }

    /** canonical 文本的游标扫描器：只识别结构，不解释语义。 */
    private static final class Scanner {

        private final String text;

        /** 可选的 reserved snapshot；为 null 表示本次扫描不做任何 key 解码与比较。 */
        private final List<String> reservedKeys;

        /** 碰撞时用于定位的 owner 路径；仅在 reservedKeys 非 null 时使用。 */
        private final FieldPath ownerPath;

        private int cursor;

        Scanner(String text, List<String> reservedKeys, FieldPath ownerPath) {
            this.text = text;
            this.reservedKeys = reservedKeys;
            this.ownerPath = ownerPath;
        }

        /**
         * 消费一个值。
         *
         * @param depth          该值在片段内的相对深度
         * @param slots          [0] 节点累计，[1] 最大相对深度
         * @param isFragmentRoot 是否为片段根
         * @param profile        片段根信息收集器（仅根层写入）
         */
        void scanValue(int depth, int[] slots, boolean isFragmentRoot, FragmentProfile profile) {
            if (this.cursor >= this.text.length()) {
                throw new IllegalStateException("the frozen canonical text ended early");
            }
            final char c = this.text.charAt(this.cursor);
            if (c == '{') {
                if (isFragmentRoot) {
                    profile.objectRoot = true;
                }
                slots[0]++;
                if (depth > slots[1]) {
                    slots[1] = depth;
                }
                scanObject(depth, slots, isFragmentRoot, profile);
            } else if (c == '[') {
                slots[0]++;
                if (depth > slots[1]) {
                    slots[1] = depth;
                }
                scanArray(depth, slots);
            } else if (c == '"') {
                slots[0]++;
                if (depth > slots[1]) {
                    slots[1] = depth;
                }
                skipString();
            } else {
                // 数字与 literal 各一个 node。
                slots[0]++;
                if (depth > slots[1]) {
                    slots[1] = depth;
                }
                if (c == 't' || c == 'f' || c == 'n') {
                    skipLiteral();
                } else {
                    skipNumber();
                }
            }
        }

        private void scanObject(int depth, int[] slots, boolean isFragmentRoot, FragmentProfile profile) {
            this.cursor++; // '{'
            int memberCount = 0;
            if (this.text.charAt(this.cursor) == '}') {
                this.cursor++;
                if (isFragmentRoot) {
                    profile.rootMemberCount = 0;
                }
                return;
            }
            while (true) {
                // key 只跳过 quoted 内容，其中的 {}[],: 与 escaped quote 都不算结构。
                final int keyStart = this.cursor + 1;
                skipString();
                final int keyEnd = this.cursor - 1;
                if (isFragmentRoot && this.reservedKeys != null && !this.reservedKeys.isEmpty()) {
                    // 只有根的 key 需要解码比较；当场解码、当场比较、当场释放，不保存供第二轮扫描。
                    // 判空依据是本方法已校验过的 snapshot，而不是原 Set 的 size()（size 可谎报）。
                    final String memberKey = decodeCanonicalKey(this.text, keyStart, keyEnd);
                    if (containsIdentity(this.reservedKeys, memberKey)) {
                        throw new SchemaException(SchemaException.CODE_RESERVED_COLLISION,
                                this.ownerPath.child(memberKey),
                                "the reserved field '" + memberKey + "' is already defined by the known schema");
                    }
                }
                memberCount++;
                this.cursor++; // ':'
                scanValue(depth + 1, slots, false, profile);
                if (this.text.charAt(this.cursor) == ',') {
                    this.cursor++;
                    continue;
                }
                this.cursor++; // '}'
                break;
            }
            if (isFragmentRoot) {
                profile.rootMemberCount = memberCount;
            }
        }

        private void scanArray(int depth, int[] slots) {
            this.cursor++; // '['
            if (this.text.charAt(this.cursor) == ']') {
                this.cursor++;
                return;
            }
            while (true) {
                scanValue(depth + 1, slots, false, null);
                if (this.text.charAt(this.cursor) == ',') {
                    this.cursor++;
                    continue;
                }
                this.cursor++; // ']'
                return;
            }
        }

        /** 跳过一段 quoted 内容：只处理引号与反斜杠转义，不解释转义语义。 */
        private void skipString() {
            if (this.text.charAt(this.cursor) != '"') {
                throw new IllegalStateException("expected a quoted string at " + this.cursor);
            }
            this.cursor++;
            while (true) {
                final char c = this.text.charAt(this.cursor);
                if (c == '\\') {
                    this.cursor += 2;
                    continue;
                }
                if (c == '"') {
                    this.cursor++;
                    return;
                }
                this.cursor++;
            }
        }

        private void skipLiteral() {
            final char c = this.text.charAt(this.cursor);
            this.cursor += c == 'n' ? 4 : (this.text.charAt(this.cursor + 1) == 'r' ? 4 : 5);
        }

        private void skipNumber() {
            while (this.cursor < this.text.length()) {
                final char c = this.text.charAt(this.cursor);
                if (c == ',' || c == '}' || c == ']') {
                    return;
                }
                this.cursor++;
            }
        }
    }

    /**
     * 把 canonical 的 quoted key 文本解码成小字符串：只处理八种 JSON 转义与 {@code \ u} 四位
     * hex，{@code '~'} 与 {@code '/'} 原样保留。解码结果只用于与 reserved 的完整原文身份比较。
     */
    private static String decodeCanonicalKey(String text, int start, int end) {
        final StringBuilder decoded = new StringBuilder(end - start);
        int i = start;
        while (i < end) {
            final char c = text.charAt(i);
            if (c != '\\') {
                decoded.append(c);
                i++;
                continue;
            }
            final char escape = text.charAt(i + 1);
            switch (escape) {
                case '"' -> decoded.append('"');
                case '\\' -> decoded.append('\\');
                case '/' -> decoded.append('/');
                case 'b' -> decoded.append('\b');
                case 'f' -> decoded.append('\f');
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case 'u' -> {
                    decoded.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
                    i += 6;
                    continue;
                }
                default -> throw new IllegalStateException("unknown escape in the frozen canonical key at " + i);
            }
            i += 2;
        }
        return decoded.toString();
    }
}
