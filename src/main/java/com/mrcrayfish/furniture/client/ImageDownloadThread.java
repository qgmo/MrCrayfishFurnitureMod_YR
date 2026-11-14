package com.mrcrayfish.furniture.client;

import com.mrcrayfish.furniture.MrCrayfishFurnitureMod;
import com.mrcrayfish.furniture.handler.ConfigurationHandler;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * Author: MrCrayfish
 * <p>
 * 悠然服务器魔改：不再每个相框各开一个线程。start() 把任务交给 {@link MediaDownloader} 的固定大小线程池；
 * 同一地址同时只加载一次，其余请求挂在等待表上，加载完一起回调（原来是每秒轮询、最多等 10 秒）。
 * 磁盘缓存的读取、下载、尺寸检查与解码都在线程池里做，成功后才放进缓存；任何异常都只记 debug 日志。
 */
public class ImageDownloadThread implements Runnable
{
    private static final String[] SUPPORTED_CONTENT_TYPES = { "image/png", "image/jpeg" };
    private static final Map<String, List<ResponseProcessor>> WAITING = new HashMap<>();

    private final URI uri;
    private final ResponseProcessor processor;

    public ImageDownloadThread(URI uri, ResponseProcessor processor)
    {
        this.uri = uri;
        this.processor = processor;
    }

    public void start()
    {
        String url = uri.toString();
        synchronized(WAITING)
        {
            List<ResponseProcessor> waiting = WAITING.get(url);
            if(waiting != null)
            {
                waiting.add(processor);
                return;
            }
            waiting = new ArrayList<>();
            waiting.add(processor);
            WAITING.put(url, waiting);
        }
        try
        {
            MediaDownloader.executor().execute(this);
        }
        catch(RejectedExecutionException e)
        {
            finish(url, ImageDownloadResult.FAILED);
        }
    }

    @Override
    public void run()
    {
        String url = uri.toString();
        ImageDownloadResult result;
        try
        {
            result = this.load(url);
        }
        catch(Exception | OutOfMemoryError | StackOverflowError e)
        {
            MrCrayfishFurnitureMod.logger().debug("Unable to load {}: {}", url, e.toString());
            result = ImageDownloadResult.FAILED;
        }
        finish(url, result);
    }

    private ImageDownloadResult load(String url)
    {
        if(ImageCache.INSTANCE.isCached(url))
        {
            return ImageDownloadResult.SUCCESS;
        }

        byte[] data = ImageCache.INSTANCE.readCachedFile(url);
        boolean fromDisk = data != null;
        if(data == null)
        {
            MediaDownloader.Response response = MediaDownloader.download(uri, SUPPORTED_CONTENT_TYPES);
            switch(response.status)
            {
                case OK:
                    data = response.data;
                    break;
                case WRONG_TYPE:
                    return ImageDownloadResult.UNKNOWN_FILE;
                case UNKNOWN_SIZE:
                    return ImageDownloadResult.UNKNOWN_SIZE;
                case TOO_LARGE:
                    return ImageDownloadResult.TOO_LARGE;
                case UNTRUSTED:
                    return ImageDownloadResult.UNTRUSTED;
                default:
                    return ImageDownloadResult.FAILED;
            }
        }

        Texture texture;
        try
        {
            texture = new Texture(MediaDecoding.decodeImage(data, ConfigurationHandler.photoMaxSize));
        }
        catch(MediaDecoding.Rejected e)
        {
            MrCrayfishFurnitureMod.logger().debug("Rejected {}: {}", url, e.getMessage());
            return e.reason == MediaDecoding.Reason.TOO_LARGE ? ImageDownloadResult.OVERSIZED : ImageDownloadResult.FAILED;
        }

        if(!fromDisk)
        {
            ImageCache.INSTANCE.writeCachedFile(url, data);
        }
        ImageCache.INSTANCE.put(url, texture);
        return ImageDownloadResult.SUCCESS;
    }

    private static void finish(String url, ImageDownloadResult result)
    {
        List<ResponseProcessor> waiting;
        synchronized(WAITING)
        {
            waiting = WAITING.remove(url);
        }
        if(waiting == null)
            return;
        for(ResponseProcessor processor : waiting)
        {
            try
            {
                processor.process(result, result.name());
            }
            catch(RuntimeException ignored) {}
        }
    }

    public interface ResponseProcessor
    {
        void process(ImageDownloadResult result, String message);
    }

    public enum ImageDownloadResult
    {
        SUCCESS("cfm.photo_frame.success"),
        FAILED("cfm.photo_frame.failed"),
        UNKNOWN_FILE("cfm.photo_frame.unknown_file"),
        TOO_LARGE("cfm.photo_frame.too_large"),
        UNTRUSTED("cfm.photo_frame.untrusted"),
        INVALID_URL("cfm.photo_frame.invalid"),
        WRONG_SCHEME("cfm.photo_frame.wrong_scheme"),
        UNSUPPORTED_FILE_TYPE("cfm.photo_frame.unsupported_type"),
        UNKNOWN_SIZE("cfm.photo_frame.unknown_size"),
        OVERSIZED("cfm.photo_frame.oversized");

        private String key;

        ImageDownloadResult(String key)
        {
            this.key = key;
        }

        public String getKey()
        {
            return key;
        }
    }
}
