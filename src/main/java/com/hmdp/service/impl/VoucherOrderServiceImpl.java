package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private SeckillVoucherServiceImpl seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //阻塞队列
    /*private BlockingQueue<VoucherOrder> orderTask = new ArrayBlockingQueue<>(1024 * 1024);*/
    private static final ExecutorService SECKILL_ORDER_EXCUTOR = Executors.newSingleThreadExecutor();
    private IVoucherOrderService proxy;

    @PostConstruct
    private void init() {
        String queueName = "stream.orders";
        SECKILL_ORDER_EXCUTOR.submit(() -> {

            while (true) {
                try {
                    /*VoucherOrder voucherOrder = orderTask.take();*/

                    //获取消息队列中的订单信息
                    // XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAM stream.orders <
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息是否获取成功
                    if (read == null || read.isEmpty()) {
                        continue;
                    }
                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> entries = read.get(0);
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //下单
                    handleVoucherOrder(voucherOrder);
                    //ack确认
                    // SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", entries.getId());
                } catch (Exception e) {
                    while (true)
                    {
                        try {
                            //获取pending-list队列中的订单信息
                            // XREADGROUP GROUP g1 c1 COUNT 1 STREAM stream.orders 0
                            List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(
                                    Consumer.from("g1", "c1"),
                                    StreamReadOptions.empty().count(1),
                                    StreamOffset.create(queueName, ReadOffset.from("0"))
                            );
                            //判断消息是否获取成功
                            if (read == null || read.isEmpty()) {
                                //说明pending-list中没有异常消息
                                break;
                            }
                            //解析消息中的订单信息
                            MapRecord<String, Object, Object> entries = read.get(0);
                            Map<Object, Object> value = entries.getValue();
                            VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                            //下单
                            handleVoucherOrder(voucherOrder);
                            //ack确认
                            // SACK stream.orders g1 id
                            stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", entries.getId());
                        } catch (Exception ex) {
                            log.error("处理pending-list异常", e);
                        }
                    }
                }
            }
        });
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户ID
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不许重复下单");
            return;
        }

        try {
            proxy.createVoucherOrder(voucherOrder);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }



    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //订单id
        long orderId = redisIdWorker.nextId("voucherOrder");
        // 执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );

        //判断结果
        int r = result.intValue();
        if(r != 0){
            return Result.fail(r == 1?"库存不足": "不能重复下单");
        }
        //获得代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //返回订单id
        return Result.ok(orderId);


        /*//获取用户id
        Long userId = UserHolder.getUser().getId();
        // 执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );

        //判断结果
        int r = result.intValue();
        if(r != 0){
            return Result.fail(r == 1?"库存不足": "不能重复下单");
        }
        //有购买资格
        //生成id
        long orderId = redisIdWorker.nextId("order");

        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.nextId("voucherOrder"));
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setStatus(1);

        //获得代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //添加到阻塞队列
        orderTask.add(voucherOrder);
        //返回订单id
        return Result.ok(orderId);*/
    }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠券信息
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        //判断秒杀是否开始
//        LocalDateTime now = LocalDateTime.now();
//        if(now.isAfter(seckillVoucher.getEndTime()))
//        {
//            return Result.fail("秒杀已结束");
//        }
//        if(now.isBefore(seckillVoucher.getBeginTime()))
//        {
//            return Result.fail("秒杀未开始");
//        }
//
//        //判断库存是否充足
//        if(seckillVoucher.getStock() < 1)
//        {
//            return Result.fail("已售罄");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//
//        /*synchronized(userId.toString().intern()) {
//            //获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId, seckillVoucher);
//        }*/
//
//        //创建锁对象
////        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //尝试获取锁
//        if(!lock.tryLock())
//        {
//            return Result.fail("购买失败");
//        }
//        //获取代理对象
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId, seckillVoucher);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            lock.unlock();
//        }
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //一人一单
        //查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("用户已经购买过一次");
            return ;
        }


        //扣减库存
        /*SeckillVoucher afterSeckillVoucher = new SeckillVoucher();
        afterSeckillVoucher.setVoucherId(seckillVoucher.getVoucherId());
        afterSeckillVoucher.setStock(seckillVoucher.getStock() - 1);
        int i = seckillVoucherMapper.updateById(afterSeckillVoucher);
        if(i <= 0)
        {
            return Result.fail("已售罄");
        }*/
        //使用乐观锁的方式
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) {
            log.error("库存不足");
            return ;
        }

        //将订单添加到数据库
        this.save(voucherOrder);
    }
}
