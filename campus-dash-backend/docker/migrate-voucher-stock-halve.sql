-- 优惠券库存整体减半：重置为新的初始值（演示库里已消耗的额度一并回收）
UPDATE voucher_stock SET stock = 50, last_change_time = NOW(3) WHERE type IN (1, 4, 8, 10);
UPDATE voucher_stock SET stock = 25, last_change_time = NOW(3) WHERE type IN (2, 5, 9, 11);
UPDATE voucher_stock SET stock = 12, last_change_time = NOW(3) WHERE type IN (3, 6);
UPDATE voucher_stock SET stock = 6,  last_change_time = NOW(3) WHERE type IN (7);
