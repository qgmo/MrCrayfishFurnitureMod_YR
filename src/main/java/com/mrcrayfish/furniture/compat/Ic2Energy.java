package com.mrcrayfish.furniture.compat;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.fml.common.Loader;

import javax.annotation.Nullable;

/**
 * IC2 电网受电端的入口（悠然服务器魔改）。没装 IC2 时返回 null，不加载任何 IC2 的类。
 */
public final class Ic2Energy
{
    public interface Sink
    {
        /** 每 tick 调（第一次会接进电网）：最多收 roomFe，返回收到的 FE */
        int take(int roomFe, int maxFePerTick);

        /** 方块实体拆掉、区块卸载时从电网拿掉 */
        void unload();
    }

    private Ic2Energy() {}

    @Nullable
    public static Sink createSink(TileEntity tile, int maxFePerTick)
    {
        if(!Loader.isModLoaded("ic2"))
            return null;
        return new Ic2Sink(tile, maxFePerTick);
    }
}
