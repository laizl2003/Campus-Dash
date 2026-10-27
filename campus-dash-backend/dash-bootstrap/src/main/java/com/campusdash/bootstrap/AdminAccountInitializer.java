package com.campusdash.bootstrap;

import com.campusdash.domain.auth.model.UserAccount;
import com.campusdash.domain.auth.ports.PasswordHasher;
import com.campusdash.domain.auth.ports.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 种入系统唯一的仲裁账号：username=Admin / password=12345678 / userId=001 / role=Admin。
 *
 * 为什么用代码而不是 SQL 种子：密码必须是 PBKDF2 哈希，且盐是随机的，
 * 写在 SQL 里只能硬编码一串哈希；用初始化器可以在任意环境生成正确哈希，
 * 同时保留"已存在就跳过"的幂等性。
 *
 * 注意：这个密码不满足注册接口的复杂度规则（纯数字），
 * 它是按需求固定下来的系统账号，不经过注册流程。
 */
@Component
public class AdminAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountInitializer.class);
    private static final String ADMIN_PHONE = "13800000000";

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final String adminPassword;

    public AdminAccountInitializer(UserRepository users,
                                   PasswordHasher hasher,
                                   @Value("${dash.auth.admin-password:12345678}") String adminPassword) {
        this.users = users;
        this.hasher = hasher;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.findById(UserAccount.ADMIN_USER_ID).isPresent()) {
            return;
        }
        Instant now = Instant.now();
        UserAccount admin = new UserAccount(
                UserAccount.ADMIN_USER_ID,
                UserAccount.ADMIN_USERNAME,
                ADMIN_PHONE,
                hasher.hash(adminPassword),
                now, now, now,
                UserAccount.ROLE_ADMIN,
                "",
                1L,
                100,
                true,
                null, "", 0,
                "系统固定仲裁账号");
        users.insert(admin);
        log.info("已创建系统仲裁账号 userId={} username={}", UserAccount.ADMIN_USER_ID, UserAccount.ADMIN_USERNAME);
    }
}
