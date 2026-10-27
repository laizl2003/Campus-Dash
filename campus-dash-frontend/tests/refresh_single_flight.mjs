// 双 token 续期的行为验证。
//
// 不启动浏览器、不连数据库：用 esbuild 把真实的 src/api.ts 转成 ESM，
// 在 Node 里注入最小浏览器环境（localStorage / window / location / fetch），
// 让假后端按脚本决定"哪个 access 已失效、refresh 是否还认"。
//
// 覆盖：登录保存双 token、并发 401 只刷新一次、轮换后保存新 refresh、
//       刷新失败才登出、登出不重试、切换身份后旧请求不重放。
//
// 用法：node tests/refresh_single_flight.mjs   （在 campus-dash-frontend 下）
import { transform } from 'esbuild';
import { readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const failures = [];
let checks = 0;
function check(name, condition, detail = '') {
  checks++;
  if (condition) {
    console.log('  PASS ' + name);
  } else {
    failures.push(name + (detail ? ' — ' + detail : ''));
    console.log('  FAIL ' + name + (detail ? ' — ' + detail : ''));
  }
}

function createStorage() {
  const map = new Map();
  return {
    getItem: key => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => { map.set(key, String(value)); },
    removeItem: key => { map.delete(key); },
  };
}

function createWindow() {
  const target = new EventTarget();
  const fired = [];
  const dispatch = target.dispatchEvent.bind(target);
  target.dispatchEvent = event => { fired.push(event.type); return dispatch(event); };
  return { target, fired };
}

function createBackend() {
  const state = {
    access: 'access-1',
    refresh: 'refresh-1',
    issued: 1,
    loginCalls: 0,
    refreshCalls: 0,
    logoutCalls: 0,
    businessCalls: 0,
    revoked: [],
    always401Paths: new Set(),
    holdNext: false,
    release: null,
  };
  const respond = (body, status = 200) =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
  const unauthorized = () => respond({ code: 'UNAUTHORIZED', message: '会话已过期', data: null }, 401);

  async function fetchImpl(url, init = {}) {
    const path = String(url);
    const headers = init.headers || {};
    const authorization = headers.Authorization || headers.authorization;
    const body = init.body ? JSON.parse(init.body) : null;

    if (path === '/api/auth/login') {
      state.loginCalls++;
      return respond({ code: 'OK', message: 'ok',
        data: { token: state.access, accessToken: state.access, refreshToken: state.refresh } });
    }
    if (path === '/api/auth/refresh') {
      state.refreshCalls++;
      if (!state.refresh || body?.refreshToken !== state.refresh) return unauthorized();
      state.issued++;
      state.access = 'access-' + state.issued;
      state.refresh = 'refresh-' + state.issued;
      return respond({ code: 'OK', message: 'ok',
        data: { token: state.access, accessToken: state.access, refreshToken: state.refresh } });
    }
    if (path === '/api/auth/logout') {
      state.logoutCalls++;
      if (body?.refreshToken) {
        state.revoked.push(body.refreshToken);
        if (body.refreshToken === state.refresh) state.refresh = null;
      }
      return respond({ code: 'OK', message: 'ok', data: null });
    }

    state.businessCalls++;
    if (state.holdNext) {
      state.holdNext = false;
      return new Promise(resolve => { state.release = () => resolve(unauthorized()); });
    }
    if (state.always401Paths.has(path)) return unauthorized();
    if (authorization !== 'Bearer ' + state.access) return unauthorized();
    return respond({ code: 'OK', message: 'ok', data: [] });
  }

  return { state, fetchImpl };
}

let moduleSeq = 0;
async function freshApi(options = {}) {
  const backend = options.backend || createBackend();
  const storage = options.storage || createStorage();
  const { target, fired } = createWindow();

  globalThis.localStorage = storage;
  globalThis.window = target;
  globalThis.location = { hash: '' };
  globalThis.fetch = backend.fetchImpl;

  const source = await readFile(new URL('../src/api.ts', import.meta.url), 'utf8');
  const { code } = await transform(source, { loader: 'ts', format: 'esm', target: 'es2022' });
  const file = join(tmpdir(), 'campus-dash-api-' + process.pid + '-' + (++moduleSeq) + '.mjs');
  await writeFile(file, code, 'utf8');
  const mod = await import(pathToFileURL(file).href);
  return { mod, storage, fired, backend };
}

const rejectedCode = error => (error && error.code) || String(error);

console.log('场景 1：登录保存 access 与 refresh');
{
  const { mod, storage, backend } = await freshApi();
  await mod.login(1001);
  check('dash_token 写入', storage.getItem('dash_token') === backend.state.access);
  check('dash_refresh 写入', storage.getItem('dash_refresh') === backend.state.refresh);
}

console.log('场景 2：access 过期，5 个并发请求只刷新一次且全部成功');
{
  const { mod, storage, backend } = await freshApi();
  await mod.login(1001);
  backend.state.access = 'expired-server-side';
  const results = await Promise.all(Array.from({ length: 5 }, () => mod.api.list(1)));
  check('5 个请求都拿到数据', results.length === 5 && results.every(r => Array.isArray(r)));
  check('只调用一次 /api/auth/refresh', backend.state.refreshCalls === 1,
    '实际 ' + backend.state.refreshCalls);
  check('刷新后保存了新的 access', storage.getItem('dash_token') === backend.state.access);
  check('轮换后的新 refresh 已保存', storage.getItem('dash_refresh') === backend.state.refresh);
}

console.log('场景 3：refresh 也失效时跳登录，并清空本地');
{
  const { mod, storage, backend, fired } = await freshApi();
  await mod.login(1001);
  backend.state.access = 'expired-server-side';
  backend.state.refresh = 'revoked-elsewhere';
  const outcomes = await Promise.allSettled(Array.from({ length: 5 }, () => mod.api.list(1)));
  check('5 个请求全部失败', outcomes.every(o => o.status === 'rejected'));
  check('失败码是 UNAUTHORIZED', outcomes.every(o => rejectedCode(o.reason) === 'UNAUTHORIZED'));
  check('并发 401 仍然只刷新一次', backend.state.refreshCalls === 1,
    '实际 ' + backend.state.refreshCalls);
  check('本地 access 已清空', storage.getItem('dash_token') === null);
  check('本地 refresh 已清空', storage.getItem('dash_refresh') === null);
  check('派发了 dash:logout', fired.includes('dash:logout'));
}

console.log('场景 4：登出把 refresh 交给服务端撤销');
{
  const { mod, storage, backend } = await freshApi();
  await mod.login(1001);
  mod.logout();
  await new Promise(resolve => setTimeout(resolve, 30));
  check('调用了 /api/auth/logout', backend.state.logoutCalls >= 1);
  check('提交了待撤销的 refresh', backend.state.revoked.includes('refresh-1'),
    JSON.stringify(backend.state.revoked));
  check('本地凭证已清空', storage.getItem('dash_token') === null && storage.getItem('dash_refresh') === null);
}

console.log('场景 5：刷新成功但业务仍 401，只重试一次不死循环');
{
  const { mod, backend } = await freshApi();
  await mod.login(1001);
  backend.state.access = 'expired-server-side';
  backend.state.always401Paths.add('/api/errands?campusId=1');
  let code = 'none';
  try { await mod.api.list(1); } catch (error) { code = rejectedCode(error); }
  check('最终抛 UNAUTHORIZED', code === 'UNAUTHORIZED', code);
  check('业务请求共 2 次（原请求 + 重试）', backend.state.businessCalls === 2,
    '实际 ' + backend.state.businessCalls);
  check('刷新只发生一次', backend.state.refreshCalls === 1, '实际 ' + backend.state.refreshCalls);
}

console.log('场景 6：切身份后旧请求的 401 不重放、不退出新会话');
{
  const { mod, storage, backend, fired } = await freshApi();
  await mod.login(1001);
  backend.state.holdNext = true;
  const pending = mod.api.list(1);
  await new Promise(resolve => setTimeout(resolve, 10));
  await mod.login(2001);
  const switchedAccess = storage.getItem('dash_token');
  backend.state.release();
  let code = 'none';
  try { await pending; } catch (error) { code = rejectedCode(error); }
  check('旧请求以 UNAUTHORIZED 结束', code === 'UNAUTHORIZED', code);
  check('旧请求没有被重放', backend.state.businessCalls === 1, '实际 ' + backend.state.businessCalls);
  check('新会话未被退出', storage.getItem('dash_token') === switchedAccess && switchedAccess !== null);
  check('没有派发 dash:logout', !fired.includes('dash:logout'));
}

console.log('');
console.log(checks + ' 项检查，' + failures.length + ' 项失败');
if (failures.length > 0) {
  for (const failure of failures) console.log(' - ' + failure);
  process.exit(1);
}
console.log('全部通过');
