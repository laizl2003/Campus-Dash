package com.campusdash.application.usecase;

import com.campusdash.domain.credit.ports.CreditRepository;
import org.springframework.stereotype.Service;

/**
 * 信用分快照校准。
 *
 * P5 的业务事务增量更新 credit_score；这个用例由 worker 每日触发，
 * 按信用事件 30 天窗口重算快照，移除窗口外事件的历史贡献。
 */
@Service
public class CreditCalibrationUseCase {

    private final CreditRepository creditRepository;

    public CreditCalibrationUseCase(CreditRepository creditRepository) {
        this.creditRepository = creditRepository;
    }

    public int calibrate(int windowDays, int limit) {
        return creditRepository.calibrateScores(Math.max(windowDays, 1), Math.max(limit, 1));
    }
}
