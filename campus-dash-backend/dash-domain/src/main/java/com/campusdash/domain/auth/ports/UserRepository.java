package com.campusdash.domain.auth.ports;

import com.campusdash.domain.auth.model.UserAccount;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 账号仓储端口。
 *
 * 唯一性由数据库唯一索引兜底：existsBy* 只是为了给出更友好的错误码，
 * 真正的并发防线是 uk_user_username / uk_user_phone。
 */
public interface UserRepository {

    Optional<UserAccount> findByUsername(String username);

    Optional<UserAccount> findById(String userId);

    Optional<UserAccount> findByPhone(String phone);

    /** 全量账号（启动时补齐信用分快照用） */
    List<UserAccount> findAll();

    /** 当前在线人数：user.login_status = 1 */
    int countOnline();

    boolean existsByUsername(String username);

    boolean existsByPhone(String phone);

    /** 插入新账号；撞唯一索引时抛 BizException(USERNAME_TAKEN / PHONE_TAKEN) */
    void insert(UserAccount account);

    void markLoginSuccess(String userId, Instant at, String ip);

    /** 登录/心跳：标记在线并刷新 last_seen_at */
    void markOnline(String userId);

    /** 主动登出/强制下线：立即标记离线 */
    void markOffline(String userId);

    /** 清理心跳超时的在线标记，返回影响行数 */
    int sweepOffline(Instant cutoff);

    /** 启用/禁用账号：status 1=启用，0=禁用 */
    void updateStatus(String userId, boolean enabled);

    void markLoginFailure(String userId);

    /** 修改密码：只更新 password_hash / password_updated_at / updated_at */
    void updatePassword(String userId, String passwordHash, Instant at);

    /** 注销：物理删除账号（钱包与流水另行处理） */
    boolean deleteById(String userId);
}
