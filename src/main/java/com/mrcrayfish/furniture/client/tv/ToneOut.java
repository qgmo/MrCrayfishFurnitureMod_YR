package com.mrcrayfish.furniture.client.tv;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;

import java.nio.ShortBuffer;

/**
 * 测试卡的“嘟——”声（悠然服务器魔改，服主 11-18，只在渲染线程用）：同 {@link AudioOut}，直接用 LWJGL 的 OpenAL 开一个 source，
 * 不占 Minecraft 声音系统的通道；一块 {@link Tone} 算好的单声道缓冲循环播放。在世界里放在电视的位置（距离衰减由调用方算进 gain，
 * OpenAL 自己的衰减关掉），大屏里跟着听者。没有声卡（OpenAL 没初始化）时什么也不做。
 */
final class ToneOut
{
    private int source = -1;
    private int buffer = -1;
    private boolean failed;
    private boolean playing;

    void play(float gain, double x, double y, double z, boolean relative)
    {
        if(failed || !AL.isCreated())
            return;
        try
        {
            if(source < 0 && !create())
                return;
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, relative ? AL10.AL_TRUE : AL10.AL_FALSE);
            if(relative)
                AL10.alSource3f(source, AL10.AL_POSITION, 0F, 0F, 0F);
            else
                AL10.alSource3f(source, AL10.AL_POSITION, (float) x, (float) y, (float) z);
            AL10.alSourcef(source, AL10.AL_GAIN, Math.max(0F, Math.min(1F, gain)));
            if(!playing)
            {
                AL10.alSourcePlay(source);
                playing = true;
            }
        }
        catch(RuntimeException e)
        {
            failed = true;
            MrCrayfishFurnitureMod.logger().warn("TV test card tone unavailable: {}", e.toString());
        }
    }

    void stop()
    {
        if(source >= 0 && playing && AL.isCreated())
            AL10.alSourceStop(source);
        playing = false;
    }

    private boolean create()
    {
        AL10.alGetError();
        int s = AL10.alGenSources();
        if(AL10.alGetError() != AL10.AL_NO_ERROR)
        {
            failed = true;
            return false;
        }
        int b = AL10.alGenBuffers();
        if(AL10.alGetError() != AL10.AL_NO_ERROR)
        {
            AL10.alDeleteSources(s);
            failed = true;
            return false;
        }
        short[] samples = Tone.standard();
        ShortBuffer data = BufferUtils.createShortBuffer(samples.length);
        data.put(samples).flip();
        AL10.alBufferData(b, AL10.AL_FORMAT_MONO16, data, Tone.SAMPLE_RATE);
        AL10.alSourcei(s, AL10.AL_BUFFER, b);
        AL10.alSourcei(s, AL10.AL_LOOPING, AL10.AL_TRUE);
        AL10.alSourcef(s, AL10.AL_ROLLOFF_FACTOR, 0F);
        AL10.alSourcef(s, AL10.AL_REFERENCE_DISTANCE, 1F);
        if(AL10.alGetError() != AL10.AL_NO_ERROR)
        {
            AL10.alDeleteSources(s);
            AL10.alDeleteBuffers(b);
            failed = true;
            return false;
        }
        source = s;
        buffer = b;
        return true;
    }

    void release()
    {
        if(AL.isCreated())
        {
            try
            {
                if(source >= 0)
                {
                    AL10.alSourceStop(source);
                    AL10.alDeleteSources(source);
                }
                if(buffer >= 0)
                    AL10.alDeleteBuffers(buffer);
            }
            catch(RuntimeException e)
            {
                // 关的时候出错不要紧
            }
        }
        source = -1;
        buffer = -1;
        playing = false;
    }
}
