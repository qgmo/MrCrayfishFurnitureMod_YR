package com.mrcrayfish.furniture.web;

import moe.qingu.youran.api.network.Ipv6Address;
import moe.qingu.youran.api.network.stack.NetStack;

import javax.annotation.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** 网站的运行环境（网络线程上） */
public interface WebContext
{
    /** 网站自己的地址 */
    Ipv6Address address();

    /** 交给服务端主线程做，做完 future 在网络线程上完成 */
    <T> CompletableFuture<T> onMainThread(Supplier<T> task);

    /**
     * 网站自己的协议栈（在网络线程上用）：后台要访问游戏内网络上别的服务（如 {@code id.yr} 兑换授权码、查玩家信息）时用。
     * 不是机房主机（测试里）时为 null。
     */
    @Nullable
    default NetStack stack()
    {
        return null;
    }

    /** 这台主机开了 HTTPS（服务器配了中间 CA） */
    default boolean hasTls()
    {
        return false;
    }
}
