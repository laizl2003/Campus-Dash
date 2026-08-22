package com.campusdash.worker;

import com.campusdash.application.usecase.CreditCalibrationUseCase;
import com.campusdash.domain.credit.model.CreditEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 信用分快照每日校准。
 *
 * 增量计分保证业务事务内无中间态；每日校准负责把 30 天窗口外的历史贡献移除。
 */
@Component
public class CreditCalibrationJob {

    private static final Logger log = LoggerFactory.getLogger(CreditCalibrationJob.class);

    private final CreditCalibrationUseCase useCase;
    private final int batchSize;

    public CreditCalibrationJob(CreditCalibrationUseCase useCase,
                                @Value("${dash.credit.calibration-batch-size:1000}") int batchSize) {
        this.useCase = useCase;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${dash.credit.calibration-cron:0 0 3 * * ?}")
    public void calibrate() {
        int changed = useCase.calibrate(CreditEventType.WINDOW_DAYS, batchSize);
        if (changed > 0) {
            log.info("信用分快照校准完成 changed={}", changed);
        }
    }
}
