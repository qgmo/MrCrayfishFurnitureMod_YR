package com.mrcrayfish.furniture.client.tv;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.BlockingQueue;

/**
 * 一台电视的声音（悠然服务器魔改，只在渲染线程用）：直接用 LWJGL 的 OpenAL 开一个 source 循环喂 PCM，
 * 不占 Minecraft 声音系统的通道。OpenAL 的听者位置由 Minecraft 每帧更新。
 * <ul>
 * <li>在世界里：单声道（立体声 source 不做空间化），放在电视的位置，按距离线性衰减（衰减由调用方算进 gain，
 * OpenAL 自己的衰减关掉，不改全局的距离模型）；</li>
 * <li>大屏：立体声、跟着听者（相对坐标 0,0,0），不衰减。</li>
 * </ul>
 * 声音按目标位置对齐：队列空时从目标位置开始放（多出来的前半段丢掉），超前超过 {@value #DRIFT_MS} ms 就暂停等，
 * 落后超过就清空重来。没有声卡（OpenAL 没初始化）时只把过期的声音丢掉。
 */
final class AudioOut
{
    private static final int BUFFERS = 24;
    /** 最多提前排多少声音 */
    private static final long AHEAD_MS = 400;
    static final long DRIFT_MS = 300;

    private int source = -1;
    private final Deque<Integer> free = new ArrayDeque<>();
    /** 排队中的每块：{缓冲区, 起点 pts, 时长} */
    private final Deque<long[]> queued = new ArrayDeque<>();
    private boolean stereo;
    private boolean failed;
    private ShortBuffer scratch = BufferUtils.createShortBuffer(8192);
    private final IntBuffer ids = BufferUtils.createIntBuffer(BUFFERS);

