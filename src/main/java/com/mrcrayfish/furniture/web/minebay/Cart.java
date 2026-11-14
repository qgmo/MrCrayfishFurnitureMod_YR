package com.mrcrayfish.furniture.web.minebay;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 一个玩家的购物车（存在服务端、按玩家 UUID、随存档；纯逻辑，主线程上用）。
 * 最多 {@value #MAX_LINES} 种商品，每种最多 {@value #MAX_QUANTITY} 份（商品自己的上限更小时按商品的）。
 * 东西只在玩家自己删除、或者结算成功买走时才从车里拿掉；商品下架了也留在车里（标成“已下架”，由玩家自己删）。
 */
public final class Cart
{
    public static final int MAX_LINES = 32;
    public static final int MAX_QUANTITY = 64;

    public static final class Line
    {
        public final String id;
        int quantity;

        Line(String id, int quantity)
        {
            this.id = id;
            this.quantity = quantity;
        }

        public int quantity()
        {
            return quantity;
        }
    }

    public enum Change
    {
        OK,
        /** 种类满了 */
        FULL,
        /** 这一种超过上限 */
        TOO_MANY,
        /** 数量不对（不是 1 到上限） */
        BAD_QUANTITY,
        /** 车里没有这一种 */
        NOT_IN_CART
    }

    private final List<Line> lines = new ArrayList<>();

    public List<Line> lines()
    {
        return Collections.unmodifiableList(lines);
    }

    public boolean isEmpty()
    {
        return lines.isEmpty();
    }

    /** 一共几份（顶栏“购物车 (N)”） */
    public int totalQuantity()
    {
        int n = 0;
        for(Line l : lines)
            n += l.quantity;
        return n;
    }

    public int quantityOf(String id)
    {
        Line l = find(id);
        return l == null ? 0 : l.quantity;
    }

    private Line find(String id)
    {
        for(Line l : lines)
        {
            if(l.id.equals(id))
                return l;
        }
        return null;
    }

    static int limit(int productLimit)
    {
        return Math.max(1, Math.min(MAX_QUANTITY, productLimit));
    }

    /** 加 quantity 份（已经有就加到原来的上面）；超了不改 */
    public Change add(String id, int quantity, int productLimit)
    {
        int max = limit(productLimit);
        if(quantity < 1 || quantity > max)
            return Change.BAD_QUANTITY;
        Line l = find(id);
        if(l == null)
        {
            if(lines.size() >= MAX_LINES)
                return Change.FULL;
            lines.add(new Line(id, quantity));
            return Change.OK;
        }
        if(l.quantity + quantity > max)
            return Change.TOO_MANY;
        l.quantity += quantity;
        return Change.OK;
    }

    /** 改成 quantity 份 */
    public Change set(String id, int quantity, int productLimit)
    {
        Line l = find(id);
        if(l == null)
            return Change.NOT_IN_CART;
        if(quantity < 1 || quantity > limit(productLimit))
            return Change.BAD_QUANTITY;
        l.quantity = quantity;
        return Change.OK;
    }

    public boolean remove(String id)
    {
        return lines.removeIf(l -> l.id.equals(id));
    }

    /** 结算成功后：减掉买走的份数，减到 0 的那一种拿掉 */
    public void removeBought(Map<String, Integer> bought)
    {
        for(Map.Entry<String, Integer> e : bought.entrySet())
        {
            Line l = find(e.getKey());
            if(l == null)
                continue;
            l.quantity -= e.getValue();
            if(l.quantity <= 0)
                lines.remove(l);
        }
    }

    public NBTTagList write()
    {
        NBTTagList list = new NBTTagList();
        for(Line l : lines)
        {
            NBTTagCompound t = new NBTTagCompound();
            t.setString("id", l.id);
            t.setInteger("n", l.quantity);
            list.appendTag(t);
        }
        return list;
    }

    /** 读存档：坏的条目跳过，超出上限的截掉（存档被改过时也不会超限） */
    public static Cart read(NBTTagList list)
    {
        Cart c = new Cart();
        for(int i = 0; i < list.tagCount() && c.lines.size() < MAX_LINES; i++)
        {
            NBTTagCompound t = list.getCompoundTagAt(i);
            if(!t.hasKey("id", Constants.NBT.TAG_STRING))
                continue;
            String id = t.getString("id");
            int n = t.getInteger("n");
            if(!ShopParams.isProductId(id) || n < 1 || c.find(id) != null)
                continue;
            c.lines.add(new Line(id, Math.min(n, MAX_QUANTITY)));
        }
        return c;
    }
}
