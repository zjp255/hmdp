package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.var;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * @author ZhuJinPeng
 * @version 1.0
 */

@Component
public class RedisCacheUtil {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);
    /**
     * 将数据存储到redis中
     * @param key
     * @param value
     * @param ttl
     * @param timeUnit
     */
    public void saveToRedis(String key, Object value, Long ttl,TimeUnit timeUnit)
    {
        //1.将value序列化为json数据
        String jsonStr = JSONUtil.toJsonStr(value);
        //2.jsonStr存入redis
        stringRedisTemplate.opsForValue().set(key,jsonStr,ttl,timeUnit);
    }


    /**
     * 将数据添加逻辑过期时间并存储到redis中
     * @param key
     * @param value
     * @param ttl
     * @param timeUnit
     */
    public void saveToRedisWithLogic(String key, Object value, Long ttl,TimeUnit timeUnit)
    {
        //1.将数据包装并序列化为json数据
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(ttl)));
        redisData.setData(value);
        String jsonStr = JSONUtil.toJsonStr(redisData);
        //2.jsonStr存入redis
        stringRedisTemplate.opsForValue().set(key,jsonStr);
    }

    //读取数据
    public <R, ID> R readFromRedis(String keyPrefix, ID id, Class<R> type, Long ttl, TimeUnit timeUnit, Function<ID, R> dbCallBack)
    {
        String key = keyPrefix + id.toString();
        //1.从redis中读取数据
        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(jsonStr))
        {
            //存在直接返回
            return JSONUtil.toBean(jsonStr,type);
        }
        //判断是否为空值
        if(jsonStr != null)
        {
            return null;
        }

        //根据id查询数据
        R r = dbCallBack.apply(id);

        if(r == null)
        {
            this.saveToRedis(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //见数据保存到redis中
        saveToRedis(key,r,ttl,timeUnit);

        return r;
    }

    //读取数据（逻辑过期）
    public <R, ID> R readFromRedisWithLogic(String keyPrefix, ID id, Class<R> type, Long ttl, TimeUnit timeUnit, Function<ID, R> dbCallBack) {
        String key = keyPrefix + id.toString();
        //从redis中读取数据
        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(jsonStr)) {
            //未找到直接返回空
            return null;
        }

        //找到 查看数据是否过期
        RedisData redisData = JSONUtil.toBean(jsonStr, RedisData.class);
        if (redisData.getExpireTime().isBefore(LocalDateTime.now())) {
            String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
            if (tryLock(lockKey)) {
                CACHE_REBUILD_EXECUTOR.submit(() -> {

                    try {
                        R r = dbCallBack.apply(id);
                        saveToRedisWithLogic(key, r, ttl, timeUnit);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        delLock(lockKey);
                    }

                });
            }
        }
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        return r;
    }

    /**
     * 加锁
     * @param key
     * @return
     */
    private boolean tryLock(String key)
    {
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(isLock);
    }

    /**
     * 释放锁
     * @param key
     */
    private void delLock(String key)
    {
        stringRedisTemplate.delete(key);
    }
}
