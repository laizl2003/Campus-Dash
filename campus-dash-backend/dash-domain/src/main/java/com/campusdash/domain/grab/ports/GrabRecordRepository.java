package com.campusdash.domain.grab.ports;

import com.campusdash.domain.grab.model.GrabRecord;

public interface GrabRecordRepository {

    /**
     * 插入抢单记录。撞唯一索引时必须抛异常，由应用层捕获后判定为抢单失败。
     * 这是防超卖的最后一道防线，绝不能用 INSERT IGNORE 把冲突吞掉。
     */
    void insert(GrabRecord record);

    int countGrabbed(long campusId, long errandId);
}
