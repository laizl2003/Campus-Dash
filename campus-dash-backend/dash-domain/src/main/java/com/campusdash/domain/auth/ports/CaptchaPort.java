package com.campusdash.domain.auth.ports;

/**
 * 图形验证码端口。
 *
 * 语义要求：一次性（校验成功即作废）、有有效期、大小写不敏感。
 */
public interface CaptchaPort {

    Captcha create();

    boolean verify(String captchaId, String code);

    record Captcha(String captchaId, String imageBase64, int expireSeconds) {}
}
