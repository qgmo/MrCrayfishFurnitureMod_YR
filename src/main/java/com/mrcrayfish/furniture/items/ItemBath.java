package com.mrcrayfish.furniture.items;

import com.mrcrayfish.furniture.util.PlacementHelper;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class ItemBath extends ItemBlock
{
    public ItemBath(Block block)
    {
        super(block);
    }

    @Override
    public boolean canPlaceBlockOnSide(World worldIn, BlockPos pos, EnumFacing side, EntityPlayer player, ItemStack stack)
    {
        return worldIn.isAirBlock(pos.offset(side)) && worldIn.isAirBlock(pos.offset(side).offset(player.getHorizontalFacing(), 1));
    }

    /**
     * 浴缸、跳水板的第二格放在玩家朝向的下一格（见各方块的 onBlockPlacedBy）。
     * 原版流程只检查第一格，这里先核对第二格，不能放就整体不放、物品不扣。
     */
    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World worldIn, BlockPos pos, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ)
    {
        boolean replaceable = worldIn.getBlockState(pos).getBlock().isReplaceable(worldIn, pos);
        BlockPos base = PlacementHelper.resolveBasePos(replaceable, pos, facing);
        BlockPos second = base.offset(player.getHorizontalFacing());
        if(!PlacementHelper.canPlayerPlaceAt(worldIn, player, second, facing, player.getHeldItem(hand)))
        {
            return EnumActionResult.FAIL;
        }
        return super.onItemUse(player, worldIn, pos, hand, facing, hitX, hitY, hitZ);
    }
}
