package com.mrcrayfish.furniture.web.minebay;

import com.google.gson.JsonObject;
import moe.qingu.youran.api.network.idyr.IdYrClient;
import moe.qingu.youran.api.network.idyr.IdYrException;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.NameCache;
import moe.qingu.youran.api.network.stack.NetStack;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * MineBay 当 {@code id.yr}（游戏角色一键登录，YouranAuth 提供）的普通 OAuth 网站（服主 11-15）：授权码 + PKCE（S256），不预先注册，
 * {@code client_id} 就是 {@code http(s)://minebay.yr/}；要 {@code position} 范围，结算时凭访问令牌现查玩家在线状态与精确坐标。{@code verify} 登录时不要
 * （服主 11-16）：玩家打开“操作前必须验证身份”时才追加授权，打开以后下单前请 id.yr 验证身份。用 YouranMod 的 {@link IdYrClient}，兑换、查信息、验证都走游戏内网络（MineBay 这台主机的协议栈），
 * 不在代码上依赖 YouranAuth。网络线程上用。
 */
final class IdLogin
{
    static final String DOMAIN = IdYrClient.DEFAULT_DOMAIN;
    /** 登录时要 position（结算时现查位置）；verify 在打开“操作前必须验证身份”时追加 */
    static final String SCOPE = "position";
    static final long TIMEOUT_MS = IdYrClient.TIMEOUT_MS;

    /** 兑换出来的：角色与访问令牌 */
    static final class Token
    {
        final UUID uuid;
        final String name;
        final String accessToken;
        /** 令牌什么时候过期（毫秒） */
        final long expiresAt;
        final java.util.Set<String> scopes;

        Token(UUID uuid, String name, String accessToken, long expiresAt, java.util.Set<String> scopes)
        {
            this.uuid = uuid;
            this.name = name;
            this.accessToken = accessToken;
            this.expiresAt = expiresAt;
            this.scopes = scopes;
        }
    }

    /** 现查的位置（userinfo 的 position、logged_in） */
    static final class Position
    {
        final boolean online;
        /** 在游戏里登录了（YouranAuth） */
        final boolean loggedIn;
        final int dimension;
        final double x, y, z;

        Position(boolean online, boolean loggedIn, int dimension, double x, double y, double z)
        {
            this.online = online;
            this.loggedIn = loggedIn;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** 令牌失效（401）：要重新登录 */
    static final class TokenExpired extends RuntimeException
    {
        TokenExpired()
        {
            super("id.yr 的访问令牌失效了");
        }
    }

    private IdLogin() {}

    /** id.yr 解析得到没有（MineBay 主机的协议栈经递归解析器查，按 TTL 缓存）；主机起来以前为 null */
    private static volatile NameCache names;

    /** MineBay 主机起来了（网络线程）：开始查 id.yr */
    static void attach(@Nullable NetStack stack)
    {
        moe.qingu.youran.api.network.Ipv6Address resolver = moe.qingu.youran.api.network.YouranNetworkApi.resolverAddress();
        if(stack == null || resolver == null)
            return;
        NameCache n = new NameCache(new DnsResolver(stack, resolver), stack.loop());
        n.refresh(DOMAIN);
        names = n;
    }

    /** 这个服务器开着 id.yr（装了 YouranAuth 并开了 ingame-id）：id.yr 经递归解析器解析得到地址（上一次的回答，到期在后台再问） */
    static boolean available()
    {
        NameCache n = names;
        return n != null && n.resolves(DOMAIN);
    }

    /** MineBay 这台主机的 id.yr 客户端（{@code tls}：后台请求走 https） */
    static IdYrClient client(@Nullable NetStack stack, boolean tls)
    {
        return stack == null ? new IdYrClient(null, null, DOMAIN, tls, MineBaySite.DOMAIN) : IdYrClient.of(stack, DOMAIN, tls, MineBaySite.DOMAIN);
    }

    static String authorizeUrl(String scheme, String state, String verifier)
    {
        return client(null, false).authorizeUrl(scheme, state, verifier, SCOPE);
    }

    /** 跳回来的 {@code iss} 对不对（没带时不查） */
    static boolean issuerOk(@Nullable String iss)
    {
        return client(null, false).issuerOk(iss);
    }

    /** id.yr 跳回来时带的错误 → 给玩家看的说明 */
    static String errorText(String error, @Nullable String description)
    {
        return IdYrClient.loginErrorText(error, description);
    }

    /** 后台兑换授权码 */
    static CompletableFuture<Token> exchange(NetStack stack, boolean tls, String scheme, String code, String verifier)
    {
        return client(stack, tls).exchange(scheme, code, verifier).thenApply(t -> new Token(t.uuid, t.name, t.accessToken, t.expiresAt, t.scopes));
    }

    /** 凭访问令牌现查玩家的在线状态与精确坐标；令牌失效时以 {@link TokenExpired} 结束 */
    static CompletableFuture<Position> position(NetStack stack, boolean tls, String accessToken)
    {
        return client(stack, tls).userinfo(accessToken).handle((o, e) ->
        {
            if(o != null)
                return parsePosition(o);
            Throwable c = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            if(c instanceof IdYrException && ((IdYrException) c).tokenExpired())
                throw new TokenExpired();
            throw c instanceof RuntimeException ? (RuntimeException) c : new IllegalStateException(c);
        });
    }

    /** userinfo 里的 {@code position}、{@code logged_in}（单元测试也用） */
    static Position parsePosition(JsonObject o)
    {
        boolean loggedIn = o.has("logged_in") && o.get("logged_in").getAsBoolean();
        if(!o.has("position") || !o.get("position").isJsonObject())
            throw new IllegalStateException("id.yr 没给位置（没有 position 范围）");
        JsonObject p = o.getAsJsonObject("position");
        if(!p.has("online") || !p.get("online").getAsBoolean())
            return new Position(false, loggedIn, 0, 0, 0, 0);
        return new Position(true, loggedIn, p.get("dimension").getAsInt(), p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble());
    }

    static String challenge(String verifier)
    {
        return IdYrClient.challenge(verifier);
    }

    static String enc(String s)
    {
        return IdYrClient.enc(s);
    }
}
