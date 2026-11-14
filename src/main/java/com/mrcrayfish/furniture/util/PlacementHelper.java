package com.mrcrayfish.furniture.util;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.function.Predicate;

/**
 * 多格家具放置时对每个要写入的格子做的检查（悠然服务器魔改）。
 * <p>
 * 家具的第二格（浴缸、跳水板、礼物、双人床、推拉门上半等）原来不经过原版放置流程，
 * 会直接覆盖旁边的方块。这里对每个要写入的格子核对：在世界范围内、区块已加载、
 * 玩家可以编辑这一格（{@link EntityPlayer#canPlayerEdit}，冒险模式、出生点保护等）、
 * 目标格可替换（空气、草、雪片这类）。任何一格不过就整体不放。
 * <p>
 * 写入本身仍然在玩家右键方块的处理过程中进行，SpongeForge 会把这些改动记到玩家名下，
 * 领地插件照常拦截。
 */
public final class PlacementHelper
{
    private PlacementHelper() {}

    /**
     * 原版 ItemBlock 的规则：点到的方块可替换时放在它的位置，否则放在点到的那一面外侧。
     */
    public static BlockPos resolveBasePos(boolean clickedReplaceable, BlockPos clicked, EnumFacing face)
    {
        return clickedReplaceable ? clicked : clicked.offset(face);
    }

    /**
     * 高度是否在可建造范围内（0 ≤ y &lt; height）。
     */
    public static boolean withinHeight(int y, int height)
    {
        return y >= 0 && y < height;
    }

    /**
     * 全部格子都通过检查才返回 true；没有格子时返回 false。
     */
    public static boolean all(Iterable<BlockPos> cells, Predicate<BlockPos> check)
    {
        boolean any = false;
        for(BlockPos cell : cells)
        {
            if(!check.test(cell))
            {
                return false;
            }
            any = true;
        }
        return any;
    }

    /**
     * 玩家能否把家具的一格写到 pos：范围、区块、编辑权限、出生点保护、目标格可替换。
     */
    public static boolean canPlayerPlaceAt(World world, EntityPlayer player, BlockPos pos, EnumFacing side, ItemStack stack)
    {
        if(!withinHeight(pos.getY(), world.getHeight()))
            return false;
        if(!world.getWorldBorder().contains(pos))
            return false;
        if(!world.isBlockLoaded(pos))
            return false;
        if(!player.canPlayerEdit(pos, side, stack))
            return false;
        if(!world.isRemote)
        {
            MinecraftServer server = world.getMinecraftServer();
            if(server != null && server.isBlockProtected(world, pos, player))
                return false;
        }
        return world.getBlockState(pos).getBlock().isReplaceable(world, pos);
    }

    /**
     * 多格家具拆掉一半时，只有另一半确实是配套的方块才一起拆，避免把旁边别的方块删掉。
     */
    public static boolean isPartner(World world, BlockPos pos, Predicate<net.minecraft.block.Block> partner)
    {
        return world.isBlockLoaded(pos) && partner.test(world.getBlockState(pos).getBlock());
    }
}
