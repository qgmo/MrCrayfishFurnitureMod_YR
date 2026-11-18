package com.mrcrayfish.furniture.web.minebay;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.advancement.Triggers;
import moe.qingu.youran.api.pay.AccountRef;
import moe.qingu.youran.api.pay.OrderInfo;
import moe.qingu.youran.api.pay.OrderSpec;
import moe.qingu.youran.api.pay.PayApi;
import moe.qingu.youran.api.pay.PayException;
import moe.qingu.youran.api.pay.PayItem;
import moe.qingu.youran.api.pay.PaymentInfo;
import moe.qingu.youran.api.pay.PaymentListener;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MineBay 收钱、发货（服务端主线程；服主 11-15 改接 pay.yr）：
 * <ol>
 * <li>{@link #open}：结算时先检查库存（减去别人还没付的单预留的）、准备好要发的东西，再用 pay.yr 的 Java 接口开收款单（收款进家具模组的虚拟账户
 * {@value #ACCOUNT}，只许 MineBay 登录着的那名玩家付），记下来（预留库存）并当场写盘，把浏览器跳到 pay.yr；</li>
 * <li>付款成功的回调（{@link #paid}）：正式扣库存、记订单、从购物车里拿掉买走的、当场写盘，再发货；</li>
 * <li>发货（{@link #deliver}）：放进那名玩家的背包，满了掉在脚边；在玩家数据里记下这一单（标记），立即写玩家存档，再拿掉这张单并写盘。
 * 玩家不在线时等他上线再发。崩了以后：玩家存档里有标记 = 已经发了，没有 = 再发一次，所以不会发两次、也不会漏发。</li>
 * <li>每秒对账（{@link Events}）：还在等付款的单按 pay.yr 的状态处理——付了（回调丢了）就当付了；过期、取消就拿掉（放回预留的库存）。</li>
 * </ol>
 * 扣钱、记账、崩溃恢复都是 pay.yr 的事；MineBay 只用它的公开接口，不再按来源地址认操作者。
 */
public final class MineBayShop
{
    /** pay.yr 里收款的虚拟账户 */
    public static final String ACCOUNT = "cfm:minebay";
    /** 收款账户给人看的名字（pay.yr 的付款页、确认框、收支记录里） */
    public static final String ACCOUNT_NAME = "MineBay";
    /** 收款单多久过期（秒） */
    static final int ORDER_TTL_SECONDS = 600;
    /** 一单里最多发几叠物品（防模组回调出错一下子给太多） */
    static final int MAX_STACKS_PER_ORDER = 512;
    /** 玩家数据（ForgeData）里记“已经发过货的单”的键，最多留几条 */
    static final String MARKER = "cfm_minebay_delivered";
    static final int MAX_MARKERS = 64;

    private MineBayShop() {}

    public static void init()
    {
        PayApi.addListener(AccountRef.virtual(ACCOUNT), new PaymentListener()
        {
            @Override
            public void onPaid(OrderInfo order, PaymentInfo payment)
            {
                paid(order, payment);
            }
        });
        PayApi.setDisplayName(ACCOUNT, ACCOUNT_NAME);
        MinecraftForge.EVENT_BUS.register(new Events());
        MinecraftForge.EVENT_BUS.register(new Support.Events());
    }

    static AccountRef account()
    {
        return AccountRef.virtual(ACCOUNT);
    }

    /** 开单的结果：成功时 {@link #url} 是 pay.yr 付款页，否则 {@link #error} 是给人看的原因 */
    static final class Opened
    {
        @Nullable
        final String url;
        @Nullable
        final String error;
        @Nullable
        final PayOrders.Entry entry;

        private Opened(@Nullable String url, @Nullable String error, @Nullable PayOrders.Entry entry)
        {
            this.url = url;
            this.error = error;
            this.entry = entry;
        }

        static Opened fail(String why)
        {
            return new Opened(null, why, null);
        }
    }

    /**
     * 开 pay.yr 收款单（主线程）。{@code scheme} 是玩家打开 MineBay 用的协议（跳回来的网址用同样的）。
     * 这个玩家上一张还没付的单先取消（放回预留的库存）；上一张正在付的不让开新的。
     */
    static Opened open(MinecraftServer server, MineBayData data, Catalog catalog, UUID buyer, String buyerName, Checkout.Plan plan, boolean fromCart,
                       String scheme, long now)
    {
        if(!PayApi.isAvailable())
            return Opened.fail("pay.yr 现在不可用，暂时不能下单。");
        EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(buyer);
        if(player == null)
            return Opened.fail(buyerName + " 不在线，不能下单。");
        PayOrders orders = data.payOrders();
        PayOrders.Entry prev = orders.openOf(buyer);
        if(prev != null)
        {
            OrderInfo pi = PayApi.order(prev.payId);
            if(pi != null && pi.status == OrderInfo.Status.PAYING)
                return Opened.fail("上一张单正在 pay.yr 付款，付完或取消后再下新的。");
            if(pi != null && pi.status == OrderInfo.Status.PAID)
                paid(server, data, prev);
            else
            {
                PayApi.cancelOrder(prev.payId, account());
                orders.remove(prev.payId);
            }
        }
        if(orders.isFull())
            return Opened.fail("MineBay 现在太忙，请稍后再试。");
        for(Checkout.Item i : plan.items)
        {
            Listing l = catalog.listing(i.offer.id());
            long left = l == null ? -1 : data.remaining(l.id, l.stock, now);
            if(left >= 0 && left < i.quantity)
                return Opened.fail((l.name != null ? l.name : l.sortName) + " 库存只剩 " + left + " 份。");
        }
        int[] stacks = {0};
        List<List<ItemStack>> goods = Checkout.prepare(plan, item -> prepare(catalog, player, item, stacks));
        if(goods == null)
            return Opened.fail("有商品现在发不了货，没有下单。");
        long number = data.nextOrderNumber();
        String no = MineBayPages.number(number);
        // 不指定付款人（服主 11-15 信任模型）：谁在 pay.yr 上付都行，东西发给 MineBay 登录着的买家
        OrderSpec spec = new OrderSpec(account(), MineBaySite.DOMAIN).description("MineBay 订单 " + no).reference(no)
                .returnUrl(scheme + "://" + MineBaySite.DOMAIN + "/paid").cancelUrl(scheme + "://" + MineBaySite.DOMAIN + "/unpaid")
                .ttlSeconds(ORDER_TTL_SECONDS);
        try
        {
            for(Map.Entry<String, Long> e : plan.totals.entrySet())
            {
                ItemStack cur = currencyOf(catalog, plan, e.getKey());
                if(cur.isEmpty())
                    return Opened.fail("有商品的货币不对，没有下单。");
                spec.item(payItem(cur), e.getValue());
            }
        }
        catch(IllegalArgumentException | ArithmeticException e)
        {
            return Opened.fail("价格不对，没有下单。");
        }
        OrderInfo info;
        try
        {
            info = PayApi.createOrder(spec);
        }
        catch(PayException e)
        {
            MrCrayfishFurnitureMod.logger().warn("MineBay: pay.yr refused order {} of {}: {} {}", no, buyerName, e.code, e.getMessage());
            return Opened.fail("pay.yr 开不了单：" + e.getMessage());
        }
        List<Orders.Item> items = new ArrayList<>();
        List<PayOrders.Line> lines = new ArrayList<>();
        for(int k = 0; k < plan.items.size(); k++)
        {
            Checkout.Item i = plan.items.get(k);
            Listing l = (Listing) i.offer;
            items.add(new Orders.Item(l.id, l.icon, l.count, l.name, l.nameKey, l.sortName, i.quantity, l.currency, i.cost));
            List<NBTTagCompound> g = new ArrayList<>();
            for(ItemStack s : goods.get(k))
                g.add(s.writeToNBT(new NBTTagCompound()));
            lines.add(new PayOrders.Line(l.id, i.quantity, g));
        }
        PayOrders.Entry entry = new PayOrders.Entry(info.id, number, buyer, buyerName, now, info.expiresAt, info.checkoutUrl, items, lines, fromCart);
        orders.add(entry);
        data.markDirty();
        data.saveNow(server);
        MrCrayfishFurnitureMod.logger().info("MineBay: {} opened order {} ({}) for {}", buyerName, no, info.id, plan.quantities());
        return new Opened(info.checkoutUrl, null, entry);
    }

    static PayItem payItem(ItemStack currency)
    {
        return PayItem.of(String.valueOf(currency.getItem().getRegistryName()), currency.getMetadata());
    }

    static ItemStack currencyOf(Catalog catalog, Checkout.Plan plan, String currency)
    {
        for(Checkout.Item i : plan.items)
        {
            Catalog.Entry e = catalog.byId.get(i.offer.id());
            if(e != null && i.offer.currency().equals(currency))
                return e.currency;
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    private static List<ItemStack> prepare(Catalog catalog, EntityPlayerMP buyer, Checkout.Item item, int[] stacks)
    {
        Catalog.Entry e = catalog.byId.get(item.offer.id());
        if(e == null)
            return null;
        List<ItemStack> out = new ArrayList<>();
        if(e.delivery != null)
        {
            List<ItemStack> made = e.delivery.prepare(buyer, item.quantity);
            if(made == null)
                return null;
            for(ItemStack s : made)
            {
                if(s != null && !s.isEmpty())
                    out.addAll(split(s.copy(), s.getCount()));
            }
        }
        else if(!e.item.isEmpty())
            out.addAll(split(e.item, (long) e.item.getCount() * item.quantity));
        stacks[0] += out.size();
        if(stacks[0] > MAX_STACKS_PER_ORDER)
        {
            MrCrayfishFurnitureMod.logger().warn("MineBay: order for {} would hand out more than {} stacks", buyer.getName(), MAX_STACKS_PER_ORDER);
            return null;
        }
        return out;
    }

    /** 按最大堆叠数拆开（最多拆出 MAX_STACKS_PER_ORDER + 1 叠，多了由调用方拒绝） */
    static List<ItemStack> split(ItemStack unit, long total)
    {
        List<ItemStack> out = new ArrayList<>();
        int max = Math.max(1, unit.getMaxStackSize());
        long left = total;
        while(left > 0 && out.size() <= MAX_STACKS_PER_ORDER)
        {
            ItemStack s = unit.copy();
            int n = (int) Math.min(max, left);
            s.setCount(n);
            out.add(s);
            left -= n;
        }
        return out;
    }

    // ---------------------------------------------------------------- 付了以后

    /**
     * pay.yr 的收款回调（主线程）。至少来一次、可能重复（服主 11-18）：处理过的单（付了没发货、发完货拿掉了）认出来不再处理，
     * 也不当成不认识的单退款。MineBay 的存档还没读到时抛异常，pay.yr 过一阵再发（每秒的对账也会补上）
     */
    static void paid(OrderInfo order, PaymentInfo payment)
    {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        MineBayData data = MineBayData.get(server);
        if(data == null)
            throw new IllegalStateException("MineBay: data not loaded yet, payment " + payment.id + " not taken");
        PayOrders.Seen seen = data.payOrders().seen(order.id);
        if(seen == PayOrders.Seen.HANDLED)
        {
            MrCrayfishFurnitureMod.logger().info("MineBay: payment {} for order {} was already handled (notice sent again)", payment.id, order.id);
            return;
        }
        PayOrders.Entry e = data.payOrders().get(order.id);
        if(e == null)
        {
            if(payment.refundable().isZero())
                return;
            // 不是 MineBay 开的单（或者 MineBay 的存档丢了）：原样退回，不让钱白付
            MrCrayfishFurnitureMod.logger().error("MineBay: got payment {} for unknown order {} from {}; refunding", payment.id, order.id, payment.payerName);
            try
            {
                PayApi.refund(payment.id, payment.refundable(), "MineBay 找不到这张单，原样退回");
            }
            catch(PayException ex)
            {
                MrCrayfishFurnitureMod.logger().error("MineBay: could not refund {}: {}", payment.id, ex.getMessage());
            }
            return;
        }
        paid(server, data, e);
    }

    /** 记成付了：扣库存、记订单、拿掉购物车里买走的、写盘，再发货（买家在线时）。同一张单只做一次 */
    static void paid(MinecraftServer server, MineBayData data, PayOrders.Entry e)
    {
        if(!data.payOrders().markPaid(e.payId))
            return;
        long now = System.currentTimeMillis();
        Catalog catalog = Catalog.get();
        for(PayOrders.Line l : e.lines)
        {
            Listing li = catalog.listing(l.productId);
            Stock.State st = li == null ? null : data.stock(l.productId, li.stock, now);
            if(st != null)
                Stock.takeReserved(st, l.quantity);
        }
        data.record(e.buyer, new Orders.Order(e.number, now, e.items, e.payId));
        if(e.fromCart)
        {
            data.cart(e.buyer).removeBought(e.quantities());
            data.cartChanged(e.buyer);
        }
        data.markDirty();
        data.saveNow(server);
        MrCrayfishFurnitureMod.logger().info("MineBay: order {} ({}) of {} paid", MineBayPages.number(e.number), e.payId, e.buyerName);
        EntityPlayerMP p = server.getPlayerList().getPlayerByUUID(e.buyer);
        if(p != null)
            deliver(server, data, e, p);
    }

    /** 发货（主线程，买家在线）：放进背包 → 记标记、写玩家存档 → 拿掉这张单、写盘 → 通知发货回调 */
    static void deliver(MinecraftServer server, MineBayData data, PayOrders.Entry e, EntityPlayerMP player)
    {
        if(e.state() != PayOrders.State.PAID)
            return;
        if(hasMarker(player, e.payId))
        {
            // 上次发了、玩家存档也存下了，只是 MineBay 的存档没来得及写
            data.payOrders().remove(e.payId);
            data.saveNow(server);
            return;
        }
        Catalog catalog = Catalog.get();
        Map<PayOrders.Line, List<ItemStack>> stacks = new LinkedHashMap<>();
        for(PayOrders.Line l : e.lines)
        {
            List<ItemStack> g = new ArrayList<>();
            for(NBTTagCompound t : l.goods)
            {
                ItemStack s = new ItemStack(t);
                if(!s.isEmpty())
                    g.add(s);
            }
            stacks.put(l, g);
        }
        Checkout.deliver(e.lines, stacks::get, new Checkout.Shipper<PayOrders.Line, ItemStack>()
        {
            @Override
            public void give(PayOrders.Line line, List<ItemStack> goods)
            {
                for(ItemStack s : goods)
                    ItemHandlerHelper.giveItemToPlayer(player, s.copy());
            }

            @Override
            public void delivered(PayOrders.Line line, List<ItemStack> goods)
            {
                if(line == e.lines.get(0))
                {
                    // 第一行通知之前：先把“发过了”存进玩家存档，再拿掉这张单
                    addMarker(player, e.payId);
                    try
                    {
                        server.getWorld(0).getSaveHandler().getPlayerNBTManager().writePlayerData(player);
                    }
                    catch(RuntimeException ex)
                    {
                        MrCrayfishFurnitureMod.logger().error("MineBay: could not save player data of {} after delivery", player.getName(), ex);
                    }
                    data.payOrders().remove(e.payId);
                    data.saveNow(server);
                }
                Catalog.Entry ce = catalog.byId.get(line.productId);
                if(ce == null || ce.delivery == null)
                    return;
                try
                {
                    ce.delivery.delivered(player, line.quantity, Collections.unmodifiableList(goods));
                }
                catch(RuntimeException ex)
                {
                    MrCrayfishFurnitureMod.logger().error("MineBay: delivery callback of {} failed after delivery", line.productId, ex);
                }
            }
        });
        player.inventoryContainer.detectAndSendChanges();
        Triggers.trigger(Triggers.MINEBAY_PURCHASE, player);
        MrCrayfishFurnitureMod.logger().info("MineBay: delivered order {} to {}", MineBayPages.number(e.number), player.getName());
    }

    static boolean hasMarker(EntityPlayerMP p, String payId)
    {
        NBTTagList l = p.getEntityData().getTagList(MARKER, Constants.NBT.TAG_STRING);
        for(int i = 0; i < l.tagCount(); i++)
        {
            if(payId.equals(l.getStringTagAt(i)))
                return true;
        }
        return false;
    }

    static void addMarker(EntityPlayerMP p, String payId)
    {
        NBTTagList l = p.getEntityData().getTagList(MARKER, Constants.NBT.TAG_STRING);
        l.appendTag(new NBTTagString(payId));
        while(l.tagCount() > MAX_MARKERS)
            l.removeTag(0);
        p.getEntityData().setTag(MARKER, l);
    }

    /** 上线时：补发付了没发的；去掉已经不需要的标记（对应的单已经拿掉了） */
    static void onLogin(MinecraftServer server, EntityPlayerMP p)
    {
        MineBayData data = MineBayData.get(server);
        if(data == null)
            return;
        NBTTagList l = p.getEntityData().getTagList(MARKER, Constants.NBT.TAG_STRING);
        for(int i = l.tagCount() - 1; i >= 0; i--)
        {
            PayOrders.Entry e = data.payOrders().get(l.getStringTagAt(i));
            if(e == null || e.state() != PayOrders.State.PAID)
                l.removeTag(i);
        }
        for(PayOrders.Entry e : data.payOrders().paidOf(p.getUniqueID()))
            deliver(server, data, e, p);
    }

    /** 每秒：还在等付款的按 pay.yr 的状态对账；付了没发的、买家在线就发 */
    static void reconcile(MinecraftServer server)
    {
        if(!PayApi.isAvailable())
            return;
        MineBayData data = MineBayData.get(server);
        if(data == null)
            return;
        boolean changed = false;
        for(PayOrders.Entry e : data.payOrders().all())
        {
            if(e.state() == PayOrders.State.OPEN)
            {
                OrderInfo o = PayApi.order(e.payId);
                if(o == null || o.status == OrderInfo.Status.EXPIRED || o.status == OrderInfo.Status.CANCELED)
                {
                    data.payOrders().remove(e.payId);
                    changed = true;
                }
                else if(o.status == OrderInfo.Status.PAID)
                    paid(server, data, e);
            }
            else
            {
                EntityPlayerMP p = server.getPlayerList().getPlayerByUUID(e.buyer);
                if(p != null)
                    deliver(server, data, e, p);
            }
        }
        if(changed)
            data.markDirty();
    }

    /** 收款单的状态（给页面用）；pay.yr 不可用时为 null */
    @Nullable
    static OrderInfo.Status status(String payId)
    {
        OrderInfo o = PayApi.order(payId);
        return o == null ? null : o.status;
    }

    /** 玩家点“返回网站”没付：取消这张单（放回库存）。已经付了的照付了处理。返回 true 表示取消了 */
    static boolean cancel(MinecraftServer server, MineBayData data, PayOrders.Entry e)
    {
        if(e.state() != PayOrders.State.OPEN)
            return false;
        if(PayApi.cancelOrder(e.payId, account()))
        {
            data.payOrders().remove(e.payId);
            data.markDirty();
            return true;
        }
        if(status(e.payId) == OrderInfo.Status.PAID)
            paid(server, data, e);
        return false;
    }

    public static final class Events
    {
        private int ticks;

        @SubscribeEvent
        public void onLogin(PlayerEvent.PlayerLoggedInEvent e)
        {
            if(e.player instanceof EntityPlayerMP && e.player.getServer() != null)
                MineBayShop.onLogin(e.player.getServer(), (EntityPlayerMP) e.player);
        }

        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent e)
        {
            if(e.phase != TickEvent.Phase.END || ++ticks % 20 != 0)
                return;
            MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
            if(server != null)
            {
                try
                {
                    reconcile(server);
                }
                catch(RuntimeException ex)
                {
                    MrCrayfishFurnitureMod.logger().error("MineBay: reconcile failed", ex);
                }
            }
        }
    }
}
