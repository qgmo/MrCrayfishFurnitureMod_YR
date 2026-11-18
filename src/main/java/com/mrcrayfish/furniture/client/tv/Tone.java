package com.mrcrayfish.furniture.client.tv;

/**
 * 直播没开播时测试卡配的提示音（纯逻辑，悠然服务器魔改，服主 11-18）：当年停播时那种持续的“嘟——”，约 1 kHz 的正弦波。
 * 采样由程序算出来（不用音频素材），做成一段正好是整数个周期的缓冲，循环播放时接缝处不会咔哒。
 */
final class Tone
{
    static final int SAMPLE_RATE = 48000;
    static final int FREQUENCY = 1000;
    /** 0.1 秒、正好 100 个周期 */
    static final int SAMPLES = SAMPLE_RATE / 10;
    /** 振幅只有满幅的约四分之一（再乘电视音量、距离衰减），“音量要小” */
    static final short AMPLITUDE = 8000;

    private Tone() {}

    /** 单声道 16 位的正弦波；samples 要是 rate / freq 的整数倍才能无缝循环 */
    static short[] sine(int rate, int freq, int samples, short amplitude)
    {
        short[] out = new short[samples];
        for(int i = 0; i < samples; i++)
            out[i] = (short) Math.round(amplitude * Math.sin(2 * Math.PI * freq * i / (double) rate));
        return out;
    }

    static short[] standard()
    {
        return sine(SAMPLE_RATE, FREQUENCY, SAMPLES, AMPLITUDE);
    }
}
