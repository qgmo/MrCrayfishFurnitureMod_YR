package com.mrcrayfish.furniture.client.tv;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;

/**
 * 碰 JavaCV / FFmpeg 的类只在这里和 {@link VideoDecoder} 里出现（悠然服务器魔改），本地库就绪前不会被加载。
 */
final class FfmpegBridge
{
    private FfmpegBridge() {}

    /** 加载 FFmpeg 的全部本地库（avutil、avcodec、avformat、swscale、swresample、avfilter、avdevice），失败抛错 */
    static void load() throws Exception
    {
        FFmpegFrameGrabber.tryLoad();
        // FFmpeg 默认往标准错误输出大量信息，只留错误
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
    }
}
