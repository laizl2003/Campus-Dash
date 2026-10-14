"""校园跑腿前端浏览器冒烟验收。仅在浏览器层 mock API，不启动或改写生产数据。
运行前启动 Vite；默认执行完整验收，--preview 仅生成登录与广场截图。
"""
import argparse
import copy
import json
import re
import time
import traceback
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from playwright.sync_api import sync_playwright, expect

BASE = 'http://127.0.0.1:5173'
OUT = Path(__file__).resolve().parents[1] / 'verification' / '2026-10-14'
IDS = ['215731113107132416', '215731113107132417', '215731113107132418', '215731113107132419', '215731113107132420', '215731113107132421']
NEW_ID = '215731113107132429'
TITLES = ['帮忙取一份快递，从菜鸟驿站送到 6 号宿舍楼', '校园超市代买笔记本与一瓶矿泉水', '图书馆打印室排队取复习资料', '把借阅的三本书送还图书馆', '三食堂带一份午餐到实验楼', '东门快递站代取小件包裹']
TYPES = ['DELIVERY', 'BUY', 'QUEUE', 'OTHER', 'BUY', 'DELIVERY']
REWARDS = ['850', '1200', '2000', '600', '1050', '1500']

class MockApi:
    def __init__(self):
        self.cards = [{ 'id': ident, 'title': title, 'status': 'PUBLISHED', 'type': typ, 'rewardCents': reward,
            'slotTotal': 1, 'slotTaken': 0, 'publisherId': '1001', 'grabberId': '-1', 'round': 0 }
            for ident, title, typ, reward in zip(IDS, TITLES, TYPES, REWARDS)]
        self.calls = []
        self.publishes = []
        self.fail_square = False
        self.empty_square = False
        self.credit_empty = False
        self.missing = []
        self.unread_count = '2'
        self.fail_login = False
        self.unauthorized_credit = False
        self.action_results = {}
        self.hold_actions = set()
        self.pending_actions = {}

    def resolved(self, card, user):
        c = copy.deepcopy(card)
        publisher = c['publisherId'] == user
        runner = c['grabberId'] == user
        actions = []
        if c['status'] == 'PUBLISHED' and not publisher: actions.append('GRAB')
        if publisher and c['status'] in ['DRAFT', 'PUBLISHED']: actions.append('CANCEL')
        if runner and c['status'] == 'LOCKED': actions.append('CONFIRM')
        if runner and c['status'] == 'ACCEPTED': actions.append('PICKUP')
        if runner and c['status'] == 'PICKED_UP': actions.append('DELIVER')
        if publisher and c['status'] == 'DELIVERED': actions.append('SETTLE')
        if (publisher or runner) and c['status'] in ['ACCEPTED', 'PICKED_UP', 'DELIVERED']: actions.append('DISPUTE')
        if user == '9001' and c['status'] == 'DISPUTED': actions.append('ARBITRATE')
        c['role'] = 'ARBITRATOR' if user == '9001' else 'PUBLISHER' if publisher else 'CURRENT_RUNNER' if runner else 'VIEWER'
        c['availableActions'] = actions
        return c

    def route(self, route):
        req = route.request
        parsed = urlparse(req.url); path = parsed.path; q = parse_qs(parsed.query)
        user = req.headers.get('authorization', '').removeprefix('Bearer mock-') or '2001'
        self.calls.append({'method': req.method, 'path': path, 'query': parsed.query, 'user': user})
        def ok(data): route.fulfill(status=200, content_type='application/json', body=json.dumps({'code':'OK','message':'成功','data':data}, ensure_ascii=False))
        if path == '/api/auth/login':
            if self.fail_login: return route.fulfill(status=503,body='mock login unavailable')
            uid = str(req.post_data_json['userId']); return ok({'token':f'mock-{uid}', 'userId':uid})
        if path == '/api/wallet': return ok({'availableCents':'26880' if user == '2001' else '120000', 'frozenCents':'0'})
        if path == '/api/wallet/ledger':
            return ok([{'time':'2026-10-14T04:32:00Z','direction':d,'amountCents':'850','refType':'ESCROW','refId':IDS[0],'bizNo':f'escrow:{IDS[0]}'} for d in ['DEBIT','CREDIT']])
        if path == '/api/credit/self' and self.unauthorized_credit: return route.fulfill(status=401,body='mock expired token')
        if path == '/api/credit/self': return ok({'score':82, 'windowDays':30, 'events':[] if self.credit_empty else [{'type':'SETTLE','description':'完成结算','delta':2,'refId':IDS[0],'time':'2026-10-14T02:35:00Z'}, {'type':'GRAB_TIMEOUT_REVERT','description':'抢中后超时未确认','delta':-5,'refId':IDS[1],'time':'2026-10-13T06:00:00Z'}]})
        if path == '/api/credit/ranking': return ok([{'userId':'2002','score':96},{'userId':'2001','score':82},{'userId':'215731113107132499','score':76}])
        if path == '/api/notifications/unread': return ok({'count':self.unread_count})
        if path == '/api/notifications': return ok([{'id':'215731113107132501','errandId':IDS[0],'type':'ERRAND_ACCEPTED','content':'同学已确认接单，任务即将开始。','time':'2026-10-14T04:35:00Z'}, {'id':'215731113107132502','errandId':IDS[1],'type':'SETTLED','content':'任务已完成结算，感谢这次校园互助。','time':'2026-10-13T09:20:00Z'}])
        if path == '/api/health': return ok({'status':'UP'})
        if path == '/api/errands' and req.method == 'POST':
            body=req.post_data_json; self.publishes.append(body)
            card={'id':NEW_ID,'title':body['title'],'status':'PUBLISHED','type':body['type'],'rewardCents':str(body['rewardCents']),'slotTotal':1,'slotTaken':0,'publisherId':user,'grabberId':'-1','round':0}
            self.cards.append(card); time.sleep(.2); return ok({'errandId':NEW_ID,'status':'PUBLISHED','frozenCents':str(body['rewardCents'])})
        if path == '/api/errands':
            if self.fail_square: return route.fulfill(status=503,body='mock temporary failure')
            return ok([] if self.empty_square else [self.resolved(c,user) for c in self.cards if c['status']=='PUBLISHED'])
        if path == '/api/errands/mine':
            role=q.get('role',['PUBLISHED_BY_ME'])[0]
            return ok([self.resolved(c,user) for c in self.cards if c['publisherId']==user] if role=='PUBLISHED_BY_ME' else [self.resolved({**self.cards[1],'status':'ACCEPTED','slotTaken':1,'grabberId':user},user)])
        match=re.fullmatch(r'/api/errands/(\d+)(?:/(\w+))?',path)
        if match:
            ident, action=match.groups()
            card=next((c for c in self.cards if c['id']==ident),None)
            if not card: return route.fulfill(status=404,body='missing mock')
            if action=='timeline':
                states=['DRAFT','PUBLISHED']
                flow=['LOCKED','ACCEPTED','PICKED_UP','DELIVERED','SETTLED']
                if card['status'] in flow: states.extend(flow[:flow.index(card['status'])+1])
                elif card['status']!='PUBLISHED': states.append(card['status'])
                return ok([{'time':f'2026-10-14T04:{index*5:02}:00Z','from':before,'to':after,'round':0,'operatorId':card['publisherId'] if index==0 else user} for index,(before,after) in enumerate(zip(states,states[1:]))])
            if not action: return ok(self.resolved(card,user))
            if (ident,action) in self.hold_actions:
                self.pending_actions[(ident,action)] = route
                return
            if (ident,action) in self.action_results:
                result,status = self.action_results[(ident,action)]
                if status: card['status'] = status
                return ok(result)
            if action=='cancel': card['status']='CANCELLED'; return ok({'result':'REFUNDED'})
            if action=='grab': card.update(status='LOCKED',grabberId=user,slotTaken=1); return ok({'code':'OK','grabbed':True})
            if action=='confirm': card['status']='ACCEPTED'; return ok(None)
            if action=='pickup': card['status']='PICKED_UP'; return ok(None)
            if action=='deliver': card['status']='DELIVERED'; return ok(None)
            if action=='settle': card['status']='SETTLED'; return ok({'result':'SETTLED'})
            if action=='dispute': card['status']='DISPUTED'; return ok(None)
            if action=='arbitrate': card['status']='REFUNDED'; return ok({'result':'REFUNDED_TO_PUBLISHER'})
        self.missing.append(path); route.fulfill(status=404,body='unhandled mock route')


