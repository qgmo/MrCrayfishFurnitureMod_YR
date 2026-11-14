package com.mrcrayfish.furniture.compat;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.util.PowerCellCost;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.common.Loader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * RFTools 的能量单元卡（{@code rftools:powercell_card}，悠然服务器魔改）：电器插上卡，作为该卡网络的用电户从公共池子取电。
 * <p>
 * RFTools 是可选依赖，这里全部用反射（对照整合包里 rftools-1.12-7.73、mcjtylib-1.12-3.5.4 的 jar 用 javap 核对过签名），
 * 不引用 RFTools 的任何类；没装 RFTools 或反射失败时（记一条日志）卡不供电。只在服务端、主线程调用。
 * <ul>
 * <li>网络数据：{@code PowerCellNetwork.getChannels(World)}（世界数据 {@code RFToolsPowerCellNetwork}）→
 * {@code getChannel(int)} 得到 {@code Network}：{@code getEnergy()}、{@code extractEnergy(int)}、{@code getBlocks()}；
 * 改完调 {@code save()}（标记世界数据要存盘）。</li>
 * <li>卡的网络号：物品 NBT 的 {@code id}，没有时是 -1（没绑定网络）。</li>
 * <li>成本参数：{@code PowerCellConfiguration.powerCellCostFactor / powerCellDistanceCap / powerCellMinDistance}。</li>
 * </ul>
 */
public final class RfToolsCards
{
    private static final ResourceLocation CARD = new ResourceLocation("rftools", "powercell_card");

    private static boolean checked;
    private static boolean broken;
    private static Method getChannels, getChannel, save;
    private static Method getEnergy, extractEnergy, getBlocks;
    private static Method getCoordinate, getDimension;
    private static Field costFactorField, distanceCapField, minDistanceField;
    private static Method doubleValueGet;

    private RfToolsCards() {}

    public static boolean isLoaded()
    {
        return Loader.isModLoaded("rftools");
    }

    /** 是不是能量单元卡（没装 RFTools 时恒 false） */
    public static boolean isCard(ItemStack stack)
    {
        return !stack.isEmpty() && CARD.equals(stack.getItem().getRegistryName());
    }

    /** 卡绑定的网络号；没绑定是 -1 */
    public static int cardId(ItemStack card)
    {
        NBTTagCompound tag = card.getTagCompound();
        if(tag == null || !tag.hasKey("id", Constants.NBT.TAG_ANY_NUMERIC))
            return -1;
        return tag.getInteger("id");
    }

    private static boolean ready()
    {
        if(!checked)
        {
            checked = true;
            if(!isLoaded())
            {
                broken = true;
                return false;
            }
            try
            {
                Class<?> networkData = Class.forName("mcjty.rftools.blocks.powercell.PowerCellNetwork");
                Class<?> network = Class.forName("mcjty.rftools.blocks.powercell.PowerCellNetwork$Network");
                Class<?> coordinate = Class.forName("mcjty.lib.varia.GlobalCoordinate");
                Class<?> config = Class.forName("mcjty.rftools.blocks.powercell.PowerCellConfiguration");
                Class<?> doubleValue = Class.forName("mcjty.lib.thirteen.ConfigSpec$DoubleValue");
                getChannels = networkData.getMethod("getChannels", World.class);
                getChannel = networkData.getMethod("getChannel", int.class);
                save = networkData.getMethod("save");
                getEnergy = network.getMethod("getEnergy");
                extractEnergy = network.getMethod("extractEnergy", int.class);
                getBlocks = network.getMethod("getBlocks");
                getCoordinate = coordinate.getMethod("getCoordinate");
                getDimension = coordinate.getMethod("getDimension");
                costFactorField = config.getField("powerCellCostFactor");
                distanceCapField = config.getField("powerCellDistanceCap");
                minDistanceField = config.getField("powerCellMinDistance");
                doubleValueGet = doubleValue.getMethod("get");
            }
            catch(ReflectiveOperationException | LinkageError e)
            {
                disable(e);
            }
        }
        return !broken;
    }

    private static void disable(Throwable e)
    {
        broken = true;
        MrCrayfishFurnitureMod.logger().warn("RFTools power cell network unavailable, power cell cards in furniture will not supply power: {}", e.toString());
    }

    private static Object network(World world, ItemStack card) throws ReflectiveOperationException
    {
        int id = cardId(card);
        if(id < 0)
            return null;
        Object data = getChannels.invoke(null, world);
        return data == null ? null : getChannel.invoke(data, id);
    }

    private static double config(Field field, double fallback) throws ReflectiveOperationException
    {
        Object value = field.get(null);
        return value == null ? fallback : (Double) doubleValueGet.invoke(value);
    }

    /** 网络现有电量；没装 RFTools、卡没绑定、网络不存在时 -1 */
    public static int networkEnergy(World world, ItemStack card)
    {
        if(world.isRemote || !isCard(card) || !ready())
            return -1;
        try
        {
            Object network = network(world, card);
            return network == null ? -1 : (Integer) getEnergy.invoke(network);
        }
        catch(ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            disable(e);
            return -1;
        }
    }

    /** 电器在 pos 时从这张卡的网络取电的成本系数；取不到时 1 */
    public static float costFactor(World world, BlockPos pos, ItemStack card)
    {
        if(world.isRemote || !isCard(card) || !ready())
            return 1.0F;
        try
        {
            Object network = network(world, card);
            if(network == null)
                return 1.0F;
            Collection<?> blocks = (Collection<?>) getBlocks.invoke(network);
            List<PowerCellCost.Cell> cells = new ArrayList<>(blocks.size());
            for(Object block : blocks)
            {
                BlockPos p = (BlockPos) getCoordinate.invoke(block);
                int dim = (Integer) getDimension.invoke(block);
                cells.add(new PowerCellCost.Cell(p.getX(), p.getY(), p.getZ(), dim));
            }
            PowerCellCost.Cell self = new PowerCellCost.Cell(pos.getX(), pos.getY(), pos.getZ(), world.provider.getDimension());
            return PowerCellCost.factor(cells, self, config(costFactorField, 1.1), config(distanceCapField, 10000.0), config(minDistanceField, 100.0));
        }
        catch(ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            disable(e);
            return 1.0F;
        }
    }

    /**
     * 从卡的网络取 amount FE（照 RFTools：最多给 {@code (int)(网络电量 / 成本)}）；给不够 amount 就一点也不取，返回 0。
     */
    public static int extract(World world, ItemStack card, int amount, float factor)
    {
        if(amount <= 0 || world.isRemote || !isCard(card) || !ready())
            return 0;
        try
        {
            Object network = network(world, card);
            if(network == null)
                return 0;
            int energy = (Integer) getEnergy.invoke(network);
            if(PowerCellCost.available(energy, factor) < amount)
                return 0;
            int taken = (Integer) extractEnergy.invoke(network, amount);
            save.invoke(getChannels.invoke(null, world));
            return taken;
        }
        catch(ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            disable(e);
            return 0;
        }
    }
}
