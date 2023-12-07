package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Test
    public void test1() throws InterruptedException {
        shopService.saveShopRedis(2L,10L);
    }

    @Test
    public void test2() throws InterruptedException {
        for(int i=1;i<=100;i++){
            System.out.println(redisIdWorker.nextId("test"));
        }
    }

}
