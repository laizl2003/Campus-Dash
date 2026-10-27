ALTER TABLE wallet_account
  ADD COLUMN user_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT '账户所属用户/系统户名称' AFTER owner_type;

UPDATE wallet_account wa JOIN `user` u ON wa.owner_id = u.user_id
   SET wa.user_name = u.username
 WHERE wa.owner_type = 'USER';

UPDATE wallet_account SET user_name = '托管账户' WHERE owner_id = '-1';
UPDATE wallet_account SET user_name = '佣金账户' WHERE owner_id = '-2';
UPDATE wallet_account SET user_name = '外部资金池' WHERE owner_id = '-3';
UPDATE wallet_account SET user_name = '营销账户' WHERE owner_id = '-4';
