package com.mrcrayfish.furniture.computer;

import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import moe.qingu.youran.api.network.DeviceHandle;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.NetworkDevices;
import moe.qingu.youran.api.network.YouranNetworkApi;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 家具电脑接入 YouranMod 的游戏内网络（服务端，主线程）：每台电脑在设备表里登记成它那一格的设备（地址是这一格的 0 号地址），
 * 有电时通电，有人操作且有电时把网卡接到操作者的客户端（协议栈跑在操作者的客户端上，服务器只当路由器）。
 * 网站要知道请求是哪台电脑发的时，用 {@link #computerAt} 按来源地址找回电脑（在主线程上）。
 */
public final class ComputerNetwork
{
    /** 地址 → 电脑所在的维度与位置 */
    private static final Map<Ipv6Address, Location> BY_ADDRESS = new ConcurrentHashMap<>();

    private ComputerNetwork() {}

    static final class Location
    {
        final int dimension;
        final BlockPos pos;

        Location(int dimension, BlockPos pos)
        {
            this.dimension = dimension;
            this.pos = pos;
        }
    }

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
        DeviceHandle h = devices.register(dim, pos.getX(), pos.getY(), pos.getZ());
        if(h != null)
        {
            BY_ADDRESS.put(h.address(), new Location(dim, pos.toImmutable()));
        }
        return h;
    }

    /** 拿掉（方块实体没了、区块卸载） */
    public static void unregister(@Nullable DeviceHandle handle)
    {
        if(handle == null)
            return;
        Location l = BY_ADDRESS.get(handle.address());
        if(l != null)
        {
            BY_ADDRESS.remove(handle.address(), l);
        }
        if(!handle.isRemoved())
            handle.remove();
    }

    /** 按设备地址找电脑（主线程）：区块没加载、已经不是电脑时返回 null */
    @Nullable
    public static TileEntityComputer computerAt(Ipv6Address address)
    {
        Location l = BY_ADDRESS.get(address);
        if(l == null)
            return null;
        WorldServer world = DimensionManager.getWorld(l.dimension);
        if(world == null || !world.isBlockLoaded(l.pos))
            return null;
        TileEntity te = world.getTileEntity(l.pos);
        if(te instanceof TileEntityComputer && ((TileEntityComputer) te).deviceAddress() != null && address.equals(((TileEntityComputer) te).deviceAddress()))
            return (TileEntityComputer) te;
        return null;
    }
}
