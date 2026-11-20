(() => {
  if (window.__pg) return;
  const API = 'https://alkalimakersuite-pa.clients6.google.com/$rpc/google.internal.alkali.applications.makersuite.v1.MakerSuiteService/';
  const post = (...a) => PgBridge.post(JSON.stringify(a));
  const fetch0 = window.fetch;
  let service, snapshot, template, armed = true, lock = Promise.resolve();
  const runs = {};

  const hex = async (algo, text) => [...new Uint8Array(await crypto.subtle.digest(algo, new TextEncoder().encode(text)))]
    .map(b => b.toString(16).padStart(2, '0')).join('');

  function captured(url, headers) {
    if (!armed || !new URL(String(url), location.href).pathname.endsWith('/GenerateContent')) return false;
    armed = false;
    template = headers();
    return true;
  }
  window.fetch = function (input, init) {
    const url = typeof input === 'string' ? input : input && input.url;
    return captured(url, () => Object.fromEntries(new Headers(init && init.headers))) ? new Promise(() => {}) : fetch0.apply(this, arguments);
  };
  const xhr = XMLHttpRequest.prototype, open = xhr.open, set = xhr.setRequestHeader, send = xhr.send;
  xhr.open = function (m, url) { this.__url = url; this.__h = {}; return open.apply(this, arguments); };
  xhr.setRequestHeader = function (k, v) { this.__h[k.toLowerCase()] = v; return set.apply(this, arguments); };
  xhr.send = function () { if (!captured(this.__url, () => this.__h)) return send.apply(this, arguments); };

  function hookSnapshot() {
    const d = window.default_MakerSuite;
    if (!d) return false;
    for (const k of Object.keys(d)) {
      const f = d[k];
      if (typeof f !== 'function' || f.__pg) continue;
      const s = String(f);
      if (!s.includes('.snapshot') || !s.includes('content') || !s.includes('yield')) continue;
      d[k] = function (...a) { service = a[0]; return f.apply(this, a); };
      d[k].__pg = true;
      snapshot = f;
      return true;
    }
    return false;
  }

  async function headers() {
    const h = {};
    for (const [k, v] of Object.entries(template)) if (k !== 'authorization' && k !== 'content-length') h[k] = v;
    const c = Object.fromEntries(document.cookie.split('; ').map(x => [x.slice(0, x.indexOf('=')), x.slice(x.indexOf('=') + 1)]));
    const ts = Math.floor(Date.now() / 1000), sign = async v => ts + '_' + await hex('SHA-1', ts + ' ' + v + ' https://aistudio.google.com');
    const sid = c.SAPISID || c['__Secure-1PAPISID'];
    h.authorization = ['SAPISIDHASH ' + await sign(sid), 'SAPISID1PHASH ' + await sign(c['__Secure-1PAPISID'] || sid), 'SAPISID3PHASH ' + await sign(c['__Secure-3PAPISID'] || sid)].join(' ');
    return h;
  }

  const wait = async (fn, ms) => {
    for (const end = Date.now() + ms; Date.now() < end; await new Promise(r => setTimeout(r, 200))) {
      const v = fn();
      if (v) return v;
    }
    return null;
  };

  window.__pg = {
    async start() {
      try {
        if (!await wait(hookSnapshot, 30000)) throw new Error('页面没加载完');
        const box = await wait(() => document.querySelector('ms-prompt-box textarea, textarea'), 30000);
        if (!box) throw new Error('找不到输入框，先在浏览器里打开一次 AI Studio，把弹窗都点掉');
        Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(box, 'hi');
        box.dispatchEvent(new InputEvent('input', { bubbles: true, data: 'hi', inputType: 'insertText' }));
        await new Promise(r => setTimeout(r, 300));
        const run = document.querySelector("button.ctrl-enter-submits, button[aria-label*='Run'], button[aria-label*='运行']")
          || [...document.querySelectorAll('button')].find(b => /^(Run|运行)/.test(b.innerText.trim()));
        if (!run) throw new Error('找不到运行按钮');
        run.click();
        if (!await wait(() => service && template, 45000)) throw new Error('页面没发出请求，可能有弹窗挡着，先在浏览器里打开一次 AI Studio 把弹窗都点掉');
        const r = await fetch0(API + 'ListModels', { method: 'POST', headers: await headers(), body: '[]', credentials: 'include' });
        const text = await r.text();
        if (!r.ok) throw new Error('读模型列表失败（' + r.status + '）' + text.slice(0, 200));
        const email = window.WIZ_global_data && WIZ_global_data.oPEP7c;
        post('ready', email || '', text.replace(/^\)\]\}'\s*/, ''));
      } catch (e) {
        post('broken', String(e.message || e));
      }
    },
    stop(id) {
      if (runs[id]) runs[id].abort();
    },
    async run(id, wire) {
      const ctl = runs[id] = new AbortController();
      try {
        const text = [];
        for (const c of wire[1]) for (const p of c[0]) text.push(typeof p[1] === 'string' ? p[1] : p[2] ? p[2][1] : '');
        const digest = await hex('SHA-256', text.join(' '));
        wire[4] = await (lock = lock.catch(() => {}).then(() => snapshot(service, digest)));
        const r = await fetch0(API + 'GenerateContent', { method: 'POST', headers: await headers(), body: JSON.stringify(wire), credentials: 'include', signal: ctl.signal });
        if (!r.ok) return post('fail', id, r.status, (await r.text()).slice(0, 1000));
        const reader = r.body.getReader(), dec = new TextDecoder();
        for (let x; !(x = await reader.read()).done;) post('chunk', id, dec.decode(x.value, { stream: true }));
        post('end', id);
      } catch (e) {
        post('fail', id, 502, String(e.message || e));
      } finally {
        delete runs[id];
      }
    }
  };
})();
