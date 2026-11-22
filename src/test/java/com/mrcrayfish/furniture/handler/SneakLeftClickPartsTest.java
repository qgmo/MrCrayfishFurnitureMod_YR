package com.mrcrayfish.furniture.handler;

import com.mrcrayfish.furniture.power.PartRules;
import com.mrcrayfish.furniture.tileentity.TileEntityCeilingFan;
import com.mrcrayfish.furniture.tileentity.TileEntityToaster;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Bootstrap;
import net.minecraft.inventory.Container;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 潜行左键插拔能量单元卡（吊扇、烤面包机）在只许创造模式改部件的维度里：不动卡，只提示（服主 11-22） */
class SneakLeftClickPartsTest
{
    static Item cardItem;
    static sun.misc.Unsafe unsafe;

    @BeforeAll
    static void init() throws Exception
    {
        Bootstrap.register();
        cardItem = new Item().setRegistryName("rftools", "powercell_card");
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        unsafe = (sun.misc.Unsafe) f.get(null);
    }

    static final class Env implements PartRules.Env
    {
        boolean creative;
        int dimension = 3;
        long time;
        List<Long> messages = new ArrayList<>();

        @Override public int[] dimensions() { return new int[]{3}; }
        @Override public int dimension(EntityPlayer player) { return dimension; }
        @Override public boolean creative(EntityPlayer player) { return creative; }
        @Override public boolean isServer(EntityPlayer player) { return true; }
        @Override public long time(EntityPlayer player) { return time; }
        @Override public void resync(EntityPlayer player, Container container) {}
        @Override public void message(EntityPlayer player) { messages.add(time); }
    }

    Env env;
    EntityPlayer player;

    @BeforeEach
    void setUp() throws Exception
    {
        env = new Env();
        PartRules.setEnv(env);
        EntityPlayerMP p = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        Field inv = EntityPlayer.class.getDeclaredField("inventory");
        inv.setAccessible(true);
        inv.set(p, new InventoryPlayer(p));
        Field id = Entity.class.getDeclaredField("entityUniqueID");
        id.setAccessible(true);
        id.set(p, UUID.randomUUID());
        player = p;
    }

    @AfterEach
    void tearDown()
    {
        PartRules.setEnv(null);
    }

    @Test
    void survivalCannotInsertCardIntoCeilingFan()
    {
        TileEntityCeilingFan fan = new TileEntityCeilingFan();
        ItemStack held = new ItemStack(cardItem);
        player.inventory.setInventorySlotContents(0, held);
        SneakLeftClick.Action action = SneakLeftClick.card(null, null, fan, player, held);
        assertNotNull(action, "仍要接住这次左键（不然会去挖方块）");
        action.run(null, null, player);
        assertFalse(fan.getAppliance().hasCard(), "卡没插进去");
        assertEquals(1, player.inventory.getStackInSlot(0).getCount(), "手上的卡还在");
        assertEquals(1, env.messages.size());
    }

    @Test
    void survivalCannotTakeCardOutOfToaster()
    {
        TileEntityToaster toaster = new TileEntityToaster();
        toaster.getAppliance().setCard(new ItemStack(cardItem));
        SneakLeftClick.Action action = SneakLeftClick.card(null, null, toaster, player, ItemStack.EMPTY);
        assertNotNull(action);
        action.run(null, null, player);
        assertTrue(toaster.getAppliance().hasCard(), "卡拔不出来");
        assertEquals(1, env.messages.size());
    }

    @Test
    void nothingToDoStillMeansNormalDigging()
    {
        TileEntityCeilingFan fan = new TileEntityCeilingFan();
        assertNull(SneakLeftClick.card(null, null, fan, player, ItemStack.EMPTY), "没卡可拔、手上也没卡：不归它管，照常挖");
        assertNull(SneakLeftClick.card(null, null, fan, player, new ItemStack(net.minecraft.init.Items.STICK)));
    }

    @Test
    void creativeAndOtherDimensionsKeepWorkingAsBefore()
    {
        TileEntityCeilingFan fan = new TileEntityCeilingFan();
        ItemStack held = new ItemStack(cardItem);
        env.creative = true;
        assertNotNull(SneakLeftClick.card(null, null, fan, player, held));
        env.creative = false;
        env.dimension = 0;
        assertNotNull(SneakLeftClick.card(null, null, fan, player, held));
        assertTrue(env.messages.isEmpty());
        // 能进到原来的插卡动作：用的是 sendStatusMessage，这里没有网络连接，不跑；动作本身的行为由以前的测试与实测覆盖
    }

    @Test
    void noticeLimitedToOnePerTwoSeconds()
    {
        TileEntityCeilingFan fan = new TileEntityCeilingFan();
        ItemStack held = new ItemStack(cardItem);
        for(int t = 0; t < 40; t += 8)
        {
            env.time = 500 + t;
            SneakLeftClick.card(null, null, fan, player, held).run(null, null, player);
        }
        assertEquals(1, env.messages.size(), "按住左键每 8 tick 一次，2 秒内只提示一次");
        env.time = 540;
        SneakLeftClick.card(null, null, fan, player, held).run(null, null, player);
        assertEquals(2, env.messages.size());
    }
}
