package com.mrcrayfish.furniture.compat;

import com.mrcrayfish.furniture.Reference;
import com.mrcrayfish.furniture.init.FurnitureBlocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.oredict.ShapedOreRecipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 电栅栏的配方（悠然服务器魔改，服主 11-17：原来在整合包 CraftTweaker 脚本里、只认三种电池，改由模组注册）：
 * <pre>
 * I I I      I 铁锭
 * I F I      F 白色栅栏
 * I B I      B 任意电池（对外放得出 Forge Energy 的，或 IC2 能放电的电器；空的也行）
 * </pre>
 * 做出来的电栅栏的容量就是这节电池的容量、电量就是电池里剩的电（{@link com.mrcrayfish.furniture.items.ItemElectricFence#onCreated}）。
 * 原来的 {@code electric_fence.json}（红石火把那条）删了。
 */
@Mod.EventBusSubscriber(modid = Reference.MOD_ID)
public final class ElectricFenceRecipe
{
    static final ResourceLocation ID = new ResourceLocation(Reference.MOD_ID, "electric_fence");

    private ElectricFenceRecipe() {}

    @SubscribeEvent
    public static void register(RegistryEvent.Register<IRecipe> event)
    {
        event.getRegistry().register(new ShapedOreRecipe(ID, new ItemStack(FurnitureBlocks.ELECTRIC_FENCE), "III", "IFI", "IBI",
                'I', "ingotIron", 'F', new ItemStack(FurnitureBlocks.WHITE_FENCE), 'B', new BatteryIngredient()).setRegistryName(ID));
    }

    /** 任意电池：按 {@link ItemBatteries#isBattery} 认；JEI 里列出所有认得的电池 */
    static final class BatteryIngredient extends Ingredient
    {
        BatteryIngredient()
        {
            super(samples());
        }

        static ItemStack[] samples()
        {
            List<ItemStack> l = new ArrayList<>();
            for(Item item : ForgeRegistries.ITEMS)
            {
                try
                {
                    ItemStack s = new ItemStack(item);
                    if(ItemBatteries.isBattery(s))
                        l.add(s);
                }
                catch(RuntimeException | LinkageError ignored)
                {
                    // 有的物品不带 NBT 时查能力会出错：不列
                }
            }
            return l.toArray(new ItemStack[0]);
        }

        @Override
        public boolean apply(@Nullable ItemStack stack)
        {
            return stack != null && !stack.isEmpty() && ItemBatteries.isBattery(ItemBatteries.single(stack));
        }

        @Override
        public boolean isSimple()
        {
            return false;
        }
    }
}
