const crypto = require('crypto');
const fs = require('fs');

const count = 50;
const externalStart = Number(process.argv[2] || 0);
const letters = 'abcdefghijkmnpqrstuvwxyz';
const alnum = 'abcdefghijkmnpqrstuvwxyz23456789';
const digits = '23456789';

function pick(n, alphabet) {
  let out = '';
  for (let i = 0; i < n; i++) out += alphabet[crypto.randomInt(alphabet.length)];
  return out;
}
function password() {
  // 8-32 位且同时含字母与数字
  let s = 'Cd' + pick(4, letters) + pick(2, digits) + pick(2, alnum);
  return s.split('').sort(() => crypto.randomInt(3) - 1).join('');
}

const users = [];
const names = new Set();
const phones = new Set();
while (users.length < count) {
  const username = 'stu' + pick(6, alnum);
  if (names.has(username)) continue;
  let phone;
  do { phone = '13' + pick(9, digits); } while (phones.has(phone));
  names.add(username);
  phones.add(phone);
  const pw = password();
  const salt = crypto.randomBytes(16);
  const hash = crypto.pbkdf2Sync(pw, salt, 120000, 32, 'sha256');
  users.push({
    userId: crypto.randomUUID(),
    username, phone, pw, salt,
    stored: 'pbkdf2-sha256$120000$' + salt.toString('base64') + '$' + hash.toString('base64'),
  });
}

const sql = [];
sql.push('START TRANSACTION;');
users.forEach((u, i) => {
  const walletId = (960000000000000000n + BigInt(i * 2)).toString();
  const ledgerId = (970000000000000000n + BigInt(i * 10)).toString();
  const bizNo = 'seed:' + u.userId;
  const externalAfter = externalStart - (i + 1) * 50000;
  sql.push(
    'INSERT INTO `user` (user_id, username, phone, password_hash, password_updated_at, created_at, updated_at, role, avatar_url, campus_id, credit_score, status, last_login_at, last_login_ip, login_fail_count, remark) VALUES (' +
    ['\'' + u.userId + '\'', '\'' + u.username + '\'', '\'' + u.phone + '\'', '\'' + u.stored + '\'', 'NOW(3)', 'NOW(3)', 'NOW(3)', "'user'", "''", '1', '60', '1', 'NULL', "''", '0', "'seed'"].join(', ') + ');'
  );
  sql.push(
    'INSERT INTO wallet_account (id, owner_id, user_name, owner_type, available, frozen, version) VALUES (' +
    [walletId, '\'' + u.userId + '\'', '\'' + u.username + '\'', "'USER'", '50000', '0', '0'].join(', ') + ');'
  );
  sql.push(
    'INSERT INTO wallet_ledger (id, biz_no, account_id, user_id, direction, amount, balance_after, ref_type, ref_id) VALUES (' +
    [ledgerId, '\'' + bizNo + '\'', walletId, '\'' + u.userId + '\'', "'CREDIT'", '50000', '50000', "'RECHARGE'", '0'].join(', ') + ');'
  );
  sql.push(
    'INSERT INTO wallet_ledger (id, biz_no, account_id, user_id, direction, amount, balance_after, ref_type, ref_id) VALUES (' +
    [(BigInt(ledgerId) + 1n).toString(), '\'' + bizNo + '\'', '(SELECT id FROM wallet_account WHERE owner_id = \'-3\')', '\'' + u.userId + '\'', "'DEBIT'", '50000', String(externalAfter), "'RECHARGE'", '0'].join(', ') + ');'
  );
});
sql.push('UPDATE wallet_account SET available = available - ' + (count * 50000) + " WHERE owner_id = '-3';");
sql.push('COMMIT;');
fs.writeFileSync('seed-users.sql', sql.join('\n'));

const lines = ['# 种子用户（50 个）', '', '> 密码为明文，仅用于本地演示；数据库里存的是 PBKDF2-SHA256 哈希。', '', '| # | 用户名 | 密码 | 用户 ID | 手机号 | 余额 |', '| --- | --- | --- | --- | --- | --- |'];
users.forEach((u, i) => lines.push('| ' + (i + 1) + ' | ' + u.username + ' | ' + u.pw + ' | ' + u.userId + ' | ' + u.phone + ' | 500.00 元 |'));
lines.push('', '共 ' + count + ' 个账号，合计余额 ' + (count * 500) + ' 元；充值来源为外部资金池（-3），每笔都有一借一贷两条 wallet_ledger 记录。');
fs.writeFileSync('USER.md', lines.join('\n') + '\n');
console.log('generated', users.length);
