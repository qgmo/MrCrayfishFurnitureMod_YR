package com.mrcrayfish.furniture.power;

import com.mrcrayfish.furniture.gui.inventory.InventoryApplianceCard;
import com.mrcrayfish.furniture.gui.slots.SlotPeripheral;
import com.mrcrayfish.furniture.gui.slots.SlotPowerCard;
import com.mrcrayfish.furniture.handler.ConfigurationHandler;
import moe.qingu.youran.api.peripheral.PeripheralItem;
import moe.qingu.youran.api.peripheral.Peripherals;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Bootstrap;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
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

/**
 * 家具部件（能量单元卡、外设）只许创造模式取放的维度规则（服主 11-22）。
 * 判断是纯函数；界面点击的各条路径用原版的 {@link Container#slotClick}（真的跑原版逻辑）加测试用的玩家、界面验证。
 * 没有真实时间：提示的限流用 tick，由测试给。
 */
class PartRulesTest
{
    static Item cardItem, otherItem, peripheralItem;
    static sun.misc.Unsafe unsafe;

    @BeforeAll
    static void items() throws Exception
    {
        Bootstrap.register();
        // 同 RfToolsCards：注册名 rftools:powercell_card 的物品就是能量单元卡
        cardItem = new Item().setRegistryName("rftools", "powercell_card");
        otherItem = new Item().setRegistryName("cfmtest", "stick");
        peripheralItem = new PeripheralItem(Peripherals.KIND_FLOPPY, 16).setRegistryName("youranmod", "floppy_drive");
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        unsafe = (sun.misc.Unsafe) f.get(null);
    }

    // ---- 测试用的“世界”：玩家在哪个维度、什么模式、现在几 tick，收到了什么 ----

    static final class TestEnv implements PartRules.Env
    {
        int[] dims = {3};
        int dimension = 3;
        boolean creative = false;
        long time = 0;
        int resyncs = 0;
        List<Long> messages = new ArrayList<>();

        @Override public int[] dimensions() { return dims; }
        @Override public int dimension(EntityPlayer player) { return dimension; }
        @Override public boolean creative(EntityPlayer player) { return creative; }
        @Override public boolean isServer(EntityPlayer player) { return true; }
        @Override public long time(EntityPlayer player) { return time; }
        @Override public void resync(EntityPlayer player, Container container) { resyncs++; }
        @Override public void message(EntityPlayer player) { messages.add(time); }
    }

    TestEnv env;
    EntityPlayer player;
    TestContainer container;

    @BeforeEach
    void setUp() throws Exception
    {
        env = new TestEnv();
        PartRules.setEnv(env);
        // 不跑构造函数的玩家：只有背包和 UUID（原版点击逻辑用到的就是这些）
        EntityPlayerMP p = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        Field inv = EntityPlayer.class.getDeclaredField("inventory");
        inv.setAccessible(true);
        inv.set(p, new InventoryPlayer(p));
        Field id = net.minecraft.entity.Entity.class.getDeclaredField("entityUniqueID");
        id.setAccessible(true);
        id.set(p, UUID.randomUUID());
        player = p;
        container = new TestContainer(player.inventory);
    }

    @AfterEach
    void tearDown()
    {
        PartRules.setEnv(null);
    }

    /** 36 个背包格 + 一个能量单元卡槽 + 一个外设槽，和家具界面一样“部件格在最后”；Shift 点击走真实的 {@link ApplianceContainers#transfer} */
    static final class TestContainer extends Container
    {
        static final int CARD = 36, PERIPHERAL = 37;
        final ApplianceEnergy energy = new ApplianceEnergy(new TileEntity() {}, 10, f -> true, () -> {});
        final InventoryBasic peripherals = new InventoryBasic("p", false, 1);
        final EntityPlayer owner;

