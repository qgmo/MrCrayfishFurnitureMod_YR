package com.mrcrayfish.furniture.web.minebay;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.advancement.Triggers;
import com.mrcrayfish.furniture.computer.ComputerNetwork;
import com.mrcrayfish.furniture.computer.FormEncoding;
import com.mrcrayfish.furniture.computer.RateLimiter;
import com.mrcrayfish.furniture.tileentity.TileEntityComputer;
import com.mrcrayfish.furniture.util.RejectLog;
import com.mrcrayfish.furniture.web.WebContext;
import com.mrcrayfish.furniture.web.WebHandler;
import com.mrcrayfish.furniture.web.WebRequest;
import com.mrcrayfish.furniture.web.WebResponse;
import com.mrcrayfish.furniture.web.Websites;
import moe.qingu.youran.api.network.Ipv6Address;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * MineBay 网站（{@code minebay.yr}，主机登记名 {@value #HOST_KEY}）：做成一个购物网站。
 * <ul>
 * <li>页面：首页 / 分类 / 搜索（{@code GET /?c=&q=&s=&p=}）、商品详情（{@code /item?id=}）、购物车（{@code /cart}）、
 * 结算确认（{@code /checkout}，立即购买为 {@code /checkout?buy=&n=}）、我的订单（{@code /orders}、{@code /order?no=}）、帮助（{@code /help}）。</li>
 * <li>改东西的都是 POST：{@code /cart/add}、{@code /cart/update}、{@code /checkout}。成功后 303 跳到结果页；
 * 失败时直接回一页（4xx）说明原因，玩家填的数量原样填回去（浏览器也保留原页面表单里的内容）。</li>
 * <li>谁在买：按请求的来源地址找回电脑（{@link ComputerNetwork#computerAt}），再找它的操作者：电脑要有电，操作者要开着这台电脑的界面、
 * 在 8 格内。购物车、订单按操作者的 UUID 存在存档里（{@link MineBayData}）。</li>
 * <li>安全：POST 表单带按来源地址发的随机令牌（防别的网站骗点）；结算确认页另发一个一次性的确认码，绑定玩家与确认时的订单内容，
 * 提交时对不上（重复提交、确认后改了购物车或价格）就不扣钱；所有参数先校验；按地址、按玩家限频；扣钱发货在主线程上全有或全无（{@link Checkout}）。</li>
 * </ul>
 * 网络线程上只做路由、令牌、按地址限频；其余在主线程（{@link WebContext#onMainThread}）。
 */
public final class MineBaySite implements WebHandler
{
    public static final String HOST_KEY = "cfm:minebay";
    public static final String DOMAIN = "minebay.yr";
    static final int MAX_TOKENS = 1024;
    /** 确认页的确认码多久失效 */
    static final long CONFIRM_TTL_MS = 10 * 60 * 1000L;
    /** 一单里最多发几叠物品（防模组回调出错一下子给太多） */
    static final int MAX_STACKS_PER_ORDER = 512;

    private final SecureRandom random = new SecureRandom();
    /** 网络线程：来源地址 → 表单令牌 */
    private final Map<Ipv6Address, String> tokens = lru(MAX_TOKENS);
    /** 网络线程：来源地址 → 限频（每秒 10 次，突发 20） */
    private final Map<Ipv6Address, RateLimiter> addressLimits = lru(MAX_TOKENS);
    /** 主线程：玩家 → 看页面限频（每秒 6 次，突发 12）、改东西限频（每秒 3 次，突发 6） */
    private final Map<UUID, RateLimiter> viewLimits = lru(1024);
    private final Map<UUID, RateLimiter> changeLimits = lru(1024);
    /** 主线程：玩家 → 待确认的订单 */
    private final Map<UUID, Pending> pending = lru(1024);
    /** 主线程：玩家 → 最近一次已经下单的确认码（重复提交时认出来） */
    private final Map<UUID, Done> done = lru(1024);

    private static <K, V> Map<K, V> lru(int max)
    {
        return new LinkedHashMap<K, V>(64, 0.75f, true)
        {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest)
            {
                return size() > max;
            }
        };
    }

    static final class Pending
    {
        final String nonce;
        final String signature;
        @Nullable
        final String buyId;
        final int buyQuantity;
        final long created;

        Pending(String nonce, String signature, @Nullable String buyId, int buyQuantity, long created)
        {
            this.nonce = nonce;
            this.signature = signature;
            this.buyId = buyId;
            this.buyQuantity = buyQuantity;
            this.created = created;
        }
    }

    static final class Done
    {
        final String nonce;
        final long order;

        Done(String nonce, long order)
        {
            this.nonce = nonce;
            this.order = order;
        }
    }

    public static void register()
    {
        Websites.register(HOST_KEY, DOMAIN, new MineBaySite());
    }

    // ---------------------------------------------------------------- 网络线程

    enum Route
    {
        HOME("/", false), ITEM("/item", false), CART("/cart", false), CART_ADD("/cart/add", true), CART_UPDATE("/cart/update", true),
        CHECKOUT("/checkout", false), CHECKOUT_POST("/checkout", true), ORDERS("/orders", false), ORDER("/order", false), HELP("/help", false);

        final String path;
        final boolean post;

        Route(String path, boolean post)
        {
            this.path = path;
            this.post = post;
        }

        @Nullable
        static Route of(String path, boolean post)
        {
            String p = path.equals("/index.xhtml") ? "/" : path;
            for(Route r : values())
            {
                if(r.path.equals(p) && r.post == post)
                    return r;
            }
            return null;
        }

        static boolean exists(String path)
        {
            return of(path, true) != null || of(path, false) != null;
        }
    }

    /** 请求里的参数（先表单、后查询串，同名取第一个；最多 32 个） */
    static Map<String, String> params(WebRequest request)
    {
        Map<String, String> out = new HashMap<>();
        List<Map.Entry<String, String>> all = new ArrayList<>(request.form());
        all.addAll(request.queryParameters());
        for(Map.Entry<String, String> e : all)
        {
            if(out.size() >= 32)
                break;
            out.putIfAbsent(e.getKey(), e.getValue());
        }
        return out;
    }

    @Override
    public CompletableFuture<WebResponse> handle(WebRequest request, WebContext context)
    {
        if(!addressLimits.computeIfAbsent(request.remoteAddress, a -> new RateLimiter(10, 20)).tryAcquire(System.currentTimeMillis(), 1))
            return CompletableFuture.completedFuture(WebResponse.error(429, "Too Many Requests"));
        if(request.path.equals("/buy"))
        {
            // 改版前的购买表单（还开着旧页面的）：回首页，什么都不买
            return CompletableFuture.completedFuture(WebResponse.seeOther("/"));
        }
        boolean post = request.method.equals("POST");
        if(!post && !request.method.equals("GET"))
            return CompletableFuture.completedFuture(WebResponse.error(405, "Method Not Allowed"));
        Route route = Route.of(request.path, post);
        if(route == null && Route.exists(request.path))
            return CompletableFuture.completedFuture(WebResponse.error(405, "Method Not Allowed"));
        String token = tokenFor(request.remoteAddress);
        Map<String, String> params = params(request);
        boolean tokenOk = !post || token.equals(params.get("t"));
        Ipv6Address from = request.remoteAddress;
        return context.onMainThread(() ->
        {
            try
            {
                Session s = session(from, token);
                return route == null ? notFound(s) : handleMain(route, params, s, tokenOk);
            }
            catch(RuntimeException e)
            {
                MrCrayfishFurnitureMod.logger().error("MineBay: failed to handle {} {}", request.method, request.path, e);
                return WebResponse.error(500, "Internal Server Error");
            }
        });
    }

    private String tokenFor(Ipv6Address address)
    {
        String t = tokens.get(address);
        if(t == null)
        {
            t = randomHex();
            tokens.put(address, t);
        }
        return t;
    }

    private String randomHex()
    {
        byte[] b = new byte[16];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for(byte x : b)
            sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    // ---------------------------------------------------------------- 主线程

    /** 这次请求是谁发的 */
    static final class Session
    {
        /** 能买东西的操作者；电脑没电、人走远、没人操作时为 null */
        @Nullable
        final EntityPlayerMP player;
        /** player 为 null 的原因 */
        final String why;
        @Nullable
        final MineBayData data;
        final String token;

        Session(@Nullable EntityPlayerMP player, String why, @Nullable MineBayData data, String token)
        {
            this.player = player;
            this.why = why;
            this.data = data;
            this.token = token;
        }

        MineBayPages.Viewer viewer()
        {
            return new MineBayPages.Viewer(player == null ? null : player.getName(), player == null || data == null ? 0 : data.cart(player.getUniqueID()).totalQuantity(), token);
        }
    }

    static Session session(Ipv6Address from, String token)
    {
        MineBayData data = MineBayData.get(FMLCommonHandler.instance().getMinecraftServerInstance());
        TileEntityComputer computer = ComputerNetwork.computerAt(from);
        if(computer == null)
            return new Session(null, "只有家具电脑能在 MineBay 买东西。", data, token);
        EntityPlayerMP player = computer.getOperatorPlayer();
        if(player == null)
            return new Session(null, "这台电脑现在没有人在操作：要开着电脑的界面才能买东西。", data, token);
        if(!computer.getAppliance().isRunning())
            return new Session(null, "电脑没电了。", data, token);
        if(player.getDistanceSq(computer.getPos().getX() + 0.5, computer.getPos().getY() + 0.5, computer.getPos().getZ() + 0.5) > 64.0)
            return new Session(null, "离电脑太远了。", data, token);
        return new Session(player, "", data, token);
    }

    private WebResponse handleMain(Route route, Map<String, String> params, Session s, boolean tokenOk)
    {
        long now = System.currentTimeMillis();
        if(s.player != null)
        {
            Map<UUID, RateLimiter> limits = route.post ? changeLimits : viewLimits;
            RateLimiter l = limits.computeIfAbsent(s.player.getUniqueID(), k -> route.post ? new RateLimiter(3, 6) : new RateLimiter(6, 12));
            if(!l.tryAcquire(now, 1))
            {
                RejectLog.log(s.player, "MineBay requests too fast (" + route + ")");
                return page(429, "Too Many Requests", MineBayPages.problem(s.viewer(), "操作太快了", "请过一两秒再试。你填的内容没有丢：按“后退”回到原来的页面再提交一次。"));
            }
        }
        if(route.post)
        {
            if(!tokenOk)
            {
                if(s.player != null)
                    RejectLog.log(s.player, "MineBay form with a bad token (" + route + ")");
                return page(403, "Forbidden", MineBayPages.problem(s.viewer(), "页面过期了", "这个请求不是从 MineBay 当前的页面发出的，没有处理。请回到上一页刷新后再试。"));
            }
            if(s.player == null || s.data == null)
                return page(403, "Forbidden", MineBayPages.problem(s.viewer(), "现在不能买东西", s.why.isEmpty() ? "服务器还没准备好。" : s.why));
        }
        switch(route)
        {
            case HOME: return home(params, s);
            case ITEM: return item(params, s);
            case CART: return cart(params, s);
            case CART_ADD: return cartAdd(params, s);
            case CART_UPDATE: return cartUpdate(params, s);
            case CHECKOUT: return checkout(params, s, now);
            case CHECKOUT_POST: return checkoutPost(params, s, now);
            case ORDERS: return orders(params, s);
            case ORDER: return order(params, s);
            case HELP: return WebResponse.xhtml(MineBayPages.help(s.viewer()));
            default: return notFound(s);
        }
    }

    static WebResponse page(int status, String reason, String html)
    {
        return WebResponse.xhtml(status, reason, html);
    }

    private static WebResponse notFound(Session s)
    {
        return page(404, "Not Found", MineBayPages.problem(s.viewer(), "找不到这个页面", "网址不对，或者页面已经不在了。"));
    }

    private static WebResponse badRequest(Session s, String text)
    {
        return page(400, "Bad Request", MineBayPages.problem(s.viewer(), "网址里的参数不对", text));
    }

    private static WebResponse goneItem(Session s)
    {
        return page(404, "Not Found", MineBayPages.problem(s.viewer(), "找不到这件商品", "它可能已经下架了。"));
    }

    private static WebResponse home(Map<String, String> params, Session s)
    {
        String c = params.get("c");
        if(c != null && c.isEmpty())
            c = null;
        if(c != null && Categories.get(c) == null)
            return badRequest(s, "没有这个分类。");
        String q = ShopParams.query(params.get("q"));
        if(q == null)
            return badRequest(s, "搜索词最多 " + ShopParams.MAX_QUERY + " 个字，不能有控制字符。");
        ShopParams.Sort sort = ShopParams.Sort.parse(params.get("s"));
        if(sort == null)
            return badRequest(s, "没有这种排序。");
        int p = ShopParams.page(params.get("p"));
        if(p < 0)
            return badRequest(s, "页码要是 1 到 " + ShopParams.MAX_PAGE + " 的整数。");
        Catalog catalog = Catalog.get();
        List<Listing> shown = ShopParams.select(catalog.listings, c, q, sort);
        return WebResponse.xhtml(MineBayPages.list(s.viewer(), catalog.listings, shown, c, q, sort, p, null));
    }

    private static WebResponse item(Map<String, String> params, Session s)
    {
        String id = params.get("id");
        if(!ShopParams.isProductId(id))
            return badRequest(s, "商品编号不对。");
        Listing l = Catalog.get().listing(id);
        if(l == null)
            return goneItem(s);
        return WebResponse.xhtml(MineBayPages.item(s.viewer(), l, inCart(s, id), null, null));
    }

    private static int inCart(Session s, String id)
    {
        return s.player == null || s.data == null ? 0 : s.data.cart(s.player.getUniqueID()).quantityOf(id);
    }

    private static WebResponse cart(Map<String, String> params, Session s)
    {
        MineBayPages.Notice notice = null;
        String r = params.get("r");
        if("added".equals(r))
        {
            Listing l = ShopParams.isProductId(params.get("id")) ? Catalog.get().listing(params.get("id")) : null;
            notice = MineBayPages.Notice.ok(l == null ? "已加入购物车。" : "已加入购物车：" + l.sortName + "。");
        }
        else if("updated".equals(r))
            notice = MineBayPages.Notice.ok("数量改好了。");
        else if("removed".equals(r))
            notice = MineBayPages.Notice.ok("已从购物车删除。");
        return cartPage(200, "OK", s, notice, Collections.<String, String>emptyMap());
    }

    private static WebResponse cartPage(int status, String reason, Session s, @Nullable MineBayPages.Notice notice, Map<String, String> typed)
    {
        Catalog catalog = Catalog.get();
        Cart cart = s.player == null || s.data == null ? new Cart() : s.data.cart(s.player.getUniqueID());
        Checkout.Plan plan = Checkout.plan(cart.lines(), catalog::listing);
        return page(status, reason, MineBayPages.cart(s.viewer(), cart, catalog::listing, plan, notice, typed));
    }

    private static WebResponse cartAdd(Map<String, String> params, Session s)
    {
        String id = params.get("id");
        if(!ShopParams.isProductId(id))
            return badRequest(s, "商品编号不对。");
        Listing l = Catalog.get().listing(id);
        if(l == null)
            return goneItem(s);
        int max = Cart.limit(l.maxPerOrder);
        String typed = params.get("n");
        int n = ShopParams.quantity(typed, max);
        if(n < 0)
            return page(422, "Unprocessable Entity", MineBayPages.item(s.viewer(), l, inCart(s, id), MineBayPages.Notice.bad("数量要填 1 到 " + max + " 之间的整数。"), typed));
        if("buy".equals(params.get("go")))
            return WebResponse.seeOther("/checkout?buy=" + urlPart(id) + "&n=" + n);
        UUID uuid = s.player.getUniqueID();
        Cart cart = s.data.cartForChange(uuid);
        if(cart == null)
            return page(503, "Service Unavailable", MineBayPages.problem(s.viewer(), "购物车暂时用不了", "服务器上存的购物车太多了，请告诉服主。"));
        Cart.Change c = cart.add(id, n, l.maxPerOrder);
        s.data.cartChanged(uuid);
        switch(c)
        {
            case OK:
                return WebResponse.seeOther("/cart?r=added&id=" + urlPart(id));
            case FULL:
                return cartPage(409, "Conflict", s, MineBayPages.Notice.bad("购物车满了（最多 " + Cart.MAX_LINES + " 种商品），没有加进去。先结算或删掉一些再加。"), Collections.<String, String>emptyMap());
            case TOO_MANY:
                return page(409, "Conflict", MineBayPages.item(s.viewer(), l, inCart(s, id),
                        MineBayPages.Notice.bad("购物车里已有 " + cart.quantityOf(id) + " 份，这件最多放 " + max + " 份，没有加进去。"), typed));
            default:
                return page(422, "Unprocessable Entity", MineBayPages.item(s.viewer(), l, inCart(s, id), MineBayPages.Notice.bad("数量要填 1 到 " + max + " 之间的整数。"), typed));
        }
    }

    private static WebResponse cartUpdate(Map<String, String> params, Session s)
    {
        String id = params.get("id");
        if(!ShopParams.isProductId(id))
            return badRequest(s, "商品编号不对。");
        UUID uuid = s.player.getUniqueID();
        Cart cart = s.data.cart(uuid);
        if(cart.quantityOf(id) == 0)
            return cartPage(409, "Conflict", s, MineBayPages.Notice.bad("购物车里没有这件商品（可能已经删掉或买走了）。"), Collections.<String, String>emptyMap());
        String act = params.get("act");
        if(act == null || act.isEmpty())
            act = "set";
        Listing l = Catalog.get().listing(id);
        switch(act)
        {
            case "remove":
                // 删除要先确认：玩家放进购物车的东西不经确认不丢
                return page(200, "OK", MineBayPages.removeConfirm(s.viewer(), id, l, cart.quantityOf(id)));
            case "remove_yes":
                cart.remove(id);
                s.data.cartChanged(uuid);
                return WebResponse.seeOther("/cart?r=removed");
            case "set":
            {
                if(l == null)
                    return cartPage(409, "Conflict", s, MineBayPages.Notice.bad("这件商品已经下架，不能改数量，只能删除。"), Collections.<String, String>emptyMap());
                String typed = params.get("n");
                int max = Cart.limit(l.maxPerOrder);
                int n = ShopParams.quantity(typed, max);
                if(n < 0 || cart.set(id, n, l.maxPerOrder) != Cart.Change.OK)
                    return cartPage(422, "Unprocessable Entity", s, MineBayPages.Notice.bad("数量要填 1 到 " + max + " 之间的整数（不想要了请点“删除”）。"),
                            Collections.singletonMap(id, typed == null ? "" : typed));
                s.data.cartChanged(uuid);
                return WebResponse.seeOther("/cart?r=updated");
            }
            default:
                return badRequest(s, "不认识的操作。");
        }
    }

    private WebResponse checkout(Map<String, String> params, Session s, long now)
    {
        if(s.player == null || s.data == null)
            return page(403, "Forbidden", MineBayPages.problem(s.viewer(), "现在不能结算", s.why.isEmpty() ? "服务器还没准备好。" : s.why));
        Catalog catalog = Catalog.get();
        String buy = params.get("buy");
        Checkout.Plan plan;
        int qty = 0;
        if(buy != null)
        {
            if(!ShopParams.isProductId(buy))
                return badRequest(s, "商品编号不对。");
            Listing l = catalog.listing(buy);
            if(l == null)
                return goneItem(s);
            qty = ShopParams.quantity(params.get("n"), Cart.limit(l.maxPerOrder));
            if(qty < 0)
                return badRequest(s, "数量要填 1 到 " + Cart.limit(l.maxPerOrder) + " 之间的整数。");
            plan = Checkout.single(l, qty);
            if(plan == null)
                return badRequest(s, "这件商品现在买不了。");
        }
        else
        {
            plan = Checkout.plan(s.data.cart(s.player.getUniqueID()).lines(), catalog::listing);
            if(plan.isEmpty())
                return WebResponse.seeOther("/cart");
        }
        return checkoutPage(200, "OK", s, plan, buy, qty, null, now);
    }

    private WebResponse checkoutPage(int status, String reason, Session s, Checkout.Plan plan, @Nullable String buy, int qty, @Nullable MineBayPages.Notice notice, long now)
    {
        Catalog catalog = Catalog.get();
        Map<String, Long> have = new HashMap<>();
        for(String cur : plan.totals.keySet())
            have.put(cur, count(s.player, currencyOf(catalog, plan, cur)));
        String nonce = null;
        if(plan.affordable(c -> have.getOrDefault(c, 0L)))
        {
            nonce = randomHex();
            pending.put(s.player.getUniqueID(), new Pending(nonce, plan.signature, buy, qty, now));
        }
        else
            pending.remove(s.player.getUniqueID());
        return page(status, reason, MineBayPages.checkout(s.viewer(), plan, catalog::listing, have, nonce, buy, notice));
    }

    private WebResponse checkoutPost(Map<String, String> params, Session s, long now)
    {
        UUID uuid = s.player.getUniqueID();
        String k = params.get("k");
        Done d = done.get(uuid);
        if(k != null && d != null && d.nonce.equals(k))
            return WebResponse.seeOther("/orders?done=" + d.order);
        Pending p = pending.get(uuid);
        if(k == null || p == null || !p.nonce.equals(k) || now - p.created > CONFIRM_TTL_MS)
        {
            RejectLog.log(s.player, "MineBay checkout with a stale or unknown confirmation");
            return page(409, "Conflict", MineBayPages.problem(s.viewer(), "确认页过期了", "这张确认页已经失效（超过 10 分钟，或者之后又打开过别的确认页）。什么都没有扣，请重新结算。"));
        }
        pending.remove(uuid);
        Catalog catalog = Catalog.get();
        Cart cart = s.data.cart(uuid);
        Checkout.Plan plan = p.buyId == null ? Checkout.plan(cart.lines(), catalog::listing) : Checkout.single(catalog.listing(p.buyId), p.buyQuantity);
        if(plan == null || plan.isEmpty())
            return page(409, "Conflict", MineBayPages.problem(s.viewer(), "没有能买的商品", "确认之后商品下架了，或者购物车被改空了。什么都没有扣。"));
        if(!plan.signature.equals(p.signature))
            return checkoutPage(409, "Conflict", s, plan, p.buyId, p.buyQuantity, MineBayPages.Notice.bad("确认之后购物车或价格变了，这次没有下单、什么都没有扣。请核对下面的新订单，再确认一次。"), now);
        PlayerTill till = new PlayerTill(s.player, catalog, plan);
        Checkout.Outcome outcome = Checkout.run(plan, till);
        switch(outcome)
        {
            case OK:
            {
                List<Orders.Item> items = new ArrayList<>();
                for(Checkout.Item i : plan.items)
                {
                    Listing l = (Listing) i.offer;
                    items.add(new Orders.Item(l.id, l.icon, l.count, l.name, l.sortName, i.quantity, l.currency, i.cost));
                }
                Orders.Order order = s.data.record(uuid, System.currentTimeMillis(), items);
                if(p.buyId == null)
                {
                    cart.removeBought(plan.quantities());
                    s.data.cartChanged(uuid);
                }
                done.put(uuid, new Done(k, order.number));
                s.player.inventoryContainer.detectAndSendChanges();
                Triggers.trigger(Triggers.MINEBAY_PURCHASE, s.player);
                MrCrayfishFurnitureMod.logger().info("MineBay: {} placed order {}: {} paid {}", s.player.getName(), MineBayPages.number(order.number), plan.quantities(), plan.totals);
                return WebResponse.seeOther("/orders?done=" + order.number);
            }
            case POOR:
                return checkoutPage(402, "Payment Required", s, plan, p.buyId, p.buyQuantity, MineBayPages.Notice.bad("背包里的钱不够，这一单没有下。什么都没有扣。"), now);
            default:
                MrCrayfishFurnitureMod.logger().warn("MineBay: order of {} could not be prepared: {}", s.player.getName(), plan.quantities());
                return page(500, "Internal Server Error", MineBayPages.problem(s.viewer(), "这一单没能完成", "有商品现在发不了货。这一单没有下，什么都没有扣，购物车原样保留。"));
        }
    }

    private static WebResponse orders(Map<String, String> params, Session s)
    {
        int p = ShopParams.page(params.get("p"));
        if(p < 0)
            return badRequest(s, "页码要是 1 到 " + ShopParams.MAX_PAGE + " 的整数。");
        long highlight = -1;
        MineBayPages.Notice notice = null;
        List<Orders.Order> list = s.player == null || s.data == null ? Collections.<Orders.Order>emptyList() : s.data.orders(s.player.getUniqueID()).list();
        if(params.containsKey("done"))
        {
            long no = ShopParams.orderNumber(params.get("done"));
            if(no < 0)
                return badRequest(s, "订单号不对。");
            for(Orders.Order o : list)
            {
                if(o.number == no)
                {
                    highlight = no;
                    notice = MineBayPages.Notice.ok("下单成功！订单号 " + MineBayPages.number(no) + "，东西已经放进你的背包（背包满了的话掉在脚边）。");
                }
            }
        }
        return WebResponse.xhtml(MineBayPages.orders(s.viewer(), list, p, notice, highlight));
    }

    private static WebResponse order(Map<String, String> params, Session s)
    {
        long no = ShopParams.orderNumber(params.get("no"));
        if(no < 0)
            return badRequest(s, "订单号不对。");
        Orders.Order o = s.player == null || s.data == null ? null : s.data.orders(s.player.getUniqueID()).find(no);
        if(o == null)
            return page(404, "Not Found", MineBayPages.problem(s.viewer(), "找不到这张订单", "只能看自己最近 " + Orders.MAX_ORDERS + " 单（要开着电脑的界面）。"));
        return WebResponse.xhtml(MineBayPages.orderDetail(s.viewer(), o));
    }

    private static String urlPart(String s)
    {
        return FormEncoding.encode(Collections.singletonList(FormEncoding.field("x", s))).substring(2);
    }

    // ---------------------------------------------------------------- 付款、发货

    private static ItemStack currencyOf(Catalog catalog, Checkout.Plan plan, String currency)
    {
        for(Checkout.Item i : plan.items)
        {
            Catalog.Entry e = catalog.byId.get(i.offer.id());
            if(e != null && i.offer.currency().equals(currency))
                return e.currency;
        }
        return ItemStack.EMPTY;
    }

    static long count(EntityPlayerMP player, ItemStack currency)
    {
        if(currency.isEmpty())
            return 0;
        long have = 0;
        for(ItemStack s : player.inventory.mainInventory)
        {
            if(matches(s, currency))
                have += s.getCount();
        }
        return have;
    }

    static boolean matches(ItemStack stack, ItemStack currency)
    {
        if(stack.isEmpty() || stack.getItem() != currency.getItem())
            return false;
        return !currency.getHasSubtypes() || stack.getMetadata() == currency.getMetadata();
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

    /** 结算时的游戏状态：操作者的背包 */
    static final class PlayerTill implements Checkout.Till<ItemStack>
    {
        final EntityPlayerMP player;
        final Catalog catalog;
        final Checkout.Plan plan;
        int stacks;

        PlayerTill(EntityPlayerMP player, Catalog catalog, Checkout.Plan plan)
        {
            this.player = player;
            this.catalog = catalog;
            this.plan = plan;
        }

        @Override
        public long have(String currency)
        {
            return count(player, currencyOf(catalog, plan, currency));
        }

        @Nullable
        @Override
        public List<ItemStack> prepare(Checkout.Item item)
        {
            Catalog.Entry e = catalog.byId.get(item.offer.id());
            if(e == null)
                return null;
            List<ItemStack> out = new ArrayList<>();
            if(e.delivery != null)
            {
                List<ItemStack> made = e.delivery.prepare(player, item.quantity);
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
            stacks += out.size();
            if(stacks > MAX_STACKS_PER_ORDER)
            {
                MrCrayfishFurnitureMod.logger().warn("MineBay: order for {} would hand out more than {} stacks", player.getName(), MAX_STACKS_PER_ORDER);
                return null;
            }
            return out;
        }

        @Override
        public long take(String currency, long amount)
        {
            ItemStack cur = currencyOf(catalog, plan, currency);
            NonNullList<ItemStack> inv = player.inventory.mainInventory;
            long left = amount;
            for(int i = 0; i < inv.size() && left > 0; i++)
            {
                ItemStack s = inv.get(i);
                if(matches(s, cur))
                {
                    int take = (int) Math.min(left, s.getCount());
                    s.shrink(take);
                    left -= take;
                }
            }
            player.inventory.markDirty();
            return amount - left;
        }

        @Override
        public void refund(String currency, long amount)
        {
            ItemStack cur = currencyOf(catalog, plan, currency);
            if(cur.isEmpty())
                return;
            ItemStack unit = cur.copy();
            unit.setCount(1);
            for(ItemStack s : split(unit, amount))
                ItemHandlerHelper.giveItemToPlayer(player, s);
        }

        @Override
        public void give(Checkout.Item item, List<ItemStack> goods)
        {
            for(ItemStack s : goods)
                ItemHandlerHelper.giveItemToPlayer(player, s.copy());
        }

        @Override
        public void delivered(Checkout.Item item, List<ItemStack> goods)
        {
            Catalog.Entry e = catalog.byId.get(item.offer.id());
            if(e == null || e.delivery == null)
                return;
            try
            {
                e.delivery.delivered(player, item.quantity, Collections.unmodifiableList(goods));
            }
            catch(RuntimeException ex)
            {
                MrCrayfishFurnitureMod.logger().error("MineBay: delivery callback of {} failed after delivery", item.offer.id(), ex);
            }
        }
    }
}
