package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import net.bytebuddy.matcher.FilterableList;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followId, Boolean flag) {
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        if(flag){
            //关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followId);
            boolean isSuccess =save(follow);
            if (isSuccess) {
                // 把关注用户的id，放入redis的set集合 sadd userId followerUserId
                stringRedisTemplate.opsForSet().add(key, followId.toString());
            }
        }
        else {
            //取关
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followId));
            if (isSuccess) {
                // 把关注用户的id从Redis集合中移除
                stringRedisTemplate.opsForSet().remove(key, followId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followId) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followId).count();
        // 3.判断
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long followId) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        String userKey = "follows:" + userId;
        String followUserKey = "follows:" + followId;
        Set<String> stringSet = stringRedisTemplate.opsForSet().intersect(userKey,followUserKey);
        //2.遍历解析id
        if (null == stringSet){
            return Result.ok(Collections.emptyList());
        }
        List<UserDTO> list = new ArrayList<>();
        for(String id:stringSet){
            //查询用户信息
            User user = userService.getById(id);
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            list.add(userDTO);
        }
        return Result.ok(list);
    }
}
