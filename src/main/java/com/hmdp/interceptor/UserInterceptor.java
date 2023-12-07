package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.UserHolder2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class UserInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    //这里无法直接获取
    public UserInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        log.info("拦截器生效");
        //HttpSession session = request.getSession();
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            response.setStatus(401);
            return false;
        }
        //UserDTO user = (UserDTO) session.getAttribute("user");
        Map userMap = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY+token);
        log.info(userMap.toString());

        if(null == userMap){
            response.setStatus(401);
            return false;
        }
        UserDTO user = BeanUtil.fillBeanWithMap(userMap,new UserDTO(),false);
        UserHolder.saveUser(user);
        //刷新有效期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token,30, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
