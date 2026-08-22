package com.campusdash.domain.wallet.ports;

/**
 * 资金审计端口。实现必须用 REQUIRES_NEW 独立事务：
 * 审计写失败不该回滚资金；资金回滚了审计也要留下（那正是排查现场）。
 */
public interface FundAuditPort {

    void record(String bizNo, String action, long errandId, long operatorId,
                String detailJson, boolean success, String message);
}
