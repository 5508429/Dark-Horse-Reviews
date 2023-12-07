package com.hmdp.config;

import com.hmdp.interceptor.UserInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class InterceptorConfig {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Bean
    public WebMvcConfigurer inter1(){
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new UserInterceptor(stringRedisTemplate))
                        .addPathPatterns("/**")
                        .excludePathPatterns(
                                "/shop/**",
                                "/voucher/**",
                                "/shop-type/**",
                                "/upload/**",
                                "/blog/hot",
                                "/user/code",
                                "/user/login");
            }
        };
    }
}
