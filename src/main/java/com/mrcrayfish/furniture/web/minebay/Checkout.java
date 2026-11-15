package com.mrcrayfish.furniture.web.minebay;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * 结算（纯逻辑，主线程上用）。钱不在这里扣：MineBay 开一张 {@code pay.yr} 收款单，玩家在 pay.yr 付，付款成功的回调里发货（服主 11-15）。
 * <ol>
 * <li>{@link #plan}：按购物车（或“立即购买”的一件）算出买哪些、每种货币一共多少；下架、超过一单上限、库存不够的留在购物车里不买；</li>
 * <li>{@link #prepare}：开单之前先把要发的东西全部准备好（模组商品的发货回调在这一步），有一样准备不了就不开单；</li>
 * <li>付款成功后 {@link #deliver}：发货（放进背包，满了掉脚下），最后通知发货回调。</li>
 * </ol>
 * 页面上确认时记下 {@link Plan#signature}，提交时重算一遍对得上才开单：确认之后购物车、价格变了就要重新确认。
 */
public final class Checkout
{
    private Checkout() {}

    /** 一件在卖的商品（结算要知道的部分） */
    public interface Offer
    {
        String id();

        String currency();

        long unitPrice();

        int maxPerOrder();
    }

    public enum Unavailable
    {
        /** 下架了 */
        GONE,
        /** 车里的份数超过了这件商品现在的上限 */
        LIMIT,
        /** 库存不够 */
        STOCK
    }

    public static final class Item
    {
        public final Offer offer;
        public final int quantity;
        public final long cost;

        Item(Offer offer, int quantity)
        {
            this.offer = offer;
            this.quantity = quantity;
            this.cost = offer.unitPrice() * quantity;
        }
    }

    public static final class Plan
    {
        public final List<Item> items;
        /** 买不了的（商品编号 → 原因），留在购物车里 */
        public final Map<String, Unavailable> unavailable;
        /** 货币 → 一共要付多少（按出现顺序） */
        public final Map<String, Long> totals;
        public final String signature;

        Plan(List<Item> items, Map<String, Unavailable> unavailable)
        {
            this.items = Collections.unmodifiableList(items);
            this.unavailable = Collections.unmodifiableMap(unavailable);
            Map<String, Long> t = new LinkedHashMap<>();
            StringBuilder sig = new StringBuilder();
            for(Item i : items)
            {
                t.merge(i.offer.currency(), i.cost, Long::sum);
                sig.append(i.offer.id()).append('*').append(i.quantity).append('@').append(i.offer.currency()).append('=').append(i.offer.unitPrice()).append(';');
            }
            this.totals = Collections.unmodifiableMap(t);
            this.signature = sig.toString();
        }

        public boolean isEmpty()
        {
            return items.isEmpty();
        }

        /** 商品编号 → 买了几份 */
        public Map<String, Integer> quantities()
        {
            Map<String, Integer> out = new LinkedHashMap<>();
            for(Item i : items)
                out.put(i.offer.id(), i.quantity);
            return out;
        }
    }

    /** 不限库存 */
    public static final ToLongFunction<String> UNLIMITED = id -> -1;

    /** 按购物车算（不看库存） */
    public static Plan plan(List<Cart.Line> lines, Function<String, ? extends Offer> lookup)
    {
        return plan(lines, lookup, UNLIMITED);
    }

    /** 按购物车算；{@code remaining} 给出每件商品现在还能卖几份（-1 为不限） */
    public static Plan plan(List<Cart.Line> lines, Function<String, ? extends Offer> lookup, ToLongFunction<String> remaining)
    {
        List<Item> items = new ArrayList<>();
        Map<String, Unavailable> unavailable = new LinkedHashMap<>();
        for(Cart.Line l : lines)
        {
            Offer o = lookup.apply(l.id);
            if(o == null || o.unitPrice() <= 0)
                unavailable.put(l.id, Unavailable.GONE);
            else if(l.quantity() > Cart.limit(o.maxPerOrder()))
                unavailable.put(l.id, Unavailable.LIMIT);
            else if(isShort(remaining.applyAsLong(l.id), l.quantity()))
                unavailable.put(l.id, Unavailable.STOCK);
            else
                items.add(new Item(o, l.quantity()));
        }
        return new Plan(items, unavailable);
    }

    private static boolean isShort(long remaining, int quantity)
    {
        return remaining >= 0 && remaining < quantity;
    }

    /** 立即购买一件（不经过购物车，不看库存）；买不了返回 null */
    @Nullable
    public static Plan single(@Nullable Offer offer, int quantity)
    {
        return single(offer, quantity, -1);
    }

    /** 立即购买一件；{@code remaining} 是现在还能卖几份（-1 为不限）；买不了返回 null */
    @Nullable
    public static Plan single(@Nullable Offer offer, int quantity, long remaining)
    {
        if(offer == null || offer.unitPrice() <= 0 || quantity < 1 || quantity > Cart.limit(offer.maxPerOrder()) || isShort(remaining, quantity))
            return null;
        List<Item> items = new ArrayList<>();
        items.add(new Item(offer, quantity));
        return new Plan(items, Collections.<String, Unavailable>emptyMap());
    }

    /** 准备要发的东西（开单之前；不能改游戏状态）。准备不了返回 null 或抛异常 */
    public interface Preparer<G>
    {
        @Nullable
        List<G> prepare(Item item) throws Exception;
    }

    /**
     * 全有或全无地准备：每件都准备好才返回（和 {@code plan.items} 一一对应）；有一样准备不了（null、空、抛异常）或没有能买的返回 null。
     */
    @Nullable
    public static <G> List<List<G>> prepare(Plan plan, Preparer<G> preparer)
    {
        if(plan.isEmpty())
            return null;
        List<List<G>> goods = new ArrayList<>();
        for(Item i : plan.items)
        {
            List<G> g;
            try
            {
                g = preparer.prepare(i);
            }
            catch(Exception e)
            {
                return null;
            }
            if(g == null || g.isEmpty())
                return null;
            goods.add(g);
        }
        return goods;
    }

    /** 发货（付款成功以后，主线程） */
    public interface Shipper<L, G>
    {
        /** 放进背包（满了掉脚下，不会失败） */
        void give(L line, List<G> goods);

        /** 全部发完以后（通知发货回调；出错只记日志） */
        void delivered(L line, List<G> goods);
    }

    /** 先把每一行都发了，再逐行通知（一单里的东西要么都发、要么都不发） */
    public static <L, G> void deliver(List<L> lines, Function<L, List<G>> goods, Shipper<L, G> shipper)
    {
        for(L l : lines)
            shipper.give(l, goods.apply(l));
        for(L l : lines)
            shipper.delivered(l, goods.apply(l));
    }
}
