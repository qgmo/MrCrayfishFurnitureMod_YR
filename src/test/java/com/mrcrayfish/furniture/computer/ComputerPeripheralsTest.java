package com.mrcrayfish.furniture.computer;

import moe.qingu.youran.api.network.DeviceCapability;
import moe.qingu.youran.api.peripheral.PeripheralItem;
import moe.qingu.youran.api.peripheral.Peripherals;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 电脑的外设槽：什么外设都能插（服主 11-16），只有定位模块起作用（能力按外设报的能力算）、存档 */
class ComputerPeripheralsTest
{
    static Item locator;
    static Item other;
    static Item hanzi;

    @BeforeAll
    static void items()
    {
        Bootstrap.register();
        // 外设接口（YouranMod api.peripheral）：定位模块是种类 locator、能力 location 的外设；软驱、汉卡是别的外设，插得上但不起作用
        locator = new PeripheralItem(Peripherals.KIND_LOCATOR, 16, Peripherals.CAP_LOCATION).setRegistryName("youranmod", "locator_module");
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
        assertFalse(ComputerPeripherals.accepts(1, new ItemStack(locator)), "只有一格");
        assertTrue(p.capabilities().isEmpty());
        assertFalse(p.hasLocator());
        p.set(0, new ItemStack(locator));
        assertEquals(EnumSet.of(DeviceCapability.LOCATION), p.capabilities());
        assertTrue(p.hasLocator());
        assertEquals(1, changes.get());
        ItemStack old = p.set(0, ItemStack.EMPTY);
        assertEquals(locator, old.getItem());
        assertFalse(p.hasLocator(), "拿出来就没有");
        // 插着软驱、汉卡：没有任何能力
        p.set(0, new ItemStack(other));
        assertTrue(p.capabilities().isEmpty(), "软驱插着不起作用");
        assertEquals(1, p.installed().size());
        p.set(0, new ItemStack(hanzi));
        assertTrue(p.capabilities().isEmpty(), "汉卡插着不起作用");
        assertFalse(p.hasLocator());
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
