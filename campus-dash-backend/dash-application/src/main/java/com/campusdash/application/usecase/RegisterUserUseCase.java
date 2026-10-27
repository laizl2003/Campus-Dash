package com.campusdash.application.usecase;

import com.campusdash.domain.auth.model.UserAccount;
import com.campusdash.domain.auth.ports.CaptchaPort;
import com.campusdash.domain.auth.ports.PasswordHasher;
import com.campusdash.domain.auth.ports.UserRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 注册用例：用户名 + 手机号 + 两遍密码 + 图形验证码。
 *
 * 校验顺序是刻意排的：
 *   1. 验证码（一次性，先消耗掉，挡机器批量注册）
 *   2. 格式（不查库，纯内存判断）
 *   3. 两次密码一致
 *   4. 唯一性（查库，最后做）
 * 先查库后校验格式会让攻击者用注册接口探测"哪些用户名存在"。
 */
@Service
public class RegisterUserUseCase {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,20}$");
    /** 中国大陆手机号：1 开头，第二位 3-9，共 11 位 */
    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    /** 8-32 位，必须同时含字母和数字；允许常见符号 */
    private static final Pattern PASSWORD = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z0-9!@#$%^&*._-]{8,32}$");

    /** 新用户默认信用分，与 credit_score 表初始值保持一致 */
    private static final int DEFAULT_CREDIT = 60;
    /** 一期只做一个校区；分库分表后由注册入口决定 */
    private static final long DEFAULT_CAMPUS = 1L;

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final CaptchaPort captcha;

    public RegisterUserUseCase(UserRepository users, PasswordHasher hasher, CaptchaPort captcha) {
        this.users = users;
        this.hasher = hasher;
        this.captcha = captcha;
    }

    public record Command(String username, String phone, String password,
                          String confirmPassword, String captchaId, String captchaCode) {
    }

    public record Registered(String userId, String username, String phone, String role) {
    }

    public Registered register(Command command) {
        if (command == null) {
            throw new BizException(ErrorCode.INVALID_USERNAME, "请求体不能为空");
        }

        // 1. 验证码：必填、一次性。校验失败不暴露"是过期还是填错"
        if (!captcha.verify(command.captchaId(), command.captchaCode())) {
            throw new BizException(ErrorCode.CAPTCHA_INVALID, "请刷新后重新输入");
        }

        String username = trim(command.username());
        String phone = trim(command.phone());

        // 2. 格式
        if (!USERNAME.matcher(username).matches()) {
            throw new BizException(ErrorCode.INVALID_USERNAME, "username=" + username);
        }
        if (!PHONE.matcher(phone).matches()) {
            throw new BizException(ErrorCode.INVALID_PHONE, "phone=" + maskPhone(phone));
        }
        if (command.password() == null || !PASSWORD.matcher(command.password()).matches()) {
            throw new BizException(ErrorCode.INVALID_PASSWORD, "长度或字符类型不满足要求");
        }

        // 3. 两次密码一致
        if (!command.password().equals(command.confirmPassword())) {
            throw new BizException(ErrorCode.PASSWORD_MISMATCH);
        }

        // 4. 唯一性：先给友好错误码，并发下的最终防线仍是数据库唯一索引
        if (users.existsByUsername(username)) {
            throw new BizException(ErrorCode.USERNAME_TAKEN, "username=" + username);
        }
        if (users.existsByPhone(phone)) {
            throw new BizException(ErrorCode.PHONE_TAKEN, "phone=" + maskPhone(phone));
        }

        Instant now = Instant.now();
        String userId = UUID.randomUUID().toString();
        UserAccount account = new UserAccount(
                userId,
                username,
                phone,
                hasher.hash(command.password()),
                now,                 // password_updated_at：注册即首次设置密码
                now,                 // created_at
                now,                 // updated_at
                UserAccount.ROLE_USER,
                "",                  // avatar_url：留空，前端用用户名首字母占位
                DEFAULT_CAMPUS,
                DEFAULT_CREDIT,
                true,                // enabled
                null,                // last_login_at
                "",                  // last_login_ip
                0,                   // login_fail_count
                "");
        users.insert(account);
        return new Registered(userId, username, phone, UserAccount.ROLE_USER);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /** 手机号进日志前脱敏，避免注册失败日志泄露完整号码 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
