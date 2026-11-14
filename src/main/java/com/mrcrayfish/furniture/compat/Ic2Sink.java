package com.mrcrayfish.furniture.compat;

import ic2.api.energy.prefab.BasicSink;
import net.minecraft.tileentity.TileEntity;

/**
 * 接 IC2 电网的受电端（悠然服务器魔改）：方块实体旁挂一个 IC2 API 自带的 {@link BasicSink}，
 * 每 tick 把它收到的 EU 换成 FE（1 EU = 2 FE，同 SCP 电脑）交给方块实体。等级按最高，不会因电压过高爆炸。
 * <p>
 * <b>引用了 IC2 的类，只能在装了 IC2 时加载</b>：经 {@link Ic2Energy} 创建。
 */
final class Ic2Sink implements Ic2Energy.Sink
{
    private static final int TIER = 14;

    private final BasicSink sink;

    Ic2Sink(TileEntity tile, int maxFePerTick)
    {
        this.sink = new BasicSink(tile, Math.max(1, maxFePerTick / 2.0), TIER);
    }

    @Override
    public int take(int roomFe, int maxFePerTick)
    {
        // 缓存只留一 tick 能收的量，满了 IC2 就不再往里送
        sink.setCapacity(Math.max(1, Math.min(maxFePerTick, roomFe) / 2.0));
        sink.update();
        if(roomFe < 2)
            return 0;
        double eu = Math.min(sink.getEnergyStored(), roomFe / 2.0);
        int fe = (int) Math.floor(eu * 2 + 1e-6);
        if(fe > 0)
            sink.useEnergy(fe / 2.0);
        return fe;
    }

    @Override
    public void unload()
    {
        sink.onChunkUnload();
    }
}
