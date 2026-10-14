"""隔离环境真实 API 并发、权限与 MQ 验收；不改库、不 mock、不启动服务。"""
import argparse
import concurrent.futures
import json
import threading
import time
import traceback
import uuid
from collections import Counter
from datetime import datetime
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--isolated-backend-ready', action='store_true', required=True)
    parser.add_argument('--base', default='http://127.0.0.1:8080')
    parser.add_argument('--worker-log', required=True, help='本轮独立 worker 的可读日志，用于证明 MQ 消费来源')
    parser.add_argument('--output', default=str(ROOT / 'verification/2026-10-14/live-api'))
    args = parser.parse_args()
    out = Path(args.output)
    out.mkdir(parents=True, exist_ok=True)
    worker_log = Path(args.worker_log)
    assert worker_log.is_file(), f'缺少 worker 日志：{worker_log}'
    log_offset = worker_log.stat().st_size
    report = {'mode': '真实 HTTP + 轮询 + worker 日志；不改库、不 mock；仅专用测试账户',
              'startedAt': datetime.now().isoformat(), 'checks': [], 'requests': [], 'tasks': {},
              'concurrency': {}, 'polls': [], 'mqEvidence': [], 'balances': {}, 'passed': False}
    tokens = {}
    records_lock = threading.Lock()
    tag = datetime.now().strftime('%m%d-%H%M%S')

    def request(method, path, actor=None, data=None, request_id=None):
        headers = {'Content-Type': 'application/json'}
        if actor is not None:
            headers['Authorization'] = f'Bearer {tokens[actor]}'
        if request_id:
            headers['X-Request-Id'] = request_id
        req = Request(args.base + path, data=json.dumps(data).encode() if data is not None else (b'' if method == 'POST' else None), headers=headers, method=method)
        start = time.monotonic()
        try:
            with urlopen(req, timeout=25) as response:
                status, raw = response.status, response.read()
        except HTTPError as error:
            status, raw = error.code, error.read()
        try:
            body = json.loads(raw)
        except ValueError:
            body = {'code': 'INVALID_JSON', 'message': raw.decode(errors='replace')[:500]}
        entry = {'method': method, 'path': path, 'actor': actor, 'http': status,
                 'code': body.get('code'), 'elapsedMs': round((time.monotonic() - start) * 1000)}
        if not path.startswith('/api/auth/'):
            entry['data'] = body.get('data')
        with records_lock:
            report['requests'].append(entry)
        return status, body

    def ok(method, path, actor, data=None):
        status, body = request(method, path, actor, data)
        assert status < 400 and body.get('code') == 'OK', f'{path}: HTTP {status} {body}'
        return body.get('data')

    def check(name, fn, scope='API'):
        fn()
        report['checks'].append({'name': name, 'scope': scope, 'passed': True})
        print('PASS', scope, name, flush=True)

    def require(condition, detail):
        assert condition, detail

    def balance(actor):
        return int(ok('GET', '/api/wallet', actor)['availableCents'])

    def detail(ident):
        return ok('GET', f'/api/errands/{ident}', 11001)

    def publish(label, cents):
        result = ok('POST', '/api/errands', 11001, {'title': f'真实API验收 {tag} · {label}', 'rewardCents': cents, 'slotTotal': 1, 'type': 'DELIVERY'})
        ident = result['errandId']
        assert isinstance(ident, str) and int(ident) > 2 ** 53, ident
        report['tasks'][label] = {'id': ident, 'rewardCents': cents}
        return ident

    def action(ident, name, actor, data=None):
        return ok('POST', f'/api/errands/{ident}/{name}', actor, data)

    def deliver(ident, actor, already_grabbed=False):
        if not already_grabbed:
            action(ident, 'grab', actor)
        action(ident, 'confirm', actor)
        action(ident, 'pickup', actor)
        action(ident, 'deliver', actor)

    def parallel(label, ident, action_name, actors):
        gate = threading.Barrier(len(actors))
        def send(index):
            gate.wait(timeout=10)
            status, body = request('POST', f'/api/errands/{ident}/{action_name}', actors[index], request_id=str(uuid.uuid4()))
            return {'actor': actors[index], 'http': status, 'body': body}
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(actors)) as pool:
            results = list(pool.map(send, range(len(actors))))
        report['concurrency'][label] = results
        return results

    def credited(ident, actor, kind, cents):
        ledger = ok('GET', '/api/wallet/ledger?size=100', actor)
        rows = [row for row in ledger if row['refId'] == ident and row['refType'] == kind and row['direction'] == 'CREDIT']
        assert len(rows) == 1 and rows[0]['amountCents'] == str(cents), rows

    def poll(label, ident, predicate, timeout=180):
        start = time.monotonic()
        seen = []
        while time.monotonic() - start < timeout:
            card = detail(ident)
            state = {key: card[key] for key in ['status', 'round', 'grabberId', 'slotTaken']}
            if not seen or seen[-1]['state'] != state:
                seen.append({'elapsedSeconds': round(time.monotonic() - start, 2), 'state': state})
                print('STATE', label, json.dumps(state, ensure_ascii=False), flush=True)
            if predicate(card):
                report['polls'].append({'name': label, 'id': ident, 'elapsedSeconds': round(time.monotonic() - start, 2), 'states': seen})
                return card
            time.sleep(.5)
        report['polls'].append({'name': label, 'id': ident, 'timeout': True, 'states': seen})
        raise AssertionError(f'{label} 在 {timeout} 秒内未完成；最后状态 {seen[-1] if seen else None}')

    def log_tail():
        with worker_log.open('rb') as stream:
            stream.seek(log_offset)
            return stream.read().decode(errors='replace')

    def mq_proof(ident, label, marker):
        deadline = time.monotonic() + 5
        lines = []
        while time.monotonic() < deadline:
            lines = [line for line in log_tail().splitlines()
                     if f'errandId={ident}' in line and marker in line
                     and (label != '送达超时自动结算' or 'result=SETTLED' in line)]
            if lines:
                report['mqEvidence'].append({'name': label, 'id': ident, 'source': 'worker MQ 消费者日志', 'lines': lines})
                return
            time.sleep(.2)
        raise AssertionError(f'{label} 状态已变化，但缺少 MQ 消费证据：{ident} {marker}')

    try:
        for actor in [11001, 12001, 12002, 9001]:
            status, body = request('POST', '/api/auth/login', data={'userId': actor})
            assert status < 400 and body.get('code') == 'OK', body
            tokens[actor] = body['data']['token']
        report['balances']['before'] = {str(actor): balance(actor) for actor in [11001, 12001, 12002]}
        for system_id in [-1, -2]:
            status, body = request('POST', '/api/auth/login', data={'userId': system_id})
            check(f'系统账户{system_id}不能通过演示登录', lambda: require(body.get('code') == 'UNAUTHORIZED' and not (body.get('data') or {}).get('token'), body))

        race = publish('20请求竞争单名额', 1000)
        races = parallel('grab20', race, 'grab', [12001, 12002] * 10)
        winners = [item for item in races if item['body'].get('code') == 'OK' and (item['body'].get('data') or {}).get('grabbed') is True]
        check('20个真实并发抢单请求恰好1个成功', lambda: require(len(winners) == 1 and all(item['http'] < 500 for item in races), races))
        winner = winners[0]['actor']
        card = detail(race)
        check('并发抢单后名额与实际接单人一致', lambda: require(card['slotTaken'] == 1 and card['grabberId'] == str(winner) and card['status'] == 'LOCKED', card))
        deliver(race, winner, True)
        for actor in [12001, 12002]:
            status, body = request('POST', f'/api/errands/{race}/settle', actor)
            check(f'非发单人{actor}结算被拒绝', lambda: require(body.get('code') == 'NOT_PUBLISHER' and detail(race)['status'] == 'DELIVERED', body))
        before_settle = balance(winner)
        settlements = parallel('settle20', race, 'settle', [11001] * 20)
        results = [(item['body'].get('data') or {}).get('result') for item in settlements]
        check('同单20并发结算恰好1次SETTLED', lambda: require(results.count('SETTLED') == 1 and all(item['body'].get('code') == 'OK' and result in ['SETTLED', 'ALREADY_SETTLED', 'CONFLICT'] for item, result in zip(settlements, results)), settlements))
        check('并发结算只到账950分', lambda: require(balance(winner) == before_settle + 950, '结算余额发生重复或缺失'))
        check('并发结算只有1条真实入账流水', lambda: credited(race, winner, 'SETTLE', 950))
        events = ok('GET', '/api/credit/self', winner)['events']
        check('并发结算信用只增加一次', lambda: require(len([event for event in events if event['refId'] == race and event['type'] == 'SETTLE' and event['delta'] == 2]) == 1, events))

        before_cancel = balance(11001)
        cancel = publish('20请求重复退款', 777)
        cancellations = parallel('cancel20', cancel, 'cancel', [11001] * 20)
        refund_results = [(item['body'].get('data') or {}).get('result') for item in cancellations]
        check('20并发取消只有1次实际退款', lambda: require(refund_results.count('REFUNDED') == 1 and all(item['http'] < 500 and item['body'].get('code') in ['OK', 'ILLEGAL_STATE_TRANSITION'] for item in cancellations), cancellations))
        check('重复取消不会增加钱包余额', lambda: require(balance(11001) == before_cancel and detail(cancel)['status'] == 'CANCELLED', '取消退款后的余额或任务终态错误'))
        check('重复取消只有1条777分退款入账流水', lambda: credited(cancel, 11001, 'REFUND', 777))

        arbitration = publish('仲裁支持跑腿', 500)
        action(arbitration, 'grab', 12001)
        action(arbitration, 'confirm', 12001)
        action(arbitration, 'dispute', 11001)
        for actor in [11001, 12001, 12002]:
            status, body = request('POST', f'/api/errands/{arbitration}/arbitrate', actor, {'favor': 'RUNNER'})
            check(f'非仲裁员{actor}裁决被拒绝', lambda: require(body.get('code') == 'UNAUTHORIZED' and detail(arbitration)['status'] == 'DISPUTED', body))
        before_arbitration = balance(12001)
        verdict = action(arbitration, 'arbitrate', 9001, {'favor': 'RUNNER'})
        check('真实仲裁可将争议悬赏结算给跑腿', lambda: require(verdict['result'] == 'SETTLED_TO_RUNNER' and detail(arbitration)['status'] == 'SETTLED' and balance(12001) == before_arbitration + 475, verdict))
        repeat = action(arbitration, 'arbitrate', 9001, {'favor': 'RUNNER'})
        check('已裁决争议重复提交不再次入账', lambda: require(repeat['result'] == 'ALREADY_DONE' and balance(12001) == before_arbitration + 475, repeat))
        check('仲裁支持跑腿只有1条475分入账流水', lambda: credited(arbitration, 12001, 'SETTLE', 475))

        # 三笔独立任务同时等待期限，候选与空队列互不影响，不调用超时处理接口。
        transfer = publish('MQ超时转候选', 333)
        action(transfer, 'grab', 12001)
        status, candidate = request('POST', f'/api/errands/{transfer}/grab', 12002, request_id=str(uuid.uuid4()))
        check('未抢中同学实际进入候选队列', lambda: require(candidate.get('code') == 'SLOT_FULL' and int(candidate['data']['candidateRank']) == 1, candidate))
        empty = publish('MQ候选空回退', 444)
        action(empty, 'grab', 12001)
        auto = publish('MQ送达自动结算', 555)
        before_auto = balance(12001)
        deliver(auto, 12001)
        report['mqWaitingStartedAt'] = datetime.now().isoformat()
        poll('确认超时转给候选', transfer, lambda card: card['status'] == 'LOCKED' and card['grabberId'] == '12002' and card['round'] == 1)
        check('确认超时自动转给12002且轮次增加', lambda: require(detail(transfer)['slotTaken'] == 1, detail(transfer)), 'MQ状态')
        action(transfer, 'confirm', 12002)
        check('候选接单人可真实确认', lambda: require(detail(transfer)['status'] == 'ACCEPTED', detail(transfer)))
        check('超时转候选由MQ消费者触发', lambda: mq_proof(transfer, '确认超时转候选', 'outcome=TRANSFERRED'), 'MQ日志')
        action(transfer, 'pickup', 12002)
        action(transfer, 'deliver', 12002)
        action(transfer, 'settle', 11001)
        poll('空候选队列回退', empty, lambda card: card['status'] == 'PUBLISHED' and card['grabberId'] == '-1' and card['slotTaken'] == 0)
        check('候选为空自动回退并归还名额', lambda: require(detail(empty)['round'] == 1, detail(empty)), 'MQ状态')
        check('空候选回退由MQ消费者触发', lambda: mq_proof(empty, '空候选回退', 'outcome=REVERTED'), 'MQ日志')
        action(empty, 'cancel', 11001)
        poll('送达超时自动结算', auto, lambda card: card['status'] == 'SETTLED')
        check('送达超时自动结算到账528分', lambda: require(balance(12001) == before_auto + 528, {'before': before_auto, 'after': balance(12001)}), 'MQ状态')
        check('自动结算只有1条528分入账流水', lambda: credited(auto, 12001, 'SETTLE', 528))
        check('自动结算由MQ消费者触发', lambda: mq_proof(auto, '送达超时自动结算', '自动结算消息处理'), 'MQ日志')
        final_auto = action(auto, 'settle', 11001)
        check('自动结算后手动重复结算保持幂等', lambda: require(final_auto['result'] == 'ALREADY_SETTLED' and balance(12001) == before_auto + 528, final_auto))
        report['passed'] = True
    except Exception as exc:
        report['failure'] = str(exc)
        report['traceback'] = traceback.format_exc()
        print(report['traceback'], flush=True)
    finally:
        for actor in [11001, 12001, 12002]:
            if actor in tokens:
                try:
                    report['balances'].setdefault('after', {})[str(actor)] = balance(actor)
                except Exception as exc:
                    report.setdefault('finalReadErrors', []).append(str(exc))
        report['finishedAt'] = datetime.now().isoformat()
        report['outcomeCounts'] = {label: dict(Counter((item['body'].get('data') or {}).get('result') or item['body'].get('code') for item in values)) for label, values in report['concurrency'].items()}
        report['scannerLogLines'] = [line for line in log_tail().splitlines() if '兜底扫描完成' in line or '自动结算兜底扫描处理' in line]
        (out / 'worker-test-window.log').write_text(log_tail(), encoding='utf8')
        (out / 'api-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf8')
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
