package com.mrcrayfish.furniture.api;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import net.minecraft.init.Items;
import net.minecraft.item.ItemMonsterPlacer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

/**
 * 1.9 以前的刷怪蛋写法是 {@code minecraft:spawn_egg} 加生物编号当数据值（马是 100），1.12 认不出（显示成"生成"，也孵不出东西）。
 * 配置文件的 minebay 商品里还留着这种写法时，读配置时自动转成 1.12 的写法（{@code EntityTag: {id: "minecraft:horse"}}，数据值 0），
 * 并记一条日志（悠然服务器魔改）。
 */
public final class LegacySpawnEggs
{
    private LegacySpawnEggs()
    {
    }

    /** 是旧写法（刷怪蛋、数据值不为 0、没有 EntityTag）就返回转换后的新物品，否则原样返回；认不出的编号也原样返回 */
    public static ItemStack convert(ItemStack stack)
    {
        if(stack == null || stack.isEmpty() || stack.getItem() != Items.SPAWN_EGG || stack.getMetadata() == 0)
            return stack;
        if(stack.hasTagCompound() && stack.getTagCompound().hasKey("EntityTag"))
            return stack;
        ResourceLocation id = entityOfLegacyId(stack.getMetadata());
        if(id == null)
        {
            log("MineBay: spawn egg with unknown legacy entity id {} kept as is", stack.getMetadata());
            return stack;
        }
        ItemStack out = stack.copy();
        out.setItemDamage(0);
        ItemMonsterPlacer.applyEntityIdToItemStack(out, id);
        log("MineBay: converted pre-1.9 spawn egg (data value {}) to 1.12 form with EntityTag id {}", stack.getMetadata(), id);
        return out;
    }

    /** 1.9 以前的生物编号对应的实体：用 Forge 的实体注册表按编号取（原版实体登记时给的就是旧编号，马是 100；EntityList.getClassFromID 只有客户端有） */
    private static ResourceLocation entityOfLegacyId(int legacy)
    {
        try
        {
            EntityEntry entry = ((ForgeRegistry<EntityEntry>) ForgeRegistries.ENTITIES).getValue(legacy);
            return entry == null ? null : entry.getRegistryName();
        }
        catch(RuntimeException | LinkageError e)
        {
            return null;
        }
    }

    private static void log(String msg, Object... args)
    {
        if(MrCrayfishFurnitureMod.logger() != null)
            MrCrayfishFurnitureMod.logger().info(msg, args);
    }
}
