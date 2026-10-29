package com.campusdash.domain.chat.ports;

import java.time.Instant;

/** 聊天归档：把早于 cutoff 的消息从实时表搬到历史表。 */
public interface ChatArchivePort {

    /** 归档一批，返回搬移条数；0 表示没有更多数据。 */
    int archiveBefore(Instant cutoff, int batchSize);
}
