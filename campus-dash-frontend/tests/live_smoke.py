"""真实前后端浏览器验收：只允许在已确认的隔离测试后端运行，不 mock API。

运行前由主任务确认隔离 MySQL、Redis、MQ 与后端已启动；脚本不启动服务。
"""
import argparse
import json
import re
import time
import traceback
from datetime import datetime
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--isolated-backend-ready', action='store_true', required=True,
                        help='仅在主任务明确确认隔离后端就绪后传入')
    parser.add_argument('--base', default='http://127.0.0.1:5173')
    parser.add_argument('--output', default=str(ROOT / 'verification/2026-10-14/live-backend'))
    args = parser.parse_args()
    out = Path(args.output)
    out.mkdir(parents=True, exist_ok=True)
    run_tag = datetime.now().strftime('%m%d-%H%M%S')
    report = {
        'mode': '真实浏览器与真实 API 联调；无路由 mock；仅已确认隔离测试环境',
        'base': args.base, 'startedAt': datetime.now().isoformat(),
        'checks': [], 'tasks': {}, 'balances': {}, 'pageErrors': [], 'consoleErrors': [],
        'httpErrors': [], 'requestFailures': [], 'websocketEvents': [], 'overflow': [],
        'uiWrites': [], 'apiReads': [],
        'scope': {'ui': '登录、身份切换、发布、抢单、确认接单、取货、送达、结算、取消、争议、仲裁，以及钱包信用消息页面',
                  'api': '只读核对实际任务状态、余额、流水、信用事件与通知；业务状态全部由真实 UI 操作产生'},
    }

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(viewport={'width': 1440, 'height': 1000}, locale='zh-CN', timezone_id='Asia/Shanghai')
        page = context.new_page()
        page.set_default_timeout(15000)
        page.on('pageerror', lambda error: report['pageErrors'].append(str(error)))
        page.on('console', lambda msg: report['consoleErrors'].append(msg.text) if msg.type == 'error' else None)
        page.on('requestfailed', lambda req: report['requestFailures'].append({'path': urlparse(req.url).path, 'error': req.failure}))

        def response_seen(response):
            path = urlparse(response.url).path
            if path.startswith('/api/') and response.status >= 400:
                report['httpErrors'].append({'path': path, 'status': response.status})

        page.on('response', response_seen)

        def websocket_seen(socket):
            def frame(payload):
                try:
                    event = json.loads(payload)
                    report['websocketEvents'].append({'type': event.get('type'), 'payload': event.get('payload')})
                except (ValueError, TypeError):
                    pass
            socket.on('framereceived', frame)
        page.on('websocket', websocket_seen)

        def check(name, fn, scope='UI'):
            fn()
            report['checks'].append({'name': name, 'scope': scope, 'passed': True})
            print('PASS', scope, name, flush=True)

        def require(condition, detail):
            assert condition, detail

        def nav(path):
            page.goto(f'{args.base}/#{path}')
            page.wait_for_load_state('networkidle')

        def shot(name):
            close = page.get_by_role('button', name='关闭操作提示')
            if close.count() and close.is_visible():
                close.click()
            page.evaluate('() => {document.activeElement?.blur();window.scrollTo(0,0)}')
            page.screenshot(path=str(out / name), full_page=True)

        def api(path):
            token = page.evaluate("localStorage.getItem('dash_token')")
            response = context.request.get(args.base + path, headers={'Authorization': f'Bearer {token}'})
            report['apiReads'].append({'path': path, 'status': response.status})
            assert response.ok, f'{path}: HTTP {response.status} {response.text()[:300]}'
            body = response.json()
            assert body.get('code') == 'OK', f'{path}: {body}'
            return body['data']

        def switch(user):
            page.get_by_label('切换演示身份').select_option(str(user))
            expect(page.locator('.profile-select select')).to_have_value(str(user))
            page.wait_for_load_state('networkidle')

        def ui_write(path, click):
            with page.expect_response(lambda response: urlparse(response.url).path == path and response.request.method == 'POST') as info:
                click()
            response = info.value
            body = response.json()
            report['uiWrites'].append({'path': path, 'status': response.status, 'code': body.get('code'), 'data': body.get('data')})
            assert response.ok and body.get('code') == 'OK', f'{path}: {body}'
            return body['data']

        def action(ident, name, button, confirm=False):
            control = page.get_by_role('button', name=button, exact=True)
            if confirm:
                control.click()
                control = page.get_by_role('button', name='确认操作', exact=True)
            result = ui_write(f'/api/errands/{ident}/{name}', control.click)
            page.wait_for_load_state('networkidle')
            return result

        def publish(label, amount):
            nav('/publish')
            page.get_by_label('任务内容').fill(f'真实界面验收 {run_tag} · {label}')
            page.get_by_label('悬赏金额').fill(amount)
            result = ui_write('/api/errands', page.get_by_role('button', name='发布并托管').click)
            expect(page.get_by_role('heading', name='任务已发布，等一位顺路的同学')).to_be_visible()
            ident = result['errandId']
            assert isinstance(ident, str) and int(ident) > 2 ** 53, f'任务 ID 契约错误：{ident!r}'
            report['tasks'][label] = ident
            page.get_by_role('link', name=re.compile('查看任务进度')).click()
            expect(page.locator('.task-order-id')).to_contain_text(ident)
            page.wait_for_load_state('networkidle')
            return ident

        def status(text):
            expect(page.locator('.task-overview .status')).to_have_text(text)

        def wait_notification(ident, kind):
            deadline = time.monotonic() + 40
            latest = []
            while time.monotonic() < deadline:
                latest = api('/api/notifications')
                if any(item['errandId'] == ident and item['type'] == kind for item in latest):
                    return latest
                page.wait_for_timeout(500)
            raise AssertionError(f'40 秒内未收到 {kind} 通知：{ident}；最近通知 {latest}')

        def overflow(label):
            sizes = page.evaluate('({viewport:innerWidth,document:document.documentElement.scrollWidth})')
            report['overflow'].append({'page': label, **sizes})
            assert sizes['document'] <= sizes['viewport'] + 1, sizes

        try:
            page.goto(args.base)
            page.wait_for_load_state('networkidle')
            expect(page.get_by_role('heading', name='从一个身份开始')).to_be_visible()
            shot('login.png')
            ui_write('/api/auth/login', page.locator('.identity-btn').filter(has_text='#1001').click)
            check('发单人真实登录', lambda: expect(page.locator('.profile-select select')).to_have_value('1001'))
            # 登录响应含令牌，不将任何认证信息写入证据文件。
            report['uiWrites'][-1]['data'] = {'userId': '1001', 'token': '[不记录]'}
            publisher_before = int(api('/api/wallet')['availableCents'])
            switch(2001)
            runner_before = int(api('/api/wallet')['availableCents'])
            runner_credit_before = int(api('/api/credit/self')['score'])
            report['balances']['before'] = {'publisher': publisher_before, 'runner': runner_before}
            switch(1001)

            delivery = publish('完成配送', '12.34')
            check('发布成功并保留长任务ID', lambda: status('待抢单'))
            actual = api(f'/api/errands/{delivery}')
            check('真实接口金额为字符串1234且发单人只能取消', lambda: require(actual['rewardCents'] == '1234' and actual['availableActions'] == ['CANCEL'], actual), 'API')
            check('发布后钱包扣除1234分进入托管', lambda: require(int(api('/api/wallet')['availableCents']) == publisher_before - 1234, '发单人余额错误'), 'API')
            shot('published-detail.png')
            switch(2001)
            check('泡身份只显示可执行抢单动作', lambda: (expect(page.locator('.topbar-avatar')).to_have_text('泡'), expect(page.get_by_role('button', name='抢单', exact=True)).to_be_visible(), expect(page.get_by_role('button', name='取消并退款', exact=True)).to_have_count(0)))
            action(delivery, 'grab', '抢单')
            check('真实抢单进入待确认', lambda: status('待确认'))
            action(delivery, 'confirm', '确认接单')
            check('真实确认接单进入待取货', lambda: status('待取货'))
            action(delivery, 'pickup', '取货')
            check('真实取货进入配送中', lambda: status('配送中'))
            action(delivery, 'deliver', '送达')
            check('真实送达等待发单人确认', lambda: status('待确认完成'))
            switch(1001)
            settlement = action(delivery, 'settle', '确认完成（结算）', True)
            check('真实结算返回SETTLED且页面结束任务', lambda: (require(settlement['result'] == 'SETTLED', settlement), status('已结算')))
            shot('settled-detail.png')
            switch(2001)
            nav('/wallet')
            check('泡钱包显示结算后余额与1173分收入', lambda: expect(page.locator('.account-balance-value')).to_have_text(f'¥{(runner_before + 1173) / 100:.2f}'))
            check('钱包流水关联真实任务长ID', lambda: expect(page.locator(f'.account-table a[href="#/detail/{delivery}"]')).to_have_count(1))
            ledger = api('/api/wallet/ledger')
            check('真实结算流水为单笔贷记1173分', lambda: require(len([row for row in ledger if row['refId'] == delivery and row['refType'] == 'SETTLE' and row['direction'] == 'CREDIT' and row['amountCents'] == '1173']) == 1, ledger), 'API')
            shot('runner-wallet.png')
            nav('/credit')
            credit = api('/api/credit/self')
            check('真实信用增加2分并展示本任务足迹', lambda: (expect(page.locator('.account-score-number')).to_have_text(f'{min(100, runner_credit_before + 2)}分'), expect(page.locator(f'.account-credit-event a[href="#/detail/{delivery}"]')).to_have_count(1)))
            check('信用接口事件对应任务且只计分一次', lambda: require(len([event for event in credit['events'] if event['refId'] == delivery and event['type'] == 'SETTLE' and event['delta'] == 2]) == 1, credit), 'API')
            shot('runner-credit.png')
            check('MQ真实结算通知已落库', lambda: wait_notification(delivery, 'SETTLED'), 'API')
            nav('/notifications')
            check('消息页面能打开真实结算通知', lambda: expect(page.locator(f'.account-message[href="#/detail/{delivery}"]')).to_have_count(1))
            shot('runner-notifications.png')
            page.locator(f'.account-message[href="#/detail/{delivery}"]').click()
            check('真实消息跳转完整长ID详情', lambda: expect(page.locator('.task-order-id')).to_contain_text(delivery))

            switch(1001)
            cancel = publish('取消退款', '5.60')
            cancelled = action(cancel, 'cancel', '取消并退款', True)
            check('真实取消退款返回REFUNDED', lambda: require(cancelled['result'] == 'REFUNDED', cancelled))
            check('取消后任务终态正确', lambda: status('已取消'))
            check('取消退款后发单人余额全额恢复', lambda: require(int(api('/api/wallet')['availableCents']) == publisher_before - 1234, '取消退款未恢复金额'), 'API')
            shot('cancelled-detail.png')

            dispute = publish('争议仲裁', '7.89')
            switch(2001)
            action(dispute, 'grab', '抢单')
            action(dispute, 'confirm', '确认接单')
            action(dispute, 'dispute', '发起争议', True)
            check('真实发起争议进入争议中', lambda: status('争议中'))
            check('普通参与者没有仲裁按钮', lambda: expect(page.get_by_role('button', name='处理争议', exact=True)).to_have_count(0))
            switch(9001)
            page.get_by_role('button', name='处理争议', exact=True).click()
            arbitration = ui_write(f'/api/errands/{dispute}/arbitrate', page.get_by_role('button', name='支持发单同学 · 全额退款', exact=True).click)
            check('仲裁员真实退款裁决成功', lambda: (require(arbitration['result'] == 'REFUNDED_TO_PUBLISHER', arbitration), status('已退款')))
            shot('arbitrated-detail.png')
            switch(1001)
            nav('/wallet')
            check('仲裁退款后发单人钱包余额正确', lambda: expect(page.locator('.account-balance-value')).to_have_text(f'¥{(publisher_before - 1234) / 100:.2f}'))
            check('仲裁退款流水关联原任务', lambda: require(any(row['refId'] == dispute and row['refType'] == 'REFUND' and row['amountCents'] == '789' for row in api('/api/wallet/ledger')), '缺少退款流水'), 'API')
            check('MQ真实仲裁退款通知已落库', lambda: wait_notification(dispute, 'REFUNDED'), 'API')
            nav('/notifications')
            check('发单人消息展示真实退款通知', lambda: expect(page.locator(f'.account-message[href="#/detail/{dispute}"]')).to_have_count(1))
            shot('publisher-refund-notifications.png')
            nav('/mine')
            check('我的任务显示本轮三笔真实任务', lambda: [expect(page.locator(f'.errand-card a.card-title[href="#/detail/{ident}"]')).to_have_count(1) for ident in [delivery, cancel, dispute]])
            shot('publisher-mine.png')
            for width in [1440, 390, 320]:
                page.set_viewport_size({'width': width, 'height': 1000 if width == 1440 else 844})
                nav('/wallet')
                check(f'{width}px真实钱包无横向溢出', lambda: overflow(f'wallet-{width}'))
                if width == 390:
                    shot('mobile-wallet.png')
            check('真实流程无未捕获JavaScript异常', lambda: require(not report['pageErrors'], report['pageErrors']))
            check('真实流程无控制台错误', lambda: require(not report['consoleErrors'], report['consoleErrors']))
            check('真实流程无HTTP错误响应', lambda: require(not report['httpErrors'], report['httpErrors']), 'API')
            report['balances']['after'] = {'publisher': int(api('/api/wallet')['availableCents']), 'runnerExpected': runner_before + 1173}
            report['passed'] = True
        except Exception as exc:
            report['passed'] = False
            report['failure'] = str(exc)
            report['traceback'] = traceback.format_exc()
            try:
                shot('failure.png')
            except Exception:
                pass
            print(report['traceback'], flush=True)
        finally:
            # 即使断言提前失败，也不在证据中留下登录令牌。
            for request in report['uiWrites']:
                if request['path'].startswith('/api/auth/'):
                    request['data'] = {'authentication': '[不记录]'}
            report['finishedAt'] = datetime.now().isoformat()
            (out / 'live-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf8')
            browser.close()
    return 0 if report.get('passed') else 1


if __name__ == '__main__':
    raise SystemExit(main())
