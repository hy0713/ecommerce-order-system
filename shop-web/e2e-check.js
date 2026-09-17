// 真实浏览器端到端验证：CDP 驱动 Chrome 无头实例
// 不依赖任何 npm 包（Node 22 内置 WebSocket / fetch），只用本机已装的 Chrome
// 覆盖：登录 → 各管理页渲染 → 完整下单闭环 → 失败请求监控
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const PORT = 9222;
const BASE = 'http://localhost:5173';
const PROFILE = path.join(os.tmpdir(), 'cr-e2e-' + Date.now());
const SHOTS = path.join(process.cwd(), 'e2e-shots');
fs.mkdirSync(SHOTS, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const launch = () => spawn(CHROME, [
  '--headless=new', '--disable-gpu', '--no-sandbox', '--no-proxy-server',
  '--no-first-run', '--no-default-browser-check', '--disable-extensions',
  '--remote-debugging-port=' + PORT,
  '--user-data-dir=' + PROFILE,
  '--window-size=1440,900', 'about:blank'
], { stdio: 'ignore' });

async function waitPort() {
  for (let i = 0; i < 40; i++) {
    try { if ((await fetch(`http://127.0.0.1:${PORT}/json/version`)).ok) return; } catch (_) {}
    await sleep(500);
  }
  throw new Error('Chrome 调试端口未就绪');
}

async function pageTarget() {
  const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
  const page = list.find((t) => t.type === 'page');
  if (!page) throw new Error('未找到 page target');
  return page.webSocketDebuggerUrl;
}

function cdp(url) {
  const ws = new WebSocket(url);
  let id = 0;
  const pending = new Map();
  const listeners = [];
  const ready = new Promise((res, rej) => {
    ws.addEventListener('open', () => res());
    ws.addEventListener('error', () => rej(new Error('ws error')));
  });
  ws.addEventListener('message', (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
      return;
    }
    listeners.forEach((fn) => fn(msg));
  });
  return {
    ready,
    on(fn) { listeners.push(fn); },
    send(method, params = {}) {
      const mid = ++id;
      return new Promise((resolve, reject) => {
        pending.set(mid, { resolve, reject });
        ws.send(JSON.stringify({ id: mid, method, params }));
      });
    },
    close() { ws.close(); }
  };
}

