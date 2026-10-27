package com.campusdash.application.usecase;

import com.campusdash.domain.auth.model.UserAccount;
import com.campusdash.domain.auth.ports.AuthPort;
import com.campusdash.domain.auth.ports.CaptchaPort;
import com.campusdash.domain.auth.ports.PasswordHasher;
import com.campusdash.domain.auth.ports.RefreshTokenPort;
import com.campusdash.domain.auth.ports.UserRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 用户名 + 密码 + 图形验证码登录。
 *
 * 关键点：
 *   1. 先查库拿到 user_id，再签发 token —— token 的 sub 就是数据库里的 id；
 *      拦截器拿到的就是这个 id，不再从请求里相信任何自报身份。
 *   2. 账号不存在与密码错误返回同一个 BAD_CREDENTIALS，避免被用来枚举用户名。
 *   3. 失败累加 login_fail_count，成功清零，为后续锁定策略留数据。
 *   4. 仲裁员与普通用户走同一条路径，角色来自数据库 role 字段。
 */
@Service
public class LoginUserUseCase {

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final CaptchaPort captcha;
    private final AuthPort authPort;

    public LoginUserUseCase(UserRepository users, PasswordHasher hasher,
                            CaptchaPort captcha, AuthPort authPort) {
        this.users = users;
        this.hasher = hasher;
        this.captcha = captcha;
        this.authPort = authPort;
    }

    public record Command(String username, String password, String captchaId,
                          String captchaCode, String clientIp) {
    }

    public record LoggedIn(String userId, String username, String role,
                           String accessToken, String refreshToken) {
    }

    public LoggedIn login(Command command) {
        if (command == null) {
            throw new BizException(ErrorCode.BAD_CREDENTIALS, "空请求");
        }

        // 1. 验证码：一次性，先消耗
        if (!captcha.verify(command.captchaId(), command.captchaCode())) {
            throw new BizException(ErrorCode.CAPTCHA_INVALID, "请刷新后重新输入");
        }

        String username = command.username() == null ? "" : command.username().trim();
        String password = command.password() == null ? "" : command.password();
        if (username.isEmpty() || password.isEmpty()) {
            throw new BizException(ErrorCode.BAD_CREDENTIALS, "用户名或密码为空");
        }

        // 2. 查库：这一步拿到的 user_id 才是后续所有业务的身份
        // 账号不存在与密码错误必须给出同一句话，否则接口变成用户名枚举器
        UserAccount user = users.findByUsername(username)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_CREDENTIALS));

        if (!user.enabled()) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED, "userId=" + user.userId());
        }

        // 3. 校验密码：失败只记次数，不区分"账号不存在"与"密码错误"
        if (!hasher.matches(password, user.passwordHash())) {
            users.markLoginFailure(user.userId());
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }

        users.markLoginSuccess(user.userId(), Instant.now(),
                command.clientIp() == null ? "" : command.clientIp());

        // 4. 签发凭证：sub = 数据库 user_id
        String accessToken;
        String refreshToken = null;
        if (authPort instanceof RefreshTokenPort refreshPort) {
            RefreshTokenPort.TokenPair pair = refreshPort.loginWithRefresh(user.userId());
            accessToken = pair.accessToken();
            refreshToken = pair.refreshToken();
        } else {
            accessToken = authPort.login(user.userId());
        }

        return new LoggedIn(user.userId(), user.username(), user.role(), accessToken, refreshToken);
    }
}
