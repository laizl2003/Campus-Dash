package com.campusdash.infrastructure.auth;

import com.campusdash.domain.auth.ports.CaptchaPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * 图形验证码：服务端生成 PNG，答案只存在 Redis 里。
 *
 * 三个关键点：
 *   1. 答案不进响应、不进 Cookie，前端只拿到 captchaId 和图片
 *   2. 一次性：verify 用 GETDEL 取，取到即删，重放第二次一定失败
 *   3. 5 分钟过期：Redis TTL 兜底，前端不刷新也不会留下长期有效的答案
 */
@Component
public class ImageCaptchaService implements CaptchaPort {

    private static final String KEY_PREFIX = "auth:captcha:";
    private static final Duration TTL = Duration.ofMinutes(5);
    /** 去掉 0/O/1/I 这类肉眼易混字符 */
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int LENGTH = 4;
    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;

    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    public ImageCaptchaService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Captcha create() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        String captchaId = UUID.randomUUID().toString();
        redis.opsForValue().set(KEY_PREFIX + captchaId, code.toString(), TTL);
        return new Captcha(captchaId, render(code.toString()), (int) TTL.toSeconds());
    }

    @Override
    public boolean verify(String captchaId, String code) {
        if (captchaId == null || captchaId.isBlank() || code == null || code.isBlank()) {
            return false;
        }
        // GETDEL 原子取用：并发提交同一个 captchaId 时只有一个能拿到答案
        String expected = redis.opsForValue().getAndDelete(KEY_PREFIX + captchaId);
        return expected != null && expected.equalsIgnoreCase(code.trim());
    }

    /** 画图：渐变底 + 干扰线 + 轻微旋转的字 */
    private String render(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(246, 246, 250));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            g.setStroke(new BasicStroke(1.2f));
            for (int i = 0; i < 6; i++) {
                g.setColor(randomColor(150, 210));
                int x1 = random.nextInt(WIDTH);
                int y1 = random.nextInt(HEIGHT);
                int x2 = random.nextInt(WIDTH);
                int y2 = random.nextInt(HEIGHT);
                g.drawLine(x1, y1, x2, y2);
            }

            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
            for (int i = 0; i < code.length(); i++) {
                AffineTransform origin = g.getTransform();
                double angle = (random.nextDouble() - 0.5) * 0.6;
                int x = 14 + i * 26;
                int y = 29 + random.nextInt(4) - 2;
                g.rotate(angle, x, y);
                g.setColor(randomColor(20, 90));
                g.drawString(String.valueOf(code.charAt(i)), x, y);
                g.setTransform(origin);
            }

            for (int i = 0; i < 30; i++) {
                image.setRGB(random.nextInt(WIDTH), random.nextInt(HEIGHT), randomColor(120, 220).getRGB());
            }
        } finally {
            g.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("验证码图片生成失败", e);
        }
    }

    private Color randomColor(int min, int max) {
        return new Color(next(min, max), next(min, max), next(min, max));
    }

    private int next(int min, int max) {
        return min + random.nextInt(max - min);
    }
}
