package com.mrcrayfish.furniture.web.minebay;

import com.google.gson.JsonObject;
import moe.qingu.youran.api.network.idyr.IdYrClient;
import moe.qingu.youran.api.network.idyr.IdYrException;
import moe.qingu.youran.api.network.stack.NetStack;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * MineBay 当 {@code id.yr}（游戏角色一键登录，YouranAuth 提供）的普通 OAuth 网站（服主 11-15）：授权码 + PKCE（S256），不预先注册，
 * {@code client_id} 就是 {@code http(s)://minebay.yr/}；要 {@code position} 范围，结算时凭访问令牌现查玩家在线状态与精确坐标；要 {@code verify} 范围，
 * 帐号开了“操作前必须验证身份”时请 id.yr 验证身份。用 YouranMod 的 {@link IdYrClient}，兑换、查信息、验证都走游戏内网络（MineBay 这台主机的协议栈），
 * 不在代码上依赖 YouranAuth。网络线程上用。
 */
final class IdLogin
{
    static final String DOMAIN = IdYrClient.DEFAULT_DOMAIN;
    /** 要 position（结算时现查位置）与 verify（帐号设置“操作前必须验证身份”用） */
    static final String SCOPE = "position verify";
    static final long TIMEOUT_MS = IdYrClient.TIMEOUT_MS;

    /** 兑换出来的：角色与访问令牌 */
    static final class Token
    {
        final UUID uuid;
        final String name;
        final String accessToken;
        /** 令牌什么时候过期（毫秒） */
        final long expiresAt;

        Token(UUID uuid, String name, String accessToken, long expiresAt)
        {
            this.uuid = uuid;
            this.name = name;
            this.accessToken = accessToken;
            this.expiresAt = expiresAt;
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

    /** 这个服务器开着 id.yr（装了 YouranAuth 并开了 ingame-id） */
    static boolean available()
    {
        return IdYrClient.available(DOMAIN);
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
        return client(stack, tls).exchange(scheme, code, verifier).thenApply(t -> new Token(t.uuid, t.name, t.accessToken, t.expiresAt));
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
