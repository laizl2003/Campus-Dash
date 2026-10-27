INSERT INTO local_message (id, msg_key, topic, payload, deliver_at, status, retry_count, next_retry_at)
VALUES (999999000001, 'voucherexpire:240741253598154752:test', 'voucher-expire',
        '{"voucherId": 240741253598154752}', NOW(3), 'PENDING', 0, NOW(3));
