(() => {
  if (window.__web) return;
  const post = (...a) => WebBridge.post(JSON.stringify(a));
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const wait = async (fn, ms) => {
    for (const end = Date.now() + ms; Date.now() < end; await sleep(200)) {
      const v = fn();
      if (v) return v;
    }
    return null;
  };

  const editor = () => document.querySelector('rich-textarea .ql-editor, .ql-editor[contenteditable="true"], div[contenteditable="true"][role="textbox"]');
  const sendBtn = () => document.querySelector('button.send-button, button[aria-label*="Send"], button[aria-label*="发送"], button[aria-label*="傳送"]');
  const newChat = () => document.querySelector('[data-test-id="new-chat-button"] button, button[data-test-id="new-chat-button"], [aria-label*="New chat"], [aria-label*="新对话"], [aria-label*="新聊天"]');
  const answers = () => [...document.querySelectorAll('model-response .markdown, message-content .markdown, .model-response-text .markdown, .model-response-text')];
  const lastAnswer = () => {
    const list = answers();
    const node = list[list.length - 1];
    return node ? node.innerText.trim() : '';
  };

  function setText(box, text) {
    box.focus();
    const sel = window.getSelection();
    sel.selectAllChildren(box);
    if (!document.execCommand('insertText', false, text)) {
      box.innerHTML = '';
      const p = document.createElement('p');
      p.textContent = text;
      box.appendChild(p);
    }
    box.dispatchEvent(new InputEvent('input', { bubbles: true }));
  }

  window.__web = {
    async start() {
      try {
        if (!await wait(editor, 30000)) throw new Error('打不开 Gemini 网页，先在浏览器登一次把弹窗点掉');
        post('ready', '');
      } catch (e) {
        post('broken', String(e.message || e));
      }
    },
    async run(id, prompt) {
      try {
        const reset = newChat();
        if (reset) {
          reset.click();
          await wait(() => answers().length === 0, 5000);
          await sleep(300);
        }
        const box = await wait(editor, 15000);
        if (!box) throw new Error('找不到输入框');
        setText(box, prompt);
        const btn = await wait(() => {
          const b = sendBtn();
          return b && !b.disabled ? b : null;
        }, 10000);
        if (!btn) throw new Error('找不到发送按钮');
        await sleep(100);
        btn.click();

        let sent = '', stable = 0, started = false;
        for (let i = 0; i < 1600; i++) {
          await sleep(250);
          const text = lastAnswer();
          if (text && text !== sent) {
            if (text.startsWith(sent)) post('chunk', id, text.slice(sent.length));
            else post('chunk', id, (sent ? '\n' : '') + text);
            sent = text;
            started = true;
            stable = 0;
          } else if (started) {
            if (++stable >= 6) break;
          }
        }
        if (!started) throw new Error('等太久没出回复，可能被谷歌挡了或需要人机验证');
        post('end', id);
      } catch (e) {
        post('fail', id, 502, String(e.message || e));
      }
    }
  };
})();
