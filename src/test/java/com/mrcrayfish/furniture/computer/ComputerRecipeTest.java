package com.mrcrayfish.furniture.computer;

import moe.qingu.youran.api.pcb.BoardNbt;
import moe.qingu.youran.item.ItemMotherboard;
import moe.qingu.youran.logic.litho.ChipModel;
import moe.qingu.youran.logic.pcb.BoardType;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Bootstrap;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.oredict.OreDictionary;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 家具电脑的配方（服主 11-16）：钢板 / 塞了计算机模块的屏幕 / 钢板；钢板 / 发光按钮 / 钢板；低压接线器 / 家具电脑主板 / 低压接线器。
 * 认法同 YouranSCP 的 ComputerRecipes：屏幕要塞了计算机模块，主板要芯片装齐、类型对；钢板换成别的不认。
 */
class ComputerRecipeTest
{
    static Item computer, screen, button, connector, board, steel, iron;
    static ComputerRecipe.Recipe recipe;

    @BeforeAll
    static void setUp()
    {
        Bootstrap.register();
        computer = new Item().setRegistryName("cfm", "computer");
        screen = new Item().setRegistryName("rftools", "screen");
        button = new Item().setHasSubtypes(true).setRegistryName("industrialwires", "panel_component");
        connector = new Item().setHasSubtypes(true).setRegistryName("immersiveengineering", "connector");
        board = new ItemMotherboard();
        steel = new Item().setRegistryName("test", "plate_steel");
        iron = new Item().setRegistryName("test", "plate_iron");
        OreDictionary.registerOre("plateSteel", steel);
        OreDictionary.registerOre("plateIron", iron);
        recipe = ComputerRecipe.create(computer, screen, button, connector, board);
    }

    static ItemStack screenWithModule(boolean module)
    {
        ItemStack s = ComputerRecipe.ScreenIngredient.sample(screen);
        if(!module)
            s.getTagCompound().getTagList("Items", 10).getCompoundTagAt(0).setString("id", "rftools:clock_module");
        return s;
    }

    static NBTTagCompound yr2000(int maxFreq)
    {
        NBTTagCompound c = new NBTTagCompound();
        c.setString(ChipModel.TAG, ChipModel.YR2000.id);
        c.setString("Serial", "YR2000-0001");
        c.setBoolean("Scrapped", false);
        c.setInteger("MaxFreq", maxFreq);
        c.setInteger("StdFreq", ChipModel.YR2000.ratedKHz);
        return c;
    }

    static ItemStack furnitureBoard(boolean withCpu)
    {
        NBTTagCompound b = BoardNbt.empty(BoardType.FURNITURE);
        if(withCpu)
            b = BoardNbt.install(b, Collections.singletonList(new BoardNbt.Part(ChipModel.YR2000, yr2000(1_800_000))));
        return ItemMotherboard.make(board, BoardType.FURNITURE, b);
    }

    static InventoryCrafting grid(Object... nine)
    {
        InventoryCrafting inv = new InventoryCrafting(new Container()
        {
            @Override
            public boolean canInteractWith(EntityPlayer playerIn)
            {
                return true;
            }
        }, 3, 3);
        for(int i = 0; i < 9; i++)
            inv.setInventorySlotContents(i, nine[i] == null ? ItemStack.EMPTY : ((ItemStack) nine[i]).copy());
        return inv;
    }

    static InventoryCrafting standard(ItemStack plate, ItemStack scr, ItemStack mb)
    {
        ItemStack key = new ItemStack(button, 1, 0), lv = new ItemStack(connector, 1, 0);
        return grid(plate, scr, plate, plate, key, plate, lv, mb, lv);
    }

    @Test
    void makesComputerCarryingTheBoard()
    {
        InventoryCrafting inv = standard(new ItemStack(steel), screenWithModule(true), furnitureBoard(true));
        assertTrue(recipe.matches(inv, null));
        ItemStack out = recipe.getCraftingResult(inv);
        assertEquals(computer, out.getItem());
        NBTTagCompound b = ComputerCpu.board(out.getSubCompound("BlockEntityTag"));
        assertNotNull(b, "主板数据带进电脑");
        assertEquals(1_800_000, ComputerCpu.clockKHz(b), "最大频率 1.8 GHz 封顶");
        assertEquals("YR2000-0001", b.getCompoundTag(BoardNbt.CPU).getString("Serial"));
    }

    @Test
    void boardWithoutCpuIsRejected()
    {
        assertFalse(recipe.matches(standard(new ItemStack(steel), screenWithModule(true), furnitureBoard(false)), null), "缺芯片");
    }

    @Test
    void otherBoardTypesAreRejected()
    {
        // 个人电脑主板（装齐了也不行）、打着家具电脑元数据但数据是别的类型的
        NBTTagCompound pc = BoardNbt.empty(BoardType.PC);
        ItemStack pcBoard = ItemMotherboard.make(board, BoardType.PC, pc);
        assertFalse(recipe.matches(standard(new ItemStack(steel), screenWithModule(true), pcBoard), null));
        NBTTagCompound wrong = furnitureBoard(true).getTagCompound().getCompoundTag(BoardNbt.TAG).copy();
        wrong.setString(BoardNbt.TYPE, BoardType.PC.name);
        assertFalse(recipe.matches(standard(new ItemStack(steel), screenWithModule(true), ItemMotherboard.make(board, BoardType.FURNITURE, wrong)), null));
    }

    @Test
    void steelOnly()
    {
        assertFalse(recipe.matches(standard(new ItemStack(iron), screenWithModule(true), furnitureBoard(true)), null), "钢板换成别的板不认");
        ItemStack key = new ItemStack(button, 1, 0), lv = new ItemStack(connector, 1, 0), st = new ItemStack(steel);
        // 一角换成铁板
        assertFalse(recipe.matches(grid(new ItemStack(iron), screenWithModule(true), st, st, key, st, lv, furnitureBoard(true), lv), null));
    }

    @Test
    void screenNeedsComputerModule()
    {
        assertFalse(recipe.matches(standard(new ItemStack(steel), screenWithModule(false), furnitureBoard(true)), null));
        assertFalse(recipe.matches(standard(new ItemStack(steel), new ItemStack(screen), furnitureBoard(true)), null), "空屏幕");
    }

    @Test
    void otherPartsMustBeRight()
    {
        ItemStack st = new ItemStack(steel), lv = new ItemStack(connector, 1, 0);
        // 按钮换成别的元数据、接线器换成中压（元数据 2）
        assertFalse(recipe.matches(grid(st, screenWithModule(true), st, st, new ItemStack(button, 1, 1), st, lv, furnitureBoard(true), lv), null));
        assertFalse(recipe.matches(grid(st, screenWithModule(true), st, st, new ItemStack(button, 1, 0), st, new ItemStack(connector, 1, 2), furnitureBoard(true), lv), null));
    }
}
