package com.mrcrayfish.furniture.compat;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Loader;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;

/**
 * 家具的“潜行 + 左键”操作（插拔能量单元卡、冲马桶、开烤面包机、开关浴缸）用的使用权检查（悠然服务器魔改）。
 * <ul>
 * <li>出生点保护、世界边界：{@link World#isBlockModifiable}；冒险模式：{@link EntityPlayer#isAllowEdit()}；</li>
 * <li>装了 Universe Guard 时照它的“禁用方块”规则（{@code FlagBlockUseListener}）：玩家所在区域的 disallowed blocks
 * 里有这个方块、玩家又没有 {@code universeguard.region} 权限时不许用。UG 只管右键（InteractBlockEvent.Secondary），
 * 左键的操作它管不到，所以这里照着查一遍。</li>
 * </ul>
 * Universe Guard 与 Sponge API 都用反射调用（同 {@link FencePvp}），反射失败时记一条日志，之后只做前两项检查。
 */
public final class RegionUse
{
    private static boolean checked;
    private static boolean broken;
    private static Constructor<?> locationCtor;
    private static Method getRegion;
    private static Method getDisallowedBlocks;
    private static Method hasPermission;
    private static Object regionPermission;
    private static Class<?> spongePlayer;

    private RegionUse() {}

    public static boolean canUse(World world, EntityPlayer player, BlockPos pos)
    {
        if(!world.isBlockModifiable(player, pos) || !player.isAllowEdit())
            return false;
        return regionAllows(world, player, world.getBlockState(pos).getBlock());
    }

    private static boolean regionAllows(World world, EntityPlayer player, Block block)
    {
        if(!checked)
        {
            checked = true;
            if(!Loader.isModLoaded("universeguard"))
            {
                broken = true;
            }
            else
            {
                try
                {
                    Class<?> extent = Class.forName("org.spongepowered.api.world.extent.Extent");
                    Class<?> location = Class.forName("org.spongepowered.api.world.Location");
                    Class<?> regionUtils = Class.forName("com.universeguard.utils.RegionUtils");
                    Class<?> region = Class.forName("com.universeguard.region.Region");
                    Class<?> permissionUtils = Class.forName("com.universeguard.utils.PermissionUtils");
                    Class<?> permissionEnum = Class.forName("com.universeguard.region.enums.RegionPermission");
                    spongePlayer = Class.forName("org.spongepowered.api.entity.living.player.Player");
                    locationCtor = location.getConstructor(extent, double.class, double.class, double.class);
                    getRegion = regionUtils.getMethod("getRegion", location);
                    getDisallowedBlocks = region.getMethod("getDisallowedBlocks");
                    hasPermission = permissionUtils.getMethod("hasPermission", spongePlayer, permissionEnum);
                    regionPermission = permissionEnum.getField("REGION").get(null);
                }
                catch(ReflectiveOperationException | LinkageError e)
                {
                    disable(e);
                }
            }
        }
        if(broken)
            return true;
        try
        {
            if(!spongePlayer.isInstance(player))
                return true;
            Object location = locationCtor.newInstance(world, player.posX, player.posY, player.posZ);
            Object region = getRegion.invoke(null, location);
            if(region == null)
                return true;
            if((Boolean) hasPermission.invoke(null, player, regionPermission))
                return true;
            ResourceLocation id = block.getRegistryName();
            Collection<?> disallowed = (Collection<?>) getDisallowedBlocks.invoke(region);
            return id == null || disallowed == null || !disallowed.contains(id.toString());
        }
        catch(ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            disable(e);
            return true;
        }
    }

    private static void disable(Throwable e)
    {
        broken = true;
        MrCrayfishFurnitureMod.logger().warn("Universe Guard disallowed-block lookup unavailable, sneak + left click on furniture only checks spawn protection: {}", e.toString());
    }
}
