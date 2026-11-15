package com.mrcrayfish.furniture.web.minebay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import moe.qingu.youran.api.network.YouranNetworkApi;
import moe.qingu.youran.api.network.stack.DnsResolver;
import moe.qingu.youran.api.network.stack.HttpClient;
import moe.qingu.youran.api.network.stack.NetStack;

import javax.annotation.Nullable;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * MineBay 当 {@code id.yr}（游戏角色一键登录，YouranAuth 提供）的普通 OAuth 网站（服主 11-15）：授权码 + PKCE（S256），不预先注册，
 * {@code client_id} 就是 {@code http(s)://minebay.yr/}；要 {@code position} 范围，结算时凭访问令牌现查玩家在线状态与精确坐标。
 * 兑换、查信息都走游戏内网络（MineBay 这台主机的协议栈），不在代码上依赖 YouranAuth。网络线程上用。
 */
final class IdLogin
{
    static final String DOMAIN = "id.yr";
    static final String SCOPE = "position";
    static final long TIMEOUT_MS = 10_000;

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
        return YouranNetworkApi.addressOfName(DOMAIN) != null;
    }

    static String clientId(String scheme)
    {
        return scheme + "://" + MineBaySite.DOMAIN + "/";
    }

    static String redirectUri(String scheme)
    {
        return scheme + "://" + MineBaySite.DOMAIN + "/login/callback";
    }

    static String authorizeUrl(String scheme, String state, String verifier)
    {
        return scheme + "://" + DOMAIN + "/authorize?response_type=code&client_id=" + enc(clientId(scheme)) + "&redirect_uri=" + enc(redirectUri(scheme))
                + "&state=" + enc(state) + "&code_challenge=" + challenge(verifier) + "&code_challenge_method=S256&scope=" + SCOPE;
    }

    /** 跳回来的 {@code iss} 对不对（没带时不查） */
    static boolean issuerOk(@Nullable String iss)
    {
        return iss == null || iss.equals("https://" + DOMAIN) || iss.equals("http://" + DOMAIN);
    }

    /** id.yr 跳回来时带的错误 → 给玩家看的说明 */
    static String errorText(String error, @Nullable String description)
    {
        switch(error)
        {
            case "login_unavailable":
                return "no_operator".equals(description) ? "这台电脑现在没人操作，不能登录。" : "这台电脑不能用游戏角色一键登录。";
            case "access_denied":
                if("timeout".equals(description))
                    return "确认框超时了，没有登录。";
                if("operator_changed".equals(description))
                    return "确认时离开了电脑或换了人，没有登录。";
                return "你在确认框里拒绝了登录。";
            case "temporarily_unavailable":
                return "busy".equals(description) ? "已经有一个确认框开着，先处理那个。" : "登录服务暂时不可用，请稍后再试。";
            default:
                return "登录失败（" + error + "）。";
        }
    }

    private static DnsResolver resolver(NetStack stack)
    {
        return new DnsResolver(stack, YouranNetworkApi.resolverAddress());
    }

    /** 后台兑换授权码 */
    static CompletableFuture<Token> exchange(NetStack stack, boolean tls, String scheme, String code, String verifier)
    {
        String body = "grant_type=authorization_code&code=" + enc(code) + "&client_id=" + enc(clientId(scheme)) + "&redirect_uri=" + enc(redirectUri(scheme))
                + "&code_verifier=" + enc(verifier);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("Accept", "application/json");
        HttpClient.Url url = HttpClient.Url.parse((tls ? "https://" : "http://") + DOMAIN + "/token");
        long now = System.currentTimeMillis();
        return HttpClient.send(stack, resolver(stack), url, "POST", h, body.getBytes(StandardCharsets.US_ASCII), TIMEOUT_MS, null).thenApply(r ->
        {
            JsonObject o = json(r.bodyText(), r.status);
            if(r.status != 200 || !o.has("uuid") || !o.has("name") || !o.has("access_token"))
                throw new IllegalStateException("id.yr 拒绝兑换：" + (o.has("error") ? o.get("error").getAsString() : "HTTP " + r.status));
            long ttl = o.has("expires_in") ? o.get("expires_in").getAsLong() : 3600;
            return new Token(UUID.fromString(o.get("uuid").getAsString()), o.get("name").getAsString(), o.get("access_token").getAsString(),
                    now + Math.max(60, Math.min(86400, ttl)) * 1000L);
        });
    }

    /** 凭访问令牌现查玩家的在线状态与精确坐标；令牌失效时以 {@link TokenExpired} 结束 */
    static CompletableFuture<Position> position(NetStack stack, boolean tls, String accessToken)
    {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + accessToken);
        h.put("Accept", "application/json");
        HttpClient.Url url = HttpClient.Url.parse((tls ? "https://" : "http://") + DOMAIN + "/userinfo");
        return HttpClient.send(stack, resolver(stack), url, "GET", h, null, TIMEOUT_MS, null).thenApply(r ->
        {
            if(r.status == 401)
                throw new TokenExpired();
            JsonObject o = json(r.bodyText(), r.status);
            if(r.status != 200)
                throw new IllegalStateException("id.yr 查不了信息：HTTP " + r.status);
            return parsePosition(o);
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

    private static JsonObject json(String text, int status)
    {
        try
        {
            JsonElement e = new JsonParser().parse(text);
            return e.getAsJsonObject();
        }
        catch(RuntimeException ex)
        {
            throw new IllegalStateException("id.yr 回的不是 JSON（HTTP " + status + "）");
        }
    }

    static String challenge(String verifier)
    {
        try
        {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        }
        catch(NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }

    static String enc(String s)
    {
        try
        {
            return URLEncoder.encode(s, "UTF-8");
        }
        catch(UnsupportedEncodingException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
