package com.mrcrayfish.furniture.web;

import com.mrcrayfish.furniture.Reference;
import moe.qingu.youran.api.network.YouranNetworkApi;
import moe.qingu.youran.api.network.ca.CertificateStore;

import java.nio.file.Path;

/**
 * 游戏内网站的证书存在哪（只在服务端、网络线程上用）：世界存档的 {@code cfm/certs/<主机>/}，
 * 主机是登记名去掉 {@code cfm:}（如 {@code cfm:minebay} → {@code cfm/certs/minebay/}；别的模组的登记名把 {@code :}、{@code /} 换成 {@code _}）。
 * 里面是 {@code fullchain.pem} 与 {@code privkey.pem}，开服时还能用就直接用、不再申请。
 * <p>
 * 单独放一个类：客户端也会加载 {@link Websites}，这里用到的 YouranMod 接口只在服务端的完整包里有。
 */
final class WebCertificates
{
    private WebCertificates() {}

    /** 存储；网络没在运行（找不到世界存档）或登记名不合适做目录名时为 null（不存） */
    static CertificateStore store(String hostKey)
    {
        Path world = YouranNetworkApi.worldDirectory();
        String dir = dirName(hostKey);
        if (world == null || dir == null)
        {
            return null;
        }
        return CertificateStore.directory(world.resolve(Reference.MOD_ID).resolve("certs").resolve(dir));
    }

    static String dirName(String hostKey)
    {
        String prefix = Reference.MOD_ID + ":";
        String name = hostKey.startsWith(prefix) ? hostKey.substring(prefix.length()) : hostKey;
        name = name.replace(':', '_').replace('/', '_');
        return name.isEmpty() || name.equals(".") || name.equals("..") ? null : name;
    }
}
