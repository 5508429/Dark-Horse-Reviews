package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {


    private StringRedisTemplate stringRedisTemplate;

    //这个类也要了解一下
    private ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将数据放到redis中，对象以json格式存放
     * @param time:时间
     * @param timeUnit:时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        String v = null;
        if(String.class != value.getClass()){
            v = JSONUtil.toJsonStr(value);
        }else {
            v = (String) value;
        }
        stringRedisTemplate.opsForValue().set(key,v,time,timeUnit);
    }

    /**
     * 以逻辑过期的方式将数据存放到redis中，
     */
    public void setWithLogicalExpire(String key, Object value,Long time,TimeUnit timeUnit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 查询信息，在其中使用缓存空对象的方式解决缓存穿透的问题
     * 1. 使用泛型来加强安全性
     * 2. 使用函数式编程（待加强）
     */
    public <R,T> R queryWithPassThrough(String keyPrefix, T id, Class<R> type, Function<T,R> dbFallBack
            ,Long time,TimeUnit timeUnit) {
        //1.从redis中查询数据
        String json = stringRedisTemplate.opsForValue().get(keyPrefix+id);
        if(null != json){
            if("".equals(json)){
                //如果缓存的是空对象
                return null;
            }
            //2.存在直接返回;
            return JSONUtil.toBean(json,type);
        }
        //3.不存在时，在数据库中查询
        R result = dbFallBack.apply(id);
        if(null == result){
            //解决缓存穿透，将空值写入redis
            stringRedisTemplate.opsForValue().set(keyPrefix+id,"", timeUnit.toMinutes(time), TimeUnit.MINUTES);
            return null;
        }
        //4.将数据写入redis
        setWithLogicalExpire(keyPrefix+id,result,time,timeUnit);
        //5.返回
        return result;
    }


    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) throws InterruptedException {
        String key = keyPrefix+id;
        //1.从redis中查询数据
        String json = stringRedisTemplate.opsForValue().get(key+id);
        if(null == json){
            //2.若不存在，直接返回不存在即可。
            return null;
        }
        //3.存在时，根据是否过期进行不同处理。
        RedisData redisData = JSONUtil.toBean(json,RedisData.class);
        //我们需要了解一下这个工具
        JSONObject data = (JSONObject)redisData.getData();
        R r = JSONUtil.toBean(data,type);

        log.info(redisData.getData().toString());

        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            //4.未过期，直接返回
            return r;
        }
        //5.过期，要进行处理。
        boolean isLock = tryLock(RedisConstants.LOCK_SHOP_KEY+id);
        if(isLock){
            //6.同样，为了防止出现刚更新完成后有线程获取锁的情况，要二次判断。
            json = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+id);
            redisData = JSONUtil.toBean(json,RedisData.class);
            if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
                return JSONUtil.toBean((JSONObject)redisData.getData(),type);
            }else {
                //6.开启新线程进行更新(要看一下lambda表达式)
                CACHE_REBUILD_EXECUTOR.submit(()->{
                    try {
                        this.setWithLogicalExpire(key, r, time, unit);
                    } finally {
                        unlock(RedisConstants.LOCK_SHOP_KEY+id);
                    }
                });
            }
        }
        //7.开启更新线程的线程和未获得锁的线程直接返回数据
        return r;
    }

    /**
     * 使用锁的方式来处理缓存击穿。
     * 1. 使用泛型来加强安全性
     * 2. 使用函数式编程（待加强）
     */
    public<R,ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) throws InterruptedException {
        String key = keyPrefix+id;
        //1.从redis中查询数据
        String json = stringRedisTemplate.opsForValue().get(key);
        if(null != json){
            if("".equals(json)){
                //如果缓存的是空对象
                return null;
            }
            //2.存在直接返回
            return JSONUtil.toBean(json,type);
        }
        //3.不存在时，尝试获取锁。
        boolean bool = tryLock(RedisConstants.LOCK_SHOP_KEY+id);
        if(!bool){
            Thread.sleep(50);
            return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);//未获取锁则重新调用
        }
        //4.获取锁后,我们首先在缓存中确认是否存在数据。因为可能上一个获取到锁的线程已经进行更新了。
        json = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+id);
        if(null != json){
            if("".equals(json)){
                //如果缓存的是空对象
                return null;
            }
            //2.存在直接返回
            return JSONUtil.toBean(json,type);
        }

        R r = dbFallback.apply(id);
        Thread.sleep(200);
        if(null == r){
            //解决缓存穿透，将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"", 2, TimeUnit.MINUTES);
            return null;
        }
        //5.将数据写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(r), 30, TimeUnit.MINUTES);
        //6.释放锁
        unlock(RedisConstants.LOCK_SHOP_KEY+id);
        //7.返回
        return r;
    }


    private boolean tryLock(String key){
        //过期时间视业务
        Boolean bool = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        //上面返回的Boolean是bool的包装类，可能是null，我们要处理一下。
        if(null!=bool && bool)
            return true;
        else
            return false;
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}
