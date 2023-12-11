package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //这个类也要了解一下
    private ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result qureyById(Long id) throws InterruptedException {
        return queryWithMutex(id);
    }

    /**
     * 解决缓存击穿(逻辑实现)的处理代码
     * 这里需注意，在使用逻辑实现时，我们会事先初始化数据到redis中。
     * 也就是说，对于redis中查不到的数据，我们可以直接认为数据库中没有。
     * 同时也不需要额外考虑缓存穿透中值为空字串的情况
     */
    public Result queryWithLogicalExpire(Long id) throws InterruptedException {
        //1.从redis中查询数据
        String json = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+id);
        if(null == json){
            //2.若不存在，直接返回不存在即可。
           return Result.fail("数据不存在");
        }
        //3.存在时，根据是否过期进行不同处理。
        RedisData redisData = JSONUtil.toBean(json,RedisData.class);
        //我们需要了解一下这个工具
        JSONObject data = (JSONObject)redisData.getData();
        Shop shop = JSONUtil.toBean(data,Shop.class);

        log.info(redisData.getData().toString());

        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            //4.未过期，直接返回
            return Result.ok(shop);
        }
        //5.过期，要进行处理。
        boolean isLock = tryLock(RedisConstants.LOCK_SHOP_KEY+id);
        if(isLock){
            //6.同样，为了防止出现刚更新完成后有线程获取锁的情况，要二次判断。
            json = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+id);
            redisData = JSONUtil.toBean(json,RedisData.class);
            if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
                return Result.ok(shop);
            }else {
                //6.开启新线程进行更新(要看一下lambda表达式)
                CACHE_REBUILD_EXECUTOR.submit(()->{
                    try {
                        this.saveShopRedis(id,30L);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }finally {
                        unLock(RedisConstants.LOCK_SHOP_KEY+id);
                    }
                });
            }
        }
        //7.开启更新线程的线程和未获得锁的线程直接返回数据
        return Result.ok(shop);
    }

    /**
     * 解决缓存穿透+缓存击穿(互斥锁方法)的处理代码
     * @param id
     * @return
     */
    public Result queryWithMutex(Long id) throws InterruptedException {
        //1.从redis中查询数据
        String json = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+id);
        if(null != json){
            if("".equals(json)){
                //如果缓存的是空对象
                return Result.fail("店铺不存在");
            }
            //2.存在直接返回
            Shop shop = JSONUtil.toBean(json,Shop.class);
            return Result.ok(shop);
        }
        //3.不存在时，尝试获取锁。
        boolean bool = tryLock(RedisConstants.LOCK_SHOP_KEY+id);
        if(!bool){
            Thread.sleep(50);
            return queryWithMutex(id);//未获取锁则重新调用
        }
        //4.获取锁后,我们首先在缓存中确认是否存在数据。因为可能上一个获取到锁的线程已经进行更新了。
        json = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+id);
        if(null != json){
            if("".equals(json)){
                //如果缓存的是空对象
                return Result.fail("店铺不存在");
            }
            //2.存在直接返回
            Shop shop = JSONUtil.toBean(json,Shop.class);
            return Result.ok(shop);
        }

        Shop shop = getById(id);
        Thread.sleep(200);
        if(null == shop){
            //解决缓存穿透，将空值写入redis
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,"", 2, TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }
        //5.将数据写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop), 30, TimeUnit.MINUTES);
        //6.释放锁
        unLock(RedisConstants.LOCK_SHOP_KEY+id);
        //7.返回
        return Result.ok(shop);
    }

    /**
     * 解决缓存穿透的处理代码
     * @param id
     * @return
     */
    public Result queryWithPassThrough(Long id) {
        //1.从redis中查询数据
        String json = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY+id);
        if(null != json){
            if("".equals(json)){
                //如果缓存的是空对象
                return Result.fail("店铺不存在");
            }
            //2.存在直接返回
            Shop shop = JSONUtil.toBean(json,Shop.class);
            return Result.ok(shop);
        }
        //3.不存在时，在数据库中查询
        Shop shop = getById(id);
        if(null == shop){
            //解决缓存穿透，将空值写入redis
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,"", 2, TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }
        //4.将数据写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop), 30, TimeUnit.MINUTES);
        //5.返回
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(null == id){
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.判断使用哪种方式查询
        if(null == x||null == y){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //2.计算分页参数
        int front = (current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current*SystemConstants.DEFAULT_PAGE_SIZE;
        //3.进行分页查询
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        //4.1如果为空返回空
        if(null == results){
            return Result.ok(Collections.emptyList());
        }
        //5.进行分页
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size()<front){//该页没有了
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(front).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }

    /**
     * 尝试获取锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        //过期时间视业务
        Boolean bool = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        //上面返回的Boolean是bool的包装类，可能是null，我们要处理一下。
        if(null!=bool && bool)
            return true;
        else
            return false;
    }

    /**
     * 释放锁
     * @param key
     */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
        return;
    }

    /**
     * 用于初始化redis中的数据，在逻辑实现缓存击穿会用到。
     */
    public void saveShopRedis(Long id,Long expireSeconds) throws InterruptedException {
        //1.在数据库中查找
        Shop shop = getById(id);
        Thread.sleep(200);
        //2.处理数据
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.将数据放到redis（注意过期时间只是存放在类中，不用于设置redis数据的存活时间）
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
}
