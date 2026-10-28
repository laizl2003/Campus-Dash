package com.campusdash.application.usecase;

import com.campusdash.domain.auth.model.UserAccount;
import com.campusdash.domain.auth.ports.AuthPort;
import com.campusdash.domain.auth.ports.CaptchaPort;
import com.campusdash.domain.auth.ports.PasswordHasher;
import com.campusdash.domain.auth.ports.UserRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 修改密码：用户名 + 原密码 + 新密码 + 图形验证码。
 *
 * 校验顺序与注册一致：先消耗验证码挡机器请求，再查库、验原密码、校验新密码强度。
 * "用户名不存在"和"原密码错误"统一返回 BAD_CREDENTIALS，避免把接口变成账号探测工具。
 */
@Service
public class ChangePasswordUseCase {

    /** 与注册保持同一套强度规则：8-32 位，必须同时含字母和数字 */
    private static final Pattern PASSWORD = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z0-9!@#$%^&*._-]{8,32}$");

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final CaptchaPort captcha;
    private final AuthPort authPort;

    public ChangePasswordUseCase(UserRepository users, PasswordHasher hasher, CaptchaPort captcha,
                                 AuthPort authPort) {
        this.users = users;
        this.hasher = hasher;
        this.captcha = captcha;
        this.authPort = authPort;
    }

    public record Command(String username, String oldPassword, String newPassword,
                          String captchaId, String captchaCode) {
    }

    public void change(Command command) {
        if (command == null) {
            throw new BizException(ErrorCode.BAD_CREDENTIALS, "请求体不能为空");
        }
        // 1. 验证码一次性，先消费
        if (!captcha.verify(command.captchaId(), command.captchaCode())) {
            throw new BizException(ErrorCode.CAPTCHA_INVALID, "请刷新后重新输入");
        }

        String username = command.username() == null ? "" : command.username().trim();
        UserAccount account = users.findByUsername(username)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_CREDENTIALS, "username not found"));

        if (!account.enabled()) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        // 2. 原密码必须匹配；错误信息不区分"用户不存在/密码错"
        if (!hasher.matches(command.oldPassword(), account.passwordHash())) {
            throw new BizException(ErrorCode.BAD_CREDENTIALS, "old password mismatch");
        }

        // 3. 新密码强度与"不能与原密码相同"
        if (command.newPassword() == null || !PASSWORD.matcher(command.newPassword()).matches()) {
            throw new BizException(ErrorCode.INVALID_PASSWORD, "长度或字符类型不满足要求");
        }
        if (command.newPassword().equals(command.oldPassword())) {
            throw new BizException(ErrorCode.INVALID_PASSWORD, "新密码不能与原密码相同");
        }

        users.updatePassword(account.userId(), hasher.hash(command.newPassword()), Instant.now());
        // 密码变更 = 该账号所有旧会话立即失效：撤销全部 token 并置离线，
        // 本机账号簿也会由前端移除，不能再免密快速登录。
        authPort.revokeAllSessions(account.userId());
        users.markOffline(account.userId());
    }
}
