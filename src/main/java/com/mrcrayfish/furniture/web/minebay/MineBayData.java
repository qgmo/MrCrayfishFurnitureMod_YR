package com.mrcrayfish.furniture.web.minebay;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import net.minecraft.nbt.CompressedStreamTools;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MineBay 的存档数据（主世界的 {@code data/cfm_minebay.dat}，服务端主线程）：每个玩家的购物车、订单记录，与下一个订单号。
 * 购物车、订单都按玩家 UUID 存，和在哪台电脑上买无关；下线、关电脑、换电脑都还在。
 * 另存：开出去还没发完货的 pay.yr 收款单（{@link PayOrders}）、每件限量商品的库存（{@link Stock.State}，全服统一）、
 * 帐号设置“操作前必须验证身份”。
 * 和钱、库存有关的改动用 {@link #saveNow} 当场写盘（不等世界自动保存）。
 */
public class MineBayData extends WorldSavedData
{
    public static final String NAME = "cfm_minebay";
    /** 最多给这么多个玩家存购物车 / 订单（防止存档无限长大；到了以后新玩家不能再加购物车，已有的不受影响） */
    public static final int MAX_PLAYERS = 10000;

    private final Map<UUID, Cart> carts = new HashMap<>();
    private final Map<UUID, Orders> orders = new HashMap<>();
    private long nextOrder = 1;
    private PayOrders payOrders = new PayOrders();
    private final Map<String, Stock.State> stock = new LinkedHashMap<>();
    /** 帐号设置“操作前必须验证身份”打开了的玩家（服主 11-15；默认关闭） */
    private final java.util.Set<UUID> verify = new java.util.LinkedHashSet<>();

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

    /** 这个玩家开了“操作前必须验证身份”没有 */
    public boolean verifyRequired(UUID player)
    {
        return verify.contains(player);
    }

    public void setVerifyRequired(UUID player, boolean on)
    {
        if(on ? verify.add(player) : verify.remove(player))
            markDirty();
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
        return record(player, new Orders.Order(nextOrder++, time, items));
    }

    /** 分一个订单号（开 pay.yr 收款单时） */
    public long nextOrderNumber()
    {
        markDirty();
        return nextOrder++;
    }

    /** 记一单（号已经分好）；人数到上限时不记 */
    public Orders.Order record(UUID player, Orders.Order order)
    {
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

    public PayOrders payOrders()
    {
        return payOrders;
    }

    /** 这件商品的库存，补到 {@code now}（设置变了照规则调整）；不限量的返回 null */
    @Nullable
    public Stock.State stock(String productId, @Nullable Stock.Spec spec, long now)
    {
        if(spec == null)
            return null;
        Stock.State old = stock.get(productId);
        Stock.State st = Stock.advance(old, spec, now);
        if(st != old)
        {
            stock.put(productId, st);
            markDirty();
        }
        return st;
    }

    /** 还能下单的份数：库存减去还没付的单预留的；不限量为 -1 */
    public long remaining(String productId, @Nullable Stock.Spec spec, long now)
    {
        return remaining(productId, spec, now, null);
    }

    /** 同上，但不算 {@code viewer} 自己那张还没付的单（他再结算时那张会先取消） */
    public long remaining(String productId, @Nullable Stock.Spec spec, long now, @Nullable UUID viewer)
    {
        Stock.State st = stock(productId, spec, now);
        if(st == null)
            return -1;
        long reserved = payOrders.reserved(productId);
        PayOrders.Entry own = viewer == null ? null : payOrders.openOf(viewer);
        if(own != null)
            reserved -= own.quantities().getOrDefault(productId, 0);
        return Math.max(0, Stock.available(st) - reserved);
    }

    /**
     * 当场写盘（{@code data/cfm_minebay.dat}，先写临时文件再改名），不等世界自动保存：开单、付款、发货这几步之后调用，
     * 崩了以后照存档里的状态对账（见 {@link MineBayShop}）。写不了只记日志（世界保存时还会再写）。
     */
    public void saveNow(@Nullable MinecraftServer server)
    {
        World overworld = server == null ? null : server.getWorld(0);
        if(overworld == null)
            return;
        File f = overworld.getSaveHandler().getMapFileFromName(NAME);
        if(f == null)
            return;
        File tmp = new File(f.getPath() + ".tmp");
        try
        {
            NBTTagCompound root = new NBTTagCompound();
            root.setTag("data", writeToNBT(new NBTTagCompound()));
            // writeCompressed 会关掉传进去的流，先写进内存再写文件、同步到盘
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            CompressedStreamTools.writeCompressed(root, buf);
            try(FileOutputStream out = new FileOutputStream(tmp))
            {
                buf.writeTo(out);
                out.getChannel().force(true);
            }
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            setDirty(false);
        }
        catch(IOException | RuntimeException e)
        {
            MrCrayfishFurnitureMod.logger().error("MineBay: could not save {} right away (will be saved with the world)", f, e);
            markDirty();
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt)
    {
        carts.clear();
        orders.clear();
        stock.clear();
        verify.clear();
        NBTTagList vl = nbt.getTagList("verify", Constants.NBT.TAG_COMPOUND);
        for(int i = 0; i < vl.tagCount() && i < MAX_PLAYERS; i++)
        {
            NBTTagCompound t = vl.getCompoundTagAt(i);
            if(t.hasUniqueId("id"))
                verify.add(t.getUniqueId("id"));
        }
        payOrders = PayOrders.read(nbt.getTagList("pay", Constants.NBT.TAG_COMPOUND));
        NBTTagCompound st = nbt.getCompoundTag("stock");
        for(String k : st.getKeySet())
        {
            Stock.State s = Stock.State.read(st.getCompoundTag(k));
            if(s != null && ShopParams.isProductId(k))
                stock.put(k, s);
        }
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
        nbt.setTag("pay", payOrders.write());
        NBTTagCompound st = new NBTTagCompound();
        for(Map.Entry<String, Stock.State> e : stock.entrySet())
            st.setTag(e.getKey(), e.getValue().write());
        nbt.setTag("stock", st);
        NBTTagList vl = new NBTTagList();
        for(UUID u : verify)
            vl.appendTag(tag(u));
        nbt.setTag("verify", vl);
        return nbt;
    }

    private static NBTTagCompound tag(UUID id)
    {
        NBTTagCompound t = new NBTTagCompound();
        t.setUniqueId("id", id);
        return t;
    }
}
