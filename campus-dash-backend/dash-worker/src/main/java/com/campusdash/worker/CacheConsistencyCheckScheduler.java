package com.campusdash.worker;

import com.campusdash.application.usecase.query.CacheConsistencyCheckUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 一致性校验的调度壳：业务逻辑在 application 层（可被集成测试直接注入），
 * worker 只负责"每 5 分钟触发一次"。
 */
@Component
public class CacheConsistencyCheckScheduler {

    private final CacheConsistencyCheckUseCase checkUseCase;

    public CacheConsistencyCheckScheduler(CacheConsistencyCheckUseCase checkUseCase) {
        this.checkUseCase = checkUseCase;
    }

    @Scheduled(fixedDelayString = "${dash.cache.check-interval-ms:300000}")
    public void check() {
        checkUseCase.runOnce();
    }
}
