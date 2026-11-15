package com.mrcrayfish.furniture.web.minebay;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 帐号设置“操作前必须验证身份”：默认关，随存档存取 */
class MineBayDataTest
{
    @Test
    void verifySettingSaved()
    {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        MineBayData d = new MineBayData(MineBayData.NAME);
        assertFalse(d.verifyRequired(a));
        d.setVerifyRequired(a, true);
        assertTrue(d.verifyRequired(a));
        assertTrue(d.isDirty());
        NBTTagCompound nbt = d.writeToNBT(new NBTTagCompound());
        MineBayData r = new MineBayData(MineBayData.NAME);
        r.readFromNBT(nbt);
        assertTrue(r.verifyRequired(a));
        assertFalse(r.verifyRequired(b));
        r.setVerifyRequired(a, false);
        MineBayData r2 = new MineBayData(MineBayData.NAME);
        r2.readFromNBT(r.writeToNBT(new NBTTagCompound()));
        assertFalse(r2.verifyRequired(a));
        // 存档里没有这一项：都是关
        MineBayData old = new MineBayData(MineBayData.NAME);
        old.readFromNBT(new NBTTagCompound());
        assertFalse(old.verifyRequired(a));
    }
}
