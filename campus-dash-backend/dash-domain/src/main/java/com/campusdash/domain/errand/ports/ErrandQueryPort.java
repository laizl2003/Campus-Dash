package com.campusdash.domain.errand.ports;

import com.campusdash.domain.errand.model.Errand;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 任务读模型端口——与写仓储 ErrandRepository 分开。
 *
 * 读写分离不是为了架构好看：写路径（CAS、状态流转）的方法签名越窄越好，
 * 查询路径要分页、要关联、要时间线，两者混在一个接口里会互相拉扯。
 *
 * 分页这里用 LIMIT/OFFSET。深分页（OFFSET 很大）时 MySQL 要先扫再扔，
 * 量级到十万级要改游标分页（WHERE created_at < ? LIMIT n），见 P6 改造计划。
 */
public interface ErrandQueryPort {

    /** 任务广场：按校区 + 状态查，默认只查 PUBLISHED。走 idx_campus_status */
    List<Errand> list(long campusId, String status, int page, int size);

    /**
     * 任务广场游标分页：按 created_at DESC, id DESC 稳定排序。
     *
     * beforeCreatedAt/beforeId 为空表示第一页；非空时只取游标之后的数据。
     */
    List<CursorItem> listByCursor(long campusId, String status, Instant beforeCreatedAt, Long beforeId, int size);

    /** 我发布的。走 idx_publisher */
    List<Errand> listByPublisher(long publisherId, int page, int size);

    /** 我抢中的（历史所有轮次都算）。走 grab_record.idx_runner 反查 */
    List<Errand> listByRunner(long runnerId, int page, int size);

    /** 状态时间线：事件溯源的展示面 */
    List<StatusChange> statusLog(long campusId, long errandId);

    /**
     * 随机抽样任务 id，供一致性校验 job 使用。
     * ORDER BY RAND() 适用于万级数据；十万级以上改按 id 分段随机。
     */
    List<Long> sampleIds(int limit);

    /** 跑腿在途任务数（已接单未送达）：抢单资格校验用 */
    int countOngoingByRunner(long runnerId);

    record StatusChange(Instant time, String from, String to, int round, long operatorId) {}

    record CursorItem(Errand errand, Instant createdAt) {}
}
