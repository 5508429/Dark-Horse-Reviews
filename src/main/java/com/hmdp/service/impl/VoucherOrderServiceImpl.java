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
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
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

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.根据id获取秒杀券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //2.判断当前是否是秒杀时间
        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }
        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }
        //3.判断库存是否充足
        if(seckillVoucher.getStock()<=0){
            return Result.fail("券已卖完");
        }
        Long userId = UserHolder.getUser().getId();
        //创建锁对象
        // SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:"+userId,stringRedisTemplate);
        // boolean flag = simpleRedisLock.tryLock(100L);
        RLock lock = redissonClient.getLock("lock:order:"+userId);
        boolean flag = lock.tryLock();

        if(!flag){
            //一个用户有一个进入线程就行了，多个请求属于刷单。
            return Result.fail("不允许重复下单");
        }
        //这里可能会出现异常，我们要处理一下
        try{
            IVoucherOrderService voucherOrderService = (IVoucherOrderService) AopContext.currentProxy();
            return voucherOrderService.createVoucherOrder(voucherId);
        }finally {
            //simpleRedisLock.unlock();
            lock.unlock();
        }


    }

    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId){
        // 4.一人一单逻辑
        Long userId = UserHolder.getUser().getId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            // 用户已经购买过了
            return Result.fail("用户已经购买过一次！");
        }

        //5.扣除库存
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id",voucherId).gt("stock",0)
                .update();
        if(!success){
            return Result.fail("券已卖完");
        }
        //6.生成订单
        Long id = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(id);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(id);
    }
}
