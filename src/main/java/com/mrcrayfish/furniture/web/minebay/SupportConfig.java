package com.mrcrayfish.furniture.web.minebay;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/**
 * MineBay 在线客服用的大模型（服主 11-15），单独一个配置文件 {@code config/cfm_llm.cfg}：里面有密钥，{@code cfm.cfg} 可以随便发给人看，这个文件不要。
 * 密钥只在服务端用，不写进日志、不发给客户端。只在服务器启动时读（改了要重启）。
 * <ul>
 * <li>主接口 {@code url} / {@code model} / {@code apiKey} / {@code disableThinking}，默认 DeepSeek；</li>
 * <li>备用接口 {@code backupUrl} / {@code backupModel} / {@code backupApiKey} / {@code backupDisableThinking}：主接口失败时同一次改用它，
 * 主接口接连失败 3 次后 5 分钟内先用备用接口；</li>
 * <li>{@code timeoutSeconds}：每个接口一次最多等几秒；</li>
 * <li>{@code messagesPerHour}：每个帐号每小时最多问几条。</li>
 * </ul>
 * 主接口与备用接口都没配（{@code apiKey} 留空）= 不开客服，客服页写“暂不可用”。
 */
public final class SupportConfig
{
    public static final String FILE = "cfm_llm.cfg";
    static final String LLM = "llm";
    static final String SUPPORT = "support";

    public static volatile String url = "https://api.deepseek.com/chat/completions";
    public static volatile String model = "deepseek-chat";
    public static volatile String apiKey = "";
    public static volatile boolean disableThinking = true;
    public static volatile String backupUrl = "";
    public static volatile String backupModel = "";
    public static volatile String backupApiKey = "";
    public static volatile boolean backupDisableThinking = false;
    public static volatile int timeoutSeconds = 15;
    public static volatile int messagesPerHour = 20;

    private SupportConfig() {}

    /** 服务器启动时读（没有时按默认值生成） */
    public static void load(File configDir)
    {
        try
        {
            Configuration c = new Configuration(new File(configDir, FILE));
            c.setCategoryComment(LLM, "MineBay 在线客服“小 Bay”用的大模型（OpenAI 兼容的 chat/completions 接口）。这个文件里有密钥，不要发给别人。");
            url = c.getString("url", LLM, "https://api.deepseek.com/chat/completions", "主接口：chat/completions 的完整地址（只认 http / https）。默认 DeepSeek");
            model = c.getString("model", LLM, "deepseek-chat", "主接口的模型名。默认 deepseek-chat");
            apiKey = c.getString("apiKey", LLM, "", "主接口的 API 密钥（Authorization: Bearer 后面那段）。只在服务端用：不写进日志、不发给客户端。主接口、备用接口都留空 = 不开客服");
            disableThinking = c.getBoolean("disableThinking", LLM, true,
                    "主接口是不是 DeepSeek 那种要关“思考”的：true 时请求里带 \"thinking\": {\"type\": \"disabled\"}。别家接口不认这个字段可能报错，那就设 false");
            backupUrl = c.getString("backupUrl", LLM, "", "备用接口的完整地址：主接口失败时同一次改用它；主接口接连失败 3 次后 5 分钟内先用它。留空 = 没有备用");
            backupModel = c.getString("backupModel", LLM, "", "备用接口的模型名");
            backupApiKey = c.getString("backupApiKey", LLM, "", "备用接口的 API 密钥。只在服务端用：不写进日志、不发给客户端。留空 = 没有备用");
            backupDisableThinking = c.getBoolean("backupDisableThinking", LLM, false, "备用接口要不要像 DeepSeek 那样在请求里关“思考”");
            timeoutSeconds = c.getInt("timeoutSeconds", LLM, 15, 1, 120, "一次请求最多等几秒（主接口、备用接口各算一次，超时就换下一个）");
            c.setCategoryComment(SUPPORT, "MineBay 在线客服的限额");
            messagesPerHour = c.getInt("messagesPerHour", SUPPORT, 20, 1, 1000, "每个帐号每小时最多问几条（防止刷费用）");
            if(c.hasChanged())
                c.save();
        }
        catch(RuntimeException e)
        {
            Support.log().error("MineBay support: could not read config/{}; support stays off", FILE, e);
            apiKey = "";
            backupApiKey = "";
        }
    }

    static SupportLlm.Endpoint primary()
    {
        return new SupportLlm.Endpoint("primary", url, apiKey, model, disableThinking, true);
    }

    static SupportLlm.Endpoint backup()
    {
        return new SupportLlm.Endpoint("backup", backupUrl, backupApiKey, backupModel, backupDisableThinking, false);
    }
}
