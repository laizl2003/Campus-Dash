package com.campusdash.domain.auth.model;

import java.time.Instant;

/**
 * 账号聚合（只读视图）。
 *
 * user_id 是字符串：注册用户由服务端生成 UUID，仲裁账号固定为 '001'。
 * 密码只以哈希形式存在，任何日志、响应都不能带出 passwordHash。
 */
public record UserAccount(
        String userId,
        String username,
        String phone,
        String passwordHash,
        Instant passwordUpdatedAt,
        Instant createdAt,
        Instant updatedAt,
        String role,
        String avatarUrl,
        long campusId,
        int creditScore,
        boolean enabled,
        Instant lastLoginAt,
        String lastLoginIp,
        int loginFailCount,
        String remark) {

    /** 普通用户 */
    public static final String ROLE_USER = "user";
    /** 仲裁员，系统唯一固定账号 */
    public static final String ROLE_ADMIN = "Admin";

    /** 仲裁账号固定 id：需求规定为 001 */
    public static final String ADMIN_USER_ID = "001";
    public static final String ADMIN_USERNAME = "Admin";

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }

    /** 脱敏：日志与异常里用这个，避免把哈希带出去 */
    public String masked() {
        return "UserAccount[userId=" + userId + ", username=" + username + ", role=" + role + "]";
    }
}
