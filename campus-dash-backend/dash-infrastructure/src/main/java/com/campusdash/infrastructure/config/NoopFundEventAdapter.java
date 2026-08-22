package com.campusdash.infrastructure.config;

import com.campusdash.domain.wallet.ports.FundEventPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MQ 关闭时的兜底实现：直接执行本地事务，不发事件。
 *
 * 资金事件的消费方是站内消息（可有可无的通知），不是资金正确性的一环，
 * 所以 MQ 不可用时降级为"只保证钱对，通知丢了"是可接受的。
 * 反过来若把资金正确性建立在 MQ 上，MQ 一挂钱就错了，那就不能这么降级。
 */
@Component
@ConditionalOnProperty(name = "dash.mq.enabled", havingValue = "false", matchIfMissing = true)
public class NoopFundEventAdapter implements FundEventPort {

    private static final Logger log = LoggerFactory.getLogger(NoopFundEventAdapter.class);

    @Override
    public boolean publishInTransaction(FundEvent event, LocalWork localWork) {
        boolean ok = localWork.execute();
        log.debug("MQ 已关闭，资金事件未发布 bizNo={} committed={}", event.bizNo(), ok);
        return ok;
    }
}
