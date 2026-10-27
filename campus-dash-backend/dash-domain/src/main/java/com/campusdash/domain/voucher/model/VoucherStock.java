package com.campusdash.domain.voucher.model;

import java.time.Instant;

/** 库存视图：type 对应 {@link VoucherType#code()} */
public record VoucherStock(int type, int stock, String description, Instant lastChangeTime) {
}
