package com.campusdash.domain.errand.ports;

import java.time.Instant;

/**
 * 延迟双删的投递端口。
 *
 * 放在 domain/ports 而不是 application：infrastructure 只依赖 domain
 * （洋葱架构的依赖方向），端口放 application 会导致 infrastructure 反向依赖
 * application——ArchUnit 的"端口必须定义在 domain 层"规则也会直接判违反。
 * 这是我第一版写错的地方，编译器立刻报了出来。
 *
 * TOPIC 常量也挪到这里，避免 infrastructure 为了拿常量去 import application。
 */
public interface CacheEvictDelayPort {

    /** 延迟双删的 topic（DELAY 类型） */
    String TOPIC_CACHE_EVICT = "errand-cache-evict";

    void scheduleEvict(long errandId, Instant deliverAt);
}
