package com.mrcrayfish.furniture.web.minebay;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 结算（纯逻辑，主线程上用）：先按购物车（或“立即购买”的一件）算出 {@link Plan}——买哪些、每种货币一共多少；
 * 再 {@link #run} 全有或全无地执行：
 * <ol>
 * <li>每种货币都够才往下走，不够一样也不动（{@link Outcome#POOR}）；</li>
 * <li>先把要发的东西全部准备好（模组商品的发货回调在这一步），有一样准备不了就整单取消，不扣钱（{@link Outcome#FAILED}）；</li>
 * <li>扣钱；万一扣不齐（不该发生：同一线程上刚数过），扣了的原样退回，整单取消；</li>
 * <li>发货（放进背包，满了掉脚下，不会失败），最后通知发货回调。</li>
 * </ol>
 * 页面上确认时记下 {@link Plan#signature}，提交时重算一遍对得上才执行：确认之后购物车、价格变了就不买，要重新确认。
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
        LIMIT
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

        /** 钱够不够：have 给出每种货币背包里有多少 */
        public boolean affordable(Function<String, Long> have)
        {
            for(Map.Entry<String, Long> e : totals.entrySet())
            {
                if(have.apply(e.getKey()) < e.getValue())
                    return false;
            }
            return true;
        }
    }

    /** 按购物车算 */
    public static Plan plan(List<Cart.Line> lines, Function<String, ? extends Offer> lookup)
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
            else
                items.add(new Item(o, l.quantity()));
        }
        return new Plan(items, unavailable);
    }

    /** 立即购买一件（不经过购物车）；买不了返回 null */
    @Nullable
    public static Plan single(@Nullable Offer offer, int quantity)
    {
        if(offer == null || offer.unitPrice() <= 0 || quantity < 1 || quantity > Cart.limit(offer.maxPerOrder()))
            return null;
        List<Item> items = new ArrayList<>();
        items.add(new Item(offer, quantity));
        return new Plan(items, Collections.<String, Unavailable>emptyMap());
    }

    /** 执行结算时要用到的游戏状态（主线程；测试里用假的） */
    public interface Till<G>
    {
        /** 背包里这种货币有多少 */
        long have(String currency);

        /** 准备要发的东西（不能改游戏状态）；准备不了返回 null 或抛异常 */
        @Nullable
        List<G> prepare(Item item) throws Exception;

        /** 扣钱，返回实际扣了多少 */
        long take(String currency, long amount);

        /** 退回扣了的钱 */
        void refund(String currency, long amount);

        /** 发货 */
        void give(Item item, List<G> goods);

        /** 发完以后（通知发货回调；出错只记日志） */
        void delivered(Item item, List<G> goods);
    }

    public enum Outcome
    {
        OK, EMPTY, POOR, FAILED
    }

    public static <G> Outcome run(Plan plan, Till<G> till)
    {
        if(plan.isEmpty())
            return Outcome.EMPTY;
        for(Map.Entry<String, Long> e : plan.totals.entrySet())
        {
            if(e.getValue() <= 0 || till.have(e.getKey()) < e.getValue())
                return e.getValue() <= 0 ? Outcome.FAILED : Outcome.POOR;
        }
        List<List<G>> goods = new ArrayList<>();
        for(Item i : plan.items)
        {
            List<G> g;
            try
            {
                g = till.prepare(i);
            }
            catch(Exception e)
            {
                return Outcome.FAILED;
            }
            if(g == null || g.isEmpty())
                return Outcome.FAILED;
            goods.add(g);
        }
        Map<String, Long> taken = new LinkedHashMap<>();
        for(Map.Entry<String, Long> e : plan.totals.entrySet())
        {
            long got = till.take(e.getKey(), e.getValue());
            if(got > 0)
                taken.put(e.getKey(), got);
            if(got != e.getValue())
            {
                for(Map.Entry<String, Long> t : taken.entrySet())
                    till.refund(t.getKey(), t.getValue());
                return Outcome.FAILED;
            }
        }
        for(int k = 0; k < plan.items.size(); k++)
            till.give(plan.items.get(k), goods.get(k));
        for(int k = 0; k < plan.items.size(); k++)
            till.delivered(plan.items.get(k), goods.get(k));
        return Outcome.OK;
    }
}
