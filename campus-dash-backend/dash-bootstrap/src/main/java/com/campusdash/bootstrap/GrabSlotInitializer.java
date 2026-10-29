package com.campusdash.bootstrap;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.grab.ports.GrabSlotPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 重启/清空后，PUBLISHED 任务的抢单名额键会丢失，
 * 抢单 Lua 会直接返回 NOT_GRABBABLE。启动时按 DB 重建缺失的名额键。
 */
@Component
public class GrabSlotInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GrabSlotInitializer.class);
    private static final long SLOT_TTL_SECONDS = Duration.ofDays(7).toSeconds();

    private final ErrandRepository errands;
    private final GrabSlotPort grabSlots;

    public GrabSlotInitializer(ErrandRepository errands, GrabSlotPort grabSlots) {
        this.errands = errands;
        this.grabSlots = grabSlots;
    }

    @Override
    public void run(ApplicationArguments args) {
        int restored = 0;
        for (Errand errand : errands.findPublished()) {
            int remaining = errand.slotTotal() - errand.slotTaken();
            if (remaining <= 0) continue;
            if (grabSlots.remainingSlot(errand.id()) >= 0) continue;
            grabSlots.initSlot(errand.id(), remaining, SLOT_TTL_SECONDS);
            restored++;
        }
        if (restored > 0) {
            log.info("抢单名额已从 DB 重建 {} 个", restored);
        }
    }
}
