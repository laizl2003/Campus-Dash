package com.campusdash.worker;

import com.campusdash.application.usecase.TimeoutTransferUseCase;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.ports.ErrandRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 超时流转的兜底扫描——"主通道 + 兜底"模式的兜底侧。
 *
 * 主通道是 RocketMQ 定时消息，性能好、实时性强。但消息可能因为极端情况丢失
 * （Broker 磁盘故障、消息被误删、发送时应用崩溃），而"超时不流转"意味着
 * 任务永久卡在 LOCKED、资金永久冻结——这是不能接受的。
 *
 * 所以这里每 5 秒扫一次"超时且宽限期已过"的任务补偿处理。
 * 宽限期（grace）让主通道先处理，避免两边重复干活；即使重复了，
 * 幂等三件套也保证不会流转两次。
 */
@Component
public class TimeoutScanJob {

    private static final Logger log = LoggerFactory.getLogger(TimeoutScanJob.class);
    private static final int BATCH_LIMIT = 200;

    private final ErrandRepository errandRepository;
    private final TimeoutTransferUseCase timeoutTransferUseCase;
    private final long graceSeconds;

    public TimeoutScanJob(ErrandRepository errandRepository,
                          TimeoutTransferUseCase timeoutTransferUseCase,
                          @Value("${dash.timeout.scan-grace-seconds:2}") long graceSeconds) {
        this.errandRepository = errandRepository;
        this.timeoutTransferUseCase = timeoutTransferUseCase;
        this.graceSeconds = graceSeconds;
    }

    @Scheduled(fixedDelayString = "${dash.timeout.scan-interval-ms:5000}")
    public void scan() {
        long timeout = timeoutTransferUseCase.confirmTimeoutSeconds() + graceSeconds;
        List<Errand> candidates = errandRepository.findConfirmTimeout(timeout, BATCH_LIMIT);
        if (candidates.isEmpty()) {
            return;
        }
        int transferred = 0;
        int reverted = 0;
        int skipped = 0;
        for (Errand errand : candidates) {
            try {
                switch (timeoutTransferUseCase.handleTimeout(errand.id(), errand.round())) {
                    case TRANSFERRED -> transferred++;
                    case REVERTED -> reverted++;
                    case SKIPPED -> skipped++;
                }
            } catch (RuntimeException e) {
                // 单条失败不能中断整批：下一轮扫描还会捞到它
                log.warn("兜底流转失败 errandId={}", errand.id(), e);
            }
        }
        log.info("兜底扫描完成 捞取={} 流转={} 回退={} 跳过={}",
                candidates.size(), transferred, reverted, skipped);
    }
}
