package net.ptcrys.fpsmatch.core.data;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区域数据契约回归测试。
 *
 * <p>覆盖服务端边界迁移后必须锁死的既有语义：构造期冻结可变角点、方块端点为双端包含、
 * 实体判定沿用 {@code AABB.contains} 的最大端不含、放置区保留历史水平语义、Codec 往返保持角点顺序。
 * 绘制相关行为已迁至 {@code common.client.data.AreaRenderer}，此处不做断言。
 *
 * <p>本测试只依赖服务端可达的类（{@code AreaData}、{@code BlockPos}、{@code Vec3}、{@code JsonOps}），
 * 普通测试运行即可完成，不需要客户端环境；core 的"不引用客户端渲染类型"由结构本身与主代理的
 * 探针/编译检查保证，这里不做反射断言。
 */
class AreaDataTest {

    @Test
    void constructorFreezesMutableCorners() {
        BlockPos.MutableBlockPos first = new BlockPos.MutableBlockPos(8, 9, 10);
        BlockPos.MutableBlockPos second = new BlockPos.MutableBlockPos(2, 3, 4);

        AreaData area = new AreaData(first, second);
        // 调用方复用同一个 MutableBlockPos 继续定位，不得让已构造的区域跟着漂移。
        first.set(100, 100, 100);
        second.set(-100, -100, -100);

        assertEquals(new BlockPos(8, 9, 10), area.pos1());
        assertEquals(new BlockPos(2, 3, 4), area.pos2());
        assertEquals(2.0D, area.aabb().minX);
        assertEquals(3.0D, area.aabb().minY);
        assertEquals(4.0D, area.aabb().minZ);
        assertEquals(8.0D, area.aabb().maxX);
        assertEquals(9.0D, area.aabb().maxY);
        assertEquals(10.0D, area.aabb().maxZ);
        // 漂移后的坐标必须落在区域之外，证明 AABB 也是按快照构造的。
        assertFalse(area.isBlockPosInArea(new BlockPos(100, 100, 100)));
        assertFalse(area.isBlockPosInArea(new BlockPos(-100, -100, -100)));
    }

    @Test
    void blockEndpointsStayInclusiveForReversedCorners() {
        AreaData area = new AreaData(new BlockPos(8, 9, 10), new BlockPos(2, 3, 4));

        // 角点顺序颠倒不影响结果，两个角块本身都算在区域内。
        assertTrue(area.isBlockPosInArea(new BlockPos(2, 3, 4)));
        assertTrue(area.isBlockPosInArea(new BlockPos(8, 9, 10)));
        assertTrue(area.isBlockPosInArea(new BlockPos(5, 6, 7)));
        assertFalse(area.isBlockPosInArea(new BlockPos(9, 9, 10)));
        assertFalse(area.isBlockPosInArea(new BlockPos(1, 3, 4)));
    }

    @Test
    void entityCheckKeepsExclusiveMaxEndpoint() {
        AreaData area = new AreaData(new BlockPos(8, 9, 10), new BlockPos(2, 3, 4));

        // 实体/坐标判定走 AABB.contains：最小端含、最大端不含，与方块判定刻意不同。
        assertTrue(area.isInArea(new Vec3(2, 3, 4)));
        assertTrue(area.isInArea(new Vec3(7.9D, 8.9D, 9.9D)));
        assertFalse(area.isInArea(new Vec3(8, 9, 10)));
    }

    @Test
    void placementAreaKeepsHistoricalHorizontalSemantics() {
        AreaData area = new AreaData(new BlockPos(2, 3, 4), new BlockPos(8, 9, 10));

        BlockPos above = new BlockPos(3, 999, 5);
        assertTrue(area.isBlockPosInHorizontalArea(above));
        assertTrue(area.isBlockPosInPlacementArea(above));
        assertFalse(area.isBlockPosInArea(above));

        assertFalse(area.isBlockPosInPlacementArea(new BlockPos(9, 5, 5)));
        assertFalse(area.isBlockPosInPlacementArea(new BlockPos(3, 5, 11)));
    }

    @Test
    void codecRoundTripPreservesOrderedCorners() {
        AreaData area = new AreaData(new BlockPos(8, 9, 10), new BlockPos(2, 3, 4));

        var json = AreaData.CODEC.encodeStart(JsonOps.INSTANCE, area).result().orElseThrow();
        AreaData decoded = AreaData.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow();

        // 角点顺序是持久化契约的一部分：不得在往返中被归一化排序。
        assertEquals(area.pos1(), decoded.pos1());
        assertEquals(area.pos2(), decoded.pos2());
        assertEquals(area.aabb(), decoded.aabb());
    }

    @Test
    void degenerateAreaKeepsDistinctBlockAndEntitySemantics() {
        AreaData single = new AreaData(new BlockPos(1, 1, 1), new BlockPos(1, 1, 1));

        // 单方块区域：方块判定命中，实体坐标判定仍因最大端不含而落空——历史行为，不做"修正"。
        assertTrue(single.isBlockPosInArea(new BlockPos(1, 1, 1)));
        assertFalse(single.isInArea(new Vec3(1, 1, 1)));
    }
}