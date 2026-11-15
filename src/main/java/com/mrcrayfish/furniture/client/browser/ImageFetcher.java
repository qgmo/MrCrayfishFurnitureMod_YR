package com.mrcrayfish.furniture.client.browser;

import moe.qingu.youran.api.network.ClientNic;
import moe.qingu.youran.api.network.stack.HttpClient;
import net.minecraft.client.Minecraft;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 取一页里的图片（客户端主线程上调用，回调也在主线程）。操作者用他操作的电脑的网卡，旁观者用自己的玩家临时地址（服主 11-14），
 * 规则一样：只取页面列出的（同主机或 captcha.yr 的验证码图片，每页最多 {@value PageDocument#MAX_IMAGES} 张），一张一张取，每张 {@value Browser#IMAGE_TIMEOUT_MS} 毫秒超时、
 * 最大 {@value Browser#MAX_IMAGE_BYTES} 字节、边长不超过 {@value Browser#MAX_IMAGE_SIDE}，https 只信任 QinguMoe CA（{@link HttpClient} 默认），
 * 解码成 PNG 再交给内核（{@link Browser#checkedImage}）。取不到的图就不画。
 */
final class ImageFetcher
{
    private ImageFetcher() {}

    /** 取图时带哪些 Cookie、收到的 Cookie 交给谁（操作者才有；旁观者传 null，拿不到 Cookie） */
    interface Cookies
    {
        @Nullable
        String header(String url);

        void received(String url, List<String> setCookies);
    }

    /**
     * @param cookies 操作者的 Cookie（旁观者为 null）
     * @param wanted  还要不要（页面换了、界面关了时返回 false，剩下的不取了）
     * @param done    取完（或不要了以后不调用）：网址 → 检查过的 PNG
     */
    static void fetch(ClientNic.Session s, List<String> urls, BooleanSupplier wanted, @Nullable Cookies cookies, Consumer<Map<String, byte[]>> done)
    {
        next(s, urls, 0, new HashMap<>(), wanted, cookies, done);
    }

    private static void next(ClientNic.Session s, List<String> urls, int i, Map<String, byte[]> out, BooleanSupplier wanted, @Nullable Cookies cookies,
                             Consumer<Map<String, byte[]>> done)
    {
        if(!wanted.getAsBoolean())
            return;
        if(i >= urls.size() || i >= PageDocument.MAX_IMAGES || s.isClosed())
        {
            done.accept(out);
            return;
        }
        HttpClient.Url u = HttpClient.Url.parse(urls.get(i));
        if(u == null)
        {
            next(s, urls, i + 1, out, wanted, cookies, done);
            return;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "image/png, image/gif, image/jpeg");
        String cookie = cookies == null ? null : cookies.header(urls.get(i));
        if(cookie != null)
            headers.put("Cookie", cookie);
        HttpClient.send(s.stack(), s.dns(), u, "GET", headers, null, Browser.IMAGE_TIMEOUT_MS, null)
                .whenComplete((resp, err) -> Minecraft.getMinecraft().addScheduledTask(() ->
                {
                    if(!wanted.getAsBoolean())
                        return;
                    if(err == null && cookies != null)
                        cookies.received(urls.get(i), resp.headerValues("set-cookie"));
                    if(err == null && resp.status == 200 && resp.body.length <= Browser.MAX_IMAGE_BYTES)
                    {
                        byte[] png = Browser.checkedImage(resp.body);
                        if(png != null)
                            out.put(urls.get(i), png);
                    }
                    next(s, urls, i + 1, out, wanted, cookies, done);
                }));
    }
}
