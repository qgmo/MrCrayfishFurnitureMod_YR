package com.mrcrayfish.furniture.handler;

import com.mrcrayfish.furniture.compat.RegionUse;
import com.mrcrayfish.furniture.compat.RfToolsCards;
import com.mrcrayfish.furniture.init.FurnitureSounds;
import com.mrcrayfish.furniture.power.ApplianceEnergy;
import com.mrcrayfish.furniture.power.IAppliance;
import com.mrcrayfish.furniture.tileentity.TileEntityCeilingFan;
import com.mrcrayfish.furniture.tileentity.TileEntityToaster;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import com.mrcrayfish.furniture.blocks.BlockBath;
import com.mrcrayfish.furniture.blocks.BlockBlender;
import com.mrcrayfish.furniture.blocks.BlockCeilingLight;
import com.mrcrayfish.furniture.blocks.BlockCup;
import com.mrcrayfish.furniture.blocks.BlockEsky;
import com.mrcrayfish.furniture.tileentity.TileEntityBlender;
import com.mrcrayfish.furniture.tileentity.TileEntityCup;
import com.mrcrayfish.furniture.blocks.BlockToilet;
import com.mrcrayfish.furniture.init.FurnitureBlocks;
import com.mrcrayfish.furniture.tileentity.TileEntityBath;
import com.mrcrayfish.furniture.water.WaterBasins;

/**
 * 家具的“潜行 + 左键”操作（悠然服务器魔改）。潜行 + 空手右键留给 Carry On 搬方块，家具的新操作一律避开它：
 * <ul>
 * <li>没有界面的电器（吊扇、烤面包机）：拿能量单元卡潜行左键放入（已有卡时交换），空手潜行左键取出；
 * 电视的卡改为插在频道设置界面里、电脑的卡放在它界面的卡槽里（服主 11-14），旧存档插着的卡也在界面里取，这里都不管；</li>
 * <li>烤面包机：里面有面包片、没在烤时，空手潜行左键开始烤（原来是潜行 + 空手右键）；</li>
 * <li>马桶：潜行左键冲水（原来是潜行右键，服主 11-14 改）；</li>
 * <li>浴缸：潜行左键开 / 关放水（空手右键是坐下）；</li>
 * <li>冰桶开盖 / 合盖、吸顶灯切换模式、喝放在地上的杯子、搅拌机开始搅拌：空手潜行左键（原来都是潜行 + 空手右键，服主 11-14 改）；</li>
 * <li>别的方块、或没有可做的操作时照常挖掘。</li>
 * </ul>
 * 做了操作就取消这次左键，方块不会被挖坏（两端都取消，客户端不会先把方块挖掉）。按住左键时客户端每 tick 都会再发一次，
 * 服务端只认间隔超过 {@value #REPEAT_TICKS} tick 的那一次（按住不会反复插拔）。
 * 使用权照 {@link RegionUse}（出生点保护、冒险模式、Universe Guard 的禁用方块）。
 */
public class SneakLeftClick
{
    static final int REPEAT_TICKS = 8;

    /** 只在服务端用：每个玩家上一次潜行左键家具的时间 */
    private static final Map<UUID, Long> LAST = new HashMap<>();

    /** 一次潜行左键要做的事 */
    public interface Action
    {
        /** 只在服务端、确认是新的一次点击、玩家有使用权时调 */
        void run(World world, BlockPos pos, EntityPlayer player);
    }

    /** 各种家具自己的潜行左键（水的那几样在别处注册） */
    public interface Handler
    {
        /** 返回 null 表示这次左键不归它管（照常挖掘）；两端都会调，判断要一致 */
        Action find(World world, BlockPos pos, TileEntity tileEntity, EntityPlayer player, ItemStack held);
    }

    private static final java.util.List<Handler> HANDLERS = new java.util.ArrayList<>();

    static
    {
        HANDLERS.add(SneakLeftClick::toilet);
        HANDLERS.add(SneakLeftClick::bath);
        HANDLERS.add(SneakLeftClick::toaster);
        HANDLERS.add(SneakLeftClick::esky);
        HANDLERS.add(SneakLeftClick::ceilingLight);
        HANDLERS.add(SneakLeftClick::cup);
        HANDLERS.add(SneakLeftClick::blender);
        HANDLERS.add(SneakLeftClick::card);
    }