    void pump(BlockingQueue<VideoSession.AudioChunk> queue, int generation, long target, boolean playing, float gain, double x, double y, double z, boolean wantStereo)
    {
        if(failed || !AL.isCreated())
        {
            dropStale(queue, generation, target);
            return;
        }
        try
        {
            if(source < 0 || !AL10.alIsSource(source))
            {
                if(!create())
                {
                    dropStale(queue, generation, target);
                    return;
                }
            }
            if(wantStereo != stereo)
            {
                flush();
                stereo = wantStereo;
            }
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, stereo ? AL10.AL_TRUE : AL10.AL_FALSE);
            if(stereo)
                AL10.alSource3f(source, AL10.AL_POSITION, 0F, 0F, 0F);
            else
                AL10.alSource3f(source, AL10.AL_POSITION, (float) x, (float) y, (float) z);
            AL10.alSourcef(source, AL10.AL_GAIN, Math.max(0F, Math.min(1F, gain)));

            int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
            for(int i = 0; i < processed; i++)
            {
                int id = AL10.alSourceUnqueueBuffers(source);
                queued.pollFirst();
                free.add(id);
            }
            int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
            if(!playing)
            {
                if(state == AL10.AL_PLAYING)
                    AL10.alSourcePause(source);
                return;
            }
            long pos = position();
            if(pos >= 0 && pos < target - DRIFT_MS)
            {
                // 落后太多：清空，从目标位置重新排
                flush();
                pos = -1;
            }
            feed(queue, generation, target);
            state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
            boolean ahead = pos >= 0 && pos > target + DRIFT_MS;
            if(ahead)
            {
                if(state == AL10.AL_PLAYING)
                    AL10.alSourcePause(source);
            }
            else if(state != AL10.AL_PLAYING && !queued.isEmpty())
            {
                AL10.alSourcePlay(source);
            }
            int err = AL10.alGetError();
            if(err != AL10.AL_NO_ERROR)
                MrCrayfishFurnitureMod.logger().debug("TV video: OpenAL error {}", err);
        }
        catch(RuntimeException | LinkageError e)
        {
            failed = true;
            MrCrayfishFurnitureMod.logger().warn("TV video: sound disabled after OpenAL error: {}", e.toString());
            release();
        }
    }

    private void feed(BlockingQueue<VideoSession.AudioChunk> queue, int generation, long target)
    {
        while(!free.isEmpty() && queuedMs() < AHEAD_MS)
        {
            VideoSession.AudioChunk c = queue.peek();
            if(c == null)
                return;
            if(c.generation != generation)
            {
                queue.poll();
                continue;
            }
            long end = c.ptsMs + c.durationMs();
            if(end <= target - 50)
            {
                queue.poll();
                continue;
            }
            if(queued.isEmpty() && c.ptsMs > target + 200)
                return;
            queue.poll();
            int frames = c.samples.length / c.channels;
            int skip = 0;
            if(queued.isEmpty() && c.ptsMs < target)
                skip = (int) Math.min(frames, (target - c.ptsMs) * VideoDecoder.SAMPLE_RATE / 1000);
            int count = frames - skip;
            if(count <= 0)
                continue;
            int outChannels = stereo ? 2 : 1;
            if(scratch.capacity() < count * outChannels)
                scratch = BufferUtils.createShortBuffer(count * outChannels);
            scratch.clear();
            for(int i = skip; i < frames; i++)
            {
                int base = i * c.channels;
                int l = c.samples[base];
                int r = c.channels > 1 ? c.samples[base + 1] : l;
                if(stereo)
                {
                    scratch.put((short) l);
                    scratch.put((short) r);
                }
                else
                {
                    scratch.put((short) ((l + r) / 2));
                }
            }
            scratch.flip();
            int id = free.poll();
            AL10.alBufferData(id, stereo ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16, scratch, VideoDecoder.SAMPLE_RATE);
            AL10.alSourceQueueBuffers(source, id);
            long start = c.ptsMs + skip * 1000L / VideoDecoder.SAMPLE_RATE;
            queued.add(new long[]{id, start, count * 1000L / VideoDecoder.SAMPLE_RATE});
        }
    }

    private long queuedMs()
    {
        long sum = 0;
        for(long[] q : queued)
            sum += q[2];
        long[] head = queued.peekFirst();
        if(head != null && source >= 0)
            sum -= Math.min(head[2], AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET) * 1000L / VideoDecoder.SAMPLE_RATE);
        return sum;
    }

    /** 正在放的位置（毫秒）；没有在放时 -1 */
    long position()
    {
        long[] head = queued.peekFirst();
        if(head == null || source < 0 || failed || !AL.isCreated())
            return -1;
        return head[1] + AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET) * 1000L / VideoDecoder.SAMPLE_RATE;
    }

    private boolean create()
    {
        source = -1;
        free.clear();
        queued.clear();
        AL10.alGetError();
        int s = AL10.alGenSources();
        if(AL10.alGetError() != AL10.AL_NO_ERROR)
            return false;
        ids.clear();
        ids.limit(BUFFERS);
        AL10.alGenBuffers(ids);
        if(AL10.alGetError() != AL10.AL_NO_ERROR)
        {
            AL10.alDeleteSources(s);
            return false;
        }
        for(int i = 0; i < BUFFERS; i++)
            free.add(ids.get(i));
        source = s;
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0F);
        AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 1F);
        AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
        return true;
    }

    /** 没有声卡时：过期的声音直接丢，免得解码线程被堵住 */
    private static void dropStale(BlockingQueue<VideoSession.AudioChunk> queue, int generation, long target)
    {
        VideoSession.AudioChunk c;
        while((c = queue.peek()) != null && (c.generation != generation || c.ptsMs + c.durationMs() <= target + 100))
            queue.poll();
    }

    /** 停止并清掉排队中的声音（跳转、换立体声时） */
    void flush()
    {
        if(source < 0 || failed || !AL.isCreated())
        {
            queued.clear();
            return;
        }
        AL10.alSourceStop(source);
        int n = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        for(int i = 0; i < n; i++)
            free.add(AL10.alSourceUnqueueBuffers(source));
        queued.clear();
    }

    /** 暂时不放（播完、出错、没在播） */
    void stop()
    {
        if(source >= 0 && !failed && AL.isCreated() && AL10.alIsSource(source))
            flush();
    }

    void release()
    {
        if(source >= 0 && AL.isCreated())
        {
            try
            {
                if(AL10.alIsSource(source))
                {
                    AL10.alSourceStop(source);
                    int n = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
                    for(int i = 0; i < n; i++)
                        free.add(AL10.alSourceUnqueueBuffers(source));
                    AL10.alDeleteSources(source);
                }
                for(int id : free)
                {
                    if(AL10.alIsBuffer(id))
                        AL10.alDeleteBuffers(id);
                }
            }
            catch(RuntimeException e)
            {
                // 声音系统已经关了
            }
        }
        source = -1;
        free.clear();
        queued.clear();
    }
}
