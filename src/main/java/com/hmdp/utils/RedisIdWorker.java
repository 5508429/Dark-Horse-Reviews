package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    private final long BEGIN_TIMESTAMP = 1672531200L;
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     *我们自己来拼一个自增的主键
     */
    public long nextId(String keyPrefix){
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        Long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        Long timestamp = nowSecond-BEGIN_TIMESTAMP;
        // 2.生成序列号
        // 2.1.获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        System.out.println(count);
        //3.拼接并返回
        return timestamp<<COUNT_BITS|count;
    }

    public static void main(String[] args) {
        LocalDateTime now = LocalDateTime.now();
        Long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        System.out.println(nowSecond);
    }

}