def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--preview',action='store_true'); args=parser.parse_args()
    OUT.mkdir(parents=True,exist_ok=True)
    results={'mode':'API mock 浏览器验收；不代表真实后端联调','checks':[],'pageErrors':[],'consoleErrors':[],'overflow':[],'cardMetrics':[]}
    mock=MockApi()
    with sync_playwright() as p:
        browser=p.chromium.launch(headless=True)
        ctx=browser.new_context(viewport={'width':1440,'height':1000}, device_scale_factor=1, locale='zh-CN',timezone_id='Asia/Shanghai')
        ctx.route('**/api/**',mock.route)
        page=ctx.new_page();page.set_default_timeout(8000)
        page.on('pageerror',lambda err: results['pageErrors'].append(str(err)))
        page.on('console',lambda msg: results['consoleErrors'].append(msg.text) if msg.type=='error' else None)
        def shot(name):
            close_toast=page.get_by_role('button',name='关闭操作提示')
            if close_toast.count() and close_toast.is_visible(): close_toast.click()
            page.evaluate("() => {document.activeElement?.blur();window.scrollTo(0,0)}")
            page.wait_for_timeout(180)
            page.screenshot(path=str(OUT/name),full_page=True)
        def check(name,fn):
            fn(); results['checks'].append({'name':name,'passed':True}); print('PASS',name,flush=True)
        def overflow(label):
            data=page.evaluate('''() => ({vw:innerWidth,width:document.documentElement.scrollWidth, offenders:[...document.querySelectorAll('body *')].filter(e=>{let r=e.getBoundingClientRect();return r.width>0&&(r.left<-.5||r.right>innerWidth+.5)&&getComputedStyle(e).position!=='fixed'}).slice(0,10).map(e=>({tag:e.tagName,cls:e.className,left:e.getBoundingClientRect().left,right:e.getBoundingClientRect().right}))})''')
            results['overflow'].append({'page':label,**data}); assert data['width']<=data['vw']+1,data
        def nav(path):
            page.goto(f'{BASE}/#{path}');page.wait_for_load_state('networkidle')
        try:
            page.goto(BASE);page.wait_for_load_state('networkidle')
            (OUT/'login-dom.txt').write_text(page.locator('body').inner_text(),encoding='utf8')
            check('2001登录身份显示泡且2002名称不变',lambda: (expect(page.locator('.identity-btn').filter(has_text='#2001')).to_contain_text('泡'),expect(page.locator('.identity-btn').filter(has_text='#2002')).to_contain_text('跑腿同学 B')))
            shot('login.png')
            page.locator('.identity-btn').filter(has_text='#2001').click()
            expect(page.get_by_role('heading',name='任务广场.')).to_be_visible()
            expect(page.locator('.errand-card')).to_have_count(6)
            page.wait_for_load_state('networkidle')
            (OUT/'square-dom.txt').write_text(page.locator('body').inner_text(),encoding='utf8')
            shot('desktop-square.png');overflow('desktop-square')
            check('跑腿身份登录与6条任务展示',lambda: expect(page.locator('.profile-select select')).to_have_value('2001'))
            check('顶部与侧栏头像显示泡且身份ID保持2001',lambda: (expect(page.locator('.topbar-avatar')).to_have_text('泡'),expect(page.locator('.profile .avatar')).to_have_text('泡'),expect(page.locator('.profile-select select')).to_have_value('2001')))
            measure_cards(page,results,'desktop-square')
            if not args.preview:
                run_full(page,ctx,mock,results,check,shot,nav,overflow)
        except Exception as exc:
            results['failure']=str(exc);results['traceback']=traceback.format_exc();shot('failure.png');print(results['traceback'],flush=True)
        finally:
            results['expectedConsoleErrors']=[entry for entry in results['consoleErrors'] if 'WebSocket connection' in entry or '503' in entry or '401' in entry]
            results['unexpectedConsoleErrors']=[entry for entry in results['consoleErrors'] if entry not in results['expectedConsoleErrors']]
            results['requests']=mock.calls;results['publishRequests']=mock.publishes;results['unhandledMockRoutes']=mock.missing
            (OUT/'smoke-report.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf8')
            browser.close()
    return 1 if results.get('failure') else 0


