package net.ptcrys.fpsmatch.editor.common.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.ptcrys.fpsmatch.editor.common.model.FieldPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * R06.1a 的正式契约测试：{@link PayloadWireBudget} 的 wire 预算累加语义。
 *
 * <p>所有期望值都由本测试独立手算或由本测试自己的模型算出，绝不复用生产实现的计算结果：字节、
 * 节点与深度都按 canonical UTF-8 语义逐个字符数出来，canonical 文本本身由冻结的
 * {@link ExactJson} 认证后读取，本测试只用 {@code canonical()} 与 {@code utf8Size()} 两个公共
 * 只读入口，不复制私有树接口、不反射、不调用 {@code toJson()}、不调用 {@code deepCopy()}。</p>
 */
class PayloadWireBudgetContractTest {

    private static final ExactJson.FrozenJson EMPTY_OBJECT = ExactJson.parse("{}");

    private static final ExactJson.FrozenJson EMPTY_ARRAY = ExactJson.parse("[]");

    // ------------------------------------------------------------------
    // golden：小 wire 手算
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an empty root object costs exactly two bytes and one node")
    void emptyRootObjectGolden() {
        final PayloadWireBudget budget = new PayloadWireBudget();
        budget.addObject(0, FieldPath.root());
        assertEquals(1, budget.nodes());
        assertEquals(2, budget.bytes());
    }