        TestContainer(InventoryPlayer inventory)
        {
            owner = inventory.player;
            for(int i = 0; i < 36; i++)
                addSlotToContainer(new Slot(inventory, i, 0, 0));
            addSlotToContainer(new SlotPowerCard(new InventoryApplianceCard(energy, () -> {}, p -> true), 0, 0));
            addSlotToContainer(new SlotPeripheral(peripherals, 0, 0, 0, p -> true));
        }

        @Override
        public ItemStack slotClick(int slotId, int dragType, ClickType type, EntityPlayer player)
        {
            return PartRules.slotClick(this, slotId, dragType, type, player, super::slotClick);
        }

        @Override
        public ItemStack transferStackInSlot(EntityPlayer player, int index)
        {
            ItemStack card = ApplianceContainers.transfer(this, player, index, CARD, 0, 36, this::mergeItemStack);
            return card != null ? card : ItemStack.EMPTY;
        }

        @Override
        public boolean canInteractWith(EntityPlayer player)
        {
            return true;
        }
    }

    ItemStack card(int n)
    {
        return new ItemStack(cardItem, n);
    }

    int cards()
    {
        int n = 0;
        for(ItemStack s : player.inventory.mainInventory)
            n += s.getItem() == cardItem ? s.getCount() : 0;
        n += player.inventory.getItemStack().getItem() == cardItem ? player.inventory.getItemStack().getCount() : 0;
        n += container.energy.getCard().getItem() == cardItem ? container.energy.getCard().getCount() : 0;
        return n;
    }

    void survivalInMainCity() { env.creative = false; env.dimension = 3; }

    // ---- 纯判断 ----

    @Test
    void restrictedDimensions()
    {
        assertTrue(PartRules.isRestricted(3, new int[]{3}));
        assertFalse(PartRules.isRestricted(0, new int[]{3}));
        assertFalse(PartRules.isRestricted(-1, new int[]{3}));
        assertTrue(PartRules.isRestricted(0, new int[]{3, 0}));
        assertFalse(PartRules.isRestricted(3, new int[]{}), "空列表 = 不限制");
        assertFalse(PartRules.isRestricted(3, null));
    }

    @Test
    void creativeOnlyInRestrictedDimensions()
    {
        int[] dims = {3};
        assertFalse(PartRules.canChange(false, 3, dims), "主城里生存不行");
        assertTrue(PartRules.canChange(true, 3, dims), "主城里创造可以");
        assertTrue(PartRules.canChange(false, 0, dims), "别的维度不受影响");
        assertTrue(PartRules.canChange(true, 0, dims));
        // 冒险、旁观都不是创造
        assertFalse(PartRules.canChange(false, 3, new int[]{0, 3}));
    }

    /** 配置的默认值是主城（读配置文件那一步需要 Forge 的 Loader，在开发服务端里实测） */
    @Test
    void defaultConfigIsMainCityOnly()
    {
        assertArrayEquals(new int[]{3}, ConfigurationHandler.partsCreativeOnlyDimensions);
    }

    // ---- 提示的限流（时间是 tick，测试里直接给） ----

    @Test
    void throttleAllowsOneNoticePerTwoSeconds()
    {
        PartRules.Throttle t = new PartRules.Throttle(40);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertTrue(t.tryAcquire(a, 100));
        assertFalse(t.tryAcquire(a, 101));
        assertFalse(t.tryAcquire(a, 139), "差一 tick 满 2 秒");
        assertTrue(t.tryAcquire(b, 101), "别的玩家各算各的");
        assertTrue(t.tryAcquire(a, 140), "满 2 秒（40 tick）放行");
        assertFalse(t.tryAcquire(a, 150), "放行后重新计时");
        assertTrue(t.tryAcquire(a, 180));
    }

    @Test
    void throttleLetsTimeGoBackwards()
    {
        PartRules.Throttle t = new PartRules.Throttle(40);
        UUID a = UUID.randomUUID();
        assertTrue(t.tryAcquire(a, 10_000));
        assertTrue(t.tryAcquire(a, 5), "世界时间倒退（换了世界）不能卡死");
        assertFalse(t.tryAcquire(a, 6));
    }

