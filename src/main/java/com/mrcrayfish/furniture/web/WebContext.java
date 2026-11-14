package com.mrcrayfish.furniture.web;

import moe.qingu.youran.api.network.Ipv6Address;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** 网站的运行环境（网络线程上） */
public interface WebContext
{
    /** 网站自己的地址 */
    Ipv6Address address();

    /** 交给服务端主线程做，做完 future 在网络线程上完成 */
    <T> CompletableFuture<T> onMainThread(Supplier<T> task);
}
