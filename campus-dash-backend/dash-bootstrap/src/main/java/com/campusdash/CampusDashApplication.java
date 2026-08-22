package com.campusdash;

import com.campusdash.shared.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 组装根（composition root）：唯一知道所有层的地方。
 * 领域层与应用层都不认识 Spring Boot，装配在这里统一完成。
 */
@SpringBootApplication
@EnableTransactionManagement
public class CampusDashApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampusDashApplication.class, args);
    }

    /**
     * workerId 一期从配置读取。P6 分库分表时改为启动时从 Redis 分配，
     * 避免多实例手工配置重复导致 ID 冲突。
     */
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(@Value("${dash.worker-id:1}") long workerId) {
        return new SnowflakeIdGenerator(workerId);
    }
}