    @Test
    void throttleForgetsOldPlayers()
    {
        PartRules.Throttle t = new PartRules.Throttle(40);
        for(int i = 0; i < 600; i++)
            t.tryAcquire(UUID.randomUUID(), 0);
        assertTrue(t.size() > 512);
        t.tryAcquire(UUID.randomUUID(), 1000);
        assertTrue(t.size() < 10, "过期的记录会清掉，不会越攒越多");
    }

    // ---- judge ----

    @Test
    void judgeDeniesPartSlotsAndShiftIntoThem()
    {
        player.inventory.setInventorySlotContents(0, card(1));
        player.inventory.setInventorySlotContents(1, new ItemStack(otherItem));
        List<Slot> slots = container.inventorySlots;
        for(ClickType type : ClickType.values())
        {
            assertEquals(PartRules.Verdict.DENY, PartRules.judge(type, TestContainer.CARD, slots), "点卡槽：" + type);
            assertEquals(PartRules.Verdict.DENY, PartRules.judge(type, TestContainer.PERIPHERAL, slots), "点外设槽：" + type);
        }
        assertEquals(PartRules.Verdict.DENY, PartRules.judge(ClickType.QUICK_MOVE, 0, slots), "Shift 点背包里的卡，会进空卡槽");
        assertEquals(PartRules.Verdict.PASS, PartRules.judge(ClickType.PICKUP, 0, slots), "普通拿起背包里的卡没事");
        assertEquals(PartRules.Verdict.PASS, PartRules.judge(ClickType.QUICK_MOVE, 1, slots), "Shift 点别的东西没事");
        assertEquals(PartRules.Verdict.PASS, PartRules.judge(ClickType.PICKUP, -999, slots), "点界面外面（丢东西）没事");
        container.energy.setCard(card(1));
        assertEquals(PartRules.Verdict.PASS, PartRules.judge(ClickType.QUICK_MOVE, 0, slots), "卡槽满了，Shift 点卡不会进去（原版处理）");
    }

    // ---- 各条界面点击路径（受限玩家：主城、生存） ----

    @Test
    void pickupCannotPutOrTakeCard()
    {
        survivalInMainCity();
        player.inventory.setItemStack(card(1));
        container.slotClick(TestContainer.CARD, 0, ClickType.PICKUP, player);
        assertTrue(container.energy.getCard().isEmpty(), "鼠标上的卡放不进去");
        assertEquals(1, player.inventory.getItemStack().getCount(), "还在光标上");
        assertEquals(1, env.resyncs, "拒绝后重新同步");
        assertEquals(1, env.messages.size());

        player.inventory.setItemStack(ItemStack.EMPTY);
        container.energy.setCard(card(1));
        container.slotClick(TestContainer.CARD, 0, ClickType.PICKUP, player);
        container.slotClick(TestContainer.CARD, 1, ClickType.PICKUP, player);
        assertEquals(1, container.energy.getCard().getCount(), "卡拿不出来");
        assertTrue(player.inventory.getItemStack().isEmpty());
        assertEquals(1, cards());
    }

    @Test
    void shiftClickCannotMoveCardEitherWay()
    {
        survivalInMainCity();
        player.inventory.setInventorySlotContents(5, card(1));
        container.slotClick(5, 0, ClickType.QUICK_MOVE, player);
        assertTrue(container.energy.getCard().isEmpty(), "Shift 点放不进卡槽");
        assertEquals(1, player.inventory.getStackInSlot(5).getCount());

        player.inventory.setInventorySlotContents(5, ItemStack.EMPTY);
        container.energy.setCard(card(1));
        container.slotClick(TestContainer.CARD, 0, ClickType.QUICK_MOVE, player);
        assertEquals(1, container.energy.getCard().getCount(), "Shift 点拿不出来");
        assertEquals(1, cards());
        assertEquals(2, env.resyncs);
    }

