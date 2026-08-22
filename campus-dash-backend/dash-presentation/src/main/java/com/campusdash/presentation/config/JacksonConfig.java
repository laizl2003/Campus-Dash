package com.campusdash.presentation.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 雪花 ID 精度修复：所有 long/Long 序列化为字符串。
 *
 * JS 的 Number 只有 53 位安全整数（2^53 ≈ 9e15），雪花 ID 约 2.15e17，
 * JSON.parse 直接截断精度——前端拿到的是错的 ID，详情/抢单/操作全挂
 * （P4 浏览器联调实测：215731113107132416 被解析成 215731113107132400）。
 *
 * 反序列化方向不受影响：Jackson 把字符串转回 long 没有问题，
 * 前端传 string 或 number 都能正常绑定。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance);
            builder.modules(module);
        };
    }
}
