package com.mrcrayfish.furniture.client.browser;

import moe.qingu.youran.api.network.ClientNic;
import moe.qingu.youran.api.network.stack.HttpClient;
import net.minecraft.client.Minecraft;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 取一页里的图片（客户端主线程上调用，回调也在主线程）。操作者用他操作的电脑的网卡，旁观者用自己的玩家临时地址（服主 11-14），
 * 规则一样：只取页面列出的（同主机，每页最多 {@value PageDocument#MAX_IMAGES} 张），一张一张取，每张 {@value Browser#IMAGE_TIMEOUT_MS} 毫秒超时、
 * 最大 {@value Browser#MAX_IMAGE_BYTES} 字节、边长不超过 {@value Browser#MAX_IMAGE_SIDE}，https 只信任 QinguMoe CA（{@link HttpClient} 默认），
 * 解码成 PNG 再交给内核（{@link Browser#checkedImage}）。取不到的图就不画。
 */
final class ImageFetcher
{
    private ImageFetcher() {}

    /**
     * @param wanted 还要不要（页面换了、界面关了时返回 false，剩下的不取了）
     * @param done   取完（或不要了以后不调用）：网址 → 检查过的 PNG
     */
    static void fetch(ClientNic.Session s, List<String> urls, BooleanSupplier wanted, Consumer<Map<String, byte[]>> done)
    {
        next(s, urls, 0, new HashMap<>(), wanted, done);
    }

    private static void next(ClientNic.Session s, List<String> urls, int i, Map<String, byte[]> out, BooleanSupplier wanted, Consumer<Map<String, byte[]>> done)
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
            next(s, urls, i + 1, out, wanted, done);
            return;
        }
        HttpClient.send(s.stack(), s.dns(), u, "GET", Collections.singletonMap("Accept", "image/png, image/gif, image/jpeg"), null, Browser.IMAGE_TIMEOUT_MS, null)
                .whenComplete((resp, err) -> Minecraft.getMinecraft().addScheduledTask(() ->
                {
                    if(!wanted.getAsBoolean())
                        return;
                    if(err == null && resp.status == 200 && resp.body.length <= Browser.MAX_IMAGE_BYTES)
                    {
                        byte[] png = Browser.checkedImage(resp.body);
                        if(png != null)
                            out.put(urls.get(i), png);
                    }
                    next(s, urls, i + 1, out, wanted, done);
                }));
    }
}
