package com.campusdash.presentation;

import com.campusdash.presentation.auth.AdminInterceptor;
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
    private final AdminInterceptor adminInterceptor;
    private final List<String> corsOrigins;

    public WebConfig(AuthInterceptor authInterceptor,
                     AdminInterceptor adminInterceptor,
                     @Value("${dash.web.cors-origins:http://localhost:5173}") String corsOrigins) {
        this.authInterceptor = authInterceptor;
        this.adminInterceptor = adminInterceptor;
        this.corsOrigins = List.of(corsOrigins.split(","));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 登录/登出本身不需要认证；健康检查供前端探测
                .excludePathPatterns("/api/auth/login", "/api/auth/refresh", "/api/health")
                // 注册与验证码：此时还没有任何凭证
                .excludePathPatterns("/api/auth/register", "/api/auth/captcha")
                // 内部观测端点：仅供本机压测/运维，生产由网关屏蔽外部访问
                .excludePathPatterns("/api/internal/**")
                .order(1);

        // 管理端：必须在认证之后执行（order 2），此时 ThreadLocal 里已有 userId。
        // 未认证的请求已被 order=1 的拦截器挡下，这里只处理"已登录但不是管理员"。
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/api/admin/**")
                .order(2);
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
