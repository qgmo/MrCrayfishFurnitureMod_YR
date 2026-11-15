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
import java.util.UUID;

/**
 * MineBay 开出去、还没发完货的 pay.yr 收款单（纯逻辑，主线程上用；随 MineBay 存档）：
 * <ul>
 * <li>{@link State#OPEN}：开了单、等玩家在 pay.yr 付。这时<b>预留库存</b>（{@link #reserved}），别人看到的剩余要减掉它；
 * 收款单过期、取消时放回（不会超卖：付款只可能发生在收款单还开着的时候，那时预留还在）。</li>
 * <li>{@link State#PAID}：付了（库存已经正式扣掉、订单已经记下），还没发货（买的人不在线时等他上线）。发完货就拿掉。</li>
 * </ul>
 * 每个玩家同一时间最多一张 OPEN（再结算时先取消上一张）。要发的东西开单时就准备好了（{@link Line#goods}，物品的 NBT）。
 */
public final class PayOrders
{
    /** 最多存多少张（防存档无限长大） */
    public static final int MAX = 4096;

    public enum State
    {
        OPEN, PAID
    }

    /** 一行：哪件商品、几份、要发的物品（ItemStack 的 NBT） */
    public static final class Line
    {
        public final String productId;
        public final int quantity;
        public final List<NBTTagCompound> goods;

        public Line(String productId, int quantity, List<NBTTagCompound> goods)
        {
            this.productId = productId;
            this.quantity = quantity;
            this.goods = Collections.unmodifiableList(new ArrayList<>(goods));
        }
    }

    public static final class Entry
    {
        /** pay.yr 的收款单号 */
        public final String payId;
        /** MineBay 订单号（开单时分配，付了以后订单记录用它） */
        public final long number;
        public final UUID buyer;
        public final String buyerName;
        public final long created;
        /** 收款单什么时候过期（毫秒） */
        public final long expires;
        /** 付款页网址 */
        public final String checkoutUrl;
        /** 记进“我的订单”的样子 */
        public final List<Orders.Item> items;
        public final List<Line> lines;
        /** 从购物车结算（付了以后从车里拿掉买走的） */
        public final boolean fromCart;
        State state = State.OPEN;

        public Entry(String payId, long number, UUID buyer, String buyerName, long created, long expires, String checkoutUrl,
                     List<Orders.Item> items, List<Line> lines, boolean fromCart)
        {
            this.payId = payId;
            this.number = number;
            this.buyer = buyer;
            this.buyerName = buyerName;
            this.created = created;
            this.expires = expires;
            this.checkoutUrl = checkoutUrl;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
            this.fromCart = fromCart;
        }

        public State state()
        {
            return state;
        }

        /** 商品编号 → 份数 */
        public Map<String, Integer> quantities()
        {
            Map<String, Integer> m = new LinkedHashMap<>();
            for(Line l : lines)
                m.merge(l.productId, l.quantity, Integer::sum);
            return m;
        }
    }

    private final Map<String, Entry> byPay = new LinkedHashMap<>();

    public boolean isFull()
    {
        return byPay.size() >= MAX;
    }

    public void add(Entry e)
    {
        if(isFull())
            throw new IllegalStateException("MineBay 未完成的收款单太多了");
        byPay.put(e.payId, e);
    }

    @Nullable
    public Entry get(String payId)
    {
        return payId == null ? null : byPay.get(payId);
    }

    @Nullable
    public Entry remove(String payId)
    {
        return byPay.remove(payId);
    }

    /** 这个玩家还在等付款的那一张 */
    @Nullable
    public Entry openOf(UUID buyer)
    {
        for(Entry e : byPay.values())
        {
            if(e.state == State.OPEN && e.buyer.equals(buyer))
                return e;
        }
        return null;
    }

    /** 这个玩家付了还没发货的 */
    public List<Entry> paidOf(UUID buyer)
    {
        List<Entry> out = new ArrayList<>();
        for(Entry e : byPay.values())
        {
            if(e.state == State.PAID && e.buyer.equals(buyer))
                out.add(e);
        }
        return out;
    }

    public List<Entry> all()
    {
        return new ArrayList<>(byPay.values());
    }

    /** 记成付了；不是 OPEN 的不动、返回 false（同一张单只记一次） */
    public boolean markPaid(String payId)
    {
        Entry e = byPay.get(payId);
        if(e == null || e.state != State.OPEN)
            return false;
        e.state = State.PAID;
        return true;
    }

    /** 这件商品被还没付的单预留了几份 */
    public long reserved(String productId)
    {
        long n = 0;
        for(Entry e : byPay.values())
        {
            if(e.state != State.OPEN)
                continue;
            for(Line l : e.lines)
            {
                if(l.productId.equals(productId))
                    n += l.quantity;
            }
        }
        return n;
    }

    public NBTTagList write()
    {
        NBTTagList list = new NBTTagList();
        for(Entry e : byPay.values())
        {
            NBTTagCompound t = new NBTTagCompound();
            t.setString("pay", e.payId);
            t.setLong("no", e.number);
            t.setUniqueId("buyer", e.buyer);
            t.setString("name", e.buyerName);
            t.setLong("at", e.created);
            t.setLong("exp", e.expires);
            t.setString("url", e.checkoutUrl);
            t.setTag("items", Orders.writeItems(e.items));
            NBTTagList lines = new NBTTagList();
            for(Line l : e.lines)
            {
                NBTTagCompound lt = new NBTTagCompound();
                lt.setString("id", l.productId);
                lt.setInteger("n", l.quantity);
                NBTTagList g = new NBTTagList();
                for(NBTTagCompound s : l.goods)
                    g.appendTag(s.copy());
                lt.setTag("goods", g);
                lines.appendTag(lt);
            }
            t.setTag("lines", lines);
            t.setBoolean("cart", e.fromCart);
            t.setString("state", e.state.name());
            list.appendTag(t);
        }
        return list;
    }

    public static PayOrders read(NBTTagList list)
    {
        PayOrders out = new PayOrders();
        for(int i = 0; i < list.tagCount() && out.byPay.size() < MAX; i++)
        {
            NBTTagCompound t = list.getCompoundTagAt(i);
            if(!t.hasUniqueId("buyer") || t.getString("pay").isEmpty())
                continue;
            List<Line> lines = new ArrayList<>();
            NBTTagList lt = t.getTagList("lines", Constants.NBT.TAG_COMPOUND);
            for(int j = 0; j < lt.tagCount() && j < Cart.MAX_LINES; j++)
            {
                NBTTagCompound l = lt.getCompoundTagAt(j);
                NBTTagList g = l.getTagList("goods", Constants.NBT.TAG_COMPOUND);
                List<NBTTagCompound> goods = new ArrayList<>();
                for(int k = 0; k < g.tagCount(); k++)
                    goods.add(g.getCompoundTagAt(k));
                lines.add(new Line(l.getString("id"), l.getInteger("n"), goods));
            }
            Entry e = new Entry(t.getString("pay"), t.getLong("no"), t.getUniqueId("buyer"), t.getString("name"), t.getLong("at"), t.getLong("exp"),
                    t.getString("url"), Orders.readItems(t.getTagList("items", Constants.NBT.TAG_COMPOUND)), lines, t.getBoolean("cart"));
            e.state = "PAID".equals(t.getString("state")) ? State.PAID : State.OPEN;
            out.byPay.put(e.payId, e);
        }
        return out;
    }
}
