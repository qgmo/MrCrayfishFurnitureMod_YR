package com.mrcrayfish.furniture.web.minebay;

import javax.annotation.Nullable;

/**
 * 下单前的距离判断（服主 11-15）：MineBay 登录着的那名玩家要在线、在游戏里登录了、和电脑在同一个维度、离电脑（方块中心）{@value #MAX_DISTANCE} 格内。
 * 谁坐在电脑前操作不管（“相当于老婆帮你买东西”）。玩家坐标由 {@code id.yr} 凭访问令牌现查，电脑位置来自定位模块的签名位置证明。纯逻辑。
 */
final class Reach
{
    static final double MAX_DISTANCE = 8.0;

    private Reach() {}

    /** 能下单返回 null，否则返回给人看的原因 */
    @Nullable
    static String check(String name, IdLogin.Position p, int dimension, int x, int y, int z)
    {
        if(!p.online)
            return name + " 现在不在线，不能下单。";
        if(!p.loggedIn)
            return name + " 还没在游戏里登录，不能下单。";
        if(p.dimension != dimension)
            return name + " 不在这台电脑所在的世界，不能下单。";
        double d = distance(p, x, y, z);
        if(d > MAX_DISTANCE)
            return name + " 离这台电脑 " + String.format(java.util.Locale.ROOT, "%.1f", d) + " 格，要在 " + (int) MAX_DISTANCE + " 格内才能下单。";
        return null;
    }

    static double distance(IdLogin.Position p, int x, int y, int z)
    {
        double dx = p.x - (x + 0.5), dy = p.y - (y + 0.5), dz = p.z - (z + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
