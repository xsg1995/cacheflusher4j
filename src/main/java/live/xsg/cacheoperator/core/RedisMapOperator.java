package live.xsg.cacheoperator.core;

import live.xsg.cacheoperator.CacheOperator;
import live.xsg.cacheoperator.codec.CodecEnum;
import live.xsg.cacheoperator.codec.MapCodec;
import live.xsg.cacheoperator.common.Constants;
import live.xsg.cacheoperator.executor.AsyncCacheExecutor;
import live.xsg.cacheoperator.executor.CacheExecutor;
import live.xsg.cacheoperator.executor.SyncCacheExecutor;
import live.xsg.cacheoperator.flusher.Refresher;
import live.xsg.cacheoperator.loader.ResourceLoader;
import live.xsg.cacheoperator.transport.Transporter;
import live.xsg.cacheoperator.utils.MapUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * map类型操作实现
 * Created by xsg on 2020/8/18.
 */
public class RedisMapOperator extends AbstractRedisOperator implements MapOperator {
    //异步任务执行器
    protected CacheExecutor<Map<String, String>> asyncCacheExecutor = new AsyncCacheExecutor();

    public RedisMapOperator(Transporter transporter, CacheOperator cacheOperator, ResourceLoader resourceLoader) {
        super(transporter, cacheOperator, resourceLoader);
    }

    @Override
    public Map<String, String> getAllMap(String key) {
        Map<String, String> map = this.transporter.getAllMap(key);
        MapCodec.MapData mapData = (MapCodec.MapData) this.getDecodeData(map, CodecEnum.MAP);
        boolean invalid = this.isInvalid(mapData.getActualExpireTime());

        if (invalid) {
            return Constants.EMPTY_MAP;
        }

        return mapData.getData();
    }

    @Override
    public Map<String, String> getAllMap(String key, long expire, Refresher<Map<String, String>> flusher) {
        return this.getAllMap(key, expire, flusher, new SyncCacheExecutor<>());
    }

    @Override
    public Map<String, String> getAllMapAsync(String key, long expire, Refresher<Map<String, String>> flusher) {
        return this.getAllMap(key, expire, flusher, this.asyncCacheExecutor);
    }

    @Override
    public Map<String, String> getAllMapAsync(String key, long expire, Refresher<Map<String, String>> flusher, ExecutorService executorService) {
        return this.getAllMap(key, expire, flusher, new AsyncCacheExecutor<>(executorService));
    }

    private Map<String, String> getAllMap(String key, long expire, Refresher<Map<String, String>> flusher, CacheExecutor<Map<String, String>> cacheExecutor) {
        Map<String, String> resMap = this.transporter.getAllMap(key);

        if (MapUtils.isEmpty(resMap)) {
            //缓存中不存在数据，获取数据放入缓存
            resMap = cacheExecutor.executor(() -> this.doFillMapCache(key, expire, flusher));
        } else {
            //缓存中存在数据，则判断缓存是否已经过期
            MapCodec.MapData mapData = (MapCodec.MapData) this.getDecodeData(resMap, CodecEnum.MAP);
            boolean invalid = this.isInvalid(mapData.getActualExpireTime());

            if (invalid) {
                //缓存过期，则刷新缓存数据
                resMap = cacheExecutor.executor(() -> this.doFillMapCache(key, expire, flusher));

                //如果有其他线程在刷新缓存，则返回现在缓存中的值
                if (Constants.EMPTY_MAP.equals(resMap)) {
                    resMap = mapData.getData();
                }
            } else {
                //未过期
                resMap = mapData.getData();
            }
        }
        if (resMap == null) {
            resMap = new HashMap<>();
        }
        return resMap;
    }

    /**
     * 填充数据到缓存中
     * @param key key
     * @param expire 缓存过期时间
     * @param flusher 获取缓存数据
     * @return 返回最新数据
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> doFillMapCache(String key, long expire, Refresher<Map<String, String>> flusher) {
        boolean isLoading = false;
        try {
            isLoading = this.isLoading(key);
            //isLoading=true，则已有其他线程在刷新数据
            if (isLoading) {
                return (Map<String, String>) this.blockIfNeed(key);
            }

            Map<String, String> data = flusher.refresh();

            long newExpire = this.getExtendExpire(expire);
            MapCodec.MapData encodeMap = (MapCodec.MapData) this.getEncodeData(expire, data, CodecEnum.MAP);
            this.transporter.hset(key, newExpire, encodeMap.getData());

            if (MapUtils.isEmpty(data)) {
                data = Constants.EMPTY_MAP;
            }

            return data;
        } finally {
            if (!isLoading) {
                //设置key已经加载完毕
                this.loadFinish(key);
            }
        }
    }

    @Override
    protected Object getDataIgnoreValid(String key) {
        //检查是否已经有其他线程刷新完缓存
        Map<String, String> cacheMap = this.transporter.getAllMap(key);
        if (MapUtils.isEmpty(cacheMap)) return null;

        MapCodec.MapData mapData = (MapCodec.MapData) this.getDecodeData(cacheMap, CodecEnum.MAP);
        return mapData.getData();
    }
}
