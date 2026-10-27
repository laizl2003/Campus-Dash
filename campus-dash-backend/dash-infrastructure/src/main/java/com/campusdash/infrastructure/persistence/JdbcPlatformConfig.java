package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.config.ports.PlatformConfigPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 平台比例配置：优先读 sys_config，读不到则回退 application.yaml。
 * 更新时写库并立即刷新内存，下一次结算/发布就生效，不需要重启。
 */
@Component
public class JdbcPlatformConfig implements PlatformConfigPort {

    private static final String KEY_COMMISSION = "settle.commission-rate";
    private static final String KEY_VOUCHER_RATE = "voucher.runner-bear-ratio";

    private final JdbcTemplate jdbc;
    private final double defaultCommissionRate;
    private final double defaultVoucherRate;

    private volatile double commissionRate;
    private volatile double voucherRunnerBearRatio;

    public JdbcPlatformConfig(JdbcTemplate jdbc,
                              @Value("${dash.settle.commission-rate:0.08}") double defaultCommissionRate,
                              @Value("${dash.voucher.runner-bear-ratio:0.35}") double defaultVoucherRate) {
        this.jdbc = jdbc;
        this.defaultCommissionRate = defaultCommissionRate;
        this.defaultVoucherRate = defaultVoucherRate;
        this.commissionRate = read(KEY_COMMISSION, defaultCommissionRate);
        this.voucherRunnerBearRatio = read(KEY_VOUCHER_RATE, defaultVoucherRate);
    }

    @Override
    public double commissionRate() {
        return commissionRate;
    }

    @Override
    public double voucherRunnerBearRatio() {
        return voucherRunnerBearRatio;
    }

    public double defaultCommissionRate() {
        return defaultCommissionRate;
    }

    public double defaultVoucherRate() {
        return defaultVoucherRate;
    }

    /** 管理端修改：同时落库与刷新内存 */
    public void update(double commissionRate, double voucherRunnerBearRatio) {
        write(KEY_COMMISSION, commissionRate);
        write(KEY_VOUCHER_RATE, voucherRunnerBearRatio);
        this.commissionRate = commissionRate;
        this.voucherRunnerBearRatio = voucherRunnerBearRatio;
    }

    private double read(String key, double fallback) {
        try {
            var values = jdbc.queryForList(
                    "SELECT config_value FROM sys_config WHERE config_key = ?", String.class, key);
            return values.isEmpty() ? fallback : Double.parseDouble(values.get(0));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private void write(String key, double value) {
        jdbc.update("""
                INSERT INTO sys_config (config_key, config_value) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE config_value = VALUES(config_value)
                """, key, String.valueOf(value));
    }
}