def measure_cards(page, results, label):
    measurements=page.locator('.errand-card').evaluate_all("""cards => cards.map(card=>{
        const box=card.getBoundingClientRect();
        const controls=[...card.querySelectorAll('a,button,.action-confirm')].filter(el=>el.getBoundingClientRect().width>0);
        return {width:Math.round(box.width*100)/100,height:Math.round(box.height*100)/100,
        outside:controls.map(el=>({tag:el.tagName,text:el.textContent.trim(),box:el.getBoundingClientRect()})).filter(x=>x.box.left<box.left-.5||x.box.right>box.right+.5).map(x=>({tag:x.tag,text:x.text}))};
    })""")
    results['cardMetrics'].append({'page':label,'viewport':page.viewport_size['width'],'previousHeight':263,'cards':measurements})
    assert not any(card['outside'] for card in measurements),measurements
    return measurements


def run_full(page,ctx,mock,results,check,shot,nav,overflow):
    def assert_true(condition,message):
        assert condition,message
    check('桌面常规任务卡高度低于原263px',lambda:assert_true(max(card['height'] for card in results['cardMetrics'][0]['cards'])<263,str(results['cardMetrics'][0])))
    check('字符串金额正确相加为72元',lambda:expect(page.locator('.overview-numbers')).to_contain_text('¥72.00'))
    search=page.get_by_role('textbox',name='搜索任务')
    search.fill('打印室')
    check('广场关键词搜索',lambda:expect(page.locator('.errand-card')).to_have_count(1))
    search.fill('不存在的测试任务')
    check('搜索空态提示',lambda:expect(page.get_by_role('heading',name='没有找到匹配的任务')).to_be_visible())
    page.get_by_role('button',name='清除筛选',exact=True).click()
    page.get_by_role('button',name='帮我买',exact=True).click()
    check('分类筛选为两条代买',lambda:expect(page.locator('.errand-card')).to_have_count(2))
    page.get_by_role('button',name='全部任务',exact=True).click()
    page.get_by_label('任务排序').select_option('reward')
    check('悬赏金额降序排序',lambda:expect(page.locator('.card-title').first).to_have_text(TITLES[2]))
    page.get_by_label('任务排序').select_option('default')
    page.locator('.card-title').first.click()
    expect(page.locator('.task-order-id')).to_contain_text(IDS[0])
    check('超过2^53的任务ID完整传递到详情',lambda:assert_true(page.url.endswith(IDS[0]),page.url))
    check('跑腿详情仅显示服务端GRAB动作',lambda:assert_true(page.get_by_role('button',name='抢单',exact=True).count()==1 and page.get_by_role('button',name='取消并退款',exact=True).count()==0,'权限按钮错误'))
    page.get_by_role('button',name='抢单',exact=True).click()
    expect(page.get_by_role('button',name='确认接单',exact=True)).to_be_visible()
    page.get_by_role('button',name='确认接单',exact=True).click()
    check('后端动作刷新到取货和争议',lambda:expect(page.get_by_role('button',name='取货',exact=True)).to_be_visible())
    page.reload();page.wait_for_load_state('networkidle')
    check('详情接单同学显示泡且保留原始ID',lambda:expect(page.locator('.task-facts [title="用户 ID：2001"]')).to_have_text('泡'))
    shot('detail.png');overflow('desktop-detail')
    nav('/square')
    page.get_by_role('link',name=re.compile('好吃的帮我带')).click()
    expect(page.get_by_label('任务内容')).to_have_value('帮我从食堂带一份午餐到宿舍')
    check('快捷入口预填类型与内容',lambda:expect(page.locator('.task-type-option[aria-pressed="true"]')).to_contain_text('帮我买'))
    page.get_by_label('悬赏金额').fill('12.345')
    page.get_by_role('button',name='发布并托管').click()
    check('阻止超过两位小数的金额',lambda:expect(page.get_by_role('alert')).to_contain_text('最多保留两位小数'))
    assert len(mock.publishes)==0
    page.get_by_label('任务内容').fill('  校园超市代买一本笔记本，送到图书馆门口  ')
    page.get_by_label('悬赏金额').fill('12.34')
    shot('publish.png');overflow('desktop-publish')
    # 同一帧内重复触发表单提交，验证 ref 锁而不只依赖按钮 disabled。
    page.locator('form').evaluate("form => { form.requestSubmit(); form.requestSubmit(); }")
    expect(page.get_by_role('heading',name='任务已发布，等一位顺路的同学')).to_be_visible()
    check('发布仅请求一次且金额按分精确提交',lambda:assert_true(mock.publishes==[{'title':'校园超市代买一本笔记本，送到图书馆门口','rewardCents':1234,'slotTotal':1,'type':'BUY'}],str(mock.publishes)))
    page.get_by_role('link',name=re.compile('查看任务进度')).click()
    check('发布成功长ID跳转',lambda:expect(page.locator('.task-order-id')).to_contain_text(NEW_ID))
    page.get_by_label('切换演示身份').select_option('1001')
    expect(page.locator('.profile-select select')).to_have_value('1001')
    nav('/mine')
    expect(page.locator('.errand-card')).to_have_count(6)
    check('我的任务发布角色列表',lambda:expect(page.get_by_role('button',name='我发布的',exact=True)).to_have_attribute('aria-pressed','true'))
    page.get_by_role('button',name='我接下的',exact=True).click()
    check('我的任务切换接单角色',lambda:expect(page.locator('.errand-card')).to_have_count(1))
    page.get_by_role('button',name='我发布的',exact=True).click()
    expect(page.locator('.errand-card')).to_have_count(6)
    owner_card=page.locator('.errand-card').filter(has_text=TITLES[1])
    check('发单人只看到取消而无抢单',lambda:assert_true(owner_card.get_by_role('button',name='取消并退款',exact=True).count()==1 and owner_card.get_by_role('button',name='抢单',exact=True).count()==0,'发单人动作错误'))
    cancel_before=sum(x['path'].endswith('/cancel') for x in mock.calls)
    owner_card.get_by_role('button',name='取消并退款',exact=True).click()
    expect(owner_card.locator('.action-confirm')).to_be_visible()
    owner_card.get_by_role('button',name='暂不操作').click()
    check('取消操作可退出且未请求后端',lambda:assert_true(sum(x['path'].endswith('/cancel') for x in mock.calls)==cancel_before,'确认前发送了取消请求'))
    owner_card.get_by_role('button',name='取消并退款',exact=True).click()
    owner_card.get_by_role('button',name='确认操作').click()
    check('确认后取消状态更新',lambda:expect(owner_card.locator('.status')).to_have_text('已取消'))
    nav('/wallet')
    expect(page.locator('.account-balance-value')).to_have_text('¥1200.00')
    expect(page.locator('.account-table tbody tr')).to_have_count(2)
    check('字符串钱包金额与复式流水展示',lambda:expect(page.get_by_role('heading',name='记账流水')).to_be_visible())
    check('账本保持借记贷记语义',lambda:assert_true('借记' in page.locator('.account-table').inner_text() and '贷记' in page.locator('.account-table').inner_text(),'记账语义错误'))
    shot('wallet.png');overflow('desktop-wallet')
    page.get_by_role('button',name='贷记',exact=True).click()
    check('钱包方向筛选',lambda:expect(page.locator('.account-table tbody tr')).to_have_count(1))
    page.get_by_label('切换演示身份').select_option('2001')
    check('切换身份立即重载当前页余额',lambda:expect(page.locator('.account-balance-value')).to_have_text('¥268.80'))
    assert any(c['path']=='/api/wallet' and c['user']=='2001' for c in mock.calls)
    nav('/credit')
    check('信用分与长ID榜单展示',lambda:expect(page.locator('.account-score-number')).to_have_text('82分'))
    expect(page.locator('.account-rank-row')).to_have_count(3)
    check('信用榜2001显示泡且2002不被替换',lambda:(expect(page.locator('.account-rank-self .account-rank-user')).to_contain_text('泡'),expect(page.locator('.account-rank-row').first).to_contain_text('跑腿同学 B'),expect(page.locator('.profile-select select')).to_have_value('2001')))
    assert page.evaluate("localStorage.getItem('dash_user')")=='2001'
    page.get_by_role('button',name='扣分记录').click()
    check('信用记录筛选',lambda:expect(page.locator('.account-credit-event')).to_have_count(1))
    expect(page.locator('.account-credit-event')).to_contain_text('抢中后超时未确认')
    overflow('desktop-credit');shot('credit.png')
    nav('/notifications')
    expect(page.locator('.account-message')).to_have_count(2)
    page.get_by_role('button',name='结算',exact=True).click()
    check('消息类型筛选',lambda:expect(page.locator('.account-message')).to_have_count(1))
    page.locator('.account-message').click()
    check('消息关联任务长ID跳转',lambda:expect(page.locator('.task-order-id')).to_contain_text(IDS[1]))
    mock.fail_square=True;nav('/square')
    check('初次请求失败展示错误',lambda:expect(page.get_by_role('alert')).to_contain_text('服务暂时不可用'))
    mock.fail_square=False
    page.get_by_role('button',name='重试',exact=True).click()
    check('失败后重试恢复列表',lambda:expect(page.locator('.errand-card')).to_have_count(5))
    mock.empty_square=True
    page.get_by_role('button',name='刷新任务').click()
    check('服务端空列表展示引导',lambda:expect(page.get_by_role('heading',name='新的委托，正在路上')).to_be_visible())
    mock.empty_square=False
    page.get_by_role('button',name='刷新任务').click()
    expect(page.locator('.errand-card')).to_have_count(5)
    # 恢复初始的未接单任务，用相同数据比较桌面与手机截图。
    mock.cards[0].update(status='PUBLISHED',grabberId='-1',slotTaken=0)
    mock.cards[1]['status']='PUBLISHED';mock.cards=mock.cards[:6]
    page.set_viewport_size({'width':390,'height':844})
    nav('/square');page.get_by_role('button',name='刷新任务').click();expect(page.locator('.errand-card')).to_have_count(6)
    shot('mobile-square.png');overflow('mobile-square');measure_cards(page,results,'mobile-square')
    menu=page.get_by_role('button',name='打开导航')
    menu.click()
    expect(page.locator('.sidebar')).to_have_class(re.compile('is-open'))
    check('移动导航打开并聚焦抽屉',lambda:expect(page.get_by_role('button',name='关闭导航')).to_be_focused())
    page.get_by_role('button',name='退出登录').focus();page.keyboard.press('Tab')
    check('移动导航Tab焦点循环',lambda:expect(page.get_by_role('button',name='关闭导航')).to_be_focused())
    page.keyboard.press('Escape')
    check('Escape关闭抽屉并归还焦点',lambda:expect(menu).to_be_focused())
    expect(menu).to_have_attribute('aria-expanded','false')
    menu.click();page.locator('.sidebar').get_by_role('link',name='我的钱包',exact=True).click()
    expect(page.locator('.account-balance-value')).to_have_text('¥268.80')
    check('移动导航跳转后自动关闭',lambda:expect(page.locator('.sidebar')).not_to_have_class(re.compile('is-open')))
    overflow('mobile-wallet');shot('mobile-wallet.png')
    for path,label in [('/publish','mobile-publish'),(f'/detail/{IDS[0]}','mobile-detail'),('/mine','mobile-mine'),('/credit','mobile-credit'),('/notifications','mobile-notifications')]:
        nav(path);overflow(label);shot(label+'.png')
    # 三种宽度覆盖正常卡片、多按钮与确认面板，确保紧凑布局不挤出操作。
    for width in [1440,390,320]:
        page.set_viewport_size({'width':width,'height':1000 if width==1440 else 844})
        nav('/mine')
        page.get_by_role('button',name='我接下的',exact=True).click()
        expect(page.locator('.errand-card')).to_have_count(1)
        card=page.locator('.errand-card').first
        expect(card.get_by_role('button',name='取货',exact=True)).to_be_visible()
        card.get_by_role('button',name='发起争议',exact=True).click()
        expect(card.locator('.action-confirm')).to_be_visible()
        check(f'{width}px多操作任务卡确认面板不溢出',lambda:measure_cards(page,results,f'card-confirm-{width}'))
        overflow(f'card-confirm-{width}')
        if width==320:shot('small-mobile-card-confirm.png')
        card.get_by_role('button',name='暂不操作',exact=True).click()
    for path,label in [('/square','small-mobile-square'),('/publish','small-mobile-publish'),(f'/detail/{IDS[0]}','small-mobile-detail'),('/wallet','small-mobile-wallet'),('/credit','small-mobile-credit'),('/notifications','small-mobile-notifications')]:
        nav(path);overflow(label)
        if path=='/square':
            measure_cards(page,results,label);shot('small-mobile-square.png')
    page.set_viewport_size({'width':390,'height':844})
    mock.unread_count='0'
    nav('/wallet')
    check('字符串零未读不显示红点和角标',lambda:expect(page.locator('.notification-button i,.nav-count')).to_have_count(0))
    mock.unauthorized_credit=True
    nav('/credit')
    check('401自动退出并返回身份登录页',lambda:expect(page.get_by_role('heading',name='从一个身份开始')).to_be_visible())
    assert page.evaluate("localStorage.getItem('dash_token')")==None
    mock.unauthorized_credit=False;mock.fail_login=True
    login_button=page.locator('.identity-btn').filter(has_text='#2001')
    login_button.click()
    check('登录失败显示错误且按钮恢复可用',lambda:expect(page.get_by_role('alert')).to_contain_text('登录服务暂时不可用'))
    expect(login_button).to_be_enabled()
    mock.fail_login=False;login_button.click()
    check('登录失败后重试成功',lambda:expect(page.locator('.errand-card')).to_have_count(6))
    run_action_regressions(page,mock,check,nav)
    check('所有页面无未捕获JS异常' ,lambda:assert_true(not results['pageErrors'],str(results['pageErrors'])))
    check('全部mock请求均匹配真实契约路由',lambda:assert_true(not mock.missing,str(mock.missing)))


