package com.campusdash.domain.wallet.model;

import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Money;

/**
 * 托管单：一个任务对应一张。
 * HELD 表示资金已托管在平台中间户，RELEASED 表示已结算给跑腿，REFUNDED 表示已退回发单人。
 * 进入 DISPUTED 的任务，托管资金既不结算也不退款，等仲裁——这是资金的安全阀。
 *
 * HELD 是唯一可变状态：RELEASED 与 REFUNDED 都是终态，
 * 这保证了"一笔托管的钱只会有一个去向"，不会既结算又退款。
 */
public record EscrowOrder(long id, long campusId, long errandId, long publisherId,
                          Money amount, EscrowStatus status) {

    public static EscrowOrder held(long id, long campusId, long errandId, long publisherId, Money amount) {
        return new EscrowOrder(id, campusId, errandId, publisherId, amount, EscrowStatus.HELD);
    }

    /** 结算：HELD -> RELEASED */
    public EscrowOrder release() {
        requireHeld("release");
        return new EscrowOrder(id, campusId, errandId, publisherId, amount, EscrowStatus.RELEASED);
    }

    /** 退款：HELD -> REFUNDED */
    public EscrowOrder refund() {
        requireHeld("refund");
        return new EscrowOrder(id, campusId, errandId, publisherId, amount, EscrowStatus.REFUNDED);
    }

    private void requireHeld(String action) {
        if (status != EscrowStatus.HELD) {
            throw new BizException(ErrorCode.ESCROW_NOT_HELD,
                    "escrow=" + id + " status=" + status + " action=" + action);
        }
    }

    public enum EscrowStatus {
        HELD, RELEASED, REFUNDED
    }
}