    @Test
    @DisplayName("a known member written by hand matches the hand-computed wire exactly")
    void knownMemberGolden() {
        // 手算 wire：{"id":1}
        //   '{' 1 + '"id"' 4 + ':' 1 + '1' 1 + '}' 1 == 8 bytes，nodes：对象 1 + 数字 1 == 2
        final PayloadWireBudget budget = new PayloadWireBudget();
        budget.addObject(0, FieldPath.root());
        budget.addMember("id", 0, FieldPath.root().child("id"));
        budget.addNumber("1", 1, FieldPath.root().child("id"));
        assertEquals(2, budget.nodes());
        assertEquals(8, budget.bytes());
        assertEquals(8, "{\"id\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("array separators, an empty array and empty extras are all free of hidden cost")
    void separatorAndEmptyArrayGolden() {
        // 手算 wire：[1,2] == 5 bytes、3 nodes
        final PayloadWireBudget budget = new PayloadWireBudget();
        budget.addArray(0, FieldPath.root());
        budget.addArraySeparator(0, FieldPath.root());
        budget.addNumber("1", 1, FieldPath.root());
        budget.addArraySeparator(1, FieldPath.root());
        budget.addNumber("2", 1, FieldPath.root());
        assertEquals(3, budget.nodes());
        assertEquals(5, budget.bytes());

        // 空数组只贡献 1 node / 2 bytes，且 index 0 的分隔符贡献 0。
        final PayloadWireBudget empty = new PayloadWireBudget();
        empty.addArray(1, FieldPath.root());
        empty.addArraySeparator(0, FieldPath.root());
        assertEquals(1, empty.nodes());
        assertEquals(2, empty.bytes());

        // 空 extras：0 node、0 body 字节、0 depth，不因 ownerDepth 高而多拒一层。
        final PayloadWireBudget extras = new PayloadWireBudget();
        extras.addObject(0, FieldPath.root());
        assertEquals(0, extras.addMembers(EMPTY_OBJECT, Set.of(), 0, 0, FieldPath.root()));
        assertEquals(1, extras.nodes());
        assertEquals(2, extras.bytes());
    }

    @Test
    @DisplayName("an empty extras object at owner depth 64 is accepted, not rejected by an extra level")
    void emptyExtrasAtMaxOwnerDepthGolden() {
        final PayloadWireBudget budget = new PayloadWireBudget();
        budget.addObject(64, FieldPath.root());
        assertEquals(0, budget.addMembers(EMPTY_OBJECT, Set.of(), 64, 0, FieldPath.root()));
        assertEquals(1, budget.nodes());
        assertEquals(2, budget.bytes());
    }

    @Test
    @DisplayName("every escaped scalar shape is counted by its canonical width")
    void escapedScalarGolden() {
        // 手算：emoji 在 canonical 里不转义，宽 4 字节，加两枚引号 == 6。
        final PayloadWireBudget emoji = new PayloadWireBudget();
        emoji.addString("\uD83D\uDE00", 0, FieldPath.root());
        assertEquals(1, emoji.nodes());
        assertEquals(6, emoji.bytes());

        // 短控制字符转义为 2 字节：\n -> "\\n"
        final PayloadWireBudget shortControl = new PayloadWireBudget();
        shortControl.addString("\n", 0, FieldPath.root());
        assertEquals(4, shortControl.bytes());

        // 其他控制字符转义为 6 字节：\u0001 -> "\\u0001"
        final PayloadWireBudget otherControl = new PayloadWireBudget();
        otherControl.addString("\u0001", 0, FieldPath.root());
        assertEquals(8, otherControl.bytes());

        // 引号与反斜杠各 2 字节。
        final PayloadWireBudget quote = new PayloadWireBudget();
        quote.addString("\"", 0, FieldPath.root());
        assertEquals(4, quote.bytes());

        final PayloadWireBudget backslash = new PayloadWireBudget();
        backslash.addString("\\", 0, FieldPath.root());
        assertEquals(4, backslash.bytes());

        // 斜杠在 canonical 中不转义，只 1 字节，故 "a/b" == 2 + 3 == 5。
        final PayloadWireBudget slash = new PayloadWireBudget();
        slash.addString("a/b", 0, FieldPath.root());
        assertEquals(5, slash.bytes());

        // 两字节字符：é 宽 2，故 "é" == 2 + 2 == 4。
        final PayloadWireBudget twoByte = new PayloadWireBudget();
        twoByte.addString("\u00e9", 0, FieldPath.root());
        assertEquals(4, twoByte.bytes());
    }

    @Test
    @DisplayName("all member key shapes and a 1024-character number are counted exactly")
    void keyShapesAndLongNumberGolden() {
        // 空 key："" 宽 0，加引号 2、冒号 1 == 3。
        final PayloadWireBudget emptyKey = new PayloadWireBudget();
        emptyKey.addMember("", 0, FieldPath.root());
        assertEquals(0, emptyKey.nodes());
        assertEquals(3, emptyKey.bytes());

        // 非首成员多一个逗号：`"a"` 3 + ':' 1 + ',' 1 == 5。
        final PayloadWireBudget second = new PayloadWireBudget();
        second.addMember("a", 1, FieldPath.root());
        assertEquals(5, second.bytes());

        // key 顺序不影响单次记账：'a' 与 'z' 同宽，首成员 3 + 1 == 4。
        final PayloadWireBudget sameWidth = new PayloadWireBudget();
        sameWidth.addMember("z", 0, FieldPath.root());
        assertEquals(4, sameWidth.bytes());

        // canonical 数字按 ASCII 长度计：1024 位全 9 的字面量本身就是 canonical，且压满词法上限。
        final String canonicalNumber = "9".repeat(ExactJson.MAX_NUMBER_LITERAL_CHARS);
        assertEquals(ExactJson.MAX_NUMBER_LITERAL_CHARS, canonicalNumber.length());
        final ExactJsonNumber.ParsedNumber parsed = ExactJsonNumber.parse(canonicalNumber, "/");
        assertEquals(canonicalNumber, parsed.canonical(),
                "the fixture must already be canonical, otherwise addNumber is required to reject it");
        final PayloadWireBudget number = new PayloadWireBudget();
        number.addNumber(canonicalNumber, 0, FieldPath.root());
        assertEquals(1, number.nodes());
        assertEquals(canonicalNumber.length(), number.bytes());

        // 非 canonical 输入必须被拒：这是调用方错误（IAE），不做隐式规范化，也不包装成 SchemaException。
        final String notCanonicalText = "1" + "0".repeat(1023);
        assertEquals("1E+1023", ExactJsonNumber.parse(notCanonicalText, "/").canonical(),
                "the probe fixture must actually be non-canonical for this counterexample to mean anything");
        final PayloadWireBudget notCanonical = new PayloadWireBudget();
        final IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> notCanonical.addNumber(notCanonicalText, 0, FieldPath.root()));
        assertFalse(rejected instanceof SchemaException,
                "a non-canonical number is a caller error, not a schema violation");
        assertPoisoned(notCanonical);
    }

    // ------------------------------------------------------------------
    // 独立 oracle：1000 个不同 fragment
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a thousand distinct fragments match an independently computed node and depth model")
    void thousandFragmentsMatchIndependentOracle() {
        final Random random = new Random(20261015L);
        final Set<String> seen = new HashSet<>();
        int checked = 0;
        int deepest = 0;
        while (checked < 1000) {
            final String json = randomValue(random, 0, 4);
            if (!seen.add(json)) {
                continue;
            }
            final ExactJson.FrozenJson frozen = ExactJson.parse(json);
            if (frozen.utf8Size() > 4096) {
                continue;
            }
            // 深度用满 0..64 的边界组合，确保相对深度确实参与预算。
            final int insertDepth = switch (checked % 5) {
                case 0 -> 0;
                case 1 -> 1;
                case 2 -> 8;
                case 3 -> 32;
                default -> 64 - oracleMaxRelativeDepth(frozen.canonical());
            };
            final int relativeMax = oracleMaxRelativeDepth(frozen.canonical());
            final int oracleNodes = oracleNodeCount(frozen.canonical());
            final int oracleBytes = frozen.canonical()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            assertEquals(frozen.utf8Size(), oracleBytes,
                    "the frozen byte width must equal the independent UTF-8 count for " + json);
            assertTrue(insertDepth + relativeMax <= ExactJson.MAX_DEPTH,
                    "the chosen insertion depth must stay legal for " + json);

            final PayloadWireBudget accepted = new PayloadWireBudget();
            accepted.addValue(frozen, insertDepth, FieldPath.root());
            assertEquals(oracleNodes, accepted.nodes(), "node count for fragment " + json);
            assertEquals(oracleBytes, accepted.bytes(), "byte count for fragment " + json);

            // 越界一侧：真实深度超过 64 必须整体拒绝。
            if (insertDepth + relativeMax < ExactJson.MAX_DEPTH) {
                final int illegalDepth = ExactJson.MAX_DEPTH - relativeMax + 1;
                final PayloadWireBudget rejected = new PayloadWireBudget();
                final SchemaException failure = assertThrows(SchemaException.class,
                        () -> rejected.addValue(frozen, illegalDepth, FieldPath.root().child("over")),
                        "the same fragment at depth " + illegalDepth + " must exceed the limit: " + json);
                assertEquals("LIMIT_EXCEEDED", failure.code());
                assertEquals("/over", failure.path().pointer());
                assertPoisoned(rejected);
            }
            deepest = Math.max(deepest, insertDepth + relativeMax);
            checked++;
        }
        assertEquals(1000, checked);
        assertEquals(ExactJson.MAX_DEPTH, deepest,
                "the corpus must reach the depth boundary, not stay shallow");

        // 卡 R06.1a-b2 第 1 条后半：另加 1000 组不同 object extras 组合。期望全部来自本测试
        // 的独立 canonical 模型（modelWire/modelExtras），不拿生产 canonical 或生产成功结果推导。
        // 该语料不依赖随机数：样本编号本身决定唯一组合；原 20261015L 语料保持不动。
        final Set<String> seenNonEmptyExtras = new HashSet<>();
        final Set<String> seenCompleteWires = new HashSet<>();
        final Set<String> checkedLiterals = new HashSet<>();
        int literalSamples = 0;
        int checkedExtras = 0;
        int emptyExtrasSamples = 0;
        int nonEmptyExtrasSamples = 0;
        int overDepthRejections = 0;
        int extrasDeepest = 0;
        boolean sawKnownZero = false;
        boolean sawKnownPositive = false;
        boolean sawEmptyReservedCall = false;
        boolean sawNonEmptyReservedCall = false;
        boolean sawDepth64 = false;

        for (int sample = 0; sample < 1000; sample++) {
            final ExtrasSample extras = modelExtras(sample);

            // knownMemberCount 覆盖 0..8。候选值仍是原 sample%9，但非 sample0 的空 extras 若
            // 也为 known0，则唯一区别只剩 ownerDepth 挑选；卡 R06.1a-b3 第 1 条要求这类样本
            // 改为 known1，使「空 extras」也带一个编号 known key。sample0 保持完全空 object。
            final int candidateKnown = sample % 9;
            final int knownCount = (sample != 0 && extras.rootMembers == 0 && candidateKnown == 0)
                    ? 1
                    : candidateKnown;
            final List<KnownMember> known = modelKnown(sample, knownCount);
            final int extrasRelative = extras.maxRelativeDepth;
            final int needed = Math.max(knownCount > 0 ? 1 : 0, extrasRelative);

            // 只有 known 0 且 extras 相对深度 0 时才能占满真实深度 64。
            final int ownerDepth;
            if (knownCount == 0 && extrasRelative == 0 && sample % 4 == 0) {
                ownerDepth = ExactJson.MAX_DEPTH;
            } else {
                final int[] preferred = knownCount > 0
                        ? new int[] {0, 1, 8, 32, 63}
                        : new int[] {0, 1, 16, 40};
                int pick = -1;
                for (int i = 0; i < preferred.length; i++) {
                    final int candidate = preferred[(i + sample) % preferred.length];
                    if (candidate + needed <= ExactJson.MAX_DEPTH) {
                        pick = candidate;
                        break;
                    }
                }
                assertTrue(pick >= 0, "the model must find a legal owner depth for sample " + sample);
                ownerDepth = pick;
            }
            assertTrue(knownCount == 0 || ownerDepth <= 63,
                    "known>0 时 ownerDepth 必须 ≤63，否则 known skeleton 会先失败而测不到 extras");

            final int realDepth = ownerDepth + needed;
            assertTrue(realDepth <= ExactJson.MAX_DEPTH,
                    "the model fixture must stay legal: realDepth=" + realDepth);
            assertTrue(realDepth >= 0, "the model fixture must not be negative: realDepth=" + realDepth);

            // 根 extras key 与 known key 不得碰撞；nested 同名 key 允许存在。
            final List<String> extrasRootKeys = modelExtrasRootKeys(extras);
            assertEquals(extras.rootMembers, extrasRootKeys.size(),
                    "the model must see exactly the declared root member count: " + extras.canonical);
            for (String extrasKey : extrasRootKeys) {
                assertFalse(known.stream().anyMatch(member -> member.key.equals(extrasKey)),
                        "a root extras key must not collide with a known key: " + extrasKey);
            }

            final String wire = modelWire(ownerDepth, known, extras);
            // 卡 R06.1a-b3 第 2 条：完整 wire 必须逐项互异，且必须在 ExactJson.parse 与任何
            // 预算调用之前判定。这不是按生产结果筛样本，也不加 dummy 唯一 tag：本 1000 组
            // 样本按构造即应互异，重复即为 fixture 缺陷，必须当场失败。
            assertTrue(seenCompleteWires.add(wire),
                    "every one of the 1000 complete wires must be distinct; duplicate at sample "
                            + sample + ": " + wire);
            final int modelBytes = modelUtf8Bytes(wire);
            final int modelNodes = ownerDepth + 1 + knownCount + (extras.nodes - 1);
            assertTrue(modelBytes <= ExactJson.MAX_UTF8_BYTES,
                    "the model wire must fit the byte budget, was " + modelBytes);

            // 卡第 3 条：形状 2 与 5 的 literal 索引改为 (index/6)%MODEL_LITERALS.length，因此
            // 这两个形状才第一次真正取到 true/false/null 三值。这里在生产预算调用之前，用与
            // 夹具相同的固定公式独立给出 expected literal，并在 extras 预给 canonical 文本里
            // 核实该 literal 确实出现在成员值位置（":"+literal+"}" 或 ":"+literal+","），
            // 只记录**实际已核**的模型值，绝不以 enum 常量存在或未被调用的计数器充数。
            final int shape = sample % 6;
            if (shape == 2 || shape == 5) {
                final String expectedLiteral =
                        MODEL_LITERALS[((sample / 6) % MODEL_LITERALS.length)];
                final String asTail = ":" + expectedLiteral + "}";
                final String asHead = ":" + expectedLiteral + ",";
                assertTrue(extras.canonical.contains(asTail) || extras.canonical.contains(asHead),
                        "shape " + shape + " sample " + sample + " 的 canonical 必须在成员值位置包含 "
                                + expectedLiteral + "，实际为 " + extras.canonical);
                // 记录**取值集合**：同一个 literal 值会在多个样本上重复出现（形状 2/5 各约 167 个
                // 样本，三值轮转），这正是覆盖；只有末尾的取值集合相等才证明三值都真实到过。
                checkedLiterals.add(expectedLiteral);
                literalSamples++;
            }

            final ExactJson.FrozenJson frozen = ExactJson.parse(extras.canonical);
            assertEquals(extras.canonical, frozen.canonical(),
                    "the model canonical text must be exactly what P02 certifies: " + extras.canonical);
            assertEquals(modelUtf8Bytes(extras.canonical), frozen.utf8Size(),
                    "the frozen width must equal the independent UTF-8 count");

            // 实际预算：逐层 addArray、addObject、known addMember/addNumber，再一次 addMembers。
            final boolean reservedEmpty = sample % 3 == 0;
            final Set<String> reserved = reservedEmpty ? Set.of() : Set.of("reserved_" + sample);
            final PayloadWireBudget budget = new PayloadWireBudget();
            for (int level = 0; level < ownerDepth; level++) {
                budget.addArray(level, FieldPath.root());
            }
            budget.addObject(ownerDepth, FieldPath.root());
            int index = 0;
            for (KnownMember member : known) {
                budget.addMember(member.key, index, FieldPath.root().child(member.key));
                budget.addNumber(member.scalar, ownerDepth + 1, FieldPath.root().child(member.key));
                index++;
            }
            final int rootMembers = budget.addMembers(frozen, reserved, ownerDepth, knownCount,
                    FieldPath.root());
            assertEquals(extras.rootMembers, rootMembers,
                    "the certified root member count must equal the model: " + extras.canonical);
            assertEquals(modelBytes, budget.bytes(),
                    "whole wire bytes must equal the independent canonical model: " + extras.canonical);
            assertEquals(modelNodes, budget.nodes(),
                    "node count must equal the independent model: " + extras.canonical);

            if (reservedEmpty) {
                sawEmptyReservedCall = true;
            } else {
                sawNonEmptyReservedCall = true;
            }
            if (extras.rootMembers == 0) {
                assertEquals("{}", extras.canonical, "空 extras 只能是空 object");
                emptyExtrasSamples++;
            } else {
                assertTrue(seenNonEmptyExtras.add(extras.canonical),
                        "每个非空 extras 片段必须两两不同: " + extras.canonical);
                nonEmptyExtrasSamples++;
            }
            if (knownCount == 0) {
                sawKnownZero = true;
            } else {
                sawKnownPositive = true;
            }
            if (ownerDepth == ExactJson.MAX_DEPTH) {
                sawDepth64 = true;
            }
            extrasDeepest = Math.max(extrasDeepest, realDepth);
            checkedExtras++;

            // 超过 64 一侧：把同一个片段放到更高的 owner 位置，必须整体拒绝并毒化。
            // extrasRelative>0 时命中的是真实深度检查；extrasRelative==0（空 object）时
            // ownerDepth 自身已是 65，由 requireDepth 拒绝，两者都是 LIMIT_EXCEEDED。
            final int overOwner = ExactJson.MAX_DEPTH - extrasRelative + 1;
            final PayloadWireBudget rejected = new PayloadWireBudget();
            final SchemaException failure = assertThrows(SchemaException.class,
                    () -> rejected.addMembers(frozen, reserved, overOwner, knownCount,
                            FieldPath.root().child("over")),
                    "owner depth " + overOwner + " must exceed the real depth limit for " + extras.canonical);
            assertEquals("LIMIT_EXCEEDED", failure.code());
            assertEquals("/over", failure.path().pointer());
            assertPoisoned(rejected);
            overDepthRejections++;

            // 同一新片段也走 addValue：真实包裹 array 累计 bytes/nodes。
            // depth 语义是“该值自身所在的深度”，包裹 ownerDepth 层 array 后即 ownerDepth。
            final PayloadWireBudget viaValue = new PayloadWireBudget();
            for (int level = 0; level < ownerDepth; level++) {
                viaValue.addArray(level, FieldPath.root());
            }
            viaValue.addValue(frozen, ownerDepth, FieldPath.root());
            assertEquals(modelUtf8Bytes(extras.canonical) + 2L * ownerDepth, viaValue.bytes(),
                    "addValue inside the same wrapping arrays must match the model: " + extras.canonical);
            assertEquals(ownerDepth + extras.nodes, viaValue.nodes(),
                    "addValue node count must match the model: " + extras.canonical);

            // addValue 越深拒绝。
            final int tooDeep = ExactJson.MAX_DEPTH - extrasRelative + 1;
            final PayloadWireBudget deepRejected = new PayloadWireBudget();
            final SchemaException deepFailure = assertThrows(SchemaException.class,
                    () -> deepRejected.addValue(frozen, tooDeep, FieldPath.root().child("deep")),
                    "addValue at depth " + tooDeep + " must exceed the limit: " + extras.canonical);
            assertEquals("LIMIT_EXCEEDED", deepFailure.code());
            assertEquals("/deep", deepFailure.path().pointer());
            assertPoisoned(deepRejected);
        }

        assertEquals(1000, checkedExtras, "the extras corpus must be exactly 1000 samples");
        assertEquals(1000, emptyExtrasSamples + nonEmptyExtrasSamples);
        assertEquals(1000, seenCompleteWires.size(),
                "卡第 2 条：1000 组完整 wire 必须两两互异，实际去重后仍须为 1000");
        assertEquals(Set.of("true", "false", "null"), checkedLiterals,
                "卡第 3 条：形状 2/5 必须真实覆盖且只覆盖 true/false/null 三个 literal 值");
        assertTrue(literalSamples > 0,
                "literal 覆盖必须来自真实样本的成员值核实，而不能只是 enum 常量存在");
        assertEquals(nonEmptyExtrasSamples, seenNonEmptyExtras.size(),
                "所有非空 extras 片段必须两两不同");
        assertEquals(1000, overDepthRejections, "每个样本都必须真实覆盖超过 64 的拒例");
        assertEquals(ExactJson.MAX_DEPTH, extrasDeepest, "the extras corpus must reach the real depth boundary");
        assertTrue(emptyExtrasSamples > 0, "empty extras must be covered");
        assertTrue(nonEmptyExtrasSamples > 0, "non-empty extras must be covered");
        assertTrue(sawKnownZero, "knownMemberCount 0 must be covered");
        assertTrue(sawKnownPositive, "knownMemberCount > 0 must be covered, including the comma connector");
        assertTrue(sawEmptyReservedCall, "at least one extras call must pass an empty reserved snapshot");
        assertTrue(sawNonEmptyReservedCall, "at least one extras call must pass a non-empty reserved snapshot");
        assertTrue(sawDepth64, "owner depth 64 with known 0 must be covered");
    }

    @Test
    @DisplayName("adding the same frozen fragment repeatedly charges every occurrence")
    void repeatedFragmentIsChargedPerOccurrence() {
        final ExactJson.FrozenJson fragment = ExactJson.parse("{\"a\":[1,2,3]}");
        final int width = fragment.utf8Size();
        final PayloadWireBudget budget = new PayloadWireBudget();
        budget.addValue(fragment, 0, FieldPath.root());
        assertEquals(width, budget.bytes());
        budget.addValue(fragment, 0, FieldPath.root());
        assertEquals(2L * width, budget.bytes());
        final long nodes = budget.nodes();
        assertEquals(2L * oracleNodeCount(fragment.canonical()), nodes,
                "the same frozen value must be charged again on every occurrence");
    }

    // ------------------------------------------------------------------
    // 深度
    // ------------------------------------------------------------------

    @Test
    @DisplayName("depth 64 is accepted and depth 65 is rejected for a real wire position")
    void depthBoundaryGolden() {
        final PayloadWireBudget max = new PayloadWireBudget();
        max.addObject(64, FieldPath.root().child("deep"));
        assertEquals(2, max.bytes());

        final PayloadWireBudget overflow = new PayloadWireBudget();
        final SchemaException failure = assertThrows(SchemaException.class,
                () -> overflow.addObject(65, FieldPath.root().child("deep")));
        assertEquals("LIMIT_EXCEEDED", failure.code());
        assertEquals("/deep", failure.path().pointer());
        assertPoisoned(overflow);
    }

    @Test
    @DisplayName("a legal 64-deep fragment inserted at depth 1 exceeds the limit as a whole")
    void deepFragmentInsertedShallowIsRejected() {
        // 64 层嵌套 object，本身合法：相对最大深度 63，插到 depth 1 则真实深度 64，仍合法。
        final String sixtyFour = "{\"a\":".repeat(63) + "{}" + "}".repeat(63);
        final ExactJson.FrozenJson shallow = ExactJson.parse(sixtyFour);
        final PayloadWireBudget accepted = new PayloadWireBudget();
        accepted.addValue(shallow, 1, FieldPath.root().child("rules"));

        // 卡 R06.1a-b2 第 2 条：断言 budget 真实 nodes/bytes，而不是恒真的三元表达式。
        // 该片段是 63 层前缀 + 空 object：共 64 个 node（63 个 object + 内层空 object 计 1）。
        // canonical 文本与 ASCII 完全一致，故 bytes 就是其真实字节长度。
        final int oracleNodes = oracleNodeCount(shallow.canonical());
        final int oracleBytes = modelUtf8Bytes(shallow.canonical());
        assertEquals(64, oracleNodes, "63 层前缀加空 object 必须恰好是 64 个 node");
        assertEquals(oracleBytes, shallow.canonical().length(),
                "该片段是纯 ASCII，字节数必须等于字符数");
        assertEquals(oracleNodes, accepted.nodes(), "the accepted fragment must charge its real node count");
        assertEquals(oracleBytes, accepted.bytes(), "the accepted fragment must charge its real byte width");

        // 同一片段插到 depth 1 但片段本身是 65 层时越界；这里用 65 层片段直接在 depth 1 插入。
        final String sixtyFive = "{\"a\":".repeat(64) + "{}" + "}".repeat(64);
        final ExactJson.FrozenJson deep = ExactJson.parse(sixtyFive);
        final PayloadWireBudget rejected = new PayloadWireBudget();
        final SchemaException failure = assertThrows(SchemaException.class,
                () -> rejected.addValue(deep, 1, FieldPath.root().child("rules")));
        assertEquals("LIMIT_EXCEEDED", failure.code());
        assertEquals("/rules", failure.path().pointer(),
                "a budget failure in a fragment must report the insertion position, not an invented descendant");
        assertPoisoned(rejected);
    }

    @Test
    @DisplayName("geometry extras at owner depth 2 and 3 shift the fragment by the real owner depth")
    void extrasDepthShiftGolden() {
        // 片段 {"g":{"h":1}} 的相对最大深度是 2（片段根为 0），由其 canonical 文本独立数出。
        final String extrasText = "{\"g\":{\"h\":1}}";
        final ExactJson.FrozenJson extras = ExactJson.parse(extrasText);
        assertEquals("{\"g\":{\"h\":1}}", extras.canonical());
        assertEquals(2, oracleMaxRelativeDepth(extras.canonical()));

        // ownerDepth 2 时真实深度 4，接受。
        final PayloadWireBudget atTwo = new PayloadWireBudget();
        atTwo.addObject(2, FieldPath.root().child("geometry"));
        assertEquals(1, atTwo.addMembers(extras, Set.of(), 2, 0, FieldPath.root().child("geometry")));
        assertEquals(3, atTwo.nodes(), "owner 1 + 片段里 g 的对象与 h 的数字");
        assertEquals(2 + (extras.utf8Size() - 2), atTwo.bytes());

        // ownerDepth 3 时真实深度 5，仍接受。
        final PayloadWireBudget atThree = new PayloadWireBudget();
        atThree.addObject(3, FieldPath.root().child("geometry"));
        assertEquals(1, atThree.addMembers(extras, Set.of(), 3, 0, FieldPath.root().child("geometry")));

        // 相对深度 2 意味着 ownerDepth 62 时真实深度 64 恰好压线，接受。
        final PayloadWireBudget atSixtyTwo = new PayloadWireBudget();
        assertEquals(1, atSixtyTwo.addMembers(extras, Set.of(), 62, 0, FieldPath.root().child("geometry")));

        // ownerDepth 63 时真实深度 65，整体拒绝。
        final PayloadWireBudget atSixtyThree = new PayloadWireBudget();
        final SchemaException failure = assertThrows(SchemaException.class,
                () -> atSixtyThree.addMembers(extras, Set.of(), 63, 0, FieldPath.root().child("geometry")));
        assertEquals("LIMIT_EXCEEDED", failure.code());
        assertEquals("/geometry", failure.path().pointer());
        assertPoisoned(atSixtyThree);
    }

    // ------------------------------------------------------------------
    // 节点与字节预算
    // ------------------------------------------------------------------

    @Test
    @DisplayName("five hundred thousand nodes are accepted and one more is rejected")
    void nodeBudgetGolden() {
        final int total = ExactJson.MAX_NODES;
        final PayloadWireBudget budget = new PayloadWireBudget();
        for (int i = 0; i < total; i++) {
            budget.addString("", 1, FieldPath.root());
        }
        assertEquals(ExactJson.MAX_NODES, budget.nodes());
        // 空字符串是 2 字节，节点数与字节数是两套独立预算：这里只压满节点预算。
        assertEquals(2L * total, budget.bytes());
        final SchemaException failure = assertThrows(SchemaException.class,
                () -> budget.addString("", 1, FieldPath.root().child("oneMore")));
        assertEquals("LIMIT_EXCEEDED", failure.code());
        assertEquals("/oneMore", failure.path().pointer());
        assertPoisoned(budget);
    }

    /**
     * 最大成功树：500000 个节点的片段。
     *
     * <p>该用例必须持有整棵 frozen 树，实测需要数十 MiB 堆，因此只在常规堆下运行；
     * 64 MiB 的越限反例另有用例覆盖（那些用例的片段都很小，越限来自累计字节），
     * 不能据此宣称“所有 500000 节点处理都在 64 MiB 下已被证明”。</p>
     */
    @Test
    @Tag("large-heap")
    @DisplayName("a fragment holding five hundred thousand nodes is legal alone but rejected with a skeleton")
    void hugeFragmentAloneIsLegalButWithSkeletonIsRejected() {
        // 卡 R06.1a-b3 第 5 条：完整 wire 的 MAX-1/MAX/MAX+1 必须由 JDK 对**实际拼好的完整文本**
        // 编码核实，而不是用单个 string 的宽度加手算冒充。该 helper 必须在构建 500000 节点树之前
        // 运行，并在返回前释放大 text/byte[]，以免与最大树同时驻留。
        assertCompleteWireJdkBoundaryVectors();

        // 片段本身恰好 MAX_NODES 个节点（一个数组加 MAX_NODES-1 个元素）。
        final String body = "1,".repeat(ExactJson.MAX_NODES - 2) + "1";
        final String json = "[" + body + "]";
        final ExactJson.FrozenJson fragment = ExactJson.parse(json);
        final int oracleNodes = oracleNodeCount(fragment.canonical());
        assertEquals(ExactJson.MAX_NODES, oracleNodes);

        // 单独计入：合法，恰好压线。
        final PayloadWireBudget alone = new PayloadWireBudget();
        alone.addValue(fragment, 0, FieldPath.root());
        assertEquals(ExactJson.MAX_NODES, alone.nodes());

        // 再加一个骨架 node 即越界（这里先用 addObject 占 1 个 node，再计入片段）。
        final PayloadWireBudget withSkeleton = new PayloadWireBudget();
        withSkeleton.addObject(0, FieldPath.root());
        final SchemaException failure = assertThrows(SchemaException.class,
                () -> withSkeleton.addValue(fragment, 1, FieldPath.root().child("rules")));
        assertEquals("LIMIT_EXCEEDED", failure.code());
        assertEquals("/rules", failure.path().pointer());
        assertPoisoned(withSkeleton);
    }

    @Test
    @DisplayName("the byte budget accepts exactly eight mebibytes and rejects one more byte")
    void byteBudgetGolden() {
        // 数组承载：'[' ']' 2 字节；k 个 64 KiB 字符串元素，每个宽度 65536 + 2 引号，
        // 元素之间 k-1 个逗号。目标是恰好压满 MAX_UTF8_BYTES，同时节点数远低于上限。
        final int chunkBytes = ExactJson.MAX_STRING_UTF8_BYTES;
        final int elementBytes = chunkBytes + 2;
        final int k = (ExactJson.MAX_UTF8_BYTES - 2) / elementBytes;
        final long used = 2L + (long) k * elementBytes + (k - 1);
        assertTrue(used <= ExactJson.MAX_UTF8_BYTES,
                "the exact fixture must not exceed the limit, was " + used);

        // 剩余字节用更窄的字符串元素补齐，每个元素占 (1 逗号 + 2 引号 + n 字节) == n + 3。
        // 选 n 使补齐宽度精确等于剩余字节；n 必须落在 0..MAX_STRING_UTF8_BYTES 内。
        final long rest = ExactJson.MAX_UTF8_BYTES - used;
        assertTrue(rest >= 4, "there must be room for a trailing filler element, rest was " + rest);
        final int fillerBytes = (int) (rest - 3);
        assertTrue(fillerBytes >= 1 && fillerBytes <= ExactJson.MAX_STRING_UTF8_BYTES,
                "the filler width must be a legal string width, was " + fillerBytes);
        assertEquals(ExactJson.MAX_UTF8_BYTES, used + 3L + fillerBytes,
                "the whole wire width must equal the limit exactly");

        final String chunk = "x".repeat(chunkBytes);
        final String filler = "z".repeat(fillerBytes);
        final int maxNodes = 2 + k;

        final PayloadWireBudget exact = new PayloadWireBudget();
        exact.addArray(0, FieldPath.root());
        for (int i = 0; i < k; i++) {
            exact.addArraySeparator(i, FieldPath.root());
            exact.addString(chunk, 1, FieldPath.root());
        }
        exact.addArraySeparator(k, FieldPath.root());
        exact.addString(filler, 1, FieldPath.root());
        assertEquals(ExactJson.MAX_UTF8_BYTES, exact.bytes());
        assertEquals(maxNodes, exact.nodes());

        // 只多一个字节：再加一个 1 字节数字必须整体被拒，且报告自己的插入位置。
        final PayloadWireBudget overflow = new PayloadWireBudget();
        overflow.addArray(0, FieldPath.root());
        for (int i = 0; i < k; i++) {
            overflow.addArraySeparator(i, FieldPath.root());
            overflow.addString(chunk, 1, FieldPath.root());
        }
        overflow.addArraySeparator(k, FieldPath.root());
        overflow.addString(filler, 1, FieldPath.root());
        assertEquals(ExactJson.MAX_UTF8_BYTES, overflow.bytes());
        final SchemaException failure = assertThrows(SchemaException.class,
                () -> overflow.addNumber("1", 1, FieldPath.root().child("extra")));
        assertEquals("LIMIT_EXCEEDED", failure.code());
        assertEquals("/extra", failure.path().pointer(),
                "the rejected operation must report its own insertion position");
        assertPoisoned(overflow);

        // 差一字节的对照：把补齐元素缩窄 1 字节，则恰好少 1 字节且仍然被接受。
        final PayloadWireBudget oneLess = new PayloadWireBudget();
        oneLess.addArray(0, FieldPath.root());
        for (int i = 0; i < k; i++) {
            oneLess.addArraySeparator(i, FieldPath.root());
            oneLess.addString(chunk, 1, FieldPath.root());
        }
        oneLess.addArraySeparator(k, FieldPath.root());
        oneLess.addString("z".repeat(fillerBytes - 1), 1, FieldPath.root());
        assertEquals(ExactJson.MAX_UTF8_BYTES - 1L, oneLess.bytes(),
                "the neighbouring fixture must sit exactly one byte below the limit");

        // ------------------------------------------------------------------
        // 卡 R06.1a-b2 第 6 条：完整合法 wire 恰 8 MiB 及 +1/-1
        //
        // 固定 array 有 128 个 string：前 127 个 decoded ASCII 各 65536 bytes，最后一个长度
        //   MAX - (2 + 127*(65536+2) + 127 + 2) == 65151。
        // 完整 wire 保留 2 个 array 括号、256 个 string 引号、127 个逗号。
        // ------------------------------------------------------------------
        final int headStrings = 127;
        final int tailLegal = (int) (ExactJson.MAX_UTF8_BYTES
                - (2L + (long) headStrings * (ExactJson.MAX_STRING_UTF8_BYTES + 2) + headStrings + 2));
        assertEquals(65151, tailLegal, "最后一个 string 的合法长度必须是 65151");
        assertTrue(tailLegal >= 1 && tailLegal <= ExactJson.MAX_STRING_UTF8_BYTES,
                "the tail width must be a legal decoded string width, was " + tailLegal);

        final String head = "h".repeat(ExactJson.MAX_STRING_UTF8_BYTES);
        final int headUtf8 = head.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertEquals(ExactJson.MAX_STRING_UTF8_BYTES, headUtf8, "the head string must be exactly 64 KiB");

        // 独立 JDK UTF-8 宽度：2 括号 + 128 个 string 的引号 + 127 个逗号 + 全部 decoded 字节。
        final String tailExact = "t".repeat(tailLegal);
        final long exactWire = 2L + (long) headStrings * (headUtf8 + 2) + headStrings + 2
                + tailExact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertEquals(ExactJson.MAX_UTF8_BYTES, exactWire, "完整 wire 必须恰好等于上限");
        final int exactNodes = 1 + headStrings + 1;
        assertEquals(129, exactNodes, "完整 wire 必须是 129 个 node");

        final PayloadWireBudget exactWireBudget = new PayloadWireBudget();
        exactWireBudget.addArray(0, FieldPath.root());
        for (int i = 0; i < headStrings; i++) {
            exactWireBudget.addArraySeparator(i, FieldPath.root());
            exactWireBudget.addString(head, 1, FieldPath.root());
        }
        exactWireBudget.addArraySeparator(headStrings, FieldPath.root());
        exactWireBudget.addString(tailExact, 1, FieldPath.root());
        assertEquals(ExactJson.MAX_UTF8_BYTES, exactWireBudget.bytes(),
                "完整 wire 的累计字节必须恰好是上限");
        assertEquals(exactNodes, exactWireBudget.nodes(), "完整 wire 的 node 数必须是 129");

        // -1：最后一个 string 缩窄 1 字节，整体恰好比上限少 1，仍全部成功。
        final String tailLess = "t".repeat(tailLegal - 1);
        final long lessWire = 2L + (long) headStrings * (headUtf8 + 2) + headStrings + 2
                + tailLess.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertEquals(ExactJson.MAX_UTF8_BYTES - 1L, lessWire, "该夹具必须恰好低于上限 1 字节");
        final PayloadWireBudget lessBudget = new PayloadWireBudget();
        lessBudget.addArray(0, FieldPath.root());
        for (int i = 0; i < headStrings; i++) {
            lessBudget.addArraySeparator(i, FieldPath.root());
            lessBudget.addString(head, 1, FieldPath.root());
        }
        lessBudget.addArraySeparator(headStrings, FieldPath.root());
        lessBudget.addString(tailLess, 1, FieldPath.root());
        assertEquals(ExactJson.MAX_UTF8_BYTES - 1L, lessBudget.bytes());
        assertEquals(exactNodes, lessBudget.nodes());

        // +1：最后一个 string 加宽 1 字节，整体恰好超上限 1，必须在**最后一次 addString** 上被拒，
        // 报告真实插入位置并整体毒化。不靠另添裸 number 冒充完整 wire +1。
        final String tailMore = "t".repeat(tailLegal + 1);
        final long moreWire = 2L + (long) headStrings * (headUtf8 + 2) + headStrings + 2
                + tailMore.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertEquals(ExactJson.MAX_UTF8_BYTES + 1L, moreWire, "该夹具必须恰好超过上限 1 字节");

        final FieldPath tailPath = FieldPath.root().child("tail");
        final PayloadWireBudget moreBudget = new PayloadWireBudget();
        moreBudget.addArray(0, FieldPath.root());
        for (int i = 0; i < headStrings; i++) {
            moreBudget.addArraySeparator(i, FieldPath.root());
            moreBudget.addString(head, 1, FieldPath.root());
        }
        moreBudget.addArraySeparator(headStrings, FieldPath.root());
        final SchemaException tailFailure = assertThrows(SchemaException.class,
                () -> moreBudget.addString(tailMore, 1, tailPath),
                "完整 wire 多 1 字节必须在最后一次 addString 上被拒");
        assertEquals("LIMIT_EXCEEDED", tailFailure.code());
        assertEquals("/tail", tailFailure.path().pointer(),
                "被拒操作必须报告自己的真实插入位置");
        assertPoisoned(moreBudget);
    }

    /**
     * 卡 R06.1a-b3 第 5 条：用 JDK 对**实际拼好的完整 wire 文本**做 UTF-8 编码，核实
     * MAX-1 / MAX / MAX+1 三个完整 array wire，再对真实预算执行同样的构造。
     *
     * <p>夹具与 {@code byteBudgetGolden} 的既有公式一致：128 个 string，前 127 个各 65536 字节，
     * 末串 65151（MAX）或 65150（MAX-1）或 65152（MAX+1）。节点数恒为 129。</p>
     *
     * <p>与 {@code byteBudgetGolden} 的关键差别：本 helper 真的把完整文本拼成一个 String，
     * 由 JDK 编码后取其长度，而不是「单个 string 的 JDK 宽度 + 手算标点」。后者不能称为
     * 完整 wire 已实际编码。</p>
     *
     * <p>内存纪律：builder 预分配 {@code MAX+1}；每个 case 用完立即释放完整 text 与 byte[]，
     * 返回前不保留任何大对象，随后才构建 500000 节点的最大树，避免两者同时驻留。若本夹具发生
     * OOM，如实保存记录，不改生产、不隐藏 skip、不声称产品 OOM。</p>
     */
    private static void assertCompleteWireJdkBoundaryVectors() {
        final int headStrings = 127;
        final int tailLegal = (int) (ExactJson.MAX_UTF8_BYTES
                - (2L + (long) headStrings * (ExactJson.MAX_STRING_UTF8_BYTES + 2) + headStrings + 2));
        assertEquals(65151, tailLegal, "最后一个 string 的合法长度必须是 65151");

        {
            final String head = "h".repeat(ExactJson.MAX_STRING_UTF8_BYTES);
            // 三个完整 wire 分别做 JDK 实测。每个 case 结束后 head/tail/text 全部走出作用域。
            for (int delta = -1; delta <= 1; delta++) {
                final String tail = "t".repeat(tailLegal + delta);
                final StringBuilder builder = new StringBuilder(ExactJson.MAX_UTF8_BYTES + 1);
                builder.append('[');
                for (int i = 0; i < headStrings; i++) {
                    if (i > 0) {
                        builder.append(',');
                    }
                    builder.append('"').append(head).append('"');
                }
                builder.append(',');
                builder.append('"').append(tail).append('"');
                builder.append(']');

                final String text = builder.toString();
                final byte[] encoded = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                final long expected = ExactJson.MAX_UTF8_BYTES + delta;
                assertEquals(expected, (long) encoded.length,
                        "完整 wire 的 JDK UTF-8 宽度必须是 MAX" + (delta == 0 ? "" : (delta > 0 ? "+1" : "-1")));
                assertEquals(text.length() - 2, encoded.length - 2,
                        "纯 ASCII 夹具的字符数与字节数必须一致");

                // 真实预算：同样的完整 array 构造。
                final PayloadWireBudget budget = new PayloadWireBudget();
                budget.addArray(0, FieldPath.root());
                for (int i = 0; i < headStrings; i++) {
                    budget.addArraySeparator(i, FieldPath.root());
                    budget.addString(head, 1, FieldPath.root());
                }
                budget.addArraySeparator(headStrings, FieldPath.root());

                if (delta > 0) {
                    // 越限点必须精确：本 case 的目标整体宽度是 MAX+1，在最后一次 addString 之前
                    // 尚未写入尾串（及其结束括号），故此前累计 = (MAX+1) - 尾串宽度。
                    final int tailWidth = tail.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 2;
                    assertEquals(ExactJson.MAX_UTF8_BYTES + 1L - tailWidth, budget.bytes(),
                            "越限必须恰好发生在最后一次 addString 上：其前累计字节应为 (MAX+1)-该串宽度");
                    final FieldPath tailPath = FieldPath.root().child("tail");
                    final SchemaException overflow = assertThrows(SchemaException.class,
                            () -> budget.addString(tail, 1, tailPath),
                            "完整 wire 多 1 字节必须在最后一次 addString 上被拒");
                    assertEquals("LIMIT_EXCEEDED", overflow.code());
                    assertEquals("/tail", overflow.path().pointer(),
                            "被拒操作必须报告自己的真实插入位置");
                    assertPoisoned(budget);
                } else {
                    budget.addString(tail, 1, FieldPath.root());
                    assertEquals(expected, budget.bytes(),
                            "完整 wire 的累计字节必须等于 JDK 实测值");
                    assertEquals(129, budget.nodes(), "完整 wire 必须是 129 个 node");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // addMembers：reserved 身份、根碰撞、ownerPath 容量
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a root reserved collision is reported with the escaped child path of the actual key")
    void rootReservedCollisionReportsChildPath() {
        final ExactJson.FrozenJson extras = ExactJson.parse("{\"id\":1,\"other\":2}");

        // 普通拼写：identity 相等即碰撞。
        final PayloadWireBudget plain = new PayloadWireBudget();
        final SchemaException plainFailure = assertThrows(SchemaException.class,
                () -> plain.addMembers(extras, Set.of("id"), 0, 1, FieldPath.root().child("geometry")));
        assertEquals("RESERVED_COLLISION", plainFailure.code());
        assertEquals("/geometry/id", plainFailure.path().pointer());
        assertPoisoned(plain);

        // 转义拼写 "\u0069d" 与 id 同身份，路径用解码后的真实 key。
        final ExactJson.FrozenJson escaped = ExactJson.parse("{\"\\u0069d\":1}");
        final PayloadWireBudget escapedBudget = new PayloadWireBudget();
        final SchemaException escapedFailure = assertThrows(SchemaException.class,
                () -> escapedBudget.addMembers(escaped, Set.of("id"), 0, 0, FieldPath.root().child("geometry")));
        assertEquals("RESERVED_COLLISION", escapedFailure.code());
        assertEquals("/geometry/id", escapedFailure.path().pointer());
        assertPoisoned(escapedBudget);
    }

    @Test
    @DisplayName("nested reserved-looking keys and known-looking value text never collide")
    void nestedKeysAndValueTextDoNotCollide() {
        // 嵌套层里的 "id" 不是根 key，不冲突。
        final ExactJson.FrozenJson nested = ExactJson.parse("{\"outer\":{\"id\":1}}");
        final PayloadWireBudget budget = new PayloadWireBudget();
        assertEquals(1, budget.addMembers(nested, Set.of("id"), 0, 0, FieldPath.root().child("geometry")));

        // 值字符串中出现 reserved 字样不冲突。
        final ExactJson.FrozenJson valueText = ExactJson.parse("{\"note\":\"id\"}");
        final PayloadWireBudget budget2 = new PayloadWireBudget();
        assertEquals(1, budget2.addMembers(valueText, Set.of("id"), 0, 0, FieldPath.root().child("geometry")));

        // canonical 会把 `\/` 归一成 `/`，因此这个根 key 的原文身份确实就是 reserved 里的 "a/b"。
        final ExactJson.FrozenJson slashy = ExactJson.parse("{\"a\\/b\":1}");
        assertEquals("{\"a/b\":1}", slashy.canonical(),
                "the frozen canonical text must normalise the escaped solidus before any comparison");
        final PayloadWireBudget budget3 = new PayloadWireBudget();
        final SchemaException slashFailure = assertThrows(SchemaException.class,
                () -> budget3.addMembers(slashy, Set.of("a/b"), 0, 0, FieldPath.root()));
        assertEquals("RESERVED_COLLISION", slashFailure.code());
        assertEquals("/a~1b", slashFailure.path().pointer(),
                "the collision path must encode the real solidus with the pointer escape");
        assertPoisoned(budget3);

        // 反面对照：reserved 用未归一的写法 `a\/b` 是无法构造的（Java 字符串里它就是 a/b），
        // 但 `a~b` 这类不含 / 的 key 与 reserved 的 "a/b" 不冲突。
        final ExactJson.FrozenJson tilde = ExactJson.parse("{\"a~b\":1}");
        final PayloadWireBudget budget4 = new PayloadWireBudget();
        assertEquals(1, budget4.addMembers(tilde, Set.of("a/b"), 0, 0, FieldPath.root()),
                "a tilde-bearing key is not the same identity as a solidus-bearing one");
    }

    @Test
    @DisplayName("a 64-segment owner plus a root id collision yields exactly a 65-segment path")
    void sixtyFourSegmentOwnerCollisionYieldsSixtyFiveSegments() {
        FieldPath owner = FieldPath.root();
        for (int i = 0; i < 64; i++) {
            owner = owner.child("s" + i);
        }
        assertEquals(64, owner.segments().size());

        final ExactJson.FrozenJson extras = ExactJson.parse("{\"id\":1}");
        final PayloadWireBudget budget = new PayloadWireBudget();
        final FieldPath finalOwner = owner;
        final SchemaException failure = assertThrows(SchemaException.class,
                () -> budget.addMembers(extras, Set.of("id"), 0, 0, finalOwner));
        assertEquals("RESERVED_COLLISION", failure.code());
        assertEquals(65, failure.path().segments().size(),
                "the collision path must append exactly the 65th segment without leaking more");
        assertEquals(finalOwner.child("id"), failure.path());
        assertPoisoned(budget);
    }

    @Test
    @DisplayName("a 65-segment owner is rejected as an argument before any accounting and poisons the budget")
    void sixtyFiveSegmentOwnerIsRejectedBeforeAccounting() {
        FieldPath owner = FieldPath.root();
        for (int i = 0; i < 65; i++) {
            owner = owner.child("s" + i);
        }
        assertEquals(65, owner.segments().size());

        final PayloadWireBudget budget = new PayloadWireBudget();
        final FieldPath finalOwner = owner;
        assertThrows(IllegalArgumentException.class,
                () -> budget.addMembers(EMPTY_OBJECT, Set.of(), 0, 0, finalOwner));
        assertPoisoned(budget);

        // 65 段对其它方法仍然合法：ownerPath 容量只约束 addMembers。
        final PayloadWireBudget other = new PayloadWireBudget();
        other.addObject(0, finalOwner);
        assertEquals(2, other.bytes());
    }

    @Test
    @DisplayName("addMembers rejects a non-object fragment and measures the extras without the owner node")
    void membersRejectsNonObjectAndDoesNotRecountOwner() {
        final PayloadWireBudget wrongType = new PayloadWireBudget();
        final SchemaException failure = assertThrows(SchemaException.class,
                () -> wrongType.addMembers(EMPTY_ARRAY, Set.of(), 0, 0, FieldPath.root().child("geometry")));
        assertEquals("WRONG_TYPE", failure.code());
        assertEquals("/geometry", failure.path().pointer());
        assertPoisoned(wrongType);

        // extras 不重复计 owner node：owner 已由 addObject 计 1，extras 的虚拟根不再计。
        final ExactJson.FrozenJson extras = ExactJson.parse("{\"a\":1,\"b\":2}");
        final PayloadWireBudget budget = new PayloadWireBudget();
        budget.addObject(0, FieldPath.root());
        final int memberCount = budget.addMembers(extras, Set.of(), 0, 0, FieldPath.root().child("geometry"));
        assertEquals(2, memberCount);
        // 节点：owner 1 + 两个标量 2 == 3（虚拟根不计）
        assertEquals(3, budget.nodes());
        // 字节：owner 的 '{}' 2 + body（canonical 去掉外层花括号）。
        // 这里 knownMemberCount == 0，故不与任何已知成员连接，不额外加逗号。
        final int body = extras.utf8Size() - 2;
        assertEquals(2 + body, budget.bytes());
    }

    @Test
    @DisplayName("known members connect to the extras body with exactly one comma")
    void knownMemberConnectorGolden() {
        final ExactJson.FrozenJson extras = ExactJson.parse("{\"a\":1}");
        final PayloadWireBudget withKnown = new PayloadWireBudget();
        withKnown.addMembers(extras, Set.of(), 0, 3, FieldPath.root());
        assertEquals(extras.utf8Size() - 2 + 1, withKnown.bytes());

        final PayloadWireBudget withoutKnown = new PayloadWireBudget();
        withoutKnown.addMembers(extras, Set.of(), 0, 0, FieldPath.root());
        assertEquals(extras.utf8Size() - 2, withoutKnown.bytes());

        final PayloadWireBudget emptyWithKnown = new PayloadWireBudget();
        final int count = emptyWithKnown.addMembers(EMPTY_OBJECT, Set.of(), 0, 5, FieldPath.root());
        assertEquals(0, count);
        assertEquals(0, emptyWithKnown.bytes(), "an empty extras object adds no comma even with known members");
    }

    // ------------------------------------------------------------------
    // 参数校验、失败后不可继续使用
    // ------------------------------------------------------------------

    @Test
    @DisplayName("bad arguments are rejected without accounting and every one poisons the budget")
    void badArgumentsAreRejectedAndPoison() {
        // depth 为负：IAE
        final PayloadWireBudget negativeDepth = new PayloadWireBudget();
        assertThrows(IllegalArgumentException.class,
                () -> negativeDepth.addObject(-1, FieldPath.root()));
        assertPoisoned(negativeDepth);

        // depth 65：LIMIT_EXCEEDED
        final PayloadWireBudget tooDeep = new PayloadWireBudget();
        final SchemaException depthFailure = assertThrows(SchemaException.class,
                () -> tooDeep.addString("x", 65, FieldPath.root()));
        assertEquals("LIMIT_EXCEEDED", depthFailure.code());
        assertPoisoned(tooDeep);

        // Java null 路径
        final PayloadWireBudget nullPath = new PayloadWireBudget();
        assertThrows(IllegalArgumentException.class, () -> nullPath.addObject(0, null));
        assertPoisoned(nullPath);

        // index 为负
        final PayloadWireBudget negativeIndex = new PayloadWireBudget();
        assertThrows(IllegalArgumentException.class,
                () -> negativeIndex.addMember("a", -1, FieldPath.root()));
        assertPoisoned(negativeIndex);

        final PayloadWireBudget negativeArrayIndex = new PayloadWireBudget();
        assertThrows(IllegalArgumentException.class,
                () -> negativeArrayIndex.addArraySeparator(-1, FieldPath.root()));
        assertPoisoned(negativeArrayIndex);

        // null 内容与 null 片段
        final PayloadWireBudget nullValue = new PayloadWireBudget();
        assertThrows(IllegalArgumentException.class, () -> nullValue.addString(null, 0, FieldPath.root()));
        assertPoisoned(nullValue);

        final PayloadWireBudget nullNumber = new PayloadWireBudget();
        assertThrows(IllegalArgumentException.class, () -> nullNumber.addNumber(null, 0, FieldPath.root()));
        assertPoisoned(nullNumber);

        final PayloadWireBudget nullFragment = new PayloadWireBudget();
        assertThrows(IllegalArgumentException.class,
                () -> nullFragment.addValue(null, 0, FieldPath.root()));
        assertPoisoned(nullFragment);

        final PayloadWireBudget nullMembers = new PayloadWireBudget();
        assertThrows(IllegalArgumentException.class,
                () -> nullMembers.addMembers(null, Set.of(), 0, 0, FieldPath.root()));
        assertPoisoned(nullMembers);

        final PayloadWireBudget nullReserved = new PayloadWireBudget();
        assertThrows(IllegalArgumentException.class,
                () -> nullReserved.addMembers(EMPTY_OBJECT, null, 0, 0, FieldPath.root()));
        assertPoisoned(nullReserved);

        final PayloadWireBudget nullOwner = new PayloadWireBudget();
        assertThrows(IllegalArgumentException.class,
                () -> nullOwner.addMembers(EMPTY_OBJECT, Set.of(), 0, 0, null));
        assertPoisoned(nullOwner);
    }

    @Test
    @DisplayName("a non-canonical number is a caller error and is never silently normalised")
    void nonCanonicalNumberIsACallerError() {
        final PayloadWireBudget padded = new PayloadWireBudget();
        assertThrows(IllegalArgumentException.class,
                () -> padded.addNumber("1.0", 0, FieldPath.root()));
        assertPoisoned(padded);

        final PayloadWireBudget leadingZero = new PayloadWireBudget();
        assertThrows(IllegalArgumentException.class,
                () -> leadingZero.addNumber("01", 0, FieldPath.root()));
        assertPoisoned(leadingZero);

        // 已经是 canonical 的浮点写法被接受，且按 ASCII 长度计。
        final PayloadWireBudget canonical = new PayloadWireBudget();
        canonical.addNumber("1.5", 0, FieldPath.root());
        assertEquals(3, canonical.bytes());
    }

    @Test
    @DisplayName("an oversized key or string is rejected by the decoded UTF-8 budget")
    void oversizedTextIsRejected() {
        // key 超过 1024 decoded bytes：'a' 重复 1025 次。
        final PayloadWireBudget longKey = new PayloadWireBudget();
        final SchemaException keyFailure = assertThrows(SchemaException.class,
                () -> longKey.addMember("a".repeat(ExactJson.MAX_KEY_UTF8_BYTES + 1), 0,
                        FieldPath.root().child("k")));
        assertEquals("LIMIT_EXCEEDED", keyFailure.code());
        assertEquals("/k", keyFailure.path().pointer());
        assertPoisoned(longKey);

        // 恰好 1024 的 key 合法。
        final PayloadWireBudget exactKey = new PayloadWireBudget();
        exactKey.addMember("a".repeat(ExactJson.MAX_KEY_UTF8_BYTES), 0, FieldPath.root());
        assertEquals(ExactJson.MAX_KEY_UTF8_BYTES + 3, exactKey.bytes());

        // 字符串超过 65536 decoded bytes。
        final PayloadWireBudget longString = new PayloadWireBudget();
        final SchemaException stringFailure = assertThrows(SchemaException.class,
                () -> longString.addString("a".repeat(ExactJson.MAX_STRING_UTF8_BYTES + 1), 0,
                        FieldPath.root().child("s")));
        assertEquals("LIMIT_EXCEEDED", stringFailure.code());
        assertEquals("/s", stringFailure.path().pointer());
        assertPoisoned(longString);

        // 恰好 65536 合法：两枚引号加 65536 字节。
        final PayloadWireBudget exactString = new PayloadWireBudget();
        exactString.addString("a".repeat(ExactJson.MAX_STRING_UTF8_BYTES), 0, FieldPath.root());
        assertEquals(ExactJson.MAX_STRING_UTF8_BYTES + 2, exactString.bytes());
    }

    @Test
    @DisplayName("illegal surrogates are rejected by the decoded Unicode check")
    void illegalSurrogatesAreRejected() {
        for (String illegal : new String[] {"\uD800", "\uDBFF", "\uDC00", "\uDFFF"}) {
            final PayloadWireBudget budget = new PayloadWireBudget();
            final SchemaException failure = assertThrows(SchemaException.class,
                    () -> budget.addString(illegal, 0, FieldPath.root()));
            assertEquals("INVALID_UNICODE", failure.code());
            assertPoisoned(budget);

            final PayloadWireBudget keyBudget = new PayloadWireBudget();
            assertThrows(SchemaException.class,
                    () -> keyBudget.addMember(illegal, 0, FieldPath.root()));
            assertPoisoned(keyBudget);
        }

        // 合法代理对仍然被接受。
        final PayloadWireBudget paired = new PayloadWireBudget();
        paired.addString("\uD83D\uDE00", 0, FieldPath.root());
        assertEquals(6, paired.bytes());
    }

    @Test
    @DisplayName("after any failure every entry point and both counters throw IllegalStateException")
    void failurePoisonsEveryEntryPoint() {
        final PayloadWireBudget budget = new PayloadWireBudget();
        assertThrows(IllegalArgumentException.class, () -> budget.addObject(-1, FieldPath.root()));

        assertThrows(IllegalStateException.class, () -> budget.addObject(0, FieldPath.root()));
        assertThrows(IllegalStateException.class, () -> budget.addArray(0, FieldPath.root()));
        assertThrows(IllegalStateException.class, () -> budget.addMember("a", 0, FieldPath.root()));
        assertThrows(IllegalStateException.class, () -> budget.addArraySeparator(0, FieldPath.root()));
        assertThrows(IllegalStateException.class, () -> budget.addString("a", 0, FieldPath.root()));
        assertThrows(IllegalStateException.class, () -> budget.addNumber("1", 0, FieldPath.root()));
        assertThrows(IllegalStateException.class, () -> budget.addValue(EMPTY_OBJECT, 0, FieldPath.root()));
        assertThrows(IllegalStateException.class,
                () -> budget.addMembers(EMPTY_OBJECT, Set.of(), 0, 0, FieldPath.root()));
        assertThrows(IllegalStateException.class, budget::bytes);
        assertThrows(IllegalStateException.class, budget::nodes);
    }

    @Test
    @DisplayName("a failed operation poisons every entry point, and a sibling instance stays isolated")
    void failedOperationLeavesNoPartialAccounting() {
        // 卡 R06.1a-b3 第 4 条：方法名因原 31 契约保留，但本用例**只**验证两件事：
        //   1) 真实失败后，两个读入口与全部写入口都进入 poison；
        //   2) 未越界的兄弟实例计数不受影响（实例隔离）。
        // 公开输出读不到失败实例的内部 counter，
        // 因此「先全量检查、后一次性更新」的内部原子性**不能**由本用例断言，
        // 也不在此声明已证；该项须由主代理源码顺序审查承担。
        final PayloadWireBudget overflow = new PayloadWireBudget();
        overflow.addObject(0, FieldPath.root());
        final SchemaException failure = assertThrows(SchemaException.class,
                () -> overflow.addMember("a".repeat(ExactJson.MAX_KEY_UTF8_BYTES + 1), 0,
                        FieldPath.root().child("tooLong")));
        assertEquals("LIMIT_EXCEEDED", failure.code());
        // 失败实例上：两个 getter 与全部写入口都必须抛 IllegalStateException，且用的是合法参数，
        // 以免后续用坏参数验证出别的异常。
        assertPoisoned(overflow);

        // 未越界的兄弟实例计数不受影响，仍等于骨架宽度：这**只**证明实例隔离。
        final PayloadWireBudget sibling = new PayloadWireBudget();
        sibling.addObject(0, FieldPath.root());
        assertEquals(2, sibling.bytes());
        assertEquals(1, sibling.nodes());

        // 边界声明：公开 API 读不到失败实例的内部 counter，因此“先全检查后更新”的内部原子性
        // 不能由这些公开 getter 断言推出，须由主源码顺序审查另行证明；本测试不为它暴露生产
        // 字段、不反射。
    }

    @Test
    @DisplayName("a fake reserved set that lies about its size is stopped at the real 64-key bound")
    void fakeReservedSetCannotSmuggleKeys() {
        final Set<String> lying = new LinkedHashSet<>();
        for (int i = 0; i < 70; i++) {
            lying.add("k" + i);
        }
        // 真实 size 已超 64：在扫描与记账前拒绝并毒化。
        final PayloadWireBudget budget = new PayloadWireBudget();
        final IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> budget.addMembers(EMPTY_OBJECT, lying, 0, 0, FieldPath.root()));
        assertNotNull(rejected);
        assertFalse(rejected instanceof SchemaException, "a bad argument is not a schema failure");
        assertPoisoned(budget);

        // 恰好 64 个合法 reserved 键被接受。
        final Set<String> exact = new LinkedHashSet<>();
        for (int i = 0; i < 64; i++) {
            exact.add("k" + i);
        }
        final PayloadWireBudget ok = new PayloadWireBudget();
        assertEquals(0, ok.addMembers(EMPTY_OBJECT, exact, 0, 0, FieldPath.root()));

        // reserved 中的 null 键被拒绝。
        final Set<String> withNull = new HashSet<>();
        withNull.add(null);
        final PayloadWireBudget nullKey = new PayloadWireBudget();
        assertThrows(IllegalArgumentException.class,
                () -> nullKey.addMembers(EMPTY_OBJECT, withNull, 0, 0, FieldPath.root()));
        assertPoisoned(nullKey);

        // 卡 R06.1a-b2 第 5 条：真实 AbstractSet，size() 谎报 0、hasNext 永真、next 计数。
        // 不得真的生成无限数据，因此 next 只在被调用时按计数返回合法 key。
        final InfiniteReservedSet infinite = new InfiniteReservedSet(true);
        final PayloadWireBudget infiniteBudget = new PayloadWireBudget();
        final IllegalArgumentException overflow = assertThrows(IllegalArgumentException.class,
                () -> infiniteBudget.addMembers(EMPTY_OBJECT, infinite, 0, 0, FieldPath.root()),
                "a set that lies about its size must still be stopped by the real 64-key bound");
        assertFalse(overflow instanceof SchemaException,
                "超限的 reserved 是参数错误，不是 schema 失败");
        assertEquals(64, infinite.nextCalls,
                "必须在第 65 次 next 之前拒绝：恰好读取 64 个 key");
        assertPoisoned(infiniteBudget);

        // 反面对照：size() 谎报 0、iterator 实际只有一个 `id`，仍须按真实 snapshot 判碰撞。
        final InfiniteReservedSet single = new InfiniteReservedSet(false);
        final PayloadWireBudget collision = new PayloadWireBudget();
        final ExactJson.FrozenJson idFragment = ExactJson.parse("{\"id\":1}");
        final SchemaException collisionFailure = assertThrows(SchemaException.class,
                () -> collision.addMembers(idFragment, single, 0, 0, FieldPath.root()),
                "碰撞判定必须依据真实读到的 key，而不是报告出来的 size");
        assertEquals("RESERVED_COLLISION", collisionFailure.code());
        assertEquals("/id", collisionFailure.path().pointer());
        assertPoisoned(collision);
        assertEquals(1, single.nextCalls, "碰撞必须由真实读到的第一个 key 判定");

        // 同一谎报 size 的集合下，不碰撞的片段必须被接受：证明拒绝不是无条件发生。
        final InfiniteReservedSet single2 = new InfiniteReservedSet(false);
        final PayloadWireBudget accepted = new PayloadWireBudget();
        assertEquals(1, accepted.addMembers(ExactJson.parse("{\"other\":1}"), single2, 0, 0,
                FieldPath.root()));
        assertEquals(0, single2.size(), "该集合对外始终谎报 size 0");
    }

    @Test
    @DisplayName("knownMemberCount bounds are enforced before any scanning")
    void knownMemberCountBounds() {
        final PayloadWireBudget negative = new PayloadWireBudget();
        assertThrows(IllegalArgumentException.class,
                () -> negative.addMembers(EMPTY_OBJECT, Set.of(), 0, -1, FieldPath.root()));
        assertPoisoned(negative);

        final PayloadWireBudget tooMany = new PayloadWireBudget();
        assertThrows(IllegalArgumentException.class,
                () -> tooMany.addMembers(EMPTY_OBJECT, Set.of(), 0, ExactJson.MAX_NODES + 1, FieldPath.root()));
        assertPoisoned(tooMany);

        final PayloadWireBudget atLimit = new PayloadWireBudget();
        assertEquals(0, atLimit.addMembers(EMPTY_OBJECT, Set.of(), 0, ExactJson.MAX_NODES, FieldPath.root()));
    }

    // ------------------------------------------------------------------
    // 大片段：重复出现逐次计费，受限堆下提前字节拒
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a large fragment is rejected on the byte budget before it is scanned again")
    void largeFragmentIsRejectedEarlyOnBytes() {
        // P02 限制单个字符串 ≤ 65536 字节，故用数组承载 64 个 64 KiB 字符串凑出约 4 MiB 的合法片段。
        // 该片段只构造一次，随后立刻释放，避免在受限堆下同时持有两棵树。
        final String chunk = "x".repeat(ExactJson.MAX_STRING_UTF8_BYTES);
        final StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 64; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(chunk).append('"');
        }
        json.append(']');
        final ExactJson.FrozenJson fragment = ExactJson.parse(json.toString());
        final int width = fragment.utf8Size();
        assertTrue(width > 4 * 1024 * 1024, "the fragment must be larger than 4 MiB, was " + width);
        assertTrue(width <= ExactJson.MAX_UTF8_BYTES, "but it must still be a legal P02 document");
        assertEquals(width, fragment.canonical()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                "the frozen byte width must equal an independent UTF-8 count of the canonical text");

        // 第一次接受，第二次必然越过 8 MiB 上限：必须在扫描前按已累计字节立即拒绝。
        final PayloadWireBudget budget = new PayloadWireBudget();
        budget.addValue(fragment, 0, FieldPath.root());
        assertEquals(width, budget.bytes());

        final SchemaException failure = assertThrows(SchemaException.class,
                () -> budget.addValue(fragment, 0, FieldPath.root().child("twice")));
        assertEquals("LIMIT_EXCEEDED", failure.code());
        assertEquals("/twice", failure.path().pointer());
        assertPoisoned(budget);
    }

    @Test
    @DisplayName("a fragment larger than the whole byte budget is refused immediately")
    void oversizedFragmentIsRefusedImmediately() {
        // P02 无法构造超过 8 MiB 的单个文档，也无法在 64 MiB 堆里安全持有一棵接近 8 MiB 的树。
        // 因此越限由“累计”造出：先用若干小的合法片段把字节预算逼近上限，再计入必然越界的一次。
        // 关键是每次 addValue 的片段本身都很小，越限完全来自累计字节，且拒绝发生在扫描之前。
        final ExactJson.FrozenJson unit = ExactJson.parse("[\"" + "y".repeat(1024) + "\"]");
        final int unitWidth = unit.utf8Size();
        final int units = ExactJson.MAX_UTF8_BYTES / unitWidth;

        final PayloadWireBudget budget = new PayloadWireBudget();
        for (int i = 0; i < units; i++) {
            budget.addValue(unit, 0, FieldPath.root());
        }
        assertEquals((long) units * unitWidth, budget.bytes());
        assertTrue(budget.bytes() > ExactJson.MAX_UTF8_BYTES - unitWidth,
                "the accumulated budget must sit within one unit of the limit");
        assertTrue(budget.bytes() + unitWidth > ExactJson.MAX_UTF8_BYTES,
                "one more unit must overflow the accumulated budget");
        assertEquals((long) units * oracleNodeCount(unit.canonical()), budget.nodes());

        // 这一次 addValue 必须在不扫描片段的情况下整体被拒。
        final SchemaException failure = assertThrows(SchemaException.class,
                () -> budget.addValue(unit, 1, FieldPath.root().child("data")));
        assertEquals("LIMIT_EXCEEDED", failure.code());
        assertEquals("/data", failure.path().pointer(),
                "the early rejection must report the insertion position");
        assertPoisoned(budget);
    }

    // ------------------------------------------------------------------
    // 反向声明：本测试不依赖的入口
    // ------------------------------------------------------------------

    @Test
    @DisplayName("this test drives the real budget with independently given wire numbers and never touches the private tree")
    void noPrivateTreeOrCopyIsUsed() {
        // 只读理由：本测试仅使用 canonical() 与 utf8Size() 两个公共只读入口，不调用 toJson()、
        // 不调用 deepCopy()、不调用 canonicalUtf8()、不反射、不复制私有树接口。
        final ExactJson.FrozenJson frozen = ExactJson.parse("{\"a\":[1,\"b\"]}");
        final String canonical = frozen.canonical();
        assertEquals(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, frozen.utf8Size());
        assertFalse(canonical.isEmpty());
        assertNotEquals(0, oracleNodeCount(canonical));

        // 卡 R06.1a-b2 第 3 条：这里还要真实驱动 budget.addValue 与 addMembers（含 known skeleton），
        // 并与独立预给的 wire bytes/nodes 核对，证明本测试走的是生产预算入口而非私有树。
        // 独立预给（本测试手算，不来自生产 profile）：
        //   owner 包裹 [ 一次：addArray 1 node / 2 bytes（'[' 与 ']'）
        //   known skeleton："id" 成员 5 bytes（'"id"' 4 + ':' 1）+ 数字 1 的 1 byte、1 node
        //   extras 片段 {"a":[1,"b"]}：4 nodes、12 bytes，见下方 canonical 文本
        final String extrasText = "{\"a\":[1,\"b\"]}";
        assertEquals(extrasText, canonical, "the frozen canonical text must be the expected literal");
        final long knownBytes = "\"id\":".length() + 1L;
        final long extrasBytes = modelUtf8Bytes(extrasText);
        final long connector = 1L; // known 非空且 extras 非空：根 object 内的合并逗号

        final PayloadWireBudget budget = new PayloadWireBudget();
        budget.addArray(0, FieldPath.root());
        budget.addObject(1, FieldPath.root());
        budget.addMember("id", 0, FieldPath.root().child("id"));
        budget.addNumber("1", 2, FieldPath.root().child("id"));
        final int rootMembers = budget.addMembers(frozen, Set.of(), 1, 1, FieldPath.root());
        assertEquals(1, rootMembers, "the certified root member count must be the model count");
        assertEquals(1 + knownBytes + extrasBytes + connector + 1, budget.bytes(),
                "the real budget must charge the independently pre-given byte width");
        assertEquals(1 + 1 + oracleNodeCount(extrasText), budget.nodes(),
                "the real budget must charge the independently pre-given node count");

        // addValue 一侧用同一片段，独立预给 nodes/bytes。
        final PayloadWireBudget viaValue = new PayloadWireBudget();
        viaValue.addArray(0, FieldPath.root());
        viaValue.addValue(frozen, 1, FieldPath.root());
        assertEquals(2L + extrasBytes, viaValue.bytes());
        assertEquals(1 + oracleNodeCount(extrasText), viaValue.nodes());

        // 边界声明：以上只证明本测试使用的入口是公开只读入口与真实预算入口；“生产不做 copy /
        // 不建私有树”不能由输出正确推出，须由主源码审查或 heap probe 另行证明。
    }

    // ------------------------------------------------------------------
    // 独立模型（不复用生产实现）
    // ------------------------------------------------------------------

    /** 独立节点计数：一个字符一个字符地数，不调用生产扫描。 */
    private static int oracleNodeCount(String canonical) {
        final OracleScanner scanner = new OracleScanner(canonical);
        final int[] slots = new int[2];
        scanner.value(0, slots);
        assertEquals(canonical.length(), scanner.cursor, "the oracle must consume the whole fragment");
        return slots[0];
    }

    /** 独立最大相对深度：片段根为 0。 */
    private static int oracleMaxRelativeDepth(String canonical) {
        final OracleScanner scanner = new OracleScanner(canonical);
        final int[] slots = new int[2];
        scanner.value(0, slots);
        return slots[1];
    }

    /** 独立的 canonical 扫描模型，写在测试内，与被测实现无共享代码。 */
    private static final class OracleScanner {

        private final String text;

        private int cursor;

        OracleScanner(String text) {
            this.text = text;
        }

        void value(int depth, int[] slots) {
            final char c = this.text.charAt(this.cursor);
            slots[0]++;
            if (depth > slots[1]) {
                slots[1] = depth;
            }
            if (c == '{') {
                this.cursor++;
                if (this.text.charAt(this.cursor) != '}') {
                    while (true) {
                        string();
                        this.cursor++; // ':'
                        value(depth + 1, slots);
                        if (this.text.charAt(this.cursor) == ',') {
                            this.cursor++;
                            continue;
                        }
                        break;
                    }
                }
                this.cursor++; // '}'
            } else if (c == '[') {
                this.cursor++;
                if (this.text.charAt(this.cursor) != ']') {
                    while (true) {
                        value(depth + 1, slots);
                        if (this.text.charAt(this.cursor) == ',') {
                            this.cursor++;
                            continue;
                        }
                        break;
                    }
                }
                this.cursor++; // ']'
            } else if (c == '"') {
                string();
            } else {
                while (this.cursor < this.text.length()) {
                    final char n = this.text.charAt(this.cursor);
                    if (n == ',' || n == '}' || n == ']') {
                        break;
                    }
                    this.cursor++;
                }
            }
        }

        private void string() {
            this.cursor++; // '"'
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
    }

    /** 随机生成一个合法 JSON 值，深度受限，保证能被冻结 P02 认证。 */
    private static String randomValue(Random random, int depth, int maxDepth) {
        final int kind = random.nextInt(depth >= maxDepth ? 4 : 6);
        switch (kind) {
            case 0:
                return String.valueOf(random.nextInt(1000) - 500);
            case 1:
                return random.nextBoolean() ? "true" : "false";
            case 2:
                return "null";
            case 3:
                return "\"" + randomText(random) + "\"";
            case 4: {
                final int size = random.nextInt(3);
                final StringBuilder builder = new StringBuilder("[");
                for (int i = 0; i < size; i++) {
                    if (i > 0) {
                        builder.append(',');
                    }
                    builder.append(randomValue(random, depth + 1, maxDepth));
                }
                return builder.append(']').toString();
            }
            default: {
                final int size = random.nextInt(3);
                final StringBuilder builder = new StringBuilder("{");
                final Set<String> keys = new LinkedHashSet<>();
                for (int i = 0; i < size; i++) {
                    keys.add("k" + i + "_" + random.nextInt(50));
                }
                int written = 0;
                for (String key : keys) {
                    if (written > 0) {
                        builder.append(',');
                    }
                    builder.append('"').append(key).append("\":");
                    builder.append(randomValue(random, depth + 1, maxDepth));
                    written++;
                }
                return builder.append('}').toString();
            }
        }
    }

    /**
     * 随机文本：覆盖 ASCII、转义敏感字符、两字节与四字节字符。
     *
     * <p>元素一律写成已经 JSON 转义的片段，因为 canonical 会把 {@code \\u0001} 这类等价拼写归一，
     * 未转义的真实控制字符则根本进不了合法的 JSON 文档。</p>
     */
    private static String randomText(Random random) {
        final String[] pieces = {"", "a", "=", "~", "/", "\\\"", "\\\\", "\\n", "\\t", "\\u0001",
            "\\u00e9", "\u4e2d", "\uD83D\uDE00", "\\\\/", "\\b", "\\f", "\\r"};
        final int size = random.nextInt(4);
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < size; i++) {
            builder.append(pieces[random.nextInt(pieces.length)]);
        }
        return builder.toString();
    }

    /**
     * 失败后必须对一切入口抛 IllegalStateException。
     *
     * <p>卡 R06.1a-b2 第 7 条：覆盖 bytes/nodes 两个 getter 与**全部 8 个写入口**，
     * 参数一律合法（小片段预备），以免后续用坏参数验证出别的异常。</p>
     */
    private static void assertPoisoned(PayloadWireBudget budget) {
        assertThrows(IllegalStateException.class, budget::bytes,
                "a failed budget must refuse to report its byte count");
        assertThrows(IllegalStateException.class, budget::nodes,
                "a failed budget must refuse to report its node count");

        final ExactJson.FrozenJson small = ExactJson.parse("{\"a\":1}");
        assertThrows(IllegalStateException.class, () -> budget.addArray(0, FieldPath.root()),
                "addArray must refuse on a failed budget");
        assertThrows(IllegalStateException.class, () -> budget.addObject(0, FieldPath.root()),
                "addObject must refuse on a failed budget");
        assertThrows(IllegalStateException.class,
                () -> budget.addMember("a", 0, FieldPath.root().child("a")),
                "addMember must refuse on a failed budget");
        assertThrows(IllegalStateException.class, () -> budget.addArraySeparator(1, FieldPath.root()),
                "addArraySeparator must refuse on a failed budget");
        assertThrows(IllegalStateException.class, () -> budget.addString("s", 1, FieldPath.root()),
                "addString must refuse on a failed budget");
        assertThrows(IllegalStateException.class, () -> budget.addNumber("1", 1, FieldPath.root()),
                "addNumber must refuse on a failed budget");
        assertThrows(IllegalStateException.class, () -> budget.addValue(small, 1, FieldPath.root()),
                "addValue must refuse on a failed budget");
        assertThrows(IllegalStateException.class,
                () -> budget.addMembers(small, Set.of(), 1, 0, FieldPath.root()),
                "addMembers must refuse on a failed budget");
    }

    /**
     * 一个真实 {@link java.util.AbstractSet}：对外始终谎报 {@code size()==0}，
     * 而 {@code iterator()} 实际会产出 key（endless 时无限多），{@code next()} 每次计数。
     *
     * <p>谎报的是 {@code size()}：生产若相信报告出来的 0，就会在扫描到第 65 个真实 key 时
     * 才失败，或让碰撞漏检。{@code hasNext()} 仍如实回答，因为它描述的是本集合自己的迭代
     * 状态，不是被谎报的规模；否则测到的将是坏迭代器而不是坏 size。</p>
     *
     * <p>它不预先生成无限数据；只有真的被迭代时才产生 key。</p>
     */
    private static final class InfiniteReservedSet extends java.util.AbstractSet<String> {

        /** true 时 iterator 永续产出；false 时只产出唯一一个 {@code id}。 */
        private final boolean endless;

        /** 被调用过的 next() 次数。 */
        private int nextCalls;

        InfiniteReservedSet(boolean endless) {
            this.endless = endless;
        }

        @Override
        public java.util.Iterator<String> iterator() {
            return new java.util.Iterator<String>() {

                private int served;

                @Override
                public boolean hasNext() {
                    return endless || served == 0; // 如实回答：非 endless 只提供一个 key
                }

                @Override
                public String next() {
                    if (!hasNext()) {
                        throw new java.util.NoSuchElementException("this fake set holds exactly one key");
                    }
                    nextCalls++;
                    served++;
                    // 第一个 key 是 "id"（用于碰撞对照）；endless 时继续产出，使生产能读到第 65 项。
                    // 绝不预生成无限数据：只有真的被调用才产生 key。
                    if (served == 1) {
                        return "id";
                    }
                    return "generated" + served;
                }
            };
        }

        @Override
        public int size() {
            return 0; // 谎报
        }
    }

    /** 保留一个未使用的列表类型引用，说明本测试只依赖只读集合语义。 */
    private static List<String> unusedReadOnlyList() {
        return new ArrayList<>();
    }

    // ------------------------------------------------------------------
    // 独立 canonical 模型（卡 R06.1a-b2 第 1 条）
    //
    // 本模型在调用 ExactJson / PayloadWireBudget 之前就给出完整的 canonical wire 文本、
    // JDK UTF-8 字节长度、节点数与最大相对深度；不把生产 canonical()/profile/成功结果
    // 当作模型输入或期望来源。所有随机性由固定 seed 驱动。
    // ------------------------------------------------------------------

    /** 模型允许的 canonical 数字词法：只使用测试预给的这四种。 */
    private static final String[] MODEL_NUMBERS = {"0", "-7", "3.125", "1E+20"};

    /** 模型允许的 literal。 */
    private static final String[] MODEL_LITERALS = {"true", "false", "null"};

    /** 一个 extras 样本：canonical 片段文本、成员数、节点数、最大相对深度。 */
    private static final class ExtrasSample {

        final String canonical;

        final int rootMembers;

        final int nodes;

        final int maxRelativeDepth;

        ExtrasSample(String canonical, int rootMembers, int nodes, int maxRelativeDepth) {
            this.canonical = canonical;
            this.rootMembers = rootMembers;
            this.nodes = nodes;
            this.maxRelativeDepth = maxRelativeDepth;
        }
    }

    /** 一个 known 成员：键与它的标量 canonical 文本。 */
    private static final class KnownMember {

        final String key;

        final String scalar;

        KnownMember(String key, String scalar) {
            this.key = key;
            this.scalar = scalar;
        }
    }

    /**
     * 按 P02 固定输出规则把 decoded 字符串写成 canonical quoted 文本。
     *
     * <p>b/t/n/f/r 用短 escape；其他 U+0000–001F 用小写 hex 的 6 字节 escape；
     * quote 与 backslash 用两字节 escape；slash 不转义；合法非 ASCII 与 paired surrogate
     * 保留原文。</p>
     */
    private static String modelQuote(String decoded) {
        final StringBuilder out = new StringBuilder(decoded.length() + 2);
        out.append('"');
        int i = 0;
        while (i < decoded.length()) {
            final char c = decoded.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
            i++;
        }
        out.append('"');
        return out.toString();
    }

    /** 独立 UTF-8 字节数：用 JDK 真实编码，不使用生产 utf8Size。 */
    private static int modelUtf8Bytes(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /**
     * 生成一个 extras 样本：固定形状、按 decoded key 的 {@link String#compareTo} 排序，
     * 不复制生产 scanner，也不用生产排序结果建期望。
     *
     * <p>每个样本的根 key 都带唯一编号（{@code s<index>}），因此 canonical 文本两两不同；
     * 根 key 一律以 {@code s} 开头，与 known 的 {@code k…} 前缀不碰撞。extras 自身覆盖
     * quote、backslash、slash、control、合法非 ASCII 与 paired surrogate 的 key escape。</p>
     *
     * @param index 样本编号，保证唯一
     */
    private static ExtrasSample modelExtras(long index) {
        final int shape = (int) (index % 6);
        final String prefix = "s" + index;
        switch (shape) {
            case 0:
                // 空 extras：root key 的唯一性由其它 5 个形状承担。
                return new ExtrasSample("{}", 0, 1, 0);
            case 1: {
                final String key = prefix + extrasKeyForm(index);
                final String scalar = MODEL_NUMBERS[(int) (index % MODEL_NUMBERS.length)];
                return new ExtrasSample("{" + modelQuote(key) + ":" + scalar + "}", 1, 2, 1);
            }
            case 2: {
                final String key = prefix + extrasKeyForm(index);
                final String scalar = MODEL_LITERALS[(int) ((index / 6) % MODEL_LITERALS.length)];
                return new ExtrasSample("{" + modelQuote(key) + ":" + scalar + "}", 1, 2, 1);
            }
            case 3: {
                // 一个成员的值是数组，两个 number。
                final String key = prefix + extrasKeyForm(index);
                final String a = MODEL_NUMBERS[(int) (index % MODEL_NUMBERS.length)];
                final String b = MODEL_NUMBERS[(int) ((index + 1) % MODEL_NUMBERS.length)];
                return new ExtrasSample("{" + modelQuote(key) + ":[" + a + "," + b + "]}", 1, 4, 2);
            }
            case 4: {
                // 一个成员的值是 object，内层一个 number：外层 Unicode key、内层固定 ASCII key。
                final String outer = prefix + extrasKeyForm(index);
                final String inner = "id";
                final String scalar = MODEL_NUMBERS[(int) (index % MODEL_NUMBERS.length)];
                return new ExtrasSample("{" + modelQuote(outer) + ":{" + modelQuote(inner) + ":" + scalar + "}}",
                        1, 3, 2);
            }
            default: {
                // 两个根成员，按 decoded key 的 compareTo 排序后写出。
                final String k1 = prefix + extrasKeyForm(index);
                final String k2 = prefix + "z" + extrasKeyForm(index + 1);
                final String s1 = MODEL_NUMBERS[(int) (index % MODEL_NUMBERS.length)];
                final String s2 = MODEL_LITERALS[(int) ((index / 6) % MODEL_LITERALS.length)];
                final KnownMember[] members = k1.compareTo(k2) <= 0
                        ? new KnownMember[] {new KnownMember(k1, s1), new KnownMember(k2, s2)}
                        : new KnownMember[] {new KnownMember(k2, s2), new KnownMember(k1, s1)};
                final StringBuilder text = new StringBuilder("{");
                for (int i = 0; i < members.length; i++) {
                    if (i > 0) {
                        text.append(',');
                    }
                    text.append(modelQuote(members[i].key)).append(':').append(members[i].scalar);
                }
                return new ExtrasSample(text.append('}').toString(), 2, 3, 1);
            }
        }
    }

    /**
     * extras 根 key 的后缀，按用户给的固定次序覆盖各种 escape 形态：
     * 纯 ASCII、quote、backslash、slash、control、2 字节非 ASCII、3 字节非 ASCII、paired surrogate。
     */
    private static String extrasKeyForm(long index) {
        return switch ((int) (index % 8)) {
            case 0 -> "p";
            case 1 -> "q\"q";
            case 2 -> "b\\b";
            case 3 -> "s/s";
            case 4 -> "c\u0001c";
            case 5 -> "\u00e9";
            case 6 -> "\u4e2d";
            default -> "\uD83D\uDE00";
        };
    }

    /**
     * 生成 known 骨架成员：0–8 个，key 一律以 {@code k} 开头且与 extras 的 {@code s…} 前缀不碰撞。
     *
     * @param count 成员数，0–8
     */
    private static List<KnownMember> modelKnown(long index, int count) {
        final List<KnownMember> members = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // 覆盖合法 Unicode、quote、backslash、slash 与需要 escape 的键。
            final String[] keyForms = {"k" + index + "_" + i, "k\"q" + i, "k\\b" + i, "k/s" + i,
                "k\u4e2d" + i, "k\u0001c" + i, "k\uD83D\uDE00e" + i};
            members.add(new KnownMember(keyForms[i % keyForms.length],
                    MODEL_NUMBERS[(int) ((index + i) % MODEL_NUMBERS.length)]));
        }
        return members;
    }

    /**
     * 独立构造完整 wire：{@code [} 重复 {@code ownerDepth} 次 + known 与 extras 合并后的 object
     * + {@code ]} 重复 {@code ownerDepth} 次。
     *
     * <p>返回的 object 文本按 merged 的 decoded key 排序写出，仅用于独立模型；实际
     * accumulator 的书写次序不影响标点总宽。</p>
     */
    private static String modelWire(int ownerDepth, List<KnownMember> known, ExtrasSample extras) {
        final List<String[]> merged = new ArrayList<>();
        for (KnownMember member : known) {
            merged.add(new String[] {member.key, member.scalar});
        }
        appendExtrasMembers(merged, extras.canonical);
        merged.sort((left, right) -> left[0].compareTo(right[0]));

        final StringBuilder body = new StringBuilder("{");
        for (int i = 0; i < merged.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append(modelQuote(merged.get(i)[0])).append(':').append(merged.get(i)[1]);
        }
        body.append('}');

        final StringBuilder wire = new StringBuilder();
        for (int i = 0; i < ownerDepth; i++) {
            wire.append('[');
        }
        wire.append(body);
        for (int i = 0; i < ownerDepth; i++) {
            wire.append(']');
        }
        return wire.toString();
    }

    /** 把 extras canonical 的根成员以 [key, 值文本] 形式追加进 merged。 */
    private static void appendExtrasMembers(List<String[]> merged, String extrasCanonical) {
        if ("{}".equals(extrasCanonical)) {
            return;
        }
        final String inner = extrasCanonical.substring(1, extrasCanonical.length() - 1);
        for (String part : splitTopLevel(inner)) {
            final int colon = indexOfTopLevelColon(part);
            final String rawKey = part.substring(0, colon);
            final String value = part.substring(colon + 1);
            merged.add(new String[] {modelUnquote(rawKey), value});
        }
    }

    /** 独立取出 extras canonical 的根成员 decoded key，用于与 known key 做碰撞检查。 */
    private static List<String> modelExtrasRootKeys(ExtrasSample extras) {
        final List<String> keys = new ArrayList<>();
        if ("{}".equals(extras.canonical)) {
            return keys;
        }
        final String inner = extras.canonical.substring(1, extras.canonical.length() - 1);
        for (String part : splitTopLevel(inner)) {
            keys.add(modelUnquote(part.substring(0, indexOfTopLevelColon(part))));
        }
        return keys;
    }

    /** 按顶层逗号切分（模型输入只含 0–2 个成员且值深度 ≤2，故只需处理引号与括号）。 */
    private static List<String> splitTopLevel(String text) {
        final List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '[' || c == '{') {
                depth++;
            } else if (c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    /** 顶层冒号位置：跳过 quoted key 内部的冒号。 */
    private static int indexOfTopLevelColon(String member) {
        boolean inString = false;
        for (int i = 0; i < member.length(); i++) {
            final char c = member.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == ':') {
                return i;
            }
        }
        throw new IllegalStateException("the model member has no top-level colon: " + member);
    }

    /**
     * 把模型自己写出的 canonical quoted 文本解回 decoded 字符串。
     *
     * <p>只处理模型自身会产出的转义：b/t/n/f/r、控制字符的六字符转义、quote 与 backslash。
     * 其余 {@code \x} 一律按“反斜杠加原字符”解，与 P02 的最简转义语义一致。</p>
     */
    private static String modelUnquote(String quoted) {
        final StringBuilder out = new StringBuilder(quoted.length());
        int i = 1;
        while (i < quoted.length() - 1) {
            final char c = quoted.charAt(i);
            if (c != '\\') {
                out.append(c);
                i++;
                continue;
            }
            final char e = quoted.charAt(i + 1);
            switch (e) {
                case 'b':
                    out.append('\b');
                    i += 2;
                    break;
                case 't':
                    out.append('\t');
                    i += 2;
                    break;
                case 'n':
                    out.append('\n');
                    i += 2;
                    break;
                case 'f':
                    out.append('\f');
                    i += 2;
                    break;
                case 'r':
                    out.append('\r');
                    i += 2;
                    break;
                case 'u':
                    out.append((char) Integer.parseInt(quoted.substring(i + 2, i + 6), 16));
                    i += 6;
                    break;
                case '"':
                    out.append('"');
                    i += 2;
                    break;
                case '\\':
                    out.append('\\');
                    i += 2;
                    break;
                default:
                    // 模型不会产出这种转义；按“反斜杠加原字符”解，保持与 P02 一致。
                    out.append('\\').append(e);
                    i += 2;
                    break;
            }
        }
        return out.toString();
    }
}
