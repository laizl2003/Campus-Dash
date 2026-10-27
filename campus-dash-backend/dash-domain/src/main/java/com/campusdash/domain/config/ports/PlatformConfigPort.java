package com.campusdash.domain.config.ports;

/** 平台可在线调整的比例配置 */
public interface PlatformConfigPort {

    /** 平台佣金比例，例如 0.08 */
    double commissionRate();

    /** 优惠券面额中由跑腿承担的比例，例如 0.35 */
    double voucherRunnerBearRatio();

    /** 管理端修改比例（落库并立即生效） */
    void update(double commissionRate, double voucherRunnerBearRatio);
}
