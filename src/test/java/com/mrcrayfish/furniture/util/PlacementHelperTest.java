package com.mrcrayfish.furniture.util;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlacementHelperTest
{
    @Test
    void basePosFollowsVanillaItemBlockRule()
    {
        BlockPos clicked = new BlockPos(10, 4, 10);
        assertEquals(clicked, PlacementHelper.resolveBasePos(true, clicked, EnumFacing.UP));
        assertEquals(new BlockPos(10, 5, 10), PlacementHelper.resolveBasePos(false, clicked, EnumFacing.UP));
        assertEquals(new BlockPos(10, 4, 9), PlacementHelper.resolveBasePos(false, clicked, EnumFacing.NORTH));
    }

    @Test
    void secondCellOfBathIsNextToBaseInPlayerFacing()
    {
        // 审查报告里的场景：点草丛顶面、玩家朝东，第二格是东边一格（原来那里的基岩会被覆盖）
        BlockPos base = PlacementHelper.resolveBasePos(true, new BlockPos(10, 4, 10), EnumFacing.UP);
        assertEquals(new BlockPos(11, 4, 10), base.offset(EnumFacing.EAST));
    }

    @Test
    void heightLimits()
    {
        assertTrue(PlacementHelper.withinHeight(0, 256));
        assertTrue(PlacementHelper.withinHeight(255, 256));
        assertFalse(PlacementHelper.withinHeight(256, 256));
        assertFalse(PlacementHelper.withinHeight(-1, 256));
    }

    @Test
    void allCellsMustPass()
    {
        BlockPos a = new BlockPos(0, 4, 0), b = new BlockPos(1, 4, 0);
        Set<BlockPos> ok = new HashSet<>(Arrays.asList(a, b));
        assertTrue(PlacementHelper.all(Arrays.asList(a, b), ok::contains));
        assertFalse(PlacementHelper.all(Arrays.asList(a, b), a::equals));
        assertFalse(PlacementHelper.all(Collections.emptyList(), p -> true));
    }
}
