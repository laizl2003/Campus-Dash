package com.campusdash.presentation.auth;

import com.campusdash.application.usecase.ChangePasswordUseCase;
import com.campusdash.application.usecase.RegisterUserUseCase;
import com.campusdash.domain.auth.ports.CaptchaPort;
import com.campusdash.shared.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 注册与验证码。
 *
 * 单独一个 Controller 而不是塞进 AuthController：
 * 登录/刷新/登出只依赖 AuthPort，注册依赖账号仓储、哈希与验证码，
 * 拆开后 AuthController 的单元测试不需要为注册造一堆假依赖。
 *
 * 这两个端点都不走 AuthInterceptor（WebConfig 里已排除）：
 * 注册时还没有任何凭证。
 */
@RestController
@RequestMapping("/api/auth")
public class RegistrationController {

    private final RegisterUserUseCase registerUser;
    private final ChangePasswordUseCase changePassword;
    private final CaptchaPort captcha;

    public RegistrationController(RegisterUserUseCase registerUser,
                                  ChangePasswordUseCase changePassword,
                                  CaptchaPort captcha) {
        this.registerUser = registerUser;
        this.changePassword = changePassword;
        this.captcha = captcha;
    }

    public record RegisterRequest(String username, String phone, String password,
                                  String confirmPassword, String captchaId, String captchaCode) {
    }

    /** 取一张新验证码。答案只存在 Redis，响应里只有 id 和图片。 */
    @GetMapping("/captcha")
    public Result<Map<String, Object>> captcha() {
        CaptchaPort.Captcha issued = captcha.create();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("captchaId", issued.captchaId());
        data.put("image", "data:image/png;base64," + issued.imageBase64());
        data.put("expireSeconds", issued.expireSeconds());
        return Result.ok(data);
    }

    public record ChangePasswordRequest(String username, String oldPassword, String newPassword,
                                        String captchaId, String captchaCode) {
    }

    /** 修改密码：不要求已登录（忘记密码场景也走这里），靠原密码 + 验证码双重校验 */
    @PostMapping("/password")
    public Result<Void> changePassword(@RequestBody ChangePasswordRequest req) {
        changePassword.change(new ChangePasswordUseCase.Command(
                req.username(), req.oldPassword(), req.newPassword(),
                req.captchaId(), req.captchaCode()));
        return Result.ok(null);
    }

    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody RegisterRequest req) {
        RegisterUserUseCase.Registered registered = registerUser.register(
                new RegisterUserUseCase.Command(req.username(), req.phone(), req.password(),
                        req.confirmPassword(), req.captchaId(), req.captchaCode()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", registered.userId());
        data.put("username", registered.username());
        data.put("phone", registered.phone());
        data.put("role", registered.role());
        return Result.ok(data);
    }
}
