package com.mrcrayfish.furniture.web.minebay;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.computer.FormEncoding;
import com.mrcrayfish.furniture.computer.RateLimiter;
import com.mrcrayfish.furniture.web.WebContext;
import com.mrcrayfish.furniture.web.WebHandler;
import com.mrcrayfish.furniture.web.WebRequest;
import com.mrcrayfish.furniture.web.WebResponse;
import com.mrcrayfish.furniture.web.Websites;
import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.idyr.IdYrClient;
import moe.qingu.youran.api.network.location.LocationProof;
import moe.qingu.youran.api.network.location.LocationProofs;
import moe.qingu.youran.api.network.location.LocationVerifier;
import moe.qingu.youran.api.network.location.NonceBook;
import moe.qingu.youran.api.network.stack.NetStack;
import moe.qingu.youran.api.pay.OrderInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

import javax.annotation.Nullable;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MineBay 网站（{@code minebay.yr}，主机登记名 {@value #HOST_KEY}）：做成一个购物网站，只用游戏内网络的公开接口（服主 11-15）。
 * <ul>
 * <li>页面：首页 / 分类 / 搜索（{@code GET /?c=&q=&s=&p=}）、商品详情（{@code /item?id=}）、购物车（{@code /cart}）、
 * 结算确认（{@code /checkout}，立即购买为 {@code /checkout?buy=&n=}）、我的订单（{@code /orders}、{@code /order?no=}）、帮助（{@code /help}）。</li>
 * <li>登录：用 {@code id.yr} 游戏角色一键登录（MineBay 是它的普通 OAuth 网站，见 {@link IdLogin}），登录状态是 MineBay 自己的 Cookie {@value #COOKIE}
 * （跟着电脑走）。购物车、订单按登录的玩家 UUID 存（{@link MineBayData}）。不登录也能逛。</li>
 * <li>结算：提交时浏览器带上定位模块签名的电脑位置（{@code <input type="x-yr-location">}，没装定位模块就不能下单）；MineBay 凭 id.yr 的访问令牌现查
 * 登录的那名玩家在不在线、在哪，比距离（{@link Reach}，8 格内）；谁坐在电脑前操作不管（“相当于老婆帮你买东西”）。然后开 pay.yr 收款单、跳过去付款，
 * 付款成功的回调里发货（{@link MineBayShop}）。</li>
 * <li>改东西的都是 POST：{@code /cart/add}、{@code /cart/update}、{@code /checkout}、{@code /logout}。成功后 303 跳到结果页；
 * 失败时直接回一页（4xx）说明原因，玩家填的数量原样填回去（浏览器也保留原页面表单里的内容）。</li>
 * <li>安全：POST 表单带按来源地址发的随机令牌（防别的网站骗点）；结算确认页另发一个一次性的确认码，绑定玩家与确认时的订单内容，
 * 提交时对不上（重复提交、确认后改了购物车或价格）就不开单；位置证明核对签名、网站、来源地址、时间、一次性随机数；所有参数先校验；按地址、按玩家限频。</li>
 * </ul>
 * 网络线程上只做路由、令牌、登录、按地址限频、核对位置证明、向 id.yr 查位置；其余在主线程（{@link WebContext#onMainThread}）。
 */
public final class MineBaySite implements WebHandler
{
    public static final String HOST_KEY = "cfm:minebay";
    public static final String DOMAIN = "minebay.yr";
    static final String COOKIE = "mbsid";
    static final int MAX_TOKENS = 1024;
    static final int MAX_SESSIONS = 4096;
    static final int MAX_LOGINS = 1024;
    /** 确认页的确认码多久失效 */
    static final long CONFIRM_TTL_MS = 10 * 60 * 1000L;
    /** 登录空闲多久失效（最长到 id.yr 的访问令牌过期） */
    static final long SESSION_IDLE_MS = 30 * 60 * 1000L;
    static final long LOGIN_TTL_MS = 5 * 60 * 1000L;
    /** 位置证明最多多旧 */
    static final long PROOF_MAX_AGE_S = 300;

    private final SecureRandom random = new SecureRandom();
    /** 网络线程：来源地址 → 表单令牌 */
    private final Map<Ipv6Address, String> tokens = Collections.synchronizedMap(lru(MAX_TOKENS));
    /** 网络线程：来源地址 → 限频（每秒 10 次，突发 20） */
    private final Map<Ipv6Address, RateLimiter> addressLimits = Collections.synchronizedMap(lru(MAX_TOKENS));
    /** 网络线程：Cookie → 登录 */
    private final Map<String, Login> sessions = new ConcurrentHashMap<>();
    /** 网络线程：state → 正在登录 */
    private final Map<String, LoginStart> logins = new ConcurrentHashMap<>();
    /** 结算页给定位模块的一次性随机数 */
    private final NonceBook nonces = new NonceBook(CONFIRM_TTL_MS, 4096);
    /** 主线程：玩家 → 看页面限频（每秒 6 次，突发 12）、改东西限频（每秒 3 次，突发 6） */
    private final Map<UUID, RateLimiter> viewLimits = lru(1024);
    private final Map<UUID, RateLimiter> changeLimits = lru(1024);
    /** 主线程：玩家 → 待确认的订单 */
    private final Map<UUID, Pending> pending = lru(1024);
    /** 主线程：玩家 → 最近一次已经开单的确认码（重复提交时认出来） */
    private final Map<UUID, Done> done = lru(1024);
    /** 网络线程：编号 → 等 id.yr 的操作（下单前验证身份、改“操作前必须验证身份”） */
    private final Map<String, Gate> gates = new ConcurrentHashMap<>();
    static final int MAX_GATES = 512;
    /** 多久没动静就忘掉（id.yr 的请求 2 分钟内要打开，框作废了还可以重新弹出，最多约 6 分钟；没通过的留着给“重新验证”） */
    static final long GATE_TTL_MS = 10 * 60 * 1000L;
    /** 主线程上要先验证身份时，主线程那一步回这个，网络线程接着去 id.yr 建请求 */
    static final WebResponse NEED_VERIFY = WebResponse.error(500, "verify");

    /**
     * 一个等 id.yr 的操作：结算（带着提交的表单与位置证明，要本人验证身份）或改“操作前必须验证身份”（打开：追加授权 verify，坐在电脑前的人同意就行；
     * 关闭：本人验证身份）。这次登录还没有 verify 时，验证身份前先追加授权。没通过的留着，给“重新验证”用同样的内容重来。
     */
    static final class Gate
    {
        final String id;
        final String sessionId;
        /** 结算：提交的表单（确认页重新出过以后换成新确认码）；改设置：null */
        @Nullable
        volatile Map<String, String> params;
        @Nullable
        final LocationProof proof;
        /** 改设置：要改成开还是关 */
        final boolean on;
        final String title;
        final List<String> items;
        volatile long touched;
        volatile String confirmId;
        /** 现在这个 id.yr 请求是追加授权 */
        volatile boolean scopeStep;
        /** 没通过的原因（留着给“重新验证”）；还在进行为 null */
        @Nullable
        volatile String failed;
        final java.util.concurrent.atomic.AtomicBoolean checking = new java.util.concurrent.atomic.AtomicBoolean();

        Gate(String id, String sessionId, long now, @Nullable Map<String, String> params, @Nullable LocationProof proof, boolean on, String title, List<String> items)
        {
            this.id = id;
            this.sessionId = sessionId;
            this.touched = now;
            this.params = params;
            this.proof = proof;
            this.on = on;
            this.title = title;
            this.items = items;
        }

        /** 要本人验证身份（结算、关闭设置）；打开设置只要追加授权 */
        boolean verify()
        {
            return params != null || !on;
        }
    }

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

    /** 一个登录（Cookie 跟着电脑走：同一台电脑的下一个人看到的也是这个角色） */
    static final class Login
    {
        final String id;
        final UUID uuid;
        final String name;
        /** id.yr 的访问令牌与它的授权范围（追加授权后换成新的） */
        volatile String accessToken;
        volatile java.util.Set<String> scopes;
        final long tokenExpires;
        /** 登录用的协议（client_id 的协议；验证身份跳回来的网址要和它一样） */
        final String scheme;
        volatile long lastSeen;

        Login(String id, IdLogin.Token t, long now, String scheme)
        {
            this.id = id;
            this.uuid = t.uuid;
            this.name = t.name;
            this.accessToken = t.accessToken;
            this.scopes = t.scopes;
            this.tokenExpires = t.expiresAt;
            this.scheme = scheme;
            this.lastSeen = now;
        }

        /** 追加授权换到的新令牌（角色不变时才换） */
        void swap(@Nullable IdYrClient.Token t)
        {
            if(t != null && t.uuid.equals(uuid))
            {
                scopes = t.scopes;
                accessToken = t.accessToken;
            }
        }

        boolean alive(long now)
        {
            return now < tokenExpires && now - lastSeen < SESSION_IDLE_MS;
        }
    }

    static final class LoginStart
    {
        final String verifier;
        final String scheme;
        final String next;
        final Ipv6Address source;
        final long created;

        LoginStart(String verifier, String scheme, String next, Ipv6Address source, long created)
        {
            this.verifier = verifier;
            this.scheme = scheme;
            this.next = next;
            this.source = source;
            this.created = created;
        }
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
        final String url;

        Done(String nonce, String url)
        {
            this.nonce = nonce;
            this.url = url;
        }
    }

    public static void register()
    {
        Websites.register(HOST_KEY, DOMAIN, new MineBaySite());
        MineBayShop.init();
    }

    // ---------------------------------------------------------------- 网络线程

    enum Route
    {
        HOME("/", false), ITEM("/item", false), CART("/cart", false), CART_ADD("/cart/add", true), CART_UPDATE("/cart/update", true),
        CHECKOUT("/checkout", false), CHECKOUT_POST("/checkout", true), ORDERS("/orders", false), ORDER("/order", false), HELP("/help", false),
        LOGIN("/login", false), CALLBACK("/login/callback", false), LOGOUT("/logout", true), PAID("/paid", false), UNPAID("/unpaid", false),
        SUPPORT("/support", false), SUPPORT_POST("/support", true), ACCOUNT("/account", false), SECURITY("/account/security", true),
        VERIFIED("/verified", false), RETRY("/verified/again", true);

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
        long now = System.currentTimeMillis();
        RateLimiter al;
        synchronized(addressLimits)
        {
            al = addressLimits.computeIfAbsent(request.remoteAddress, a -> new RateLimiter(10, 20));
        }
        if(!al.tryAcquire(now, 1))
            return done(WebResponse.error(429, "Too Many Requests"));
        if(request.path.equals("/buy"))
        {
            // 改版前的购买表单（还开着旧页面的）：回首页，什么都不买
            return done(WebResponse.seeOther("/"));
        }
        boolean post = request.method.equals("POST");
        if(!post && !request.method.equals("GET"))
            return done(WebResponse.error(405, "Method Not Allowed"));
        Route route = Route.of(request.path, post);
        if(route == null && Route.exists(request.path))
            return done(WebResponse.error(405, "Method Not Allowed"));
        sweep(now);
        String token = tokenFor(request.remoteAddress);
        Map<String, String> params = params(request);
        boolean tokenOk = !post || token.equals(params.get("t"));
        Login login = session(request, now);
        String scheme = request.secure ? "https" : "http";
        if(route == Route.LOGIN)
            return done(login(request, params, scheme, now));
        if(route == Route.CALLBACK)
            return callback(request, params, context, now);
        if(route == Route.LOGOUT)
        {
            if(tokenOk && login != null)
                sessions.remove(login.id);
            return done(WebResponse.seeOther("/").withHeader("Set-Cookie", cookie("", request.secure, 0)));
        }
        if(route == Route.CHECKOUT_POST && tokenOk && login != null)
            return checkoutPost(request, params, context, login, token, scheme);
        if(route == Route.SECURITY && tokenOk && login != null)
            return security(request, params, context, login, token, scheme);
        if(route == Route.VERIFIED)
            return verified(request, params, context, login, token, scheme);
        if(route == Route.RETRY && tokenOk && login != null)
            return retry(request, params, context, login, token, scheme);
        return context.onMainThread(() -> main(request, route, s -> handleMain(route, params, s, tokenOk), login, token, scheme));
    }

    /** 在主线程上做（出错回 500） */
    private WebResponse main(WebRequest request, @Nullable Route route, java.util.function.Function<Session, WebResponse> body, @Nullable Login login,
                             String token, String scheme)
    {
        try
        {
            Session s = session(login, token, scheme);
            return route == null ? notFound(s) : body.apply(s);
        }
        catch(RuntimeException e)
        {
            MrCrayfishFurnitureMod.logger().error("MineBay: failed to handle {} {}", request.method, request.path, e);
            return WebResponse.error(500, "Internal Server Error");
        }
    }

    private static <T> CompletableFuture<T> done(T v)
    {
        return CompletableFuture.completedFuture(v);
    }

    private String tokenFor(Ipv6Address address)
    {
        synchronized(tokens)
        {
            String t = tokens.get(address);
            if(t == null)
            {
                t = randomHex();
                tokens.put(address, t);
            }
            return t;
        }
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

    private String randomText(int bytes)
    {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // ---------------------------------------------------------------- 登录（网络线程）

    static String cookie(String value, boolean secure, int maxAge)
    {
        return COOKIE + "=" + value + "; Path=/; HttpOnly; SameSite=Lax" + (secure ? "; Secure" : "") + (maxAge >= 0 ? "; Max-Age=" + maxAge : "");
    }

    @Nullable
    private Login session(WebRequest r, long now)
    {
        String c = r.header("cookie");
        if(c == null)
            return null;
        for(String part : c.split(";"))
        {
            String p = part.trim();
            if(p.startsWith(COOKIE + "="))
            {
                Login l = sessions.get(p.substring(COOKIE.length() + 1));
                if(l == null)
                    return null;
                if(!l.alive(now))
                {
                    sessions.remove(l.id);
                    return null;
                }
                l.lastSeen = now;
                return l;
            }
        }
        return null;
    }

    private void sweep(long now)
    {
        if(random.nextInt(32) != 0)
            return;
        sessions.values().removeIf(l -> !l.alive(now));
        logins.values().removeIf(l -> now - l.created > LOGIN_TTL_MS);
        gates.values().removeIf(g -> now - g.touched > GATE_TTL_MS);
    }

    /** 只许跳回 MineBay 自己的路径 */
    static String safeNext(@Nullable String n)
    {
        if(n == null || !n.startsWith("/") || n.startsWith("//") || n.length() > 200 || !n.matches("[A-Za-z0-9/_.?=&%:-]*"))
            return "/";
        return n;
    }

    private WebResponse login(WebRequest r, Map<String, String> params, String scheme, long now)
    {
        String next = safeNext(params.get("next"));
        if(!IdLogin.available())
            return page(503, "Service Unavailable", MineBayPages.problem(MineBayPages.Viewer.anonymous(""), "不能登录", "服务器没有开游戏角色登录（id.yr）。"));
        if(logins.size() >= MAX_LOGINS)
            return page(503, "Service Unavailable", MineBayPages.problem(MineBayPages.Viewer.anonymous(""), "太忙了", "登录的人太多，请稍后再试。"));
        String state = randomText(18);
        String verifier = randomText(36);
        logins.put(state, new LoginStart(verifier, scheme, next, r.remoteAddress, now));
        return WebResponse.seeOther(IdLogin.authorizeUrl(scheme, state, verifier));
    }

    private CompletableFuture<WebResponse> callback(WebRequest r, Map<String, String> q, WebContext context, long now)
    {
        String st = q.get("state");
        LoginStart l = st == null ? null : logins.remove(st);
        MineBayPages.Viewer anon = MineBayPages.Viewer.anonymous("");
        if(l == null || now - l.created > LOGIN_TTL_MS)
            return done(page(400, "Bad Request", MineBayPages.problem(anon, "登录失败", "这次登录过期了，请重新登录。")));
        if(!l.source.equals(r.remoteAddress))
            return done(page(403, "Forbidden", MineBayPages.problem(anon, "登录失败", "要在发起登录的那台电脑上完成。")));
        if(!IdLogin.issuerOk(q.get("iss")))
            return done(page(400, "Bad Request", MineBayPages.problem(anon, "登录失败", "登录服务对不上。")));
        if(q.get("error") != null)
            return done(page(400, "Bad Request", MineBayPages.problem(anon, "登录失败", IdLogin.errorText(q.get("error"), q.get("error_description")))));
        String code = q.get("code");
        NetStack stack = context.stack();
        if(code == null || code.isEmpty() || stack == null)
            return done(page(400, "Bad Request", MineBayPages.problem(anon, "登录失败", "登录服务没给授权码。")));
        return IdLogin.exchange(stack, context.hasTls(), l.scheme, code, l.verifier).handle((t, e) ->
        {
            if(t == null)
            {
                MrCrayfishFurnitureMod.logger().warn("MineBay: id.yr exchange failed: {}", cause(e));
                return page(502, "Bad Gateway", MineBayPages.problem(anon, "登录失败", "向 id.yr 兑换失败，请重新登录。"));
            }
            if(sessions.size() >= MAX_SESSIONS)
                return page(503, "Service Unavailable", MineBayPages.problem(anon, "太忙了", "登录的人太多，请稍后再试。"));
            Login s = new Login(randomText(24), t, System.currentTimeMillis(), l.scheme);
            sessions.put(s.id, s);
            MrCrayfishFurnitureMod.logger().info("MineBay: {} logged in with id.yr (from {})", t.name, r.remoteAddress);
            return WebResponse.seeOther(l.next).withHeader("Set-Cookie", cookie(s.id, l.scheme.equals("https"), -1));
        });
    }

    private static String cause(@Nullable Throwable e)
    {
        Throwable c = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
        return c == null ? "?" : c.toString();
    }

    // ---------------------------------------------------------------- 结算提交（网络线程 → 主线程）

    /**
     * 提交结算：先在网络线程上核对位置证明、凭访问令牌向 id.yr 现查位置，再到主线程上比距离、查库存、开 pay.yr 收款单。
     */
    private CompletableFuture<WebResponse> checkoutPost(WebRequest r, Map<String, String> params, WebContext context, Login login, String token, String scheme)
    {
        long now = System.currentTimeMillis();
        String loc = params.get("loc");
        LocationProof proof = null;
        String problem;
        if(loc == null)
            problem = "页面过期了，请回到购物车重新结算。";
        else if(loc.startsWith("error:"))
            problem = locationError(loc.substring(6).trim());
        else
        {
            PublicKey key = LocationProofs.publicKey();
            if(key == null)
                problem = "定位服务现在不可用，暂时不能下单。";
            else
            {
                LocationVerifier.Result v = new LocationVerifier(key, PROOF_MAX_AGE_S).verify(loc, DOMAIN, r.remoteAddress, n -> nonces.consume(n, now), now / 1000);
                proof = v.proof;
                problem = v.ok() ? null : "电脑的位置证明核对不上（" + v.reason + "），没有下单。请刷新确认页再试。";
            }
        }
        NetStack stack = context.stack();
        if(problem != null || stack == null)
        {
            String why = problem != null ? problem : "现在查不了位置，暂时不能下单。";
            return context.onMainThread(() -> main(r, Route.CHECKOUT_POST, s -> checkoutRefused(params, s, why, 403), login, token, scheme));
        }
        LocationProof p = proof;
        return IdLogin.position(stack, context.hasTls(), login.accessToken).handle((pos, e) ->
        {
            if(pos != null)
                return new Object[]{null, pos};
            Throwable c = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            if(c instanceof IdLogin.TokenExpired)
            {
                sessions.remove(login.id);
                return new Object[]{"登录过期了，请重新登录。购物车还在。", null};
            }
            MrCrayfishFurnitureMod.logger().warn("MineBay: id.yr userinfo for {} failed: {}", login.name, cause(e));
            return new Object[]{"查不到 " + login.name + " 的位置（id.yr），暂时不能下单。", null};
        }).thenCompose(x -> context.onMainThread(() -> main(r, Route.CHECKOUT_POST, s ->
        {
            if(x[0] != null)
                return checkoutRefused(params, s, (String) x[0], 403);
            String far = Reach.check(login.name, (IdLogin.Position) x[1], p.dimension, p.x, p.y, p.z);
            if(far != null)
            {
                MrCrayfishFurnitureMod.logger().info("MineBay: refused checkout of {}: {}", login.name, far);
                return checkoutRefused(params, s, far, 403);
            }
            if(s.ok() && s.data.verifyRequired(login.uuid))
                return NEED_VERIFY;
            return checkoutPostMain(params, s, System.currentTimeMillis());
        }, login, token, scheme))).thenCompose(resp -> resp != NEED_VERIFY ? done(resp)
                : start(r, context, login, token, scheme, new Gate(randomText(18), login.id, System.currentTimeMillis(), new HashMap<>(params), p, false,
                "在 MineBay 下单", Collections.singletonList("收货人：" + login.name + "（登录的帐号）"))));
    }

    // ---------------------------------------------------------------- 操作前必须验证身份（网络线程）

    /** 打开“操作前必须验证身份”：在 id.yr 的同意框里给这次登录加上“验证身份”（坐在电脑前的人同意就行）；关闭：要本人验证身份 */
    private CompletableFuture<WebResponse> security(WebRequest r, Map<String, String> params, WebContext context, Login login, String token, String scheme)
    {
        boolean on = "1".equals(params.get("verify"));
        return start(r, context, login, token, scheme, new Gate(randomText(18), login.id, System.currentTimeMillis(), null, null, on,
                "关闭操作前验证身份", java.util.Arrays.asList("MineBay 帐号：" + login.name, "关闭后，坐在这台电脑前的人就能下单")));
    }

    /** 记下这个操作，向 id.yr 建请求，把浏览器 303 过去；建不了时回原来的页面说明（可以重新验证） */
    private CompletableFuture<WebResponse> start(WebRequest r, WebContext context, Login login, String token, String scheme, Gate g)
    {
        if(context.stack() == null || gates.size() >= MAX_GATES)
            return refused(r, context, login, token, scheme, g, context.stack() == null ? "现在验证不了身份，请稍后再试。" : "现在太忙，请稍后再试。", false);
        gates.put(g.id, g);
        return ask(r, context, login, token, scheme, g);
    }

    /** 建这一步的 id.yr 请求：打开设置、或要验证身份而这次登录还没有 verify 时是追加授权；否则验证身份 */
    private CompletableFuture<WebResponse> ask(WebRequest r, WebContext context, Login login, String token, String scheme, Gate g)
    {
        NetStack stack = context.stack();
        String back = login.scheme + "://" + DOMAIN + "/verified?g=" + g.id;
        boolean scope = !g.verify() || !login.scopes.contains(IdYrClient.VERIFY);
        IdYrClient.Ask ask = scope ? IdYrClient.Ask.scope(IdYrClient.VERIFY, r.remoteAddress, back, g.id)
                : IdYrClient.Ask.verify(g.title, g.items, moe.qingu.youran.api.network.ConfirmRequest.Purpose.ACTION, r.remoteAddress, back, g.id);
        g.scopeStep = scope;
        g.confirmId = null;
        g.touched = System.currentTimeMillis();
        return IdLogin.client(stack, context.hasTls()).ask(login.accessToken, ask).handle((t, e) ->
        {
            if(t == null)
            {
                MrCrayfishFurnitureMod.logger().warn("MineBay: id.yr confirm request for {} failed: {}", login.name, cause(e));
                return null;
            }
            g.confirmId = t.id;
            return WebResponse.seeOther(t.url);
        }).thenCompose(resp -> resp != null ? done(resp)
                : refused(r, context, login, token, scheme, g, "建不了验证请求（登录可能过期了），请稍后再试或重新登录。", true));
    }

    /**
     * 没通过：结算回新的确认页（购物车原样），改设置回帐号设置页；都带“重新验证”（{@code keep}：留着这个操作）。
     * 确认页重新出过，确认码换了新的：记进这个操作，重新验证通过后照新的确认码下单。
     */
    private CompletableFuture<WebResponse> refused(WebRequest r, WebContext context, Login login, String token, String scheme, Gate g, String why, boolean keep)
    {
        g.failed = why;
        g.touched = System.currentTimeMillis();
        String label = g.verify() ? "重新验证" : "重新确认";
        return context.onMainThread(() -> main(r, Route.VERIFIED, s ->
        {
            Map<String, String> params = g.params;
            if(params == null)
                return accountPage(403, "Forbidden", s, keep ? MineBayPages.Notice.retry(why + "设置没有改。", g.id, label, s.token)
                        : MineBayPages.Notice.bad(why + "设置没有改。"));
            WebResponse page = checkoutRefused(params, s, why + "没有下单。", 403, keep ? g.id : null, label);
            Pending fresh = s.ok() ? pending.get(s.login.uuid) : null;
            if(fresh != null)
            {
                Map<String, String> next = new HashMap<>(params);
                next.put("k", fresh.nonce);
                g.params = next;
            }
            return page;
        }, login, token, scheme));
    }

    /**
     * id.yr 跳回来（{@code /verified?g=编号}；或刷新、换人坐下重新打开这一页）：向 id.yr 核对（网址里带的结果不算数）。追加授权通过了换上新令牌，
     * 要验证身份的接着去验证；验证通过了接着做；没通过回原来的页面并给“重新验证”。
     */
    private CompletableFuture<WebResponse> verified(WebRequest r, Map<String, String> params, WebContext context, @Nullable Login login, String token, String scheme)
    {
        String gid = params.get("g");
        Gate g = gid == null ? null : gates.get(gid);
        NetStack stack = context.stack();
        if(g == null || login == null || !g.sessionId.equals(login.id) || stack == null)
            return context.onMainThread(() -> main(r, Route.VERIFIED, s -> page(404, "Not Found", MineBayPages.problem(s.viewer(), "验证已经结束",
                    "这次验证已经结束或过期了，请回去重新操作。")), login, token, scheme));
        String failed = g.failed;
        if(failed != null)
            return refused(r, context, login, token, scheme, g, failed, true);
        if(g.confirmId == null || !g.checking.compareAndSet(false, true))
            return context.onMainThread(() -> main(r, Route.VERIFIED, s -> page(200, "OK", MineBayPages.waitVerify(s.viewer(), g.id)), login, token, scheme));
        boolean scopeStep = g.scopeStep;
        return IdLogin.client(stack, context.hasTls()).result(login.accessToken, g.confirmId).handle((out, e) ->
        {
            if(out == null)
            {
                MrCrayfishFurnitureMod.logger().warn("MineBay: id.yr confirm result for {} failed: {}", login.name, cause(e));
                return IdYrClient.reasonText("unavailable", true);
            }
            if(out.status == IdYrClient.Outcome.Status.PENDING)
                return "";
            if(!out.approved())
                return scopeStep ? ("denied".equals(out.reason) ? "在 id.yr 的确认框里没有同意。" : out.text(false)) : out.text(true);
            if(scopeStep)
            {
                login.swap(out.token);
                if(!login.scopes.contains(IdYrClient.VERIFY))
                    return "id.yr 没有给“验证身份”的授权。";
            }
            return null;
        }).thenCompose(why ->
        {
            g.checking.set(false);
            if("".equals(why))
                return context.onMainThread(() -> main(r, Route.VERIFIED, s -> page(200, "OK", MineBayPages.waitVerify(s.viewer(), g.id)), login, token, scheme));
            if(why != null)
                return refused(r, context, login, token, scheme, g, why, true);
            if(scopeStep && g.verify())
                // 授权有了，接着请本人验证身份（新的请求、新的框）
                return ask(r, context, login, token, scheme, g);
            gates.remove(g.id);
            if(g.params == null)
                return context.onMainThread(() -> main(r, Route.VERIFIED, s ->
                {
                    s.data.setVerifyRequired(login.uuid, g.on);
                    s.data.saveNow(FMLCommonHandler.instance().getMinecraftServerInstance());
                    MrCrayfishFurnitureMod.logger().info("MineBay: {} turned {} verify-before-checkout", login.name, g.on ? "on" : "off");
                    return WebResponse.seeOther("/account?r=" + (g.on ? "on" : "off"));
                }, login, token, scheme));
            // 结算：验证完再现查一次位置（中间可能走开了），再开单
            LocationProof p = g.proof;
            Map<String, String> form = g.params;
            return IdLogin.position(stack, context.hasTls(), login.accessToken).handle((pos, e2) -> pos).thenCompose(pos -> context.onMainThread(() ->
                    main(r, Route.VERIFIED, s ->
                    {
                        if(pos == null)
                            return checkoutRefused(form, s, "查不到 " + login.name + " 的位置（id.yr），暂时不能下单。", 403);
                        String far = Reach.check(login.name, pos, p.dimension, p.x, p.y, p.z);
                        if(far != null)
                            return checkoutRefused(form, s, far, 403);
                        return checkoutPostMain(form, s, System.currentTimeMillis());
                    }, login, token, scheme)));
        });
    }

    /** 没通过时的“重新验证”（{@code POST /verified/again}，带 {@code g}）：同一个操作、同样的内容，重新向 id.yr 建请求（新的框） */
    private CompletableFuture<WebResponse> retry(WebRequest r, Map<String, String> params, WebContext context, Login login, String token, String scheme)
    {
        String gid = params.get("g");
        Gate g = gid == null ? null : gates.get(gid);
        if(g == null || !g.sessionId.equals(login.id) || context.stack() == null)
            return context.onMainThread(() -> main(r, Route.VERIFIED, s -> page(404, "Not Found", MineBayPages.problem(s.viewer(), "验证已经结束",
                    "这次验证已经结束或过期了，请回去重新操作。")), login, token, scheme));
        if(g.failed == null || !g.checking.compareAndSet(false, true))
            return done(WebResponse.seeOther("/verified?g=" + g.id));
        g.failed = null;
        g.checking.set(false);
        return ask(r, context, login, token, scheme, g);
    }

    static String locationError(String code)
    {
        switch(code)
        {
            case "no_locator":
                return "这台电脑没有定位模块，不能下单。";
            case "denied":
                return "你没有同意提供电脑的位置，没有下单。";
            case "timeout":
                return "位置确认框超时了，没有下单。";
            case "operator_changed":
            case "no_operator":
                return "确认位置时离开了电脑或换了人，没有下单。";
            case "busy":
                return "已经有一个确认框开着，先处理那个。";
            default:
                return "拿不到电脑的位置（" + code + "），没有下单。";
        }
    }

    // ---------------------------------------------------------------- 主线程

    /** 这次请求是谁（主线程上的样子） */
    static final class Session
    {
        /** 登录的玩家；没登录为 null */
        @Nullable
        final Login login;
        @Nullable
        final MineBayData data;
        final String token;
        final String scheme;
        final long now;

        Session(@Nullable Login login, @Nullable MineBayData data, String token, String scheme, long now)
        {
            this.login = login;
            this.data = data;
            this.token = token;
            this.scheme = scheme;
            this.now = now;
        }

        boolean ok()
        {
            return login != null && data != null;
        }

        long remaining(Listing l)
        {
            return data == null ? -1 : data.remaining(l.id, l.stock, now, login == null ? null : login.uuid);
        }

        MineBayPages.Viewer viewer()
        {
            return new MineBayPages.Viewer(login == null ? null : login.name, !ok() ? 0 : data.cart(login.uuid).totalQuantity(), token, new MineBayPages.StockView()
            {
                @Override
                public long remaining(Listing l)
                {
                    return Session.this.remaining(l);
                }

                @Override
                public String refill(Listing l)
                {
                    Stock.State st = data == null ? null : data.stock(l.id, l.stock, now);
                    return st == null ? "" : Stock.describeWait(st, now);
                }
            }, IdLogin.available());
        }
    }

    static Session session(@Nullable Login login, String token, String scheme)
    {
        MineBayData data = MineBayData.get(FMLCommonHandler.instance().getMinecraftServerInstance());
        return new Session(login, data, token, scheme, System.currentTimeMillis());
    }

    private WebResponse handleMain(Route route, Map<String, String> params, Session s, boolean tokenOk)
    {
        long now = s.now;
        if(s.login != null)
        {
            Map<UUID, RateLimiter> limits = route.post ? changeLimits : viewLimits;
            RateLimiter l = limits.computeIfAbsent(s.login.uuid, k -> route.post ? new RateLimiter(3, 6) : new RateLimiter(6, 12));
            if(!l.tryAcquire(now, 1))
            {
                MrCrayfishFurnitureMod.logger().info("MineBay: requests of {} too fast ({})", s.login.name, route);
                return page(429, "Too Many Requests", MineBayPages.problem(s.viewer(), "操作太快了", "请过一两秒再试。填的内容没有丢，按“后退”再提交。"));
            }
        }
        if(route.post)
        {
            if(!tokenOk)
                return page(403, "Forbidden", MineBayPages.problem(s.viewer(), "页面过期了", "请回到上一页刷新后再试。"));
            if(!s.ok())
                return route == Route.SUPPORT_POST ? supportPage(401, "Unauthorized", s, null, "", false)
                        : loginNeeded(s, route == Route.CHECKOUT_POST ? "/checkout" : "/cart");
        }
        switch(route)
        {
            case HOME: return home(params, s);
            case ITEM: return item(params, s);
            case CART: return cart(params, s);
            case CART_ADD: return cartAdd(params, s);
            case CART_UPDATE: return cartUpdate(params, s);
            case CHECKOUT: return checkout(params, s, now);
            case CHECKOUT_POST: return checkoutPostMain(params, s, now);
            case ORDERS: return orders(params, s);
            case ORDER: return order(params, s);
            case PAID: return paid(params, s);
            case UNPAID: return unpaid(params, s);
            case HELP: return WebResponse.xhtml(MineBayPages.help(s.viewer()));
            case SUPPORT: return supportPage(200, "OK", s, null, "", "1".equals(params.get("all")));
            case SUPPORT_POST: return supportPost(params, s);
            case ACCOUNT: return account(params, s);
            case SECURITY: return loginNeeded(s, "/account");
            default: return notFound(s);
        }
    }

    // ---------------------------------------------------------------- 在线客服

    private static WebResponse supportPage(int status, String reason, Session s, @Nullable MineBayPages.Notice notice, String typed, boolean all)
    {
        SupportData d = SupportData.get(FMLCommonHandler.instance().getMinecraftServerInstance());
        boolean in = s.login != null && d != null;
        List<SupportChat.Message> msgs = in ? d.chat(s.login.uuid).messages() : Collections.<SupportChat.Message>emptyList();
        boolean waiting = in && Support.waiting(s.login.uuid, s.now);
        return page(status, reason, MineBayPages.support(s.viewer(), Support.available(), msgs, waiting, notice, typed, SupportConfig.messagesPerHour, all));
    }

    /** 发一条消息：收下了 303 回客服页（等回答）；没收下回客服页说明原因，填的原样填回去 */
    private static WebResponse supportPost(Map<String, String> params, Session s)
    {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        SupportData d = SupportData.get(server);
        String typed = params.get("m") == null ? "" : params.get("m");
        if(d == null)
            return supportPage(503, "Service Unavailable", s, MineBayPages.Notice.bad("客服暂时不可用，请稍后再试。"), typed, false);
        Support.Outcome o = Support.send(server, s.data, d, s.login.uuid, s.login.name, params.get("m"), s.now);
        switch(o)
        {
            case SENT:
                return WebResponse.seeOther("/support");
            case UNAVAILABLE:
                return supportPage(503, "Service Unavailable", s, MineBayPages.Notice.bad("在线客服暂不可用。"), typed, false);
            case TOO_LONG:
                return supportPage(422, "Unprocessable Entity", s, MineBayPages.Notice.bad("一条最多 " + SupportText.MAX_MESSAGE + " 个字，请分开发。"), typed, false);
            case EMPTY_OR_BAD:
                return supportPage(422, "Unprocessable Entity", s, MineBayPages.Notice.bad("请输入要问的话（不能有特殊字符）。"), typed, false);
            case BUSY:
                return supportPage(409, "Conflict", s, MineBayPages.Notice.bad(SupportPrompt.AGENT_NAME + " 还在回复上一条，请稍等再发。"), typed, false);
            case LIMIT:
                return supportPage(429, "Too Many Requests", s, MineBayPages.Notice.bad("每小时最多问 " + SupportConfig.messagesPerHour + " 条，请约 "
                        + Support.minutesToWait(d, s.login.uuid, s.now) + " 分钟后再来。"), typed, false);
            default:
                return supportPage(503, "Service Unavailable", s, MineBayPages.Notice.bad("客服太忙了，请稍后再试。"), typed, false);
        }
    }

    // ---------------------------------------------------------------- 帐号设置

    private static WebResponse account(Map<String, String> params, Session s)
    {
        if(!s.ok())
            return loginNeeded(s, "/account");
        String r = params.get("r");
        MineBayPages.Notice n = "on".equals(r) ? MineBayPages.Notice.ok("已打开“操作前必须验证身份”。")
                : "off".equals(r) ? MineBayPages.Notice.ok("已关闭“操作前必须验证身份”。") : null;
        return accountPage(200, "OK", s, n);
    }

    private static WebResponse accountPage(int status, String reason, Session s, @Nullable MineBayPages.Notice notice)
    {
        if(!s.ok())
            return loginNeeded(s, "/account");
        return page(status, reason, MineBayPages.account(s.viewer(), s.data.verifyRequired(s.login.uuid), notice));
    }

    static WebResponse page(int status, String reason, String html)
    {
        return WebResponse.xhtml(status, reason, html);
    }

    private static WebResponse loginNeeded(Session s, String next)
    {
        return page(401, "Unauthorized", MineBayPages.loginNeeded(s.viewer(), next));
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
        return !s.ok() ? 0 : s.data.cart(s.login.uuid).quantityOf(id);
    }

    private static WebResponse cart(Map<String, String> params, Session s)
    {
        if(!s.ok())
            return loginNeeded(s, "/cart");
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
        else if("unpaid".equals(r))
            notice = MineBayPages.Notice.bad("没有付款，订单已取消。购物车原样保留。");
        return cartPage(200, "OK", s, notice, Collections.<String, String>emptyMap());
    }

    private static Checkout.Plan cartPlan(Session s, Catalog catalog)
    {
        return Checkout.plan(s.data.cart(s.login.uuid).lines(), catalog::listing, id ->
        {
            Listing l = catalog.listing(id);
            return l == null ? -1 : s.remaining(l);
        });
    }

    private static WebResponse cartPage(int status, String reason, Session s, @Nullable MineBayPages.Notice notice, Map<String, String> typed)
    {
        Catalog catalog = Catalog.get();
        Cart cart = s.data.cart(s.login.uuid);
        return page(status, reason, MineBayPages.cart(s.viewer(), cart, catalog::listing, cartPlan(s, catalog), notice, typed));
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
        UUID uuid = s.login.uuid;
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
                return cartPage(409, "Conflict", s, MineBayPages.Notice.bad("购物车满了（最多 " + Cart.MAX_LINES + " 种），没有加进去。"), Collections.<String, String>emptyMap());
            case TOO_MANY:
                return page(409, "Conflict", MineBayPages.item(s.viewer(), l, inCart(s, id),
                        MineBayPages.Notice.bad("购物车里已有 " + cart.quantityOf(id) + " 份，这件最多 " + max + " 份，没有加进去。"), typed));
            default:
                return page(422, "Unprocessable Entity", MineBayPages.item(s.viewer(), l, inCart(s, id), MineBayPages.Notice.bad("数量要填 1 到 " + max + " 之间的整数。"), typed));
        }
    }

    private static WebResponse cartUpdate(Map<String, String> params, Session s)
    {
        String id = params.get("id");
        if(!ShopParams.isProductId(id))
            return badRequest(s, "商品编号不对。");
        UUID uuid = s.login.uuid;
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
                    return cartPage(409, "Conflict", s, MineBayPages.Notice.bad("这件商品已经下架，只能删除。"), Collections.<String, String>emptyMap());
                String typed = params.get("n");
                int max = Cart.limit(l.maxPerOrder);
                int n = ShopParams.quantity(typed, max);
                if(n < 0 || cart.set(id, n, l.maxPerOrder) != Cart.Change.OK)
                    return cartPage(422, "Unprocessable Entity", s, MineBayPages.Notice.bad("数量要填 1 到 " + max + " 之间的整数（不要了请点“删除”）。"),
                            Collections.singletonMap(id, typed == null ? "" : typed));
                s.data.cartChanged(uuid);
                return WebResponse.seeOther("/cart?r=updated");
            }
            default:
                return badRequest(s, "不认识的操作。");
        }
    }

    /** 结算确认要的：买哪些（购物车或立即购买的一件）；参数不对时 {@link #error} 不为 null */
    private static final class Wanted
    {
        @Nullable
        Checkout.Plan plan;
        @Nullable
        String buy;
        int qty;
        @Nullable
        WebResponse error;
    }

    private static Wanted wanted(Map<String, String> params, Session s, @Nullable String buy, int qtyIfKnown)
    {
        Wanted w = new Wanted();
        Catalog catalog = Catalog.get();
        if(buy != null)
        {
            if(!ShopParams.isProductId(buy))
            {
                w.error = badRequest(s, "商品编号不对。");
                return w;
            }
            Listing l = catalog.listing(buy);
            if(l == null)
            {
                w.error = goneItem(s);
                return w;
            }
            int qty = qtyIfKnown > 0 ? qtyIfKnown : ShopParams.quantity(params.get("n"), Cart.limit(l.maxPerOrder));
            if(qty < 0)
            {
                w.error = badRequest(s, "数量要填 1 到 " + Cart.limit(l.maxPerOrder) + " 之间的整数。");
                return w;
            }
            long left = s.remaining(l);
            w.plan = Checkout.single(l, qty, left);
            if(w.plan == null)
            {
                w.error = page(409, "Conflict", MineBayPages.item(s.viewer(), l, inCart(s, buy),
                        MineBayPages.Notice.bad(left >= 0 && left < qty ? "库存只剩 " + left + " 份。" : "这件商品现在买不了。"), Integer.toString(qty)));
                return w;
            }
            w.buy = buy;
            w.qty = qty;
        }
        else
            w.plan = cartPlan(s, catalog);
        return w;
    }

    private WebResponse checkout(Map<String, String> params, Session s, long now)
    {
        if(!s.ok())
            return loginNeeded(s, "/checkout" + (params.get("buy") != null ? "?buy=" + urlPart(params.get("buy")) + "&n=" + urlPart(String.valueOf(params.get("n"))) : ""));
        Wanted w = wanted(params, s, params.get("buy"), -1);
        if(w.error != null)
            return w.error;
        if(w.plan.isEmpty())
            return WebResponse.seeOther("/cart");
        return checkoutPage(200, "OK", s, w.plan, w.buy, w.qty, null, now);
    }

    private WebResponse checkoutPage(int status, String reason, Session s, Checkout.Plan plan, @Nullable String buy, int qty, @Nullable MineBayPages.Notice notice, long now)
    {
        String k = null;
        String loc = null;
        if(!plan.isEmpty())
        {
            k = randomHex();
            loc = nonces.issue(now);
            pending.put(s.login.uuid, new Pending(k, plan.signature, buy, qty, now));
        }
        else
            pending.remove(s.login.uuid);
        return page(status, reason, MineBayPages.checkout(s.viewer(), plan, Catalog.get()::listing, k, loc, buy, notice));
    }

    /** 提交被拒（位置、距离、登录）：回一张新的确认页说明原因，购物车原样 */
    private WebResponse checkoutRefused(Map<String, String> params, Session s, String why, int status)
    {
        return checkoutRefused(params, s, why, status, null, null);
    }

    /** {@code retryGate} 不为 null 时原因旁边有“重新验证”按钮 */
    private WebResponse checkoutRefused(Map<String, String> params, Session s, String why, int status, @Nullable String retryGate, @Nullable String label)
    {
        if(!s.ok())
            return loginNeeded(s, "/checkout");
        Pending p = pending.get(s.login.uuid);
        Wanted w = wanted(params, s, p == null ? null : p.buyId, p == null ? -1 : p.buyQuantity);
        if(w.error != null)
            return w.error;
        if(w.plan.isEmpty())
            return page(status, "Forbidden", MineBayPages.problem(s.viewer(), "没有下单", why));
        return checkoutPage(status, status == 403 ? "Forbidden" : "Conflict", s, w.plan, w.buy, w.qty,
                retryGate == null ? MineBayPages.Notice.bad(why) : MineBayPages.Notice.retry(why, retryGate, label, s.token), s.now);
    }

    private WebResponse checkoutPostMain(Map<String, String> params, Session s, long now)
    {
        if(!s.ok())
            return loginNeeded(s, "/checkout");
        UUID uuid = s.login.uuid;
        String k = params.get("k");
        Done d = done.get(uuid);
        if(k != null && d != null && d.nonce.equals(k))
            return WebResponse.seeOther(d.url);
        Pending p = pending.get(uuid);
        if(k == null || p == null || !p.nonce.equals(k) || now - p.created > CONFIRM_TTL_MS)
        {
            MrCrayfishFurnitureMod.logger().info("MineBay: checkout of {} with a stale or unknown confirmation", s.login.name);
            return page(409, "Conflict", MineBayPages.problem(s.viewer(), "确认页过期了", "这张确认页已经失效，没有下单。请重新结算。"));
        }
        pending.remove(uuid);
        Catalog catalog = Catalog.get();
        Wanted w = wanted(params, s, p.buyId, p.buyQuantity);
        if(w.error != null)
            return w.error;
        Checkout.Plan plan = w.plan;
        if(plan.isEmpty())
            return page(409, "Conflict", MineBayPages.problem(s.viewer(), "没有能买的商品", "确认之后商品下架、卖完了，或者购物车被改空了。没有下单。"));
        if(!plan.signature.equals(p.signature))
            return checkoutPage(409, "Conflict", s, plan, p.buyId, p.buyQuantity, MineBayPages.Notice.bad("确认之后购物车、价格或库存变了，没有下单。请核对后再确认一次。"), now);
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        MineBayShop.Opened o = MineBayShop.open(server, s.data, catalog, uuid, s.login.name, plan, p.buyId == null, s.scheme, now);
        if(o.url == null)
            return checkoutPage(409, "Conflict", s, plan, p.buyId, p.buyQuantity, MineBayPages.Notice.bad(o.error), now);
        done.put(uuid, new Done(k, o.url));
        return WebResponse.seeOther(o.url);
    }

    /** pay.yr 付完跳回来（{@code ?order=单号&status=…}；网址里的 status 不可信，照 pay.yr 的状态） */
    private static WebResponse paid(Map<String, String> params, Session s)
    {
        String id = params.get("order");
        if(id == null || !id.matches("ord_[a-z0-9]{16}"))
            return badRequest(s, "单号不对。");
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        PayOrders.Entry e = s.data == null ? null : s.data.payOrders().get(id);
        if(e != null && e.state() == PayOrders.State.OPEN && MineBayShop.status(id) == OrderInfo.Status.PAID)
            MineBayShop.paid(server, s.data, e);
        if(s.ok())
        {
            Orders.Order o = s.data.orders(s.login.uuid).findByPay(id);
            if(o != null)
                return WebResponse.seeOther("/orders?done=" + o.number);
        }
        if(e != null && e.state() == PayOrders.State.OPEN)
            return page(402, "Payment Required", MineBayPages.unpaid(s.viewer(), e.checkoutUrl));
        if(e != null)
            return page(200, "OK", MineBayPages.problem(s.viewer(), "付款成功", "东西会发给 " + e.buyerName + "（不在线时等上线再发）。"));
        return page(404, "Not Found", MineBayPages.problem(s.viewer(), "找不到这张单", "只能看自己的订单。"));
    }

    /** pay.yr 上点了“返回网站”：取消这张还没付的单（放回库存），购物车原样 */
    private static WebResponse unpaid(Map<String, String> params, Session s)
    {
        String id = params.get("order");
        if(id == null || !id.matches("ord_[a-z0-9]{16}"))
            return badRequest(s, "单号不对。");
        if(!s.ok())
            return loginNeeded(s, "/cart");
        PayOrders.Entry e = s.data.payOrders().get(id);
        if(e != null && e.buyer.equals(s.login.uuid))
        {
            MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
            MineBayShop.cancel(server, s.data, e);
        }
        Orders.Order o = s.data.orders(s.login.uuid).findByPay(id);
        if(o != null)
            return WebResponse.seeOther("/orders?done=" + o.number);
        return WebResponse.seeOther("/cart?r=unpaid");
    }

    private static WebResponse orders(Map<String, String> params, Session s)
    {
        if(!s.ok())
            return loginNeeded(s, "/orders");
        int p = ShopParams.page(params.get("p"));
        if(p < 0)
            return badRequest(s, "页码要是 1 到 " + ShopParams.MAX_PAGE + " 的整数。");
        long highlight = -1;
        MineBayPages.Notice notice = null;
        List<Orders.Order> list = s.data.orders(s.login.uuid).list();
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
                    notice = MineBayPages.Notice.ok("付款成功！订单 " + MineBayPages.number(no) + " 的东西已经发给 " + s.login.name + "（背包满了掉在脚边）。");
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
        if(!s.ok())
            return loginNeeded(s, "/orders");
        Orders.Order o = s.data.orders(s.login.uuid).find(no);
        if(o == null)
            return page(404, "Not Found", MineBayPages.problem(s.viewer(), "找不到这张订单", "只能看自己最近 " + Orders.MAX_ORDERS + " 单。"));
        return WebResponse.xhtml(MineBayPages.orderDetail(s.viewer(), o));
    }

    private static String urlPart(String s)
    {
        return FormEncoding.encode(Collections.singletonList(FormEncoding.field("x", s))).substring(2);
    }

    /** 测试用 */
    int sessionCount()
    {
        return sessions.size();
    }
}
