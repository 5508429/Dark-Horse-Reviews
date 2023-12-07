package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

//此类用于设置逻辑过期时间（利用逻辑关系解决缓存击穿）
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
