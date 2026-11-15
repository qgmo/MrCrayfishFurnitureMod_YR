package com.mrcrayfish.furniture.blocks;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.advancement.Triggers;
import com.mrcrayfish.furniture.computer.ComputerCookies;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.List;

public class BlockComputer extends BlockFurnitureTile
{
    private static final AxisAlignedBB BOUNDING_BOX = new AxisAlignedBB(0.1, 0.0, 0.1, 0.9, 1.0, 0.9);

    public BlockComputer(Material material)
    {
        super(material);
        this.setHardness(1.0F);
        this.setSoundType(SoundType.ANVIL);
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack)
    {
        if(placer instanceof EntityPlayer)
        {
            Triggers.trigger(Triggers.PLACE_APPLIANCE, (EntityPlayer) placer);
        }
        super.onBlockPlacedBy(world, pos, state, placer, stack);
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ)
    {
        // 悠然服务器魔改：谁都能打开；第一个打开（且有使用权）的人操作，其他人旁观（见 TileEntityComputer）
        TileEntity tile_entity = worldIn.getTileEntity(pos);
        if(tile_entity instanceof TileEntityComputer && !worldIn.isRemote)
        {
            playerIn.openGui(MrCrayfishFurnitureMod.instance, 0, worldIn, pos.getX(), pos.getY(), pos.getZ());
        }
        return true;
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state)
    {
        // 悠然服务器魔改：电脑拆了（含被搬走），这里存的 Cookie 跟着删掉（服主 11-15）
        if(!world.isRemote)
        {
            ComputerCookies.remove(world, pos);
            // 外设槽里的定位模块掉出来
            TileEntity te = world.getTileEntity(pos);
            if(te instanceof TileEntityComputer)
                ((TileEntityComputer) te).dropPeripherals();
        }
        super.breakBlock(world, pos, state);
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos)
    {
        return BOUNDING_BOX;
    }

    @Override
    public void addCollisionBoxToList(IBlockState state, World worldIn, BlockPos pos, AxisAlignedBB entityBox, List<AxisAlignedBB> collidingBoxes, Entity entityIn, boolean isActualState)
    {
        addCollisionBoxToList(pos, entityBox, collidingBoxes, state.getCollisionBoundingBox(worldIn, pos));
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta)
    {
        return new TileEntityComputer();
    }
}
