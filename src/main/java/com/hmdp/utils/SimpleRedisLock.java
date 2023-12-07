package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.aspectj.apache.bcel.util.ClassPath;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{


    private String name;
    private StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        Boolean bool = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name,
                ID_PREFIX+Thread.currentThread().getId(),timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(bool);//小心空指针，我们额外判断一下
    }

    @Override
    public void unlock() {
        //调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX+name),
                ID_PREFIX+Thread.currentThread().getId()
                );
    }

    // @Override
    // public void unlock() {
    //     String value = stringRedisTemplate.opsForValue().get(KEY_PREFIX+name);
    //     if(null!=value&&value.equals(ID_PREFIX+Thread.currentThread().getId())){
    //         stringRedisTemplate.delete(KEY_PREFIX+name);
    //     }
    // }
}
