package com.mrcrayfish.furniture.computer;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemIconsTest
{
    @Test
    void keepsOnlyWhatTheIconNeeds()
    {
        NBTTagCompound t = new NBTTagCompound();
        t.setString("Potion", "minecraft:night_vision");
        NBTTagCompound display = new NBTTagCompound();
        display.setString("Name", "§c骗人的名字");
        t.setTag("display", display);
        NBTTagCompound skull = new NBTTagCompound();
        skull.setString("Name", "Notch");
        t.setTag("SkullOwner", skull);
        NBTTagCompound entity = new NBTTagCompound();
        entity.setString("id", "minecraft:horse");
        entity.setString("CustomName", "很长的名字");
        entity.setTag("Passengers", new NBTTagList());
        t.setTag("EntityTag", entity);
        NBTTagCompound f = ItemIcons.filter(t);
        assertNotNull(f);
        assertEquals("minecraft:night_vision", f.getString("Potion"));
        assertFalse(f.hasKey("display"), "改名、Lore 不带");
        assertFalse(f.hasKey("SkullOwner"), "头颅皮肤会联网，不带");
        assertEquals(1, f.getCompoundTag("EntityTag").getSize(), "刷怪蛋只留生物 id");
        assertEquals("minecraft:horse", f.getCompoundTag("EntityTag").getString("id"));
        assertTrue(ItemIcons.nbtSuffix(t).startsWith("{"));
    }

    @Test
    void nothingToKeep()
    {
        assertNull(ItemIcons.filter(null));
        NBTTagCompound t = new NBTTagCompound();
        t.setInteger("Damage", 3);
        assertNull(ItemIcons.filter(t));
        assertEquals("", ItemIcons.nbtSuffix(t));
        // 太长的不带
        NBTTagList list = new NBTTagList();
        for(int i = 0; i < 100; i++)
        {
            NBTTagCompound e = new NBTTagCompound();
            e.setShort("id", (short) i);
            e.setShort("lvl", (short) 1);
            list.appendTag(e);
        }
        NBTTagCompound big = new NBTTagCompound();
        big.setTag("StoredEnchantments", list);
        assertEquals("", ItemIcons.nbtSuffix(big));
    }
}
