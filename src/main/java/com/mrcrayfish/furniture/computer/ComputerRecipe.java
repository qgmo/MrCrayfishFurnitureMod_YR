package com.mrcrayfish.furniture.computer;

import com.mrcrayfish.furniture.Reference;
import com.mrcrayfish.furniture.init.FurnitureBlocks;
import moe.qingu.youran.api.pcb.BoardNbt;
import moe.qingu.youran.item.ItemMotherboard;
import moe.qingu.youran.logic.pcb.BoardType;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.oredict.ShapedOreRecipe;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;

/**
 * 家具电脑的配方（悠然服务器魔改，服主 11-16，工作台有形；取代原来的“铁块 + 玻璃板 + 红石”）。照 SCP 个人电脑的排法：
 * <pre>
 * 钢板        RFTools 屏幕（塞了计算机模块）  钢板
 * 钢板        工业导线发光按钮（键盘）         钢板
 * 低压接线器  家具电脑主板                    低压接线器
 * </pre>
 * 钢板按矿物词典 {@code plateSteel}（电脑只用钢板）；接线器是沉浸工程的低压接线器（{@code connector} 元数据 0，电源）；发光按钮是工业导线的
 * {@code panel_component} 元数据 0；主板要 YouranMod 的家具电脑主板、装齐了芯片（一片 YR-2000）。认法同 YouranSCP 的 {@code ComputerRecipes}。
 * 做出来的电脑物品带 {@code BlockEntityTag.Board}（主板的数据，YR-2000 的品质决定浏览器的速度，见 {@link ComputerCpu}）。
 * 缺了哪个模组的东西就不加这条配方（日志里说一句）。
 */
@Mod.EventBusSubscriber(modid = Reference.MOD_ID)
public final class ComputerRecipe
{
    private static final Logger LOG = LogManager.getLogger("cfm");
    static final ResourceLocation SCREEN = new ResourceLocation("rftools", "screen");
    static final String COMPUTER_MODULE = "rftools:computer_module";
    static final ResourceLocation BUTTON = new ResourceLocation("industrialwires", "panel_component");
    static final ResourceLocation CONNECTOR = new ResourceLocation("immersiveengineering", "connector");
    static final ResourceLocation MOTHERBOARD = new ResourceLocation("youranmod", "motherboard");
    static final ResourceLocation ID = new ResourceLocation(Reference.MOD_ID, "computer");

    private ComputerRecipe()
    {
    }

    @SubscribeEvent
    public static void register(RegistryEvent.Register<IRecipe> event)
    {
        Item screen = ForgeRegistries.ITEMS.getValue(SCREEN);
        Item button = ForgeRegistries.ITEMS.getValue(BUTTON);
        Item connector = ForgeRegistries.ITEMS.getValue(CONNECTOR);
        Item board = ForgeRegistries.ITEMS.getValue(MOTHERBOARD);
        if(screen == null || button == null || connector == null || board == null)
        {
            LOG.warn("缺 RFTools 屏幕、工业导线发光按钮、沉浸工程接线器或 YouranMod 主板，家具电脑没有合成配方");
            return;
        }
        event.getRegistry().register(create(Item.getItemFromBlock(FurnitureBlocks.COMPUTER), screen, button, connector, board));
    }

    static Recipe create(Item computer, Item screen, Item button, Item connector, Item board)
    {
        return new Recipe(ID, new ItemStack(computer), "PSP", "PKP", "LML",
                'S', new ScreenIngredient(screen), 'P', "plateSteel", 'K', new ItemStack(button, 1, 0), 'L', new ItemStack(connector, 1, 0),
                'M', new BoardIngredient(board));
    }

    /** 塞了计算机模块的 RFTools 屏幕：屏幕物品的“可还原”NBT 里 {@code Items} 列表有一个 {@code rftools:computer_module} */
    static boolean hasComputerModule(ItemStack s)
    {
        if(!s.hasTagCompound())
            return false;
        NBTTagList items = s.getTagCompound().getTagList("Items", 10);
        for(int i = 0; i < items.tagCount(); i++)
        {
            if(COMPUTER_MODULE.equals(items.getCompoundTagAt(i).getString("id")))
                return true;
        }
        return false;
    }

    /** 装齐了芯片的家具电脑主板 */
    static boolean isFurnitureBoard(@Nullable ItemStack s)
    {
        return s != null && ItemMotherboard.type(s) == BoardType.FURNITURE && ItemMotherboard.complete(s)
                && BoardNbt.type(ItemMotherboard.board(s)) == BoardType.FURNITURE;
    }

    static final class ScreenIngredient extends Ingredient
    {
        private final Item screen;

        ScreenIngredient(Item screen)
        {
            super(sample(screen));
            this.screen = screen;
        }

        /** JEI 里显示的样子：塞了一个计算机模块的屏幕 */
        static ItemStack sample(Item screen)
        {
            ItemStack s = new ItemStack(screen);
            NBTTagCompound tag = new NBTTagCompound();
            NBTTagList items = new NBTTagList();
            NBTTagCompound m = new NBTTagCompound();
            m.setByte("Slot", (byte) 0);
            m.setString("id", COMPUTER_MODULE);
            m.setByte("Count", (byte) 1);
            m.setShort("Damage", (short) 0);
            items.appendTag(m);
            tag.setTag("Items", items);
            s.setTagCompound(tag);
            return s;
        }

        @Override
        public boolean apply(@Nullable ItemStack s)
        {
            return s != null && s.getItem() == screen && hasComputerModule(s);
        }

        @Override
        public boolean isSimple()
        {
            return false;
        }
    }

    static final class BoardIngredient extends Ingredient
    {
        BoardIngredient(Item board)
        {
            super(new ItemStack(board, 1, BoardType.FURNITURE.id));
        }

        @Override
        public boolean apply(@Nullable ItemStack s)
        {
            return isFurnitureBoard(s);
        }

        @Override
        public boolean isSimple()
        {
            return false;
        }
    }

    static final class Recipe extends ShapedOreRecipe
    {
        Recipe(ResourceLocation id, ItemStack out, Object... shape)
        {
            super(id, out, shape);
            setRegistryName(id);
        }

        @Override
        public ItemStack getCraftingResult(InventoryCrafting inv)
        {
            ItemStack out = super.getCraftingResult(inv);
            for(int i = 0; i < inv.getSizeInventory(); i++)
            {
                ItemStack s = inv.getStackInSlot(i);
                if(isFurnitureBoard(s))
                {
                    out.setTagInfo("BlockEntityTag", blockEntityTag(ItemMotherboard.board(s)));
                    return out;
                }
            }
            return ItemStack.EMPTY;
        }

        @Override
        public boolean isDynamic()
        {
            return true;
        }
    }

    /** 新电脑方块实体要带的数据：主板 */
    static NBTTagCompound blockEntityTag(NBTTagCompound board)
    {
        NBTTagCompound bet = new NBTTagCompound();
        bet.setTag(ComputerCpu.TAG_BOARD, board.copy());
        return bet;
    }
}
