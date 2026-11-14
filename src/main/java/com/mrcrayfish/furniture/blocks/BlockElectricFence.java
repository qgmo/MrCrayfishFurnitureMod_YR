package com.mrcrayfish.furniture.blocks;

import com.mrcrayfish.furniture.DamageSourceFence;
import com.mrcrayfish.furniture.advancement.Triggers;
import com.mrcrayfish.furniture.compat.FencePvp;
import com.mrcrayfish.furniture.init.FurnitureSounds;
import com.mrcrayfish.furniture.tileentity.TileEntityElectricFence;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.stats.StatList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.NonNullList;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;

/**
 * 悠然服务器魔改：电栅栏自己存电（见 {@link TileEntityElectricFence}）。碰到一次扣 50 FE，没电就是普通栅栏；
 * 伤害照旧 2 点、对玩家照 PvP 规则。拆下的电栅栏带着容量和剩余电量。
 */
public class BlockElectricFence extends BlockWhiteFence
{
    public static final DamageSource ELECTRIC_FENCE = new DamageSourceFence("electricFence");

    public BlockElectricFence(Material material)
    {
        super(material);
        this.setHardness(1.0F);
        this.setSoundType(SoundType.ANVIL);
        this.setLightLevel(0.2F);
    }

    @Override
    public boolean hasTileEntity(IBlockState state)
    {
        return true;
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(World world, IBlockState state)
    {
        return new TileEntityElectricFence();
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack)
    {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        TileEntity tileEntity = world.getTileEntity(pos);
        if(!world.isRemote && tileEntity instanceof TileEntityElectricFence)
        {
            ((TileEntityElectricFence) tileEntity).readFromItem(stack);
        }
    }

    @Override
    public void neighborChanged(IBlockState state, World worldIn, BlockPos pos, Block blockIn, BlockPos fromPos)
    {
        super.neighborChanged(state, worldIn, pos, blockIn, fromPos);
        TileEntity tileEntity = worldIn.getTileEntity(pos);
        if(tileEntity instanceof TileEntityElectricFence)
        {
            ((TileEntityElectricFence) tileEntity).onNeighborChanged(fromPos);
        }
    }

    /** 掉落物带上电量、容量（爆炸等在方块实体还在时走这里） */
    @Override
    public void getDrops(NonNullList<ItemStack> drops, IBlockAccess world, BlockPos pos, IBlockState state, int fortune)
    {
        ItemStack stack = new ItemStack(this);
        TileEntity tileEntity = world.getTileEntity(pos);
        if(tileEntity instanceof TileEntityElectricFence)
        {
            ((TileEntityElectricFence) tileEntity).writeToItem(stack);
        }
        drops.add(stack);
    }

    /** 玩家挖掉时方块实体已经先移除了，用传进来的那个 */
    @Override
    public void harvestBlock(World worldIn, EntityPlayer player, BlockPos pos, IBlockState state, @Nullable TileEntity te, ItemStack stack)
    {
        if(te instanceof TileEntityElectricFence)
        {
            player.addStat(StatList.getBlockStats(this));
            player.addExhaustion(0.005F);
            ItemStack drop = new ItemStack(this);
            ((TileEntityElectricFence) te).writeToItem(drop);
            spawnAsEntity(worldIn, pos, drop);
        }
        else
        {
            super.harvestBlock(worldIn, player, pos, state, te, stack);
        }
    }

    @Override
    public void onEntityCollision(World world, BlockPos pos, IBlockState state, Entity entity)
    {
        // 电不电、扣多少电都由服务端决定
        if(world.isRemote || !(entity instanceof EntityLivingBase) || entity.isDead)
            return;
        TileEntity tileEntity = world.getTileEntity(pos);
        if(!(tileEntity instanceof TileEntityElectricFence))
            return;
        TileEntityElectricFence fence = (TileEntityElectricFence) tileEntity;
        if(!fence.canZap())
            return;

        boolean zapped = false;
        if(entity instanceof EntityCreeper)
        {
            EntityCreeper creeper = (EntityCreeper) entity;
            if(!creeper.getPowered())
            {
                EntityLightningBolt lightning = new EntityLightningBolt(world, pos.getX(), pos.getY(), pos.getZ(), false);
                creeper.setFire(1);
                creeper.onStruckByLightning(lightning);
                zapped = true;
            }
        }
        else if(entity instanceof EntityPlayer)
        {
            EntityPlayer player = (EntityPlayer) entity;
            // 伤玩家前按 PvP 规则判断：服务器关了 PvP 或所在领地不允许 PvP 就不伤（悠然服务器魔改）
            if(!player.isCreative() && FencePvp.canHurtPlayer(world, player))
            {
                zapped = entity.attackEntityFrom(ELECTRIC_FENCE, (int) 2.0F);
                if(zapped)
                {
                    Triggers.trigger(Triggers.PLAYER_ZAPPED, player);
                }
            }
        }
        else
        {
            zapped = entity.attackEntityFrom(ELECTRIC_FENCE, (int) 2.0F);
        }

        if(zapped)
        {
            // 只在真的电到时扣电（受伤后的无敌时间里碰到不算）
            fence.useZap();
            world.playSound(null, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, FurnitureSounds.zap, SoundCategory.BLOCKS, 0.2F, 1.0F);
            this.sparkle(world, pos);
        }
    }

    private void sparkle(World worldIn, BlockPos pos)
    {
        if(worldIn instanceof WorldServer)
        {
            double posX = (pos.getX() + RANDOM.nextFloat());
            double posY = (pos.getY() + RANDOM.nextFloat());
            double posZ = (pos.getZ() + RANDOM.nextFloat());
            ((WorldServer) worldIn).spawnParticle(EnumParticleTypes.REDSTONE, posX, posY, posZ, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }
}
