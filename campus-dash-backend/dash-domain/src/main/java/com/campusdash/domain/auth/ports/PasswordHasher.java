package com.campusdash.domain.auth.ports;

/** 密码哈希端口：领域只关心"能不能校验通过"，不关心用什么算法 */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String storedHash);
}
