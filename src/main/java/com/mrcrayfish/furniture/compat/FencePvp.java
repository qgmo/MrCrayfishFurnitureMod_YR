package com.mrcrayfish.furniture.compat;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Loader;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 电栅栏伤玩家前按 PvP 规则判断（悠然服务器魔改）。
 * <ul>
 * <li>服务器关了 PvP（server.properties 的 pvp=false）就不伤玩家。</li>
 * <li>装了 Universe Guard（SpongeForge 插件）时，按玩家所在位置的区域 pvp 旗标判断：
 * 旗标为 false 就不伤。Universe Guard 自己的 pvp 监听只认“伤害来源是玩家”的伤害
 * （EntityDamageSource / IndirectEntityDamageSource 且来源为 Player），电栅栏的伤害
 * 没有来源实体，插件拦不到，所以这里直接查区域旗标。</li>
 * </ul>
 * Universe Guard 与 Sponge API 都用反射调用，不作为编译依赖；反射失败时记一条日志，
 * 之后只按服务器 PvP 设置判断（与原来一样会伤）。
 */
public final class FencePvp
{
    private static boolean ugChecked;
    private static boolean ugBroken;
    private static Constructor<?> locationCtor;
    private static Method getRegion;
    private static Method getFlag;
    private static Object pvpFlag;

    private FencePvp() {}

    public static boolean canHurtPlayer(World world, EntityPlayer player)
    {
        MinecraftServer server = world.getMinecraftServer();
        if(server != null && !server.isPVPEnabled())
            return false;
        return regionAllowsPvp(world, player);
    }

    private static boolean regionAllowsPvp(World world, EntityPlayer player)
    {
        if(!ugChecked)
        {
            ugChecked = true;
            if(!Loader.isModLoaded("universeguard"))
            {
                ugBroken = true;
            }
            else
            {
                try
                {
                    Class<?> extent = Class.forName("org.spongepowered.api.world.extent.Extent");
                    Class<?> location = Class.forName("org.spongepowered.api.world.Location");
                    Class<?> regionUtils = Class.forName("com.universeguard.utils.RegionUtils");
                    Class<?> region = Class.forName("com.universeguard.region.Region");
                    Class<?> flagEnum = Class.forName("com.universeguard.region.enums.EnumRegionFlag");
                    locationCtor = location.getConstructor(extent, double.class, double.class, double.class);
                    getRegion = regionUtils.getMethod("getRegion", location);
                    getFlag = region.getMethod("getFlag", flagEnum);
                    pvpFlag = flagEnum.getField("PVP").get(null);
                }
                catch(ReflectiveOperationException | LinkageError e)
                {
                    disable(e);
                }
            }
        }
        if(ugBroken)
            return true;
        try
        {
            Object location = locationCtor.newInstance(world, player.posX, player.posY, player.posZ);
            Object region = getRegion.invoke(null, location);
            return region == null || (Boolean) getFlag.invoke(region, pvpFlag);
        }
        catch(ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            disable(e);
            return true;
        }
    }

    private static void disable(Throwable e)
    {
        ugBroken = true;
        MrCrayfishFurnitureMod.logger().warn("Universe Guard pvp flag lookup unavailable, electric fence only follows server pvp setting: {}", e.toString());
    }
}