    @Test
    void shiftClickIsAlsoRefusedByTransferItself()
    {
        survivalInMainCity();
        player.inventory.setInventorySlotContents(5, card(1));
        assertTrue(container.transferStackInSlot(player, 5).isEmpty());
        assertTrue(container.energy.getCard().isEmpty(), "不经过 slotClick 直接调转移也放不进");
        container.energy.setCard(card(1));
        player.inventory.setInventorySlotContents(5, ItemStack.EMPTY);
        assertTrue(container.transferStackInSlot(player, TestContainer.CARD).isEmpty());
        assertEquals(1, container.energy.getCard().getCount(), "也拿不出");
        assertEquals(1, cards());
    }

    @Test
    void numberKeySwapCannotPutOrTake()
    {
        survivalInMainCity();
        // 数字键 1：热键栏第 0 格
        player.inventory.setInventorySlotContents(0, card(1));
        container.slotClick(TestContainer.CARD, 0, ClickType.SWAP, player);
        assertTrue(container.energy.getCard().isEmpty(), "数字键放不进");
        assertEquals(1, player.inventory.getStackInSlot(0).getCount());

        player.inventory.setInventorySlotContents(0, ItemStack.EMPTY);
        container.energy.setCard(card(1));
        container.slotClick(TestContainer.CARD, 0, ClickType.SWAP, player);
        assertEquals(1, container.energy.getCard().getCount(), "数字键拿不出");
        assertTrue(player.inventory.getStackInSlot(0).isEmpty());

        // 两边都有东西：互换也不行
        player.inventory.setInventorySlotContents(0, card(1));
        container.slotClick(TestContainer.CARD, 0, ClickType.SWAP, player);
        assertEquals(2, cards());
        assertEquals(1, container.energy.getCard().getCount());
        assertEquals(1, player.inventory.getStackInSlot(0).getCount());
    }

    @Test
    void doubleClickCollectDoesNotTakeCardFromSlot()
    {
        survivalInMainCity();
        container.energy.setCard(card(1));
        player.inventory.setItemStack(card(1));
        // 双击背包里一个空格：把界面里所有同样的东西收到光标上
        container.slotClick(3, 0, ClickType.PICKUP_ALL, player);
        assertEquals(1, container.energy.getCard().getCount(), "卡槽里的卡没被收走");
        assertEquals(1, player.inventory.getItemStack().getCount());
        assertEquals(2, cards());
        // 从后往前收（右键双击）同理
        container.slotClick(3, 1, ClickType.PICKUP_ALL, player);
        assertEquals(1, container.energy.getCard().getCount());
    }

    @Test
    void dragCannotDistributeIntoCardSlot()
    {
        survivalInMainCity();
        player.inventory.setItemStack(card(4));
        container.slotClick(-999, Container.getQuickcraftMask(0, 0), ClickType.QUICK_CRAFT, player);
        container.slotClick(TestContainer.CARD, Container.getQuickcraftMask(1, 0), ClickType.QUICK_CRAFT, player);
        container.slotClick(2, Container.getQuickcraftMask(1, 0), ClickType.QUICK_CRAFT, player);
        container.slotClick(3, Container.getQuickcraftMask(1, 0), ClickType.QUICK_CRAFT, player);
        container.slotClick(-999, Container.getQuickcraftMask(2, 0), ClickType.QUICK_CRAFT, player);
        assertTrue(container.energy.getCard().isEmpty(), "拖动分发进不了卡槽");
        assertEquals(4, cards(), "一张都没丢、没多");
        assertEquals(1, env.resyncs, "拖完把界面同步一次，客户端自己分进卡槽的显示会被纠正");
    }

