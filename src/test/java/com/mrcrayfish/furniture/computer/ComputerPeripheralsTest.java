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

/** 电脑的外设槽：只收定位模块（按外设接口的种类认）、能力按外设报的能力算、存档 */
class ComputerPeripheralsTest
{
    static Item locator;
    static Item other;

    @BeforeAll
    static void items()
    {
        Bootstrap.register();
        // 外设接口（YouranMod api.peripheral）：定位模块是种类 locator、能力 location 的外设；软驱是别的外设，家具电脑不收
        locator = new PeripheralItem(Peripherals.KIND_LOCATOR, 16, Peripherals.CAP_LOCATION).setRegistryName("youranmod", "locator_module");
        other = new PeripheralItem(Peripherals.KIND_FLOPPY, 16).setRegistryName("youranmod", "floppy_drive");
    }

    @Test
    void onlyLocatorAndCapabilities()
    {
        AtomicInteger changes = new AtomicInteger();
        ComputerPeripherals p = new ComputerPeripherals(changes::incrementAndGet);
        assertTrue(ComputerPeripherals.accepts(0, new ItemStack(locator)));
        assertFalse(ComputerPeripherals.accepts(0, new ItemStack(other)));
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
