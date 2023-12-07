package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

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
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //代理对象
    private IVoucherOrderService  proxy;

    //线程任务
    private class voucherOrderHandler implements Runnable {

        @Override
        public void run() {
            //获取队列中的信息
            try {
                VoucherOrder voucherOrder = orderTasks.take();
                // 2.创建订单（这里要用代理对象调用来保证事物不失效）
                proxy.createVoucherOrder(voucherOrder);
            } catch (Exception e) {
                log.error("处理订单异常", e);
            }
        }
    }



    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    {
        //使用代码块，让类在初始后便执行
        SECKILL_ORDER_EXECUTOR.submit(new voucherOrderHandler());
    }

    /**
     * 同步策略的秒杀流程
     * @param voucherId
     * @return
     */
    // @Override
    // public Result seckillVoucher(Long voucherId) {
    //     //1.根据id获取秒杀券信息
    //     SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
    //     //2.判断当前是否是秒杀时间
    //     if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
    //         return Result.fail("秒杀尚未开始");
    //     }
    //     if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
    //         return Result.fail("秒杀已经结束");
    //     }
    //     //3.判断库存是否充足
    //     if(seckillVoucher.getStock()<=0){
    //         return Result.fail("券已卖完");
    //     }
    //     Long userId = UserHolder.getUser().getId();
    //     //创建锁对象
    //     // SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:"+userId,stringRedisTemplate);
    //     // boolean flag = simpleRedisLock.tryLock(100L);
    //     RLock lock = redissonClient.getLock("lock:order:"+userId);
    //     boolean flag = lock.tryLock();
    //
    //     if(!flag){
    //         //一个用户有一个进入线程就行了，多个请求属于刷单。
    //         return Result.fail("不允许重复下单");
    //     }
    //     //这里可能会出现异常，我们要处理一下
    //     try{
    //         IVoucherOrderService voucherOrderService = (IVoucherOrderService) AopContext.currentProxy();
    //         return voucherOrderService.createVoucherOrder(voucherId);
    //     }finally {
    //         //simpleRedisLock.unlock();
    //         lock.unlock();
    //     }
    // }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1 执行脚本（lua脚本好像不会出现多线程问题，不过我们还是处理一下好了）
        Long userId = UserHolder.getUser().getId();
        RLock lock = redissonClient.getLock("lock:order:"+userId);
        boolean flag = lock.tryLock();
        if(!flag){
            //一个用户有一个进入线程就行了，多个请求属于刷单。
            return Result.fail("不允许重复下单");
        }
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),userId.toString()
        );
        //lock.unlock();
        //2.根据结果进行判断
        if(null == result || 0 != result){
            if(null == result)
                return Result.fail("发生错误");
            else
                return Result.fail(result==1?"库存不足":"不能重复下单");
        }
        //生成订单
        System.out.println("----------可生成订单");
        Long id = redisIdWorker.nextId("order");//生成id
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(id);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);
        //放入阻塞队列
        orderTasks.add(voucherOrder);
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(id);
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //5.扣除库存
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id",voucherOrder.getVoucherId()).gt("stock",0)
                .update();
        //6.生成订单
        save(voucherOrder);
        return ;
    }
}
