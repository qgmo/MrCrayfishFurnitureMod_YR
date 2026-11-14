package com.mrcrayfish.furniture.client;

import com.google.common.primitives.Longs;
import com.mrcrayfish.furniture.handler.ConfigurationHandler;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 相框、电视图片的下载（悠然服务器魔改）。
 * <ul>
 * <li>下载与解码都在固定大小的线程池里进行（大小见配置 media-download-threads），
 * 原来每个相框、电视各开一个线程，数量没有上限；</li>
 * <li>连接、读取都有超时，实际读到的字节数也不超过上限（原来只看 Content-Length）；</li>
 * <li>重定向不再自动跟随：目标仍须是 http(s) 且在受信任域名里才跟，最多 3 次，白名单不会被跳转绕过。</li>
 * </ul>
 */
public final class MediaDownloader
{
    public static final long MAX_FILE_SIZE = 2097152;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_REDIRECTS = 3;

    private static ExecutorService executor;

    private MediaDownloader() {}

    public enum Status
    {
        OK, WRONG_TYPE, UNKNOWN_SIZE, TOO_LARGE, UNTRUSTED, FAILED
    }

    public static final class Response
    {
        public final Status status;
        public final byte[] data;

        Response(Status status, byte[] data)
        {
            this.status = status;
            this.data = data;
        }
    }

    public static synchronized ExecutorService executor()
    {
        if(executor == null)
        {
            int threads = Math.max(1, Math.min(8, ConfigurationHandler.mediaDownloadThreads));
            AtomicInteger counter = new AtomicInteger();
            ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r ->
            {
                Thread thread = new Thread(r, "CFM Media Loader #" + counter.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
            pool.allowCoreThreadTimeOut(true);
            executor = pool;
        }
        return executor;
    }

    public static Response download(URI uri, String... contentTypes)
    {
        URI current = uri;
        try
        {
            for(int hop = 0; hop <= MAX_REDIRECTS; hop++)
            {
                URLConnection connection = current.toURL().openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.addRequestProperty("User-Agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.0)");
                if(connection instanceof HttpURLConnection)
                {
                    HttpURLConnection http = (HttpURLConnection) connection;
                    http.setInstanceFollowRedirects(false);
                    int code = http.getResponseCode();
                    if(code >= 300 && code < 400)
                    {
                        String location = http.getHeaderField("Location");
                        http.disconnect();
                        if(location == null)
                            return new Response(Status.FAILED, null);
                        URI next = current.resolve(location);
                        if(!DownloadUtils.isValidScheme(next) || !DownloadUtils.isTrustedDomain(next))
                            return new Response(Status.UNTRUSTED, null);
                        current = next;
                        continue;
                    }
                    if(code != 200)
                    {
                        http.disconnect();
                        return new Response(Status.FAILED, null);
                    }
                }

                boolean typeOk = false;
                for(String type : contentTypes)
                {
                    if(type.equals(connection.getContentType()))
                    {
                        typeOk = true;
                        break;
                    }
                }
                if(!typeOk)
                    return new Response(Status.WRONG_TYPE, null);

                String lengthString = connection.getHeaderField("Content-Length");
                Long length = lengthString == null ? null : Longs.tryParse(lengthString);
                if(length == null)
                    return new Response(Status.UNKNOWN_SIZE, null);
                if(length > MAX_FILE_SIZE)
                    return new Response(Status.TOO_LARGE, null);

                try(InputStream in = connection.getInputStream())
                {
                    byte[] data = MediaDecoding.readAtMost(in, MAX_FILE_SIZE);
                    if(data == null)
                        return new Response(Status.TOO_LARGE, null);
                    return new Response(Status.OK, data);
                }
            }
            return new Response(Status.FAILED, null);
        }
        catch(IOException | RuntimeException e)
        {
            return new Response(Status.FAILED, null);
        }
    }
}
