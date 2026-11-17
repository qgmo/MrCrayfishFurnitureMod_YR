package com.mrcrayfish.furniture.web;

import java.util.concurrent.CompletableFuture;

/**
 * 网站：收到一个请求，返回一页（在网络线程上调用，不要阻塞，不要读写世界；要动游戏状态时用
 * {@link WebContext#onMainThread}，做完再回）。抛异常或 future 以异常结束时回 500。
 */
@FunctionalInterface
public interface WebHandler
{
    CompletableFuture<WebResponse> handle(WebRequest request, WebContext context);

    /** 网站那台主机起来了（网络线程，每次开服一次）；默认什么都不做 */
    default void started(WebContext context) {}
}
