package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
       /* List<ShopType> typeList = typeService
              .query().orderByAsc("sort").list();*/
        //1.在redis中查找是否有数据
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        List<String> range = stringRedisTemplate.opsForList().range(key, 0, -1);
        if(!range.isEmpty())
        {
            //2.有数据直接返回
            List<ShopType> shopTypes = new ArrayList<>();
            range.forEach(e -> {
                shopTypes.add(JSONUtil.toBean(e, ShopType.class));
            });
            return Result.ok(shopTypes);
        }
        //3.无数据 在数据库中查询
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        if(shopTypes == null || shopTypes.size() == 0)
        {
            return Result.fail("店铺类型不存在");
        }
        shopTypes.forEach(shopType -> {range.add(JSONUtil.toJsonStr(shopType));});
        //4.查到数据后存入redis中
        stringRedisTemplate.opsForList().rightPushAll(key, range);
        stringRedisTemplate.expire(key, RedisConstants.CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        //5.返回
        return Result.ok(shopTypes);
    }
}
