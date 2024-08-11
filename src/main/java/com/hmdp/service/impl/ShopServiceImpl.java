package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisCacheUtil;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private final StringRedisTemplate stringRedisTemplate;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Resource
    private RedisCacheUtil redisCacheUtil;

    //根据id查询商店信息
    @Override
    public Result queryShopById(Long id) {
        //缓存穿透、
        //Shop = queryByThroughPass(id);
        Shop shop = redisCacheUtil.readFromRedis(RedisConstants.CACHE_SHOP_KEY, id,
                Shop.class, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES, this::getById);
        //互斥锁
        //Shop shop = queryByMutex(id);
        //逻辑过期解决问题
        //Shop shop = queryWithLogicalExpire(id);
        /*Shop shop = redisCacheUtil.readFromRedisWithLogic(RedisConstants.CACHE_SHOP_KEY, id,
                Shop.class, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES, this::getById);*/
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        //返回
        return Result.ok(shop);
    }

    public void savaShopToRedis(Long id, Long expireSeconds) {
        //1.查询店铺数据
        Shop shop = getById(id);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    private Shop queryWithLogicalExpire(Long id) {
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        //1.从redis中查询商铺缓存
        String redisDataJson = stringRedisTemplate.opsForValue().get(shopKey);
        if(StrUtil.isBlank(redisDataJson)) {
            //2.未找到 直接返回空
            return null;
        }
        //3。找到查看是否过期
        RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
        //4.未过期直接返回
        JSONObject jsonObject = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(jsonObject, Shop.class);
        if(LocalDateTime.now().isBefore(redisData.getExpireTime()))
            return shop;
        //5.过期 尝试获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        if(tryLock(lockKey))
        {
            //6. 获得互斥锁， 开启线程更新redis数据
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        savaShopToRedis(id, 300L);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        delLock(lockKey);
                    }
                }
            });
            thread.start();
        }
        //7.返回数据
        return shop;
    }


    private Shop queryByMutex(Long id) {
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        while (true) {
            //1.在redis中查找信息
            Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(shopKey);
            //2.判断是否找到
            if (!entries.isEmpty()) {
                //3.找到 返回数据
                //3.1 判断是否有nullkey
                if (entries.containsKey(RedisConstants.CACHE_NULL_STR)) {
                    return null;
                }
                Shop shop = BeanUtil.fillBeanWithMap(entries, new Shop(), false);
                return shop;
            }
            //4.未找到
            //4.1 加锁
            String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
            Shop shop;
            try {
                if (!tryLock(lockKey)) {
                    Thread.sleep(100);
                    continue;
                }
                //5.在数据库中查找数据
                shop = getById(id);
                if (shop == null) {
                    Map<String, String> map = new HashMap<>();
                    map.put(RedisConstants.CACHE_NULL_STR, RedisConstants.CACHE_NULL_STR);
                    stringRedisTemplate.opsForHash().putAll(shopKey, map);
                    stringRedisTemplate.expire(shopKey, RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return null;
                }
                //6.将数据存储在redis中
                /*Map<String, Object> shopMap =
                BeanUtil.beanToMap(shop, new HashMap<>(),
                        CopyOptions.create().
                                setFieldValueEditor((fieldName, fieldValue)-> fieldValue.toString())
                                .setIgnoreNullValue(true));*/
                Map<String, Object> shopMap = BeanUtil.beanToMap(shop, new HashMap<>(),
                        CopyOptions.create().setIgnoreNullValue(true)
                                .setFieldValueEditor((fieldName, fieldValue) -> {
                                    if (fieldValue == null) {
                                        return "";
                                    }
                                    return fieldValue.toString();
                                }));
                stringRedisTemplate.opsForHash().putAll(shopKey, shopMap);
                stringRedisTemplate.expire(shopKey, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }finally {
                //去锁
                delLock(lockKey);
            }
            //7.返回结果
            return shop;
        }
    }

    private Shop queryByThroughPass(Long id) {
        //1.在redis中查找信息
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(shopKey);
        //2.判断是否找到
        if(!entries.isEmpty()) {
            //3.找到 返回数据
            //3.1 判断是否有nullkey
            if(entries.containsKey(RedisConstants.CACHE_NULL_STR))
            {
                return null;
            }
            Shop shop = BeanUtil.fillBeanWithMap(entries, new Shop(), false);
            return shop;
        }
        //4.未找到
        //5.在数据库中查找数据
        Shop shop = getById(id);

        if(shop == null) {
            Map<String, String> map = new HashMap<>();
            map.put(RedisConstants.CACHE_NULL_STR, RedisConstants.CACHE_NULL_STR);
            stringRedisTemplate.opsForHash().putAll(shopKey, map);
            stringRedisTemplate.expire(shopKey,RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.将数据存储在redis中
        /*Map<String, Object> shopMap =
                BeanUtil.beanToMap(shop, new HashMap<>(),
                        CopyOptions.create().
                                setFieldValueEditor((fieldName, fieldValue)-> fieldValue.toString())
                                .setIgnoreNullValue(true));*/
        Map<String, Object> shopMap = BeanUtil.beanToMap(shop, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue)-> {
                            if(fieldValue == null) {
                                return "";
                            }
                            return fieldValue.toString();
                        }));
        stringRedisTemplate.opsForHash().putAll(shopKey,shopMap);
        stringRedisTemplate.expire(shopKey,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回结果
        return shop;
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


    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result updateShop(Shop shop) {
        if(shop.getId() == null) {
            return Result.fail("商铺id不能为空");
        }
        String shopKey = RedisConstants.CACHE_SHOP_KEY + shop.getId();
        updateById(shop);

        //删除redis缓存
        stringRedisTemplate.delete(shopKey);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //判断是否需要安坐标来排序
        if(x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //查询redis按照距离排序分页
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> search = stringRedisTemplate.opsForGeo().
                search(key, GeoReference.fromCoordinate(new Point(x, y)),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        //解析出id
        if (search == null)
        {
            return Result.ok();
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = search.getContent();
        if(content.size() <= from) {
            return Result.ok(Collections.emptyList());
        }
        // 截取from 到end 的部分
        List<Long> ids = new ArrayList<>();
        Map<Long, Distance> map = new HashMap<>();
        content.stream().skip(from).forEach(r -> {
            Long id = Long.valueOf(r.getContent().getName());
            ids.add(id);
            map.put(id, r.getDistance());
        });
        //根据id查shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shopList = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        //返回
        for (Shop shop : shopList) {
            shop.setDistance(map.get(shop.getId()).getValue());
        }
        return Result.ok(shopList);
    }
}
