package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * @author ZhuJinPeng
 * @version 1.0
 */
@Configuration
public class RedisConfig {
    @Bean
    public RedissonClient redisClient() {
        //配置类
        Config config = new Config();
        //添加redis地址，
        config.useSingleServer().setAddress("redis://localhost:6379").setPassword("123456");
        //创建客户端
        return Redisson.create(config);
    }
}
