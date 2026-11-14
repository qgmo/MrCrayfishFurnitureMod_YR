package com.mrcrayfish.furniture.web.minebay;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MineBay 的存档数据（主世界的 {@code data/cfm_minebay.dat}，服务端主线程）：每个玩家的购物车、订单记录，与下一个订单号。
 * 购物车、订单都按玩家 UUID 存，和在哪台电脑上买无关；下线、关电脑、换电脑都还在。
 */
public class MineBayData extends WorldSavedData
{
    public static final String NAME = "cfm_minebay";
    /** 最多给这么多个玩家存购物车 / 订单（防止存档无限长大；到了以后新玩家不能再加购物车，已有的不受影响） */
    public static final int MAX_PLAYERS = 10000;

    private final Map<UUID, Cart> carts = new HashMap<>();
    private final Map<UUID, Orders> orders = new HashMap<>();
    private long nextOrder = 1;

    public MineBayData(String name)
    {
        super(name);
    }

    @Nullable
    public static MineBayData get(@Nullable MinecraftServer server)
    {
        if(server == null)
            return null;
        World overworld = server.getWorld(0);
        if(overworld == null)
            return null;
        MapStorage storage = overworld.getMapStorage();
        if(storage == null)
            return null;
        MineBayData d = (MineBayData) storage.getOrLoadData(MineBayData.class, NAME);
        if(d == null)
        {
            d = new MineBayData(NAME);
            storage.setData(NAME, d);
        }
        return d;
    }

    /** 这个玩家的购物车（没有时给一个空的，不存进去） */
    public Cart cart(UUID player)
    {
        Cart c = carts.get(player);
        return c == null ? new Cart() : c;
    }

    /** 要改购物车时用：没有就建一个；人数到上限时返回 null */
    @Nullable
    public Cart cartForChange(UUID player)
    {
        Cart c = carts.get(player);
        if(c == null)
        {
            if(carts.size() >= MAX_PLAYERS)
                return null;
            c = new Cart();
            carts.put(player, c);
        }
        return c;
    }

    /** 改完购物车后调用：空了就不再存 */
    public void cartChanged(UUID player)
    {
        Cart c = carts.get(player);
        if(c != null && c.isEmpty())
            carts.remove(player);
        markDirty();
    }

    public Orders orders(UUID player)
    {
        Orders o = orders.get(player);
        return o == null ? new Orders() : o;
    }

    /** 记一单（订单号在这里分配）；人数到上限时不记，返回的订单照样有号 */
    public Orders.Order record(UUID player, long time, java.util.List<Orders.Item> items)
    {
        Orders.Order order = new Orders.Order(nextOrder++, time, items);
        Orders o = orders.get(player);
        if(o == null && orders.size() < MAX_PLAYERS)
        {
            o = new Orders();
            orders.put(player, o);
        }
        if(o != null)
            o.add(order);
        markDirty();
        return order;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt)
    {
        carts.clear();
        orders.clear();
        nextOrder = Math.max(1, nbt.getLong("next"));
        NBTTagList players = nbt.getTagList("players", Constants.NBT.TAG_COMPOUND);
        for(int i = 0; i < players.tagCount() && i < MAX_PLAYERS * 2; i++)
        {
            NBTTagCompound t = players.getCompoundTagAt(i);
            if(!t.hasUniqueId("id"))
                continue;
            UUID id = t.getUniqueId("id");
            Cart c = Cart.read(t.getTagList("cart", Constants.NBT.TAG_COMPOUND));
            if(!c.isEmpty() && carts.size() < MAX_PLAYERS)
                carts.put(id, c);
            Orders o = Orders.read(t.getTagList("orders", Constants.NBT.TAG_COMPOUND));
            if(!o.list().isEmpty() && orders.size() < MAX_PLAYERS)
                orders.put(id, o);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt)
    {
        nbt.setLong("next", nextOrder);
        Map<UUID, NBTTagCompound> byPlayer = new HashMap<>();
        for(Map.Entry<UUID, Cart> e : carts.entrySet())
        {
            if(!e.getValue().isEmpty())
                byPlayer.computeIfAbsent(e.getKey(), MineBayData::tag).setTag("cart", e.getValue().write());
        }
        for(Map.Entry<UUID, Orders> e : orders.entrySet())
            byPlayer.computeIfAbsent(e.getKey(), MineBayData::tag).setTag("orders", e.getValue().write());
        NBTTagList list = new NBTTagList();
        for(NBTTagCompound t : byPlayer.values())
            list.appendTag(t);
        nbt.setTag("players", list);
        return nbt;
    }

    private static NBTTagCompound tag(UUID id)
    {
        NBTTagCompound t = new NBTTagCompound();
        t.setUniqueId("id", id);
        return t;
    }
}
