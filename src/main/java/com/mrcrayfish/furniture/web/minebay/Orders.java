package com.mrcrayfish.furniture.web.minebay;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个玩家的订单记录（纯逻辑，主线程上用；随存档）。最新的在前，最多记 {@value #MAX_ORDERS} 单，再多时最早的那单不再保留。
 * 记的是下单时的样子（商品名、图标、单价），商品以后改价、下架都不影响记录。
 */
public final class Orders
{
    public static final int MAX_ORDERS = 50;

    public static final class Item
    {
        public final String id;
        public final String icon;
        public final int count;
        /** 网站给的名字；null 时浏览器按图标物品显示 */
        @Nullable
        public final String name;
        public final String sortName;
        public final int quantity;
        public final String currency;
        public final long cost;

        public Item(String id, String icon, int count, @Nullable String name, String sortName, int quantity, String currency, long cost)
        {
            this.id = id;
            this.icon = icon;
            this.count = count;
            this.name = name;
            this.sortName = sortName;
            this.quantity = quantity;
            this.currency = currency;
            this.cost = cost;
        }
    }

    public static final class Order
    {
        public final long number;
        /** 下单时间（毫秒） */
        public final long time;
        public final List<Item> items;

        public Order(long number, long time, List<Item> items)
        {
            this.number = number;
            this.time = time;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
        }

        /** 按货币合计（按出现顺序） */
        public Map<String, Long> totals()
        {
            Map<String, Long> out = new LinkedHashMap<>();
            for(Item i : items)
                out.merge(i.currency, i.cost, Long::sum);
            return out;
        }
    }

    private final List<Order> orders = new ArrayList<>();

    public List<Order> list()
    {
        return Collections.unmodifiableList(orders);
    }

    @Nullable
    public Order find(long number)
    {
        for(Order o : orders)
        {
            if(o.number == number)
                return o;
        }
        return null;
    }

    public void add(Order o)
    {
        orders.add(0, o);
        while(orders.size() > MAX_ORDERS)
            orders.remove(orders.size() - 1);
    }

    public NBTTagList write()
    {
        NBTTagList list = new NBTTagList();
        for(Order o : orders)
        {
            NBTTagCompound t = new NBTTagCompound();
            t.setLong("no", o.number);
            t.setLong("time", o.time);
            NBTTagList items = new NBTTagList();
            for(Item i : o.items)
            {
                NBTTagCompound it = new NBTTagCompound();
                it.setString("id", i.id);
                it.setString("icon", i.icon);
                it.setInteger("count", i.count);
                if(i.name != null)
                    it.setString("name", i.name);
                it.setString("sort", i.sortName);
                it.setInteger("n", i.quantity);
                it.setString("cur", i.currency);
                it.setLong("cost", i.cost);
                items.appendTag(it);
            }
            t.setTag("items", items);
            list.appendTag(t);
        }
        return list;
    }

    public static Orders read(NBTTagList list)
    {
        Orders out = new Orders();
        for(int i = 0; i < list.tagCount() && out.orders.size() < MAX_ORDERS; i++)
        {
            NBTTagCompound t = list.getCompoundTagAt(i);
            NBTTagList items = t.getTagList("items", Constants.NBT.TAG_COMPOUND);
            List<Item> parsed = new ArrayList<>();
            for(int j = 0; j < items.tagCount() && parsed.size() < Cart.MAX_LINES; j++)
            {
                NBTTagCompound it = items.getCompoundTagAt(j);
                parsed.add(new Item(it.getString("id"), it.getString("icon"), it.getInteger("count"), it.hasKey("name", Constants.NBT.TAG_STRING) ? it.getString("name") : null,
                        it.getString("sort"), it.getInteger("n"), it.getString("cur"), it.getLong("cost")));
            }
            out.orders.add(new Order(t.getLong("no"), t.getLong("time"), parsed));
        }
        return out;
    }
}
