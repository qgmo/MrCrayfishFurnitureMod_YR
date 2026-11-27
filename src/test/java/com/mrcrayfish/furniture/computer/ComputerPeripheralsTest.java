package com.mrcrayfish.furniture.computer;

import moe.qingu.youran.api.peripheral.PeripheralItem;
import moe.qingu.youran.api.peripheral.Peripherals;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 电脑的外设槽：什么外设都能插（服主 11-16），只有定位模块、身份模块起作用（能力按外设报的能力算，服主 11-27 起两格）、存档 */
class ComputerPeripheralsTest
{
    static Item locator;
    static Item identity;
    static Item other;
    static Item hanzi;

    @BeforeAll
    static void items()
    {
        Bootstrap.register();
        // 外设接口（YouranMod api.peripheral）：定位模块是种类 locator、能力 location 的外设；软驱、汉卡是别的外设，插得上但不起作用
        locator = new PeripheralItem(Peripherals.KIND_LOCATOR, 16, Peripherals.CAP_LOCATION).setRegistryName("youranmod", "locator_module");
        identity = new PeripheralItem(Peripherals.KIND_IDENTITY, 16, Peripherals.CAP_IDENTITY).setRegistryName("youranmod", "identity_module");
        other = new PeripheralItem(Peripherals.KIND_FLOPPY, 16).setRegistryName("youranmod", "floppy_drive");
        hanzi = new PeripheralItem(Peripherals.KIND_HANZI, 16).setRegistryName("youranmod", "hanzi_card");
    }

    @Test
    void anyPeripheralButOnlyLocatorWorks()
    {
        AtomicInteger changes = new AtomicInteger();
        ComputerPeripherals p = new ComputerPeripherals(changes::incrementAndGet);
        assertTrue(ComputerPeripherals.accepts(0, new ItemStack(locator)));
        assertTrue(ComputerPeripherals.accepts(0, new ItemStack(other)), "软驱插得上");
        assertTrue(ComputerPeripherals.accepts(0, new ItemStack(hanzi)), "汉卡插得上");
        assertFalse(ComputerPeripherals.accepts(0, new ItemStack(Item.getItemFromBlock(net.minecraft.init.Blocks.STONE))), "不是外设的不收");
        assertFalse(ComputerPeripherals.accepts(0, ItemStack.EMPTY));
        assertTrue(ComputerPeripherals.accepts(1, new ItemStack(locator)), "两格（服主 11-27：身份模块和定位模块并列）");
        assertFalse(ComputerPeripherals.accepts(2, new ItemStack(locator)), "只有两格");
        assertFalse(p.hasLocator());
        assertFalse(p.hasIdentity());
        p.set(0, new ItemStack(locator));
        assertTrue(p.hasLocator());
        assertFalse(p.hasIdentity());
        assertEquals(1, changes.get());
        ItemStack old = p.set(0, ItemStack.EMPTY);
        assertEquals(locator, old.getItem());
        assertFalse(p.hasLocator(), "拿出来就没有");
        // 插着软驱、汉卡：没有任何能力
        p.set(0, new ItemStack(other));
        assertFalse(p.hasLocator() || p.hasIdentity(), "软驱插着不起作用");
        assertFalse(ComputerPeripherals.isUseful(new ItemStack(other)));
        assertEquals(1, p.installed().size());
        p.set(0, new ItemStack(hanzi));
        assertFalse(p.hasLocator() || p.hasIdentity(), "汉卡插着不起作用");
        assertTrue(ComputerPeripherals.isUseful(new ItemStack(locator)));
        assertTrue(ComputerPeripherals.isUseful(new ItemStack(identity)));
    }

    @Test
    void identityAndLocatorSideBySide()
    {
        ComputerPeripherals p = new ComputerPeripherals(() -> {});
        // 没装身份模块的电脑签不了操作者证明（浏览器告诉网站 error:no_identity）
        assertEquals("no_identity", ComputerProofs.missingModule(p, ComputerProofs.OPERATOR));
        assertEquals("no_locator", ComputerProofs.missingModule(p, ComputerProofs.LOCATION));
        p.set(1, new ItemStack(identity));
        assertTrue(p.hasIdentity());
        assertNull(ComputerProofs.missingModule(p, ComputerProofs.OPERATOR));
        assertEquals("no_locator", ComputerProofs.missingModule(p, ComputerProofs.LOCATION), "只装身份模块：还是没有位置");
        p.set(0, new ItemStack(locator));
        assertNull(ComputerProofs.missingModule(p, ComputerProofs.LOCATION), "两样都装：MineBay 结算要的都有");
        assertNull(ComputerProofs.missingModule(p, ComputerProofs.OPERATOR));
        p.set(1, ItemStack.EMPTY);
        assertEquals("no_identity", ComputerProofs.missingModule(p, ComputerProofs.OPERATOR), "拔掉身份模块就签不了");
        assertEquals("bad_request", ComputerProofs.missingModule(p, 9));
    }

    @Test
    void nbtRoundTrip()
    {
        ComputerPeripherals p = new ComputerPeripherals(() -> {});
        NBTTagCompound empty = new NBTTagCompound();
        p.writeToNBT(empty);
        assertFalse(empty.hasKey(ComputerPeripherals.TAG), "空的不写");
        p.set(0, new ItemStack(Item.getItemFromBlock(net.minecraft.init.Blocks.STONE)));
        NBTTagCompound tag = new NBTTagCompound();
        p.writeToNBT(tag);
        ComputerPeripherals q = new ComputerPeripherals(() -> {});
        q.readFromNBT(tag);
        assertEquals(net.minecraft.init.Blocks.STONE, net.minecraft.block.Block.getBlockFromItem(q.get(0).getItem()));
        q.readFromNBT(new NBTTagCompound());
        assertTrue(q.isEmpty(), "旧存档没有这一项");
    }
}
