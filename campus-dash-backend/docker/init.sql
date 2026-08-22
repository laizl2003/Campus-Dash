-- CampusDash 一期建表脚本
-- 金额字段一律 BIGINT 存"分"，绝不用 DECIMAL/DOUBLE
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS errand (
  id            BIGINT       NOT NULL COMMENT '雪花 ID',
  campus_id     BIGINT       NOT NULL COMMENT '校区 ID，P6 分片键',
  publisher_id  BIGINT       NOT NULL COMMENT '发单人',
  grabber_id    BIGINT       NULL     COMMENT '当前抢中者',
  type          VARCHAR(16)  NOT NULL COMMENT 'DELIVERY/BUY/QUEUE/OTHER',
  title         VARCHAR(64)  NOT NULL,
  reward_amount BIGINT       NOT NULL COMMENT '悬赏金额，单位分',
  slot_total    INT          NOT NULL DEFAULT 1 COMMENT '名额总数',
  slot_taken    INT          NOT NULL DEFAULT 0 COMMENT '已占名额',
  status        VARCHAR(16)  NOT NULL,
  round         INT          NOT NULL DEFAULT 0 COMMENT '流转轮次',
  version       BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  locked_at     DATETIME(3)  NULL     COMMENT '本轮抢中时间，P2 超时扫描用',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_campus_status (campus_id, status, created_at),
  KEY idx_timeout_scan (status, locked_at),
  KEY idx_autosettle_scan (status, delivered_at),
  KEY idx_publisher (publisher_id, created_at),
  -- 数据库级不变式：已占名额永远不能超过总名额（INV-1 的最后保险）
  CONSTRAINT ck_slot CHECK (slot_taken >= 0 AND slot_taken <= slot_total)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跑腿任务';

CREATE TABLE IF NOT EXISTS errand_status_log (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  campus_id   BIGINT      NOT NULL COMMENT '校区 ID，P6 与 errand 绑定分片',
  errand_id   BIGINT      NOT NULL,
  from_status VARCHAR(16) NOT NULL,
  to_status   VARCHAR(16) NOT NULL,
  round       INT         NOT NULL DEFAULT 0,
  operator_id BIGINT      NOT NULL,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_campus_errand_log (campus_id, errand_id, id),
  KEY idx_errand (errand_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='状态流转事件溯源';

CREATE TABLE IF NOT EXISTS grab_record (
  id         BIGINT      NOT NULL,
  campus_id  BIGINT      NOT NULL COMMENT '校区 ID，P6 与 errand 绑定分片',
  errand_id  BIGINT      NOT NULL,
  runner_id  BIGINT      NOT NULL,
  seq        INT         NOT NULL COMMENT '第几个名额，从 1 开始；候选记录为 0',
  round      INT         NOT NULL DEFAULT 0,
  result     VARCHAR(16) NOT NULL COMMENT 'GRABBED/CANDIDATE/EXPIRED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  -- INV-1：同一轮内，同一名额只能被占一次（防超卖的最后一道防线）
  -- round 必须进索引：任务超时流转/回退后 round 会自增，旧轮次的记录不该挡住新一轮抢单。
  -- P1 时索引是 (errand_id, seq)，单轮场景没问题；P2 引入多轮流转后立刻暴露：
  -- 回退成 PUBLISHED 的任务，新抢单者插入 seq=1 会撞上旧轮次遗留的记录。
  UNIQUE KEY uk_errand_round_seq (errand_id, round, seq),
  -- INV-2：同一轮内，同一用户只能占一个名额（防刷单）
  UNIQUE KEY uk_errand_round_user (errand_id, round, runner_id),
  KEY idx_campus_errand (campus_id, errand_id),
  KEY idx_runner (runner_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='抢单记录';

CREATE TABLE IF NOT EXISTS wallet_account (
  id         BIGINT      NOT NULL,
  owner_id   BIGINT      NOT NULL COMMENT '用户 ID；系统户用固定值',
  owner_type VARCHAR(16) NOT NULL COMMENT 'USER/ESCROW/COMMISSION',
  available  BIGINT      NOT NULL DEFAULT 0 COMMENT '可用余额，单位分',
  frozen     BIGINT      NOT NULL DEFAULT 0 COMMENT '冻结余额，单位分',
  version    BIGINT      NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_owner (owner_id, owner_type),
  -- 余额永不为负：即使代码有 bug，数据库也会拒绝
  CONSTRAINT ck_balance CHECK (available >= 0 AND frozen >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钱包账户（余额快照）';

CREATE TABLE IF NOT EXISTS wallet_ledger (
  id            BIGINT      NOT NULL,
  biz_no        VARCHAR(64) NOT NULL COMMENT '业务幂等号，如 escrow:{errandId}',
  account_id    BIGINT      NOT NULL,
  user_id       BIGINT      NOT NULL COMMENT 'P6 分片键',
  direction     VARCHAR(8)  NOT NULL COMMENT 'DEBIT/CREDIT',
  amount        BIGINT      NOT NULL COMMENT '单位分，恒为正',
  balance_after BIGINT      NOT NULL COMMENT '记账后余额，对账追溯用',
  ref_type      VARCHAR(24) NOT NULL,
  ref_id        BIGINT      NOT NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  -- 幂等：同一业务同一账户同一方向只能记一次
  UNIQUE KEY uk_biz_direction (biz_no, account_id, direction),
  KEY idx_account_time (account_id, created_at),
  KEY idx_ref (ref_type, ref_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金流水（事实来源，复式记账）';

CREATE TABLE IF NOT EXISTS escrow_order (
  id           BIGINT      NOT NULL,
  campus_id    BIGINT      NOT NULL COMMENT '校区 ID，P6 与 errand 绑定分片',
  errand_id    BIGINT      NOT NULL,
  publisher_id BIGINT      NOT NULL,
  amount       BIGINT      NOT NULL COMMENT '单位分',
  status       VARCHAR(16) NOT NULL COMMENT 'HELD/RELEASED/REFUNDED',
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  -- 一个任务只能有一张托管单
  UNIQUE KEY uk_errand (errand_id),
  KEY idx_campus_errand (campus_id, errand_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金托管单';

-- ---------- 初始数据 ----------
-- 系统户：托管账户与佣金账户
INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version) VALUES
  (1, -1, 'ESCROW', 0, 0, 0),
  (2, -2, 'COMMISSION', 0, 0, 0)
ON DUPLICATE KEY UPDATE id = id;

-- 测试用户：1001 发单人（余额 1000 元），2001-2100 跑腿
INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version) VALUES
  (1001, 1001, 'USER', 100000, 0, 0)
ON DUPLICATE KEY UPDATE id = id;

-- ==================== P2 新增 ====================

-- 本地消息表：跨进程最终一致的主方案。
-- 业务写入与消息登记在同一个本地事务里，要么都成功要么都失败；
-- 发送失败由 worker 扫 PENDING 重发。把"消息有没有发出去"变成可查询的数据库状态。
CREATE TABLE IF NOT EXISTS local_message (
  id            BIGINT       NOT NULL,
  msg_key       VARCHAR(96)  NOT NULL COMMENT '幂等键，如 timeout:{errandId}:{round}',
  topic         VARCHAR(64)  NOT NULL,
  payload       JSON         NOT NULL,
  deliver_at    DATETIME(3)  NOT NULL COMMENT '期望投递时间（定时消息）',
  status        VARCHAR(16)  NOT NULL COMMENT 'PENDING/SENT/DEAD',
  retry_count   INT          NOT NULL DEFAULT 0,
  next_retry_at DATETIME(3)  NOT NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  -- 幂等：同一轮的超时消息只会被登记一次
  UNIQUE KEY uk_msg_key (msg_key),
  KEY idx_scan (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';

-- 压测/测试轮次元信息。
-- 刻意不往业务表加 run_tag 列——压测设施不侵入业务模型。
CREATE TABLE IF NOT EXISTS bench_run (
  run_id      VARCHAR(32) NOT NULL COMMENT '如 20260818-014200',
  kind        VARCHAR(16) NOT NULL COMMENT 'BENCH/IT/E2E',
  scenario    VARCHAR(24) NOT NULL COMMENT 'S1/S4/S5/IT-GRAB/IT-ESCROW',
  started_at  DATETIME(3) NOT NULL,
  finished_at DATETIME(3) NULL,
  status      VARCHAR(16) NOT NULL COMMENT 'RUNNING/PASS/FAIL',
  concurrency INT         NULL,
  summary     JSON        NULL COMMENT '成功数/P99/错误分类等结果摘要',
  env_note    VARCHAR(255) NULL COMMENT '隔离情况等环境标注',
  PRIMARY KEY (run_id),
  KEY idx_scenario_time (scenario, started_at),
  KEY idx_started (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='压测/测试轮次元信息';

-- 轮次与业务数据的关联。清理时按 run_id 精确级联删除，不再全表 DELETE。
CREATE TABLE IF NOT EXISTS bench_run_item (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  run_id      VARCHAR(32) NOT NULL,
  entity_type VARCHAR(16) NOT NULL COMMENT 'ERRAND',
  entity_id   BIGINT      NOT NULL,
  PRIMARY KEY (id),
  KEY idx_run (run_id, entity_type),
  KEY idx_entity (entity_type, entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='轮次与业务数据的关联';

-- ==================== P3 新增 ====================

-- 资金审计日志：用 REQUIRES_NEW 独立事务写入。
-- 审计写失败不该回滚资金；资金回滚了审计也要保留（正是排查所需）。
CREATE TABLE IF NOT EXISTS fund_audit_log (
  id         BIGINT       NOT NULL,
  biz_no     VARCHAR(64)  NOT NULL COMMENT '与 wallet_ledger.biz_no 对应',
  action     VARCHAR(24)  NOT NULL COMMENT 'ESCROW/SETTLE/REFUND/ARBITRATE',
  errand_id  BIGINT       NOT NULL,
  operator_id BIGINT      NOT NULL,
  detail     JSON         NULL COMMENT '金额分配明细',
  result     VARCHAR(16)  NOT NULL COMMENT 'SUCCESS/FAILED',
  message    VARCHAR(255) NULL,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_biz (biz_no),
  KEY idx_errand (errand_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金审计日志';

-- 站内消息：事务消息的消费落点。
-- P5 接 WebSocket 时直接消费这张表，生产侧不用改。
CREATE TABLE IF NOT EXISTS notification (
  id          BIGINT      NOT NULL,
  msg_key     VARCHAR(96) NOT NULL COMMENT '消费幂等键',
  user_id     BIGINT      NOT NULL COMMENT '接收人',
  errand_id   BIGINT      NOT NULL,
  type        VARCHAR(24) NOT NULL COMMENT 'SETTLED/REFUNDED/ARBITRATED',
  content     VARCHAR(255) NOT NULL,
  read_flag   TINYINT     NOT NULL DEFAULT 0,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  -- 消费端幂等：同一事件对同一用户只生成一条站内消息
  UNIQUE KEY uk_msg_user (msg_key, user_id),
  KEY idx_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内消息';

-- 对账差异：正常情况必须为空。
-- 有记录就说明代码有 bug，不是"允许存在的小误差"。
CREATE TABLE IF NOT EXISTS recon_diff (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  check_date  DATE         NOT NULL,
  check_type  VARCHAR(24)  NOT NULL COMMENT 'DEBIT_CREDIT/SNAPSHOT/ESCROW_CLOSURE',
  subject     VARCHAR(64)  NULL COMMENT '差异主体：账户 id 或 errand id',
  expected    BIGINT       NULL,
  actual      BIGINT       NULL,
  detail      VARCHAR(500) NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  -- 对账 job 自身幂等：同一天同一校验类型同一主体只记一次
  UNIQUE KEY uk_date_type_subject (check_date, check_type, subject),
  KEY idx_date (check_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账差异';

-- ==================== P5 新增 ====================

-- 缓存一致性差异：校验 job 检出 MySQL 与 Redis 不一致时落这张表并主动修正。
-- 正常情况必须为空——有记录就说明缓存链路出了问题（失效丢失 / 回填了旧值）。
CREATE TABLE IF NOT EXISTS sync_diff (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  check_time  DATETIME(3)  NOT NULL,
  errand_id   BIGINT       NOT NULL,
  field       VARCHAR(32)  NOT NULL COMMENT '不一致的字段：status/reward_amount/version',
  db_value    VARCHAR(64)  NULL,
  cache_value VARCHAR(64)  NULL,
  fixed       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已删缓存修正',
  PRIMARY KEY (id),
  KEY idx_time (check_time),
  KEY idx_errand (errand_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='缓存一致性差异';

-- 信用分快照：当前分数。由业务事务内直接更新（与资金动作同事务，无中间态），
-- 每日 job 按 30 天滑动窗口重算校准（过期事件的贡献被移除）。
CREATE TABLE IF NOT EXISTS credit_score (
  user_id    BIGINT      NOT NULL,
  score      INT         NOT NULL DEFAULT 60 COMMENT '当前信用分，初始 60',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  version    BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信用分快照';

-- 信用事件流水：每次加减分的事实记录。
-- biz_no 唯一索引保证同一笔业务不重复计分（消费/重试幂等）。
CREATE TABLE IF NOT EXISTS credit_event (
  id         BIGINT      NOT NULL,
  biz_no     VARCHAR(96) NOT NULL COMMENT '幂等键：settle:{errandId} / revert:{errandId}:{round} 等',
  user_id    BIGINT      NOT NULL,
  type       VARCHAR(32) NOT NULL COMMENT 'SETTLE/GRAB_TIMEOUT_REVERT/CANCEL_AFTER_GRAB/DELIVERY_LATE/DISPUTE_LOSE',
  delta      INT         NOT NULL COMMENT '分数变化，可负',
  ref_type   VARCHAR(16) NOT NULL COMMENT '关联业务类型',
  ref_id     BIGINT      NOT NULL COMMENT '关联业务 id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_biz (biz_no),
  KEY idx_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信用事件流水';
