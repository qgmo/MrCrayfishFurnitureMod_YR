package com.mrcrayfish.furniture.web;

import com.mrcrayfish.furniture.web.minebay.MineBaySite;
import moe.qingu.youran.api.network.SiteInfo;
import moe.qingu.youran.api.network.YouranNetworkApi;

/**
 * 家具模组的网站在浏览器主页 180.yr 上的导航信息（服主 11-15：名字、分类、一句话）。和域名注册分开：
 * 域名由网站开起来时自己注册（{@link Websites}），180.yr 显示时只列解析得到的。模组加载阶段调用。
 */
public final class NavEntries
{
    private NavEntries() {}

    public static void register()
    {
        YouranNetworkApi.registerNavigation(MineBaySite.DOMAIN, SiteInfo.of("MineBay", SiteInfo.Category.SHOPPING, "网上商城，买了直接进背包").featured());
    }
}
