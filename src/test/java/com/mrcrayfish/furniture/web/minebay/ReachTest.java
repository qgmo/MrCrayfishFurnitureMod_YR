package com.mrcrayfish.furniture.web.minebay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReachTest
{
    static IdLogin.Position at(double x, double y, double z)
    {
        return new IdLogin.Position(true, true, 0, x, y, z);
    }

    /** 8 格内能下单，9 格不能；按电脑方块中心算 */
    @Test
    void distance()
    {
        assertNull(Reach.check("Alice", at(10.5, 64.5, 18.5), 0, 10, 64, 10), "正好 8 格");
        assertNull(Reach.check("Alice", at(10.5, 64, 10.5), 0, 10, 64, 10));
        String far = Reach.check("Alice", at(19.5, 64.5, 10.5), 0, 10, 64, 10);
        assertNotNull(far, "9 格");
        assertTrue(far.contains("9.0 格") && far.contains("8 格内"), far);
        assertNotNull(Reach.check("Alice", at(10.5, 73, 10.5), 0, 10, 64, 10), "竖直方向也算");
    }

    /** 不在线、没在游戏里登录、在别的维度都不能下单 */
    @Test
    void presence()
    {
        assertTrue(Reach.check("Alice", new IdLogin.Position(false, false, 0, 0, 0, 0), 0, 0, 0, 0).contains("不在线"));
        assertTrue(Reach.check("Alice", new IdLogin.Position(true, false, 0, 0.5, 0.5, 0.5), 0, 0, 0, 0).contains("登录"));
        assertTrue(Reach.check("Alice", new IdLogin.Position(true, true, -1, 0.5, 0.5, 0.5), 0, 0, 0, 0).contains("世界"));
    }

    /** id.yr 的 userinfo：position + logged_in */
    @Test
    void parseUserinfo()
    {
        JsonObject on = new JsonParser().parse("{\"uuid\":\"x\",\"name\":\"Alice\",\"logged_in\":true,\"position\":{\"online\":true,\"dimension\":-1,\"x\":1.25,\"y\":64,\"z\":-3.5}}").getAsJsonObject();
        IdLogin.Position p = IdLogin.parsePosition(on);
        assertTrue(p.online && p.loggedIn);
        assertEquals(-1, p.dimension);
        assertEquals(1.25, p.x);
        assertEquals(-3.5, p.z);
        IdLogin.Position off = IdLogin.parsePosition(new JsonParser().parse("{\"logged_in\":false,\"position\":{\"online\":false}}").getAsJsonObject());
        assertFalse(off.online);
        assertThrows(IllegalStateException.class, () -> IdLogin.parsePosition(new JsonParser().parse("{\"logged_in\":true}").getAsJsonObject()), "没有 position 范围");
    }

    /** 跳 id.yr 的网址：client_id 是网站网址、回调同源、要 position 范围、PKCE S256 */
    @Test
    void authorizeUrl()
    {
        String u = IdLogin.authorizeUrl("https", "st", "verifier");
        assertTrue(u.startsWith("https://id.yr/authorize?response_type=code&client_id=https%3A%2F%2Fminebay.yr%2F&redirect_uri=https%3A%2F%2Fminebay.yr%2Flogin%2Fcallback"), u);
        assertTrue(u.contains("&scope=position") && u.contains("code_challenge_method=S256"));
        // RFC 7636 附录 B 的例子
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", IdLogin.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        assertTrue(IdLogin.issuerOk(null) && IdLogin.issuerOk("http://id.yr") && !IdLogin.issuerOk("https://evil.yr"));
        assertTrue(IdLogin.errorText("access_denied", "timeout").contains("超时"));
    }

    /** 位置字段的错误代号 → 说明；没装定位模块的话写清楚 */
    @Test
    void locationErrors()
    {
        assertEquals("这台电脑没有定位模块，不能下单。", MineBaySite.locationError("no_locator"));
        assertTrue(MineBaySite.locationError("denied").contains("没有同意"));
        assertTrue(MineBaySite.locationError("whatever").contains("whatever"));
    }

    /** 登录后跳回哪里：只许本站路径 */
    @Test
    void safeNext()
    {
        assertEquals("/cart", MineBaySite.safeNext("/cart"));
        assertEquals("/checkout?buy=config%3Aab&n=2", MineBaySite.safeNext("/checkout?buy=config%3Aab&n=2"));
        assertEquals("/", MineBaySite.safeNext("//evil.yr/"));
        assertEquals("/", MineBaySite.safeNext("https://evil.yr/"));
        assertEquals("/", MineBaySite.safeNext(null));
        assertEquals("/", MineBaySite.safeNext("/a<b>"));
    }
}