    @Test
    void dragWithoutPartSlotsStillWorks()
    {
        survivalInMainCity();
        player.inventory.setItemStack(new ItemStack(otherItem, 4));
        container.slotClick(-999, Container.getQuickcraftMask(0, 0), ClickType.QUICK_CRAFT, player);
        container.slotClick(2, Container.getQuickcraftMask(1, 0), ClickType.QUICK_CRAFT, player);
        container.slotClick(3, Container.getQuickcraftMask(1, 0), ClickType.QUICK_CRAFT, player);
        container.slotClick(-999, Container.getQuickcraftMask(2, 0), ClickType.QUICK_CRAFT, player);
        assertEquals(2, player.inventory.getStackInSlot(2).getCount());
        assertEquals(2, player.inventory.getStackInSlot(3).getCount());
        assertTrue(player.inventory.getItemStack().isEmpty());
        assertTrue(env.messages.isEmpty(), "平常的操作不提示");
    }

    @Test
    void peripheralSlotIsLockedToo()
    {
        survivalInMainCity();
        ItemStack disk = new ItemStack(peripheralItem);
        player.inventory.setItemStack(disk.copy());
        container.slotClick(TestContainer.PERIPHERAL, 0, ClickType.PICKUP, player);
        assertTrue(container.peripherals.getStackInSlot(0).isEmpty());
        assertFalse(player.inventory.getItemStack().isEmpty());

        player.inventory.setItemStack(ItemStack.EMPTY);
        container.peripherals.setInventorySlotContents(0, disk.copy());
        container.slotClick(TestContainer.PERIPHERAL, 0, ClickType.PICKUP, player);
        container.slotClick(TestContainer.PERIPHERAL, 0, ClickType.QUICK_MOVE, player);
        container.slotClick(TestContainer.PERIPHERAL, 0, ClickType.SWAP, player);
        assertFalse(container.peripherals.getStackInSlot(0).isEmpty(), "外设拿不出来");
        assertTrue(player.inventory.getItemStack().isEmpty());

        // Shift 点背包里的外设：进不了（槽里已有就不会进，先清空再试）
        container.peripherals.setInventorySlotContents(0, ItemStack.EMPTY);
        player.inventory.setInventorySlotContents(4, disk.copy());
        container.slotClick(4, 0, ClickType.QUICK_MOVE, player);
        assertTrue(container.peripherals.getStackInSlot(0).isEmpty());
        assertFalse(player.inventory.getStackInSlot(4).isEmpty());
    }

    @Test
    void dropAndCloneNotPossibleOnPartSlot()
    {
        survivalInMainCity();
        container.energy.setCard(card(1));
        container.slotClick(TestContainer.CARD, 0, ClickType.THROW, player);
        container.slotClick(TestContainer.CARD, 1, ClickType.THROW, player);
        container.slotClick(TestContainer.CARD, 0, ClickType.CLONE, player);
        assertEquals(1, container.energy.getCard().getCount(), "Q / Ctrl+Q 丢不出，也复制不了");
        assertTrue(player.inventory.getItemStack().isEmpty());
        assertEquals(1, cards());
    }

    @Test
    void ordinaryInventoryClicksAreUntouched()
    {
        survivalInMainCity();
        player.inventory.setInventorySlotContents(0, new ItemStack(otherItem, 3));
        container.slotClick(0, 0, ClickType.PICKUP, player);
        assertEquals(3, player.inventory.getItemStack().getCount());
        container.slotClick(7, 0, ClickType.PICKUP, player);
        assertEquals(3, player.inventory.getStackInSlot(7).getCount());
        container.slotClick(7, 0, ClickType.QUICK_MOVE, player);
        assertEquals(0, env.resyncs);
        assertTrue(env.messages.isEmpty());
    }

    // ---- 允许的情况 ----

