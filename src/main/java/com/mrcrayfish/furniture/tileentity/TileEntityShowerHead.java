package com.mrcrayfish.furniture.tileentity;

import com.mrcrayfish.furniture.blocks.BlockFurniture;
import com.mrcrayfish.furniture.compat.WaterFluids;
import com.mrcrayfish.furniture.init.FurnitureSounds;
import com.mrcrayfish.furniture.util.ParticleSpawner;
import com.mrcrayfish.furniture.util.WaterRules;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemArmor.ArmorMaterial;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;

import java.util.List;
import java.util.Random;

/**
 * 开着的花洒（悠然服务器魔改，服主 11-14）：贴着的那个方块（花洒装在它上面，后面可以接管道）是装着认得的水的 Forge 流体容器
 * 时才出水，开着时每 tick 从里面抽 {@value WaterRules#SHOWER_PER_TICK} mB。没水时不出水（没有水花、声音和洗澡的效果），开关不变。
 * <p>
 * 出水时每 5 tick 对花洒下方一格里的所有生物（{@link EntityLivingBase}，服主 11-14：原来只对玩家）清药水效果（同喝牛奶）、灭火。
 */
public class TileEntityShowerHead extends TileEntitySyncClient implements ITickable
{
    private Random rand = new Random();
    private int timer = 20;
    /** 这一 tick 有没有出水（同步给客户端画水花） */
    private boolean flowing;

    public boolean isFlowing()
    {
        return flowing;
    }

    private boolean drawWater()
    {
        IBlockState state = world.getBlockState(pos);
        if(!state.getPropertyKeys().contains(BlockFurniture.FACING))
            return false;
        EnumFacing facing = state.getValue(BlockFurniture.FACING);
        BlockPos attached = pos.offset(facing);
        if(!world.isBlockLoaded(attached))
            return false;
        IFluidHandler handler = FluidUtil.getFluidHandler(world, attached, facing.getOpposite());
        return handler != null && WaterFluids.drainExactly(handler, null, WaterRules.SHOWER_PER_TICK) != null;
    }

    @Override
    public void update()
    {
        if(this.world.isRemote)
        {
            if(flowing)
            {
                double posX = pos.getX() + 0.35D + (rand.nextDouble() / 3);
                double posZ = pos.getZ() + 0.35D + (rand.nextDouble() / 3);
                ParticleSpawner.spawnParticle("shower", posX, pos.getY() + 0.065D, posZ);
            }
            return;
        }

        boolean now = this.drawWater();
        if(now != flowing)
        {
            this.flowing = now;
            this.markDirty();
            this.sync();
        }
        if(!flowing)
            return;

        if(timer % 5 == 0)
        {
            // 花洒下方的所有生物（悠然服务器魔改：原来只管玩家）：清掉药水效果（同喝牛奶，牛奶清不掉的效果照样清不掉）、灭火；
            // 洗掉皮革盔甲的颜色只对玩家
            List<EntityLivingBase> entities = world.getEntitiesWithinAABB(EntityLivingBase.class, new AxisAlignedBB(pos.getX(), pos.getY() - 1, pos.getZ(), pos.getX() + 1.0D, pos.getY() - 1 + 1.0D, pos.getZ() + 1.0D));
            for(EntityLivingBase entity : entities)
            {
                entity.curePotionEffects(new ItemStack(Items.MILK_BUCKET));

                if(entity instanceof EntityPlayer)
                {
                    EntityPlayer player = (EntityPlayer) entity;
                    for(int i = 0; i < 4; i++)
                    {
                        ItemStack itemstack = player.inventory.armorInventory.get(i);
                        if(itemstack != ItemStack.EMPTY)
                        {
                            if(itemstack.getItem() instanceof ItemArmor)
                            {
                                ItemArmor armour = (ItemArmor) itemstack.getItem();
                                if(armour.getArmorMaterial() == ArmorMaterial.LEATHER)
                                {
                                    player.inventory.armorInventory.set(i, new ItemStack(itemstack.getItem(), 1, itemstack.getItemDamage()));
                                }
                            }
                        }
                    }
                }

                if(entity.isBurning())
                {
                    entity.extinguish();
                }
            }
        }

        if(timer >= 20)
        {
            world.playSound(null, pos, FurnitureSounds.shower, SoundCategory.BLOCKS, 0.75F, 1.0F);
            timer = 0;
        }
        timer++;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound)
    {
        super.readFromNBT(compound);
        this.flowing = compound.getBoolean("Flowing");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound)
    {
        super.writeToNBT(compound);
        compound.setBoolean("Flowing", flowing);
        return compound;
    }
}
