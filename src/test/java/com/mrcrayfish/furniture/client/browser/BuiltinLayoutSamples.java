package com.mrcrayfish.furniture.client.browser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** 浏览器自己的页面（主页、各种错误页、纯文字、图片），文字取模组的简体中文语言文件；网址、细节挑长的 */
final class BuiltinLayoutSamples
{
    private BuiltinLayoutSamples() {}

    static final String LONG_URL = "https://averyveryverylongdomainnamewithoutanybreaks.yr/some/very/long/path/that/keeps/going?with=query&and=more&page=12345678";

    static Map<String, String> lang() throws IOException
    {
        Map<String, String> m = new HashMap<>();
        try(InputStream in = BuiltinLayoutSamples.class.getResourceAsStream("/assets/cfm/lang/zh_cn.lang");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            String line;
            while((line = r.readLine()) != null)
            {
                int eq = line.indexOf('=');
                if(eq > 0 && !line.startsWith("#"))
                    m.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return m;
    }

    /** 名字 → 页面（XHTML） */
    static Map<String, byte[]> pages() throws IOException
    {
        Map<String, String> lang = lang();
        BuiltinPages.Tr tr = (key, args) ->
        {
            String f = lang.getOrDefault(key, key);
            try
            {
                return String.format(f, args);
            }
            catch(RuntimeException e)
            {
                return f;
            }
        };
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put("home", BuiltinPages.home(tr));
        for(BuiltinPages.Error e : BuiltinPages.Error.values())
            out.put("error-" + e.name().toLowerCase(java.util.Locale.ROOT), BuiltinPages.error(tr, e, LONG_URL,
                    "证书的名字是 averyveryverylongdomainnamewithoutanybreaks.yr，不是 shop.yr（CN=averyveryverylongdomainnamewithoutanybreaks.yr,O=QinguMoe）"));
        out.put("plain-text", BuiltinPages.plainText("text", "第一行\n" + LONG_URL + "\n" + "Supercalifragilisticexpialidocious_".replace("_", "") + "Supercalifragilisticexpialidocious"));
        out.put("image", BuiltinPages.image("https://minebay.yr/big.png"));
        return out;
    }
}
