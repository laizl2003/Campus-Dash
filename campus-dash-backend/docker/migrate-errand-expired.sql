-- start_time -> expired_at：改为"任务过期时间"，可选。
-- 语义变化：原来表示"计划开始时间"，现在表示"没人接单就过期的时刻"。
ALTER TABLE errand
  CHANGE COLUMN start_time expired_at DATETIME(3) NULL COMMENT '任务过期时间，为空表示永久有效';

-- 过期状态：PUBLISHED -> EXPIRED，由延迟消息驱动
-- 在"我的任务"中展示为已过期，广场不再展示
