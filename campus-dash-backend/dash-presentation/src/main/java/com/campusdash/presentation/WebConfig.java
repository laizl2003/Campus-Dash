package com.campusdash.presentation;

import com.campusdash.presentation.auth.AuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final List<String> corsOrigins;

    public WebConfig(AuthInterceptor authInterceptor,
                     @Value("${dash.web.cors-origins:http://localhost:5173}") String corsOrigins) {
        this.authInterceptor = authInterceptor;
        this.corsOrigins = List.of(corsOrigins.split(","));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 登录/登出本身不需要认证；健康检查供前端探测
                .excludePathPatterns("/api/auth/login", "/api/auth/refresh", "/api/health")
                // 内部观测端点：仅供本机压测/运维，生产由网关屏蔽外部访问
                .excludePathPatterns("/api/internal/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(corsOrigins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