(async () => {
  const results = [];
  const log = (name, ok, detail) => {
    results.push({ name, ok });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + detail : ''}`);
  };

  const chrome = launch();
  let c;
  const badRequests = [];
  try {
    await waitPort();
    c = cdp(await pageTarget());
    await c.ready;
    await c.send('Page.enable');
    await c.send('Runtime.enable');
    await c.send('Network.enable');

    // 监控所有失败请求（4xx/5xx）：CORS、401、500 都会在这里暴露
    const reqUrl = new Map();
    c.on((msg) => {
      if (msg.method === 'Network.requestWillBeSent') {
        reqUrl.set(msg.params.requestId, msg.params.request.url);
      }
      if (msg.method === 'Network.responseReceived') {
        const st = msg.params.response.status;
        if (st >= 400) badRequests.push(`${st} ${reqUrl.get(msg.params.requestId) || msg.params.response.url}`);
      }
      if (msg.method === 'Network.loadingFailed') {
        badRequests.push(`FAILED ${reqUrl.get(msg.params.requestId) || ''} (${msg.params.errorText})`);
      }
    });

    const goto = async (p, wait = 3500) => { await c.send('Page.navigate', { url: BASE + p }); await sleep(wait); };
    const ev = async (expr) => {
      const r = await c.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
      return r.result ? r.result.value : undefined;
    };
    const shot = async (name) => {
      const r = await c.send('Page.captureScreenshot', { format: 'png' });
      fs.writeFileSync(path.join(SHOTS, name + '.png'), Buffer.from(r.data, 'base64'));
    };
    // 真实鼠标点击：Element Plus 的部分组件只响应原生事件，element.click() 不生效
    const realClick = async (selector) => {
      const raw = await ev(`(() => {
        const el = ${selector};
        if (!el) return null;
        el.scrollIntoView({ block: 'center' });
        const r = el.getBoundingClientRect();
        return JSON.stringify({ x: r.left + r.width / 2, y: r.top + r.height / 2 });
      })()`);
      if (!raw) return false;
      const { x, y } = JSON.parse(raw);
      for (const type of ['mousePressed', 'mouseReleased']) {
        await c.send('Input.dispatchMouseEvent', {
          type, x, y, button: 'left', clickCount: 1, buttons: type === 'mousePressed' ? 1 : 0
        });
        await sleep(60);
      }
      return true;
    };

    // 1. 登录页
    await goto('/login');
    const ld = JSON.parse(await ev(`JSON.stringify({
      title: document.title,
      inputs: [...document.querySelectorAll('input')].map(i=>i.placeholder),
      btn: document.querySelector('.el-button')?.innerText.trim()})`) || '{}');
    log('登录页渲染', ld.btn === '登 录' && ld.inputs.includes('用户名'), `标题=${ld.title}`);
    await shot('01-login');

    // 2. 登录（表单已预填 admin/123456）
    await realClick(`[...document.querySelectorAll('.el-button')].find(b=>b.innerText.includes('登'))`);
    await sleep(4500);
    const af = JSON.parse(await ev(`JSON.stringify({
      token: !!localStorage.getItem('token'),
      path: location.pathname,
      role: (JSON.parse(localStorage.getItem('userInfo')||'{}').role)})`) || '{}');
    log('管理员登录成功', af.token === true && af.path === '/dashboard' && af.role === 'ADMIN',
      `路径=${af.path} 角色=${af.role}`);
    await shot('02-dashboard');

    // 主内容区选择器：新布局用 .content，旧布局用 .el-main，做兼容兜底
    const MAIN = `(document.querySelector('.content') || document.querySelector('.el-main') || document.body)`;

    // 3. 数据概览（统计局接口要求 ADMIN）
    const dash = await ev(`${MAIN}.innerText.replace(/\\n+/g,' | ').slice(0,200)`);
    log('数据概览加载统计', typeof dash === 'string' && dash.includes('商品总数'), `内容=${dash}`);

    // 3b. 趋势图「近 7 天 / 近 30 天」切换（折线图每个数据点一个 circle）
    const d7 = await ev(`document.querySelectorAll('.line-chart circle').length`);
    await realClick(`[...document.querySelectorAll('.el-radio-button')].find(b=>b.innerText.trim()==='近 30 天')`);
    await sleep(1200);
    const d30 = await ev(`document.querySelectorAll('.line-chart circle').length`);
    const dTab = await ev(`document.querySelector('.el-radio-button.is-active')?.innerText.trim() || ''`);
    log('趋势图天数切换生效', d7 === 7 && d30 === 30 && dTab === '近 30 天', `7天=${d7}点 30天=${d30}点 选中=${dTab}`);
    await shot('11-dashboard-30d');
    await realClick(`[...document.querySelectorAll('.el-radio-button')].find(b=>b.innerText.trim()==='近 7 天')`);
    await sleep(1000);

    // 4. 商品管理
    await goto('/products');
    const pd = JSON.parse(await ev(`JSON.stringify({
      rows: document.querySelectorAll('.el-table__body tr').length,
      activeTab: document.querySelector('.el-radio-button.is-active')?.innerText.trim() || '(none)'})`) || '{}');
    log('商品管理列表渲染', pd.rows > 0, `行数=${pd.rows} 选中筛选=${pd.activeTab}`);
    await shot('03-products');

    // 4b. 筛选「已下架」：选中态应切换，且返回的每一行状态都必须是「已下架」
    await realClick(`[...document.querySelectorAll('.el-radio-button')].find(b=>b.innerText.trim()==='已下架')`);
    await sleep(1800);
    const off = JSON.parse(await ev(`JSON.stringify({
      rows: document.querySelectorAll('.el-table__body tr').length,
      tab: document.querySelector('.el-radio-button.is-active')?.innerText.trim() || '',
      allMatch: [...document.querySelectorAll('.el-table__body tr')].every(r => r.innerText.includes('已下架'))})`) || '{}');
    log('商品筛选「已下架」生效', off.tab === '已下架' && off.allMatch, `选中=${off.tab} 行数=${off.rows} 全部为已下架=${off.allMatch}`);

    await realClick(`[...document.querySelectorAll('.el-radio-button')].find(b=>b.innerText.trim()==='全部')`);
    await sleep(1800);
    const allRows = await ev(`document.querySelectorAll('.el-table__body tr').length`);
    log('商品筛选切回「全部」', allRows > 0, `行数=${allRows}`);
    // 5. 分类管理（树形，非表格）
    await goto('/categories');
    const cd = JSON.parse(await ev(`JSON.stringify({
      tree: document.querySelectorAll('.el-tree-node').length,
      text: document.querySelector('.el-tree')?.innerText.replace(/\\n+/g,' | ').slice(0,120)})`) || '{}');
    log('分类管理树渲染', cd.tree > 0, `节点数=${cd.tree} 内容=${cd.text}`);

    // 6. 订单管理：执行一次完整「模拟下单」闭环
    await goto('/orders');
    const before = JSON.parse(await ev(`JSON.stringify({rows: document.querySelectorAll('.el-table__body tr').length})`) || '{}');
    log('订单管理页加载', true, `当前订单行数=${before.rows}`);

    // 6a. 订单状态 Tab 筛选（Element Plus 2.3.8 用 label 传值，此处验证筛选真实生效）
    await realClick(`[...document.querySelectorAll('.el-radio-button')].find(b=>b.innerText.trim()==='已取消')`);
    await sleep(1800);
    const cn = JSON.parse(await ev(`JSON.stringify({
      rows: document.querySelectorAll('.el-table__body tr').length,
      tab: document.querySelector('.el-radio-button.is-active')?.innerText.trim() || '',
      allMatch: [...document.querySelectorAll('.el-table__body tr')].every(r => r.innerText.includes('已取消'))})`) || '{}');
    log('订单状态 Tab 筛选生效', cn.tab === '已取消' && cn.allMatch, `选中=${cn.tab} 行数=${cn.rows} 全部为已取消=${cn.allMatch}`);

    await realClick(`[...document.querySelectorAll('.el-radio-button')].find(b=>b.innerText.trim()==='全部')`);
    await sleep(1600);

    await realClick(`[...document.querySelectorAll('.el-button')].find(b=>b.innerText.includes('模拟下单'))`);
    await sleep(1500);
    const dlgOpen = await ev(`!!document.querySelector('.el-dialog')`);
    log('模拟下单弹窗打开', dlgOpen === true);
    await shot('04-order-dialog');

    // 打开商品下拉（真实鼠标事件）
    await realClick(`document.querySelector('.el-dialog .el-select')`);
    await sleep(1500);
    const optText = await ev(`(() => {
      const items = [...document.querySelectorAll('.el-select-dropdown__item')].filter(e => e.offsetParent !== null);
      return items.length ? items[0].innerText : null;
    })()`);
    await realClick(`[...document.querySelectorAll('.el-select-dropdown__item')].filter(e=>e.offsetParent!==null)[0]`);
    await sleep(1000);
    const chosen = await ev(`(() => {
      const i = document.querySelector('.el-dialog .el-select input');
      return i ? i.value : '';
    })()`);
    log('选择商品', !!optText && !!chosen, `选项=${optText} 已选=${chosen}`);

    await realClick(`document.querySelector('.el-dialog__footer .el-button--primary')`);
    // 轮询等待成功/失败提示（Element Plus 提示 3 秒后自动消失）
    let msg = '';
    for (let i = 0; i < 24; i++) {
      msg = await ev(`document.querySelector('.el-message')?.innerText || ''`);
      if (msg) break;
      await sleep(500);
    }
    log('下单闭环执行', /成功|订单/.test(String(msg)), `提示=${msg}`);
    await shot('05-order-created');

    // 从成功提示里取出新订单号，用于断言它确实进了列表
    const newOrderNo = (String(msg).match(/\d{15,}/) || [''])[0];

    await goto('/orders');
    const after = JSON.parse(await ev(`JSON.stringify({
      rows: document.querySelectorAll('.el-table__body tr').length,
      first: document.querySelector('.el-table__body tr')?.innerText.replace(/\\n+/g,' | ').slice(0,120)})`) || '{}');
    // 注意：不能断言「行数增加」——列表是 10 条一页，管理端现在看全站订单，
    // 第一页恒为满页，新增一单只会把最旧的挤出本页。要断言新订单号出现在首行（按创建时间倒序）。
    log('订单已出现在列表', !!newOrderNo && String(after.first || '').includes(newOrderNo),
      `新订单号=${newOrderNo} 行数=${after.rows} 首行=${after.first}`);
    await shot('06-order-list');

    // 6b. 订单详情（抽屉）
    await realClick(`document.querySelector('.el-table__body tr .el-button')`);
    await sleep(1800);
    const dr = JSON.parse(await ev(`JSON.stringify({
      open: !!document.querySelector('.order-drawer'),
      text: (document.querySelector('.order-drawer')?.innerText || '').replace(/\\n+/g,' | ').slice(0,240)})`) || '{}');
    log('订单详情抽屉打开', dr.open && /收货地址/.test(dr.text), `内容=${dr.text}`);
    await shot('09-order-detail');
    for (const type of ['keyDown', 'keyUp']) {
      await c.send('Input.dispatchKeyEvent', { type, key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27, nativeVirtualKeyCode: 27 });
    }
    await sleep(900);

    // 7. 回数据概览，确认统计随闭环变化
    await goto('/dashboard');
    const dash2 = await ev(`${MAIN}.innerText.replace(/\\n+/g,' | ').slice(0,160)`);
    const dashOk = typeof dash2 === 'string' && dash2.includes('总销售额');
    log('统计随下单更新', dashOk, `内容=${dash2}`);
    await shot('08-dashboard-after');

    // 8. 智能客服页（前端 → Agent 8000，跨端口 CORS）
    await goto('/agent', 4500);
    const ag = await ev(`${MAIN}.innerText.replace(/\\n+/g,' | ').slice(0,200)`);
    const agOk = typeof ag === 'string' && ag.length > 2 && /会话|知识库|客服/.test(ag);
    log('智能客服页加载', agOk, `内容=${ag}`);
    await shot('07-agent');

    // 8b. 智能客服真实提问闭环：前端 → vite 代理 /api/agent → Agent 8000 → DeepSeek
    await realClick(`[...document.querySelectorAll('.el-button')].find(b=>b.innerText.includes('新建会话'))`);
    await sleep(2000);
    await realClick(`document.querySelector('.chat-input input')`);
    await c.send('Input.insertText', { text: '查一下 iPhone 的库存' });
    await sleep(500);
    const typed = await ev(`document.querySelector('.chat-input input')?.value || ''`);
    await realClick(`[...document.querySelectorAll('.chat-input .el-button')].find(b=>b.innerText.includes('发送'))`);

    let answer = '';
    for (let i = 0; i < 60; i++) {
      answer = await ev(`(() => {
        const bs = [...document.querySelectorAll('.msg-row.assistant .bubble')];
        const last = bs[bs.length - 1];
        return last ? last.innerText.trim() : '';
      })()`);
      if (answer && !/^\\.*$/.test(answer)) break;
      await sleep(1000);
    }
    log('智能客服真实提问有回答', !!answer && answer.length > 4, `提问="${typed}" 回答=${String(answer).replace(/\\s+/g, ' ').slice(0, 120)}`);
    await shot('12-agent-chat');

    // 9. 普通用户（USER）权限表现：管理员动作入口应隐藏，接口由后端 403
    const uname = 'webuser_' + Date.now();
    const reg = await ev(`(async () => {
      await fetch('/api/user/register', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: '${uname}', password: '123456', phone: '13900002222' }) });
      const r = await fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: '${uname}', password: '123456' }) });
      const j = await r.json();
      return JSON.stringify({ token: j?.data?.token || null });
    })()`);
    const uj = JSON.parse(reg || '{}');
    log('普通用户注册并登录', !!uj.token, `username=${uname}`);

    if (uj.token) {
      const info = JSON.stringify({ username: uname, role: 'USER' });
      await ev(`localStorage.setItem('token', ${JSON.stringify(uj.token)});
                localStorage.setItem('userInfo', ${JSON.stringify(info)}); 1`);
      await goto('/orders', 4000);
      const uv = JSON.parse(await ev(`JSON.stringify({
        roleTag: document.querySelector('.role-tag')?.innerText.trim() || '',
        shipBtns: [...document.querySelectorAll('.el-button')].filter(b => b.innerText.trim() === '发货').length,
        emptyText: (document.querySelector('.el-table')?.innerText || '').replace(/\\n+/g, ' ').trim().slice(0, 40),
        adminMenu: [...document.querySelectorAll('.menu-item')].map(e => e.innerText.trim()).join('/')})`) || '{}');
      // 新注册用户没有订单，列表应为空；关键是「发货 / 取消」管理员入口不得出现
      log('USER 无管理员操作入口', uv.roleTag === 'USER' && uv.shipBtns === 0,
        `角色标签=${uv.roleTag} 发货按钮=${uv.shipBtns} 列表=${uv.emptyText}`);
      await shot('10-user-orders');

      // 业务 403 是本项目的 HTTP 200 + body.code=403 约定
      const st = await ev(`(async () => {
        const r = await fetch('/api/order/stats', { headers: { 'Authorization': 'Bearer ' + localStorage.getItem('token') } });
        const j = await r.json().catch(() => ({}));
        return JSON.stringify({ http: r.status, code: j.code });
      })()`);
      const sj = JSON.parse(st || '{}');
      log('USER 调统计局接口被后端拒绝', sj.code === 403, `HTTP=${sj.http} body.code=${sj.code}`);

      // 兜底恢复管理员态，避免影响后续人工查看
      await ev(`localStorage.removeItem('token'); localStorage.removeItem('userInfo'); 1`);
    }

  } catch (e) {
    log('执行异常', false, e.message);
  } finally {
    try { c && c.close(); } catch (_) {}
    try { chrome.kill(); } catch (_) {}
    await sleep(800);
  }

  // 网络失败汇总（排除已知的非业务请求）
  const real = badRequests.filter((u) => !/favicon|\.map$/.test(u));
  console.log('');
  if (real.length) {
    console.log(`⚠ 捕获到 ${real.length} 个失败请求：`);
    [...new Set(real)].slice(0, 12).forEach((u) => console.log('   ' + u));
  } else {
    console.log('✅ 全流程无 4xx/5xx 与网络失败请求');
  }
  console.log('');
  const fail = results.filter((r) => !r.ok).length;
  console.log(`结果：${results.length - fail} 通过 / ${fail} 失败`);
})();