def run_action_regressions(page,mock,check,nav):
    """复现资金结果分支与详情路由切换竞态，不调用真实后端。"""
    page.set_viewport_size({'width':1440,'height':1000})
    page.get_by_label('切换演示身份').select_option('1001')
    expect(page.locator('.profile-select select')).to_have_value('1001')
    first=mock.cards[0]
    initial_cards=copy.deepcopy(mock.cards)

    def funds_case(action,result,status,message,success=False):
        initial='PUBLISHED' if action=='cancel' else 'DELIVERED'
        first.update(status=initial,grabberId='2001' if action=='settle' else '-1',slotTaken=1 if action=='settle' else 0)
        mock.action_results[(IDS[0],action)]=(result,status)
        nav('/mine')
        nav(f'/detail/{IDS[0]}')
        label='取消并退款' if action=='cancel' else '确认完成（结算）'
        before=sum(call['path']==f'/api/errands/{IDS[0]}' for call in mock.calls)
        page.get_by_role('button',name=label,exact=True).click()
        page.get_by_role('button',name='确认操作',exact=True).click()
        expect(page.locator('.toast')).to_contain_text(message)
        if not success: expect(page.locator('.toast')).not_to_contain_text('成功')
        page.wait_for_load_state('networkidle')
        assert sum(call['path']==f'/api/errands/{IDS[0]}' for call in mock.calls)>before,'资金操作结果返回后未刷新详情'
        if status: expect(page.get_by_role('button',name=label,exact=True)).to_have_count(0)
        mock.action_results.pop((IDS[0],action))

    for action,done,terminal in [('cancel','REFUNDED','CANCELLED'),('settle','SETTLED','SETTLED')]:
        check(f'{action}冲突不报成功并刷新状态',lambda:funds_case(action,{'result':'CONFLICT'},terminal,'任务状态已发生变化'))
        check(f'{action}已处理结果不重复报告资金成功',lambda:funds_case(action,{'result':f'ALREADY_{done}'},terminal,'已取消并退款' if action=='cancel' else '已完成结算'))
        check(f'{action}缺失业务结果不能报告成功',lambda:funds_case(action,None,None,'暂时无法确认处理结果'))
        check(f'{action}真实成功结果正常刷新',lambda:funds_case(action,{'result':done},terminal,'成功',True))

    def delayed_action(action):
        user='9001' if action=='arbitrate' else '1001'
        page.get_by_label('切换演示身份').select_option(user)
        expect(page.locator('.profile-select select')).to_have_value(user)
        first.update(status='DISPUTED' if action=='arbitrate' else 'PUBLISHED',grabberId='2001' if action=='arbitrate' else '-1')
        nav('/mine')
        nav(f'/detail/{IDS[0]}')
        key=(IDS[0],action);mock.hold_actions.add(key)
        if action=='arbitrate':
            page.get_by_role('button',name='处理争议',exact=True).click()
            page.get_by_role('button',name='支持发单同学 · 全额退款',exact=True).click()
        else:
            page.get_by_role('button',name='取消并退款',exact=True).click()
            page.get_by_role('button',name='确认操作',exact=True).click()
        for _ in range(40):
            if key in mock.pending_actions: break
            page.wait_for_timeout(50)
        assert key in mock.pending_actions,'未截获待返回的操作请求'
        # 只切换 hash，让 React 在同一页面从 A 详情直接进入 B 详情。
        page.evaluate('(id)=>{location.hash=`#/detail/${id}`}',IDS[1])
        expect(page.locator('.task-order-id')).to_contain_text(IDS[1])
        first['status']='REFUNDED' if action=='arbitrate' else 'CANCELLED'
        result='REFUNDED_TO_PUBLISHER' if action=='arbitrate' else 'REFUNDED'
        mock.pending_actions.pop(key).fulfill(status=200,content_type='application/json',body=json.dumps({'code':'OK','message':'成功','data':{'result':result}}))
        mock.hold_actions.remove(key)
        page.wait_for_load_state('networkidle')
        expect(page.locator('.task-order-id')).to_contain_text(IDS[1])
        expect(page.locator('.task-overview h2')).to_have_text(TITLES[1])
        assert page.url.endswith(IDS[1]),page.url

    check('取消请求晚返回不将A任务覆盖进B详情',lambda:delayed_action('cancel'))
    check('仲裁请求晚返回不将A任务覆盖进B详情',lambda:delayed_action('arbitrate'))
    mock.cards=initial_cards

if __name__=='__main__':raise SystemExit(main())
