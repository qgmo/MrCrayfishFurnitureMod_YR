package com.mrcrayfish.furniture.computer;

import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import moe.qingu.youran.api.network.DeviceCapability;
import moe.qingu.youran.api.network.DeviceHandle;
import moe.qingu.youran.api.network.NetworkDevices;
import moe.qingu.youran.api.network.YouranNetworkApi;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * 家具电脑接入 YouranMod 的游戏内网络（服务端，主线程）：每台电脑在设备表里登记成它那一格的设备（地址是这一格的 0 号地址），
 * 有电时通电，有人操作且有电时把网卡接到操作者的客户端（协议栈跑在操作者的客户端上，服务器只当路由器）。
 * 网站要知道是谁、在哪，只用公开接口：{@code id.yr} 游戏角色一键登录（设备声明的一键登录能力）、定位模块签名的位置证明；
 * 不再提供按来源地址找回电脑与操作者的专门入口（服主 11-15）。
 */
public final class ComputerNetwork
{
    private ComputerNetwork() {}

    /** 登记这台电脑；网络没在运行、这一格没有地址时返回 null（之后再试） */
    @Nullable
    public static DeviceHandle register(TileEntityComputer computer)
    {
        NetworkDevices devices = YouranNetworkApi.devices();
        World world = computer.getWorld();
        if(devices == null || world == null || world.isRemote)
            return null;
        BlockPos pos = computer.getPos();
        int dim = world.provider.getDimension();
        // 家具电脑的网卡附带一键登录能力（服主 11-15）；定位模块由外设槽决定（TileEntityComputer 每 tick 对齐）
        DeviceHandle h = devices.register(dim, pos.getX(), pos.getY(), pos.getZ(), DeviceCapability.ONE_CLICK_LOGIN);
        if(h != null && computer.getPeripherals().hasLocator())
            h.setCapability(DeviceCapability.LOCATION, true);
        return h;
    }

    /** 拿掉（方块实体没了、区块卸载） */
    public static void unregister(@Nullable DeviceHandle handle)
    {
        if(handle == null)
            return;
        if(!handle.isRemoved())
            handle.remove();
    }
}