    @Test
    void creativePlayerCanPutAndTakeInMainCity()
    {
        env.creative = true;
        player.inventory.setItemStack(card(1));
        container.slotClick(TestContainer.CARD, 0, ClickType.PICKUP, player);
        assertEquals(1, container.energy.getCard().getCount());
        container.slotClick(TestContainer.CARD, 0, ClickType.PICKUP, player);
        assertTrue(container.energy.getCard().isEmpty());
        assertEquals(1, player.inventory.getItemStack().getCount());
        player.inventory.setItemStack(ItemStack.EMPTY);
        player.inventory.setInventorySlotContents(5, card(1));
        container.slotClick(5, 0, ClickType.QUICK_MOVE, player);
        assertEquals(1, container.energy.getCard().getCount(), "创造 Shift 点放得进");
        container.slotClick(TestContainer.CARD, 0, ClickType.QUICK_MOVE, player);
        assertTrue(container.energy.getCard().isEmpty(), "Shift 点拿得出");
        assertEquals(1, cards());
        player.inventory.setInventorySlotContents(0, card(1));
        container.slotClick(TestContainer.CARD, 0, ClickType.SWAP, player);
        assertEquals(1, container.energy.getCard().getCount(), "数字键放得进");
        assertEquals(0, env.resyncs);
        assertTrue(env.messages.isEmpty());
    }

    @Test
    void survivalPlayerOutsideRestrictedDimensionsIsUnaffected()
    {
        env.creative = false;
        env.dimension = 0;
        player.inventory.setItemStack(card(1));
        container.slotClick(TestContainer.CARD, 0, ClickType.PICKUP, player);
        assertEquals(1, container.energy.getCard().getCount());
        container.slotClick(TestContainer.CARD, 0, ClickType.QUICK_MOVE, player);
        assertTrue(container.energy.getCard().isEmpty());
        assertEquals(1, cards());
        ItemStack disk = new ItemStack(peripheralItem);
        player.inventory.setItemStack(disk);
        container.slotClick(TestContainer.PERIPHERAL, 0, ClickType.PICKUP, player);
        assertFalse(container.peripherals.getStackInSlot(0).isEmpty());
        assertEquals(0, env.resyncs);
    }

    @Test
    void emptyConfigTurnsTheRuleOff()
    {
        env.dims = new int[0];
        player.inventory.setItemStack(card(1));
        container.slotClick(TestContainer.CARD, 0, ClickType.PICKUP, player);
        assertEquals(1, container.energy.getCard().getCount());
    }

    @Test
    void slotsAloneRefuseTakingForRestrictedPlayers()
    {
        survivalInMainCity();
        container.energy.setCard(card(1));
        Slot s = container.getSlot(TestContainer.CARD);
        assertFalse(s.canTakeStack(player));
        env.creative = true;
        assertTrue(s.canTakeStack(player));
        env.creative = false;
        env.dimension = 0;
        assertTrue(s.canTakeStack(player));
    }

    // ---- 提示 ----

    @Test
    void noticeIsLimitedToOnePerTwoSeconds()
    {
        survivalInMainCity();
        player.inventory.setItemStack(card(1));
        env.time = 1000;
        for(int i = 0; i < 5; i++)
            container.slotClick(TestContainer.CARD, 0, ClickType.PICKUP, player);
        assertEquals(1, env.messages.size(), "连点只提示一次");
        assertEquals(5, env.resyncs, "但每次拒绝都要重新同步");
        env.time = 1039;
        container.slotClick(TestContainer.CARD, 0, ClickType.PICKUP, player);
        assertEquals(1, env.messages.size());
        env.time = 1040;
        container.slotClick(TestContainer.CARD, 0, ClickType.PICKUP, player);
        assertEquals(2, env.messages.size());
        assertEquals(1, cards());
    }

    @Test
    void noticeTextIsPlainChineseSoOldClientsShowIt()
    {
        assertEquals("主城的公共家具，部件只有创造模式能取放", PartRules.NOTICE);
    }
}
