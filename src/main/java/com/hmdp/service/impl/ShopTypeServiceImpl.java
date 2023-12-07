package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final String KEY = "cache:typeList";

    @Override
    public Result queryTypeList() {
        String listJson = stringRedisTemplate.opsForValue().get(KEY);
        if(null != listJson){
            List<ShopType> typeList = JSONUtil.toList(listJson,ShopType.class);
            return Result.ok(typeList);
        }

        List<ShopType> typeList = query().orderByAsc("sort").list();
        if(null == typeList){
            return Result.fail("未找到对应列表");
        }
        stringRedisTemplate.opsForValue().set(KEY,JSONUtil.toJsonStr(typeList));
        return Result.ok(typeList);
    }
}