    public static void register(Handler handler)
    {
        HANDLERS.add(0, handler);
    }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event)
    {
        EntityPlayer player = event.getEntityPlayer();
        if(player == null || !player.isSneaking() || event.getHand() != EnumHand.MAIN_HAND)
            return;
        World world = event.getWorld();
        BlockPos pos = event.getPos();
        if(!world.isBlockLoaded(pos))
            return;
        TileEntity tileEntity = world.getTileEntity(pos);
        ItemStack held = player.getHeldItemMainhand();
        Action action = null;
        for(Handler handler : HANDLERS)
        {
            action = handler.find(world, pos, tileEntity, player, held);
            if(action != null)
                break;
        }
        if(action == null)
            return;
        event.setCanceled(true);
        if(world.isRemote || !fresh(world, player))
            return;
        if(!RegionUse.canUse(world, player, pos))
            return;
        action.run(world, pos, player);
    }

    private static boolean fresh(World world, EntityPlayer player)
    {
        long now = world.getTotalWorldTime();
        Long last = LAST.put(player.getUniqueID(), now);
        if(LAST.size() > 1000)
            LAST.values().removeIf(t -> now - t > REPEAT_TICKS);
        return last == null || now - last > REPEAT_TICKS || now < last;
    }

    /** 没有界面的电器：插拔能量单元卡（电视、电脑不在其中，它们的卡槽在界面里） */
    private static Action card(World world, BlockPos pos, TileEntity tileEntity, EntityPlayer player, ItemStack held)
    {
        if(!(tileEntity instanceof TileEntityCeilingFan || tileEntity instanceof TileEntityToaster))
            return null;
        ApplianceEnergy energy = ((IAppliance) tileEntity).getAppliance();
        if(RfToolsCards.isCard(held))
        {
            return (w, p, pl) ->
            {
                ItemStack hand = pl.getHeldItemMainhand();
                if(!RfToolsCards.isCard(hand))
                    return;
                ItemStack one = hand.splitStack(1);
                ItemStack old = energy.setCard(one);
                syncAppliance(tileEntity);
                if(!old.isEmpty())
                {
                    if(hand.isEmpty())
                        pl.setHeldItem(EnumHand.MAIN_HAND, old);
                    else
                        ItemHandlerHelper.giveItemToPlayer(pl, old);
                }
                pl.sendStatusMessage(new TextComponentTranslation("cfm.message.card_inserted"), true);
            };
        }
        if(held.isEmpty() && energy.hasCard())
        {
            return (w, p, pl) ->
            {
                if(!energy.hasCard())
                    return;
                ItemStack old = energy.setCard(ItemStack.EMPTY);
                syncAppliance(tileEntity);
                ItemHandlerHelper.giveItemToPlayer(pl, old);
                pl.sendStatusMessage(new TextComponentTranslation("cfm.message.card_removed"), true);
            };
        }
        return null;
    }

    /** 马桶：潜行左键冲水 */
    private static Action toilet(World world, BlockPos pos, TileEntity tileEntity, EntityPlayer player, ItemStack held)
    {
        if(world.getBlockState(pos).getBlock() != FurnitureBlocks.TOILET)
            return null;
        return BlockToilet::flush;
    }

    /** 浴缸：潜行左键开 / 关 */
    private static Action bath(World world, BlockPos pos, TileEntity tileEntity, EntityPlayer player, ItemStack held)
    {
        if(!(tileEntity instanceof TileEntityBath))
            return null;
        return (w, p, pl) ->
        {
            TileEntityBath master = ((TileEntityBath) tileEntity).getMaster();
            if(master != null)
            {
                WaterBasins.toggle(w, p, pl, master.getTank(), "cfm.message.bath", BlockBath.sources(master));
            }
        };
    }

    /** 烤面包机：空手潜行左键开始烤（有面包片、没在烤时） */
    private static Action toaster(World world, BlockPos pos, TileEntity tileEntity, EntityPlayer player, ItemStack held)
    {
        if(!(tileEntity instanceof TileEntityToaster) || !held.isEmpty())
            return null;
        TileEntityToaster toaster = (TileEntityToaster) tileEntity;
        if(toaster.isToasting() || !toaster.hasSlices())
            return null;
        return (w, p, pl) ->
        {
            if(toaster.isToasting() || !toaster.hasSlices())
                return;
            toaster.startToasting();
            w.updateComparatorOutputLevel(p, w.getBlockState(p).getBlock());
            w.playSound(null, p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D, FurnitureSounds.toaster_down, SoundCategory.BLOCKS, 0.75F, 1.0F);
        };
    }

    /** 冰桶：空手潜行左键开盖 / 合盖 */
    private static Action esky(World world, BlockPos pos, TileEntity tileEntity, EntityPlayer player, ItemStack held)
    {
        if(!held.isEmpty() || !(world.getBlockState(pos).getBlock() instanceof BlockEsky))
            return null;
        return BlockEsky::toggleOpen;
    }

    /** 吸顶灯：空手潜行左键切换“右键开关 / 红石控制” */
    private static Action ceilingLight(World world, BlockPos pos, TileEntity tileEntity, EntityPlayer player, ItemStack held)
    {
        if(!held.isEmpty() || !(world.getBlockState(pos).getBlock() instanceof BlockCeilingLight))
            return null;
        return BlockCeilingLight::toggleMode;
    }

    /** 放在地上的杯子：饿了、杯里有饮料时空手潜行左键喝掉 */
    private static Action cup(World world, BlockPos pos, TileEntity tileEntity, EntityPlayer player, ItemStack held)
    {
        if(!held.isEmpty() || !(tileEntity instanceof TileEntityCup) || !BlockCup.canDrink(player, (TileEntityCup) tileEntity))
            return null;
        return BlockCup::drink;
    }

    /** 搅拌机：原料能做成饮料时空手潜行左键开始搅拌 */
    private static Action blender(World world, BlockPos pos, TileEntity tileEntity, EntityPlayer player, ItemStack held)
    {
        if(!held.isEmpty() || !(tileEntity instanceof TileEntityBlender) || !BlockBlender.canStart((TileEntityBlender) tileEntity))
            return null;
        return BlockBlender::start;
    }

    private static void syncAppliance(TileEntity tileEntity)
    {
        tileEntity.markDirty();
        World world = tileEntity.getWorld();
        if(world != null)
        {
            world.notifyBlockUpdate(tileEntity.getPos(), world.getBlockState(tileEntity.getPos()), world.getBlockState(tileEntity.getPos()), 3);
        }
    }
}
