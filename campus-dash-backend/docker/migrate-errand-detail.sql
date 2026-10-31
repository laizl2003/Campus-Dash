-- 任务结构化字段：取货地、送达地、开始时间、任务描述
-- 仅 DELIVERY / BUY / QUEUE 使用前三个地点/时间字段；OTHER 只填 title + description
ALTER TABLE errand
  ADD COLUMN location_from VARCHAR(128) NULL COMMENT '取货地点' AFTER title,
  ADD COLUMN location_to   VARCHAR(128) NULL COMMENT '送达地点' AFTER location_from,
  ADD COLUMN start_time    DATETIME(3)  NULL COMMENT '任务开始时间' AFTER location_to,
  ADD COLUMN description   VARCHAR(300) NULL COMMENT '任务描述' AFTER start_time;
