package com.mrcrayfish.furniture.computer;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;

/**
 * 浏览器主页（服主 11-15）：两层。服务器配置给默认主页（{@code computer-home-page}，默认 {@value #DEFAULT}）；
 * 玩家可以把当前页“设为这台电脑的主页”（存在电脑方块实体里，跟着电脑走）。{@code about:home}（浏览器自带的页）保留作离线兜底：
 * 主页打不开时显示它。纯逻辑，两端都用。
 */
public final class HomePage
{
    public static final String DEFAULT = "http://180.yr/";

    private HomePage() {}

    /** 配置里写的默认主页规范化（可以只写 {@code 180.yr}）；写得不对时用 {@code about:home}，{@code problem[0]} 写原因 */
    public static String serverDefault(@Nullable String configured, String[] problem)
    {
        String c = configured == null ? "" : configured.trim();
        if(c.isEmpty() || c.equals(UrlInput.HOME))
            return UrlInput.HOME;
        UrlInput.Result r = UrlInput.fromAddressBar(c);
        if(!r.isOk() || !BrowserHistory.isStorable(r.url))
        {
            problem[0] = "computer-home-page is not a valid http/https address: " + c;
            return UrlInput.HOME;
        }
        return r.url;
    }

    /** 这台电脑设的主页能不能存：规范化过的 http / https，或者 about:home */
    public static boolean isValid(@Nullable String url)
    {
        return BrowserHistory.isStorable(url);
    }

    /** 用哪个：这台电脑设了就用它，否则服务器默认 */
    public static String effective(@Nullable String perComputer, String serverDefault)
    {
        return perComputer != null && !perComputer.isEmpty() ? perComputer : serverDefault;
    }

    /** 发给操作者：默认主页 + 换行 + 这台电脑的（没设为空） */
    public static byte[] encode(String serverDefault, @Nullable String perComputer)
    {
        return (serverDefault + "\n" + (perComputer == null ? "" : perComputer)).getBytes(StandardCharsets.UTF_8);
    }

    /** 解开；格式不对返回 null。结果 {默认, 这台电脑的（没设为 null）} */
    @Nullable
    public static String[] decode(byte[] data)
    {
        String s = new String(data, StandardCharsets.UTF_8);
        int nl = s.indexOf('\n');
        if(nl < 0)
            return null;
        String def = s.substring(0, nl);
        String own = s.substring(nl + 1);
        if(!isValid(def) || (!own.isEmpty() && !isValid(own)))
            return null;
        return new String[]{def, own.isEmpty() ? null : own};
    }
}
