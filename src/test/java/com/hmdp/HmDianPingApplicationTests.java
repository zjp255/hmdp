package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.List;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void contextLoads() {
        shopService.savaShopToRedis(1L , 30L);
    }


    //加载店铺坐标信息到redis中
    @Test
    void loadGeo()
    {
        //得到所有的店铺
        List<Shop> list = shopService.list();
        String key1 = RedisConstants.SHOP_GEO_KEY + 1;
        String key2 = RedisConstants.SHOP_GEO_KEY + 2;
        String key = "";
        for (Shop shop : list) {
            if(shop.getTypeId() == 1)
            {
                key = key1;
            }else{
                key = key2;
            }
            stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), String.valueOf(shop.getId()));
        }
    }

}
