package com.mrcrayfish.furniture.web.minebay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 在线客服调大模型（OpenAI 兼容的 chat/completions，服务端）。两个接口：主接口与备用接口（{@link SupportConfig}）。
 * <ul>
 * <li>一次提问：按 {@link #order} 的先后试，成功就停；主接口失败（超时、HTTP 错误、网络错误、回的不是能用的答案）就在同一次里改用备用接口；</li>
 * <li>主接口接连失败 {@value #FAILS_TO_SWITCH} 次以后 {@value #PREFER_BACKUP_MINUTES} 分钟内先试备用接口，过后再先试主接口（{@link Failover}）；</li>
 * <li>密钥只放在请求头里，不进日志；HTTP 出错时不读回来的正文（可能把请求回显出来）。</li>
 * </ul>
 * 请求在后台线程里发，结果交给回调（后台线程，回调自己回到主线程）。
 */
final class SupportLlm
{
    static final int FAILS_TO_SWITCH = 3;
    static final int PREFER_BACKUP_MINUTES = 5;
    /** 回答最多读多少字节 */
    static final int MAX_RESPONSE_BYTES = 256 * 1024;

    /** 一个接口 */
    static final class Endpoint
    {
        final String name;
        final String url;
        final String key;
        final String model;
        final boolean disableThinking;
        final boolean primary;

        Endpoint(String name, @Nullable String url, @Nullable String key, @Nullable String model, boolean disableThinking, boolean primary)
        {
            this.name = name;
            this.url = url == null ? "" : url.trim();
            this.key = key == null ? "" : key.trim();
            this.model = model == null ? "" : model.trim();
            this.disableThinking = disableThinking;
            this.primary = primary;
        }

        /** 地址（http / https）与密钥都填了才算配了 */
        boolean usable()
        {
            return !key.isEmpty() && (url.startsWith("https://") || url.startsWith("http://"));
        }

        /** 给日志看的：只有主机名 */
        String host()
        {
            try
            {
                return new URL(url).getHost();
            }
            catch(IOException e)
            {
                return "?";
            }
        }
    }

    /** 一条对话消息（role 是 system / user / assistant） */
    static final class Message
    {
        final String role;
        final String content;

        Message(String role, String content)
        {
            this.role = role;
            this.content = content;
        }
    }

    /** 结果：成功时有回答，失败时有原因（不含密钥） */
    static final class Result
    {
        @Nullable
        final String content;
        @Nullable
        final String error;
        /** 用的哪个接口（最后试的那个） */
        @Nullable
        final String endpoint;

        Result(@Nullable String content, @Nullable String error, @Nullable String endpoint)
        {
            this.content = content;
            this.error = error;
            this.endpoint = endpoint;
        }

        boolean ok()
        {
            return content != null;
        }
    }

    /** 发一个请求、读回答正文；失败抛 {@link IOException}（测试换成假的） */
    interface Transport
    {
        String post(Endpoint endpoint, String body, int timeoutMs) throws IOException;
    }

    /** 主接口接连失败几次以后，一段时间里先用备用接口（纯逻辑） */
    static final class Failover
    {
        private final int failsToSwitch;
        private final long preferMillis;
        private int fails;
        private long preferBackupUntil;

        Failover(int failsToSwitch, long preferMillis)
        {
            this.failsToSwitch = failsToSwitch;
            this.preferMillis = preferMillis;
        }

        synchronized boolean preferBackup(long now)
        {
            return now < preferBackupUntil;
        }

        /** 记主接口这一次的结果；刚好因此改成先用备用接口时返回 true */
        synchronized boolean primaryResult(boolean ok, long now)
        {
            if(ok)
            {
                fails = 0;
                preferBackupUntil = 0;
                return false;
            }
            fails++;
            if(fails >= failsToSwitch)
            {
                fails = 0;
                preferBackupUntil = now + preferMillis;
                return true;
            }
            return false;
        }
    }

    private final Supplier<Endpoint> primary;
    private final Supplier<Endpoint> backup;
    private final Transport transport;
    private final Supplier<Integer> timeoutSeconds;
    final Failover failover = new Failover(FAILS_TO_SWITCH, PREFER_BACKUP_MINUTES * 60_000L);
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), r ->
    {
        Thread t = new Thread(r, "MineBay-support");
        t.setDaemon(true);
        return t;
    });

    SupportLlm(Supplier<Endpoint> primary, Supplier<Endpoint> backup, Supplier<Integer> timeoutSeconds, Transport transport)
    {
        this.primary = primary;
        this.backup = backup;
        this.timeoutSeconds = timeoutSeconds;
        this.transport = transport;
        workers.allowCoreThreadTimeOut(true);
    }

    /** 用配置文件里的接口、真的 HTTP */
    static SupportLlm fromConfig()
    {
        return new SupportLlm(SupportConfig::primary, SupportConfig::backup, () -> SupportConfig.timeoutSeconds, new Http());
    }

    /** 主接口或备用接口配了一个就开客服 */
    boolean configured()
    {
        return primary.get().usable() || backup.get().usable();
    }

    /** 开服日志用：哪个接口、哪个模型（不含密钥） */
    String describe()
    {
        Endpoint p = primary.get();
        Endpoint b = backup.get();
        return (p.usable() ? "primary " + p.host() + " (" + p.model + ")" : "no primary") + ", " + (b.usable() ? "backup " + b.host() + " (" + b.model + ")" : "no backup");
    }

    /** 这次按什么顺序试（只含配了的接口） */
    List<Endpoint> order(long now)
    {
        Endpoint p = primary.get();
        Endpoint b = backup.get();
        List<Endpoint> out = new ArrayList<>(2);
        boolean backupFirst = b.usable() && failover.preferBackup(now);
        if(backupFirst)
            out.add(b);
        if(p.usable())
            out.add(p);
        if(!backupFirst && b.usable())
            out.add(b);
        return out;
    }

    /** 请求体 */
    static String body(Endpoint ep, List<Message> messages, int maxTokens, double temperature)
    {
        JsonObject req = new JsonObject();
        req.addProperty("model", ep.model);
        req.addProperty("temperature", temperature);
        req.addProperty("max_tokens", maxTokens);
        req.addProperty("stream", false);
        if(ep.disableThinking)
        {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "disabled");
            req.add("thinking", thinking);
        }
        JsonArray arr = new JsonArray();
        for(Message m : messages)
        {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role);
            o.addProperty("content", m.content);
            arr.add(o);
        }
        req.add("messages", arr);
        return req.toString();
    }

    /** 从回答正文里取出 choices[0].message.content；取不到抛 {@link IOException} */
    static String content(String response) throws IOException
    {
        try
        {
            JsonObject o = new JsonParser().parse(response).getAsJsonObject();
            JsonElement c = o.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content");
            if(c == null || c.isJsonNull())
                throw new IOException("no content in the answer");
            String s = c.getAsString();
            if(s.trim().isEmpty())
                throw new IOException("empty answer");
            return s;
        }
        catch(RuntimeException e)
        {
            throw new IOException("the answer is not a chat completion", e);
        }
    }

    /** 同步问一次（后台线程上调用）：按顺序试各个接口 */
    Result askNow(List<Message> messages, int maxTokens, double temperature)
    {
        List<Endpoint> eps = order(System.currentTimeMillis());
        if(eps.isEmpty())
            return new Result(null, "not configured", null);
        int timeoutMs = Math.max(1, timeoutSeconds.get()) * 1000;
        Result last = null;
        for(Endpoint ep : eps)
        {
            Result r;
            try
            {
                r = new Result(content(transport.post(ep, body(ep, messages, maxTokens, temperature), timeoutMs)), null, ep.name);
            }
            catch(IOException | RuntimeException e)
            {
                r = new Result(null, describe(e), ep.name);
            }
            if(ep.primary && failover.primaryResult(r.ok(), System.currentTimeMillis()))
                Support.log().warn("MineBay support: primary endpoint failed {} times in a row (last: {}); using the backup first for {} minutes",
                        FAILS_TO_SWITCH, r.error, PREFER_BACKUP_MINUTES);
            if(r.ok())
                return r;
            if(last == null || eps.size() > 1)
                Support.log().warn("MineBay support: {} endpoint failed: {}", ep.name, r.error);
            last = r;
        }
        return last;
    }

    /** 在后台问；{@code done} 只调用一次（后台线程，或排不上队时当场） */
    void ask(List<Message> messages, int maxTokens, double temperature, Consumer<Result> done)
    {
        try
        {
            workers.execute(() -> done.accept(askNow(messages, maxTokens, temperature)));
        }
        catch(RejectedExecutionException e)
        {
            done.accept(new Result(null, "too many questions waiting", null));
        }
    }

    private static String describe(Throwable e)
    {
        Throwable c = e;
        while(c.getCause() != null && c.getMessage() == null)
            c = c.getCause();
        String m = c.getMessage();
        return c.getClass().getSimpleName() + (m == null ? "" : ": " + (m.length() > 120 ? m.substring(0, 120) : m));
    }

    /** 真的 HTTP：连接加读取共 timeoutMs，到时由另一个线程掐断 */
    static final class Http implements Transport
    {
        private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread t = new Thread(r, "MineBay-support-timer");
            t.setDaemon(true);
            return t;
        });

        @Override
        public String post(Endpoint ep, String body, int timeoutMs) throws IOException
        {
            HttpURLConnection c = (HttpURLConnection) new URL(ep.url).openConnection();
            boolean[] timedOut = {false};
            ScheduledFuture<?> killer = TIMER.schedule(() ->
            {
                timedOut[0] = true;
                c.disconnect();
            }, timeoutMs, TimeUnit.MILLISECONDS);
            try
            {
                c.setConnectTimeout(timeoutMs);
                c.setReadTimeout(timeoutMs);
                c.setInstanceFollowRedirects(false);
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("Authorization", "Bearer " + ep.key);
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(b.length);
                try(OutputStream out = c.getOutputStream())
                {
                    out.write(b);
                }
                int code = c.getResponseCode();
                if(code != 200)
                    throw new IOException("HTTP " + code);
                try(InputStream in = c.getInputStream())
                {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    int n;
                    while((n = in.read(chunk)) > 0)
                    {
                        if(buf.size() + n > MAX_RESPONSE_BYTES)
                            throw new IOException("answer too long");
                        buf.write(chunk, 0, n);
                    }
                    return new String(buf.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            catch(IOException e)
            {
                if(timedOut[0])
                    throw new IOException("timed out after " + timeoutMs / 1000 + " s");
                throw e;
            }
            finally
            {
                killer.cancel(false);
                c.disconnect();
            }
        }
    }
}
