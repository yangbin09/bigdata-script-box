async (page) => {
  const BASE = 'http://8.163.99.17';
  const IGNORE = [/favicon\.ico/];
  const RUN_EXEC = false;
  const ROUTES = [
    { hash: '#/',          h2: '执行中心', minRows: 0,  minCards: 1, notContain: ['暂无可执行脚本'] },
    { hash: '#/scripts',   h2: '脚本管理', minRows: 8,  minCards: 0 },
    { hash: '#/tenants',   h2: '租户管理', minRows: 1,  minCards: 0 },
    { hash: '#/scenarios', h2: '场景编排', minRows: 1,  minCards: 0 },
    { hash: '#/history',   h2: '执行历史', minRows: 50, minCards: 0 },
    { hash: '#/settings',  h2: '设置',     minRows: 7,  minCards: 0 },
  ];
  const R = [];
  const tap = () => {
    const errs = new Set();
    const c = m => {
      if (m.type() !== 'error') return;
      const u = (m.location && m.location().url) || '';
      errs.add(m.text().split('\n')[0] + (u ? ' @ ' + u : ''));
    };
    const p = e => errs.add('PAGEERROR: ' + String(e.message).split('\n')[0]);
    page.on('console', c); page.on('pageerror', p);
    return { errs, off: () => { page.off('console', c); page.off('pageerror', p); } };
  };
  const go = async (h) => { await page.goto(`${BASE}/?cb=${Date.now()}${h}`, { waitUntil: 'load', timeout: 30000 }); await page.waitForTimeout(3500); };

  for (const r of ROUTES) {
    const t = tap(); const f = []; let i = { rows: 0, cards: 0 };
    try {
      await go(r.hash);
      i = await page.evaluate(() => ({
        h2: [...document.querySelectorAll('h2')].map(e => e.textContent.trim()).join(' | '),
        rows: document.querySelectorAll('tbody tr').length,
        cards: document.querySelectorAll('.sb-script-card').length,
        txt: document.body.innerText,
      }));
      if (i.h2 !== r.h2) f.push(`h2="${i.h2}" 期望 "${r.h2}"`);
      if (i.rows < r.minRows) f.push(`行数 ${i.rows} < ${r.minRows}`);
      if (i.cards < r.minCards) f.push(`卡片 ${i.cards} < ${r.minCards}`);
      for (const s of (r.notContain || [])) if (i.txt.includes(s)) f.push(`不应出现「${s}」`);
    } catch (e) { f.push('异常: ' + String(e.message).split('\n')[0]); }
    t.off();
    for (const e of t.errs) if (!IGNORE.some(re => re.test(e))) f.push('console: ' + e);
    R.push({ c: `页面 ${r.hash}`, ok: !f.length, f, d: `${i.rows}行/${i.cards}卡片` });
  }

  {
    const f = []; let d = '';
    try {
      await page.bringToFront();
      await go('#/');
      await page.keyboard.press('Control+k');
      await page.waitForSelector('.el-dialog.sb-search-dialog', { state: 'visible', timeout: 8000 });
      await page.locator('.sb-search-dialog input').first().fill('成功');
      await page.waitForTimeout(1200);
      const x = await page.evaluate(() => {
        const t = document.querySelector('.sb-search-dialog')?.innerText || '';
        return { hit: t.includes('成功示例'), miss: t.includes('睡眠30秒'), len: t.length };
      });
      if (!x.hit) f.push('未命中「成功示例」');
      if (x.miss) f.push('未过滤掉「睡眠30秒」');
      await page.keyboard.press('Escape');
      await page.waitForTimeout(900);
      if (await page.locator('.el-dialog.sb-search-dialog').isVisible().catch(() => false)) f.push('Esc 未关闭');
      d = `面板 ${x.len} 字`;
    } catch (e) { f.push('异常: ' + String(e.message).split('\n')[0]); }
    R.push({ c: '全局查找 ⌘K', ok: !f.length, f, d });
  }

  if (RUN_EXEC) {
    const f = []; let d = '';
    try {
      await page.bringToFront();
      await go('#/history');
      const before = await page.evaluate(() => document.querySelectorAll('tbody tr').length);
      await go('#/');
      await page.evaluate(() => {
        const card = [...document.querySelectorAll('.sb-script-card')].find(c => (c.innerText || '').includes('演示脚本成功执行'));
        if (!card) throw new Error('找不到「成功示例」卡片');
        const el = [...card.querySelectorAll('*')].filter(e => (e.innerText || '').trim().replace(/\s+/g, '') === '执行→').pop();
        if (!el) throw new Error('找不到执行入口');
        el.click();
      });
      await page.waitForSelector('.sb-exec-drawer', { state: 'visible', timeout: 10000 });
      await page.waitForTimeout(1500);
      await page.evaluate(() => {
        const b = [...document.querySelectorAll('.el-drawer button')].find(x => (x.innerText || '').trim() === '执行脚本');
        if (!b) throw new Error('找不到「执行脚本」按钮');
        b.click();
      });
      await page.waitForTimeout(8000);
      await go('#/history');
      const a = await page.evaluate(() => ({
        rows: document.querySelectorAll('tbody tr').length,
        first: (document.querySelector('tbody tr')?.innerText || '').replace(/\s+/g, ' ').slice(0, 70),
      }));
      if (a.rows !== before + 1) f.push(`历史未 +1（${before} -> ${a.rows}）`);
      if (!/成功/.test(a.first)) f.push('最新记录非成功: ' + a.first);
      d = `${before} -> ${a.rows} 行`;
    } catch (e) { f.push('异常: ' + String(e.message).split('\n')[0]); }
    R.push({ c: '真实执行闭环', ok: !f.length, f, d });
  } else {
    R.push({ c: '真实执行闭环', ok: true, f: [], d: '已跳过' });
  }

  const bad = R.filter(r => !r.ok);
  return {
    summary: bad.length ? `${bad.length}/${R.length} 项失败` : `全部通过 (${R.length} 项)`,
    lines: R.map(r => `${r.ok ? 'PASS' : 'FAIL'}  ${r.c}  [${r.d}]`),
    failures: bad.flatMap(r => r.f.map(x => `${r.c}: ${x}`)),
    bundle: await page.evaluate(() => ([...document.querySelectorAll('script[src]')].map(s => s.src.split('/').pop())[0] || '?')),
  };
}
