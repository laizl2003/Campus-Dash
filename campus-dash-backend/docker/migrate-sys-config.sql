CREATE TABLE IF NOT EXISTS sys_config (
  config_key   VARCHAR(64) NOT NULL,
  config_value VARCHAR(64) NOT NULL,
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台配置';

INSERT IGNORE INTO sys_config (config_key, config_value) VALUES
  ('settle.commission-rate', '0.08'),
  ('voucher.runner-bear-ratio', '0.35');
