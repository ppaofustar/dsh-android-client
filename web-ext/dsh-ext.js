/* ============================================================
 * DSH 手机客户端 · 界面修正（热更新脚本）
 * ------------------------------------------------------------
 * 由 App 在页面加载完成后注入执行。改完无需重装 APK：
 *   App 内「返回键 → 刷新界面修正（热更新）」即可生效。
 *
 * 本脚本解决：DSH 网页端只支持「拖拽 / 粘贴」投喂图片，
 * 手机上这两个交互都不好用（没有 <input type=file>，没有鼠标拖拽）。
 * 这里补一个悬浮按钮 → 调系统文件选择器 → 把选中的图片
 * 合成一个 drop 事件喂给 DSH 自己的图片投喂管线。
 * ============================================================ */
(function () {
  'use strict';
  try {
    if (window.__dshAttachInstalled) return;
    window.__dshAttachInstalled = true;

    var BTN_ID = 'dsh-attach-btn';
    var POS_KEY = 'dsh-attach-pos';

    function log() {
      try { console.log.apply(console, ['[dsh-ext]'].concat([].slice.call(arguments))); } catch (e) { }
    }

    /* ---------- 隐藏的文件输入（会触发 App 的原生文件选择器） ---------- */
    var picker = document.createElement('input');
    picker.type = 'file';
    picker.accept = 'image/png,image/jpeg,image/webp,image/gif';
    picker.multiple = true;
    picker.setAttribute('aria-hidden', 'true');
    picker.style.cssText = 'position:fixed;left:-9999px;top:0;width:1px;height:1px;opacity:0;';

    /* ---------- 把文件喂给 DSH ---------- */
    function feed(files) {
      var list = [].slice.call(files || []);
      if (!list.length) return false;

      var dt;
      try {
        dt = new DataTransfer();
        for (var i = 0; i < list.length; i++) dt.items.add(list[i]);
      } catch (e) {
        log('DataTransfer 不可用', e);
        return false;
      }

      var hasFilesType = false;
      try {
        hasFilesType = [].slice.call(dt.types || []).indexOf('Files') >= 0;
      } catch (e) { }

      // 首选：合成 drop 事件（DSH 的 document 级 onDrop 会读 dataTransfer.files）
      if (hasFilesType) {
        try {
          var dropEv;
          try {
            dropEv = new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt });
          } catch (e) {
            dropEv = new Event('drop', { bubbles: true, cancelable: true });
            Object.defineProperty(dropEv, 'dataTransfer', { value: dt });
          }
          document.dispatchEvent(dropEv);
          log('已通过 drop 投喂', list.length, '个文件');
          return true;
        } catch (e) {
          log('drop 投喂失败', e);
        }
      }

      // 备选：合成 paste 事件（DSH 的输入框 paste 处理器同样接受文件）
      try {
        var pasteEv = new ClipboardEvent('paste', { bubbles: true, cancelable: true, clipboardData: dt });
        document.dispatchEvent(pasteEv);
        log('已通过 paste 投喂', list.length, '个文件');
        return true;
      } catch (e) {
        log('paste 投喂失败', e);
      }
      return false;
    }

    picker.addEventListener('change', function () {
      try { feed(picker.files); } catch (e) { log(e); }
      try { picker.value = ''; } catch (e) { }
    });

    /* ---------- 悬浮按钮（可拖动，位置记忆） ---------- */
    function applyPos(btn) {
      var pos = null;
      try { pos = JSON.parse(localStorage.getItem(POS_KEY) || 'null'); } catch (e) { }
      if (pos && typeof pos.left === 'number' && typeof pos.top === 'number') {
        btn.style.left = pos.left + 'px';
        btn.style.top = pos.top + 'px';
        btn.style.right = 'auto';
        btn.style.bottom = 'auto';
      } else {
        // 默认放右下角：左侧是侧边栏图标条，避免遮挡
        btn.style.right = '14px';
        btn.style.bottom = '124px';
      }
    }

    function mount() {
      if (!document.body) return;
      if (!picker.parentNode) document.body.appendChild(picker);
      if (document.getElementById(BTN_ID)) return;

      var btn = document.createElement('button');
      btn.id = BTN_ID;
      btn.type = 'button';
      btn.title = '添加图片（可拖动）';
      btn.textContent = '\uD83D\uDDBC';   // 🖼
      btn.style.cssText = [
        'position:fixed',
        'z-index:2147483000',
        'width:44px', 'height:44px',
        'padding:0', 'border:none', 'border-radius:50%',
        'background:rgba(77,107,254,.92)', 'color:#fff',
        'font-size:21px', 'line-height:44px', 'text-align:center',
        'box-shadow:0 2px 12px rgba(0,0,0,.3)',
        'cursor:pointer', 'touch-action:none',
        'user-select:none', '-webkit-user-select:none'
      ].join(';');
      applyPos(btn);

      var dragging = false, moved = false, startX = 0, startY = 0, originLeft = 0, originTop = 0;

      function onDown(ev) {
        var p = ev.touches ? ev.touches[0] : ev;
        dragging = true; moved = false;
        startX = p.clientX; startY = p.clientY;
        var r = btn.getBoundingClientRect();
        originLeft = r.left; originTop = r.top;
        btn.style.left = originLeft + 'px';
        btn.style.top = originTop + 'px';
        btn.style.bottom = 'auto';
      }

      function onMove(ev) {
        if (!dragging) return;
        var p = ev.touches ? ev.touches[0] : ev;
        var dx = p.clientX - startX, dy = p.clientY - startY;
        if (Math.abs(dx) > 6 || Math.abs(dy) > 6) moved = true;
        if (!moved) return;
        var left = Math.max(0, Math.min(window.innerWidth - 44, originLeft + dx));
        var top = Math.max(0, Math.min(window.innerHeight - 44, originTop + dy));
        btn.style.left = left + 'px';
        btn.style.top = top + 'px';
        if (ev.cancelable) ev.preventDefault();
      }

      function onUp() {
        if (!dragging) return;
        dragging = false;
        if (moved) {
          try {
            localStorage.setItem(POS_KEY, JSON.stringify({
              left: parseFloat(btn.style.left) || 0,
              top: parseFloat(btn.style.top) || 0
            }));
          } catch (e) { }
        }
      }

      btn.addEventListener('touchstart', onDown, { passive: true });
      btn.addEventListener('touchmove', onMove, { passive: false });
      btn.addEventListener('touchend', onUp);
      btn.addEventListener('touchcancel', onUp);
      btn.addEventListener('mousedown', function (ev) {
        onDown(ev);
        document.addEventListener('mousemove', onMove);
        document.addEventListener('mouseup', function up() {
          document.removeEventListener('mousemove', onMove);
          document.removeEventListener('mouseup', up);
          onUp();
        });
      });
      btn.addEventListener('click', function (ev) {
        ev.preventDefault();
        ev.stopPropagation();
        if (moved) return;            // 拖动后不触发选择
        try { picker.click(); } catch (e) { log(e); }
      });

      document.body.appendChild(btn);
      log('图片按钮已就绪');
    }

    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', mount);
    } else {
      mount();
    }
    // 页面是 SPA，可能在挂载后才出现 body / 被重渲染，补几次
    setTimeout(mount, 1200);
    setTimeout(mount, 3000);
  } catch (e) { }
})();

/* ============================================================
 * 自动关闭「内测声明」
 * ------------------------------------------------------------
 * DSH 的欢迎声明只在【回环地址】浏览器上能持久记住"已读"；远程浏览器
 * （手机上走 Funnel）的设置作用域是内存模式，标记只存在进程内存里，
 * 因此每次加载都会重新弹出并挡住界面。
 * 这里检测到该弹窗就自动点「继续」。不想自动关就把这段删掉。
 * ============================================================ */
(function () {
  'use strict';
  try {
    if (window.__dshNoticeDismisser) return;
    window.__dshNoticeDismisser = true;

    // 文案会随版本变：旧版叫「内测声明」，0.2.x 叫「预览版说明」
    var MARKERS = ['内测声明', '预览版说明'];
    var LABELS = ['继续', '知道了', '我明白了', '开始使用', '开始'];

    function tryDismiss() {
      try {
        if (!document.body) return;
        var bodyText = document.body.innerText || '';
        var hit = false;
        for (var k = 0; k < MARKERS.length; k++) {
          if (bodyText.indexOf(MARKERS[k]) !== -1) { hit = true; break; }
        }
        if (!hit) return;

        // 只在「包含声明的、按钮最少的最内层容器」里找按钮，
        // 避免整页扫描误点到界面上的其它按钮。
        var all = document.querySelectorAll('body *');
        var scope = null;
        for (var i = 0; i < all.length; i++) {
          var t = all[i].innerText || '';
          if (t.indexOf(MARKERS[0]) === -1 && t.indexOf(MARKERS[1]) === -1) continue;
          var n = all[i].querySelectorAll('button').length;
          if (n >= 1 && n <= 3) scope = all[i];   // 文档序里越靠后越内层
        }
        if (!scope) return;

        var btns = scope.querySelectorAll('button');
        for (var j = 0; j < btns.length; j++) {
          var label = (btns[j].textContent || '').trim();
          for (var m = 0; m < LABELS.length; m++) {
            if (label === LABELS[m]) { btns[j].click(); return; }
          }
        }
      } catch (e) { }
    }

    tryDismiss();
    setInterval(tryDismiss, 1500);
  } catch (e) { }
})();

/* ============================================================
 * 侧边栏状态标记（配合 dsh-ext.css 的「抽屉浮层」）
 * ------------------------------------------------------------
 * 0.2.0 不再输出 data-sidebar-collapsed / data-details-collapsed 属性，
 * 折叠状态只体现在 frame 的行内 style 上：
 *   展开  grid-template-columns: 280px minmax(0px, 1fr) minmax(0px, 0px)
 *   收起  grid-template-columns:  56px minmax(0px, 1fr) minmax(0px, 0px)
 *                    ↑ 用 100px 作阈值                ↑ 0 表示详情未打开
 * 纯 CSS 读不到行内值，所以在这里观察并写成 <html> 上的 class。
 * 不想用抽屉布局：删掉本段，再把 dsh-ext.css 第 1 节删掉即可。
 * ============================================================ */
(function () {
  'use strict';
  try {
    if (window.__dshSidebarStateInstalled) return;
    window.__dshSidebarStateInstalled = true;

    var last = '';

    // 按空格切分，但跳过括号内部的空格（minmax(0px, 1fr) 不能被切开）
    function splitCols(value) {
      return value.split(/\s+(?![^()]*\))/)
        .map(function (s) { return s.trim(); })
        .filter(Boolean);
    }

    function pxOf(token) {
      var m = /^([\d.]+)px$/.exec(token || '');
      return m ? parseFloat(m[1]) : null;
    }

    function sync() {
      try {
        var frame = document.querySelector('[class*="_frame"]');
        var root = document.documentElement;
        if (!frame) { root.classList.remove('dsh-sb-open'); root.classList.remove('dsh-dt-open'); return; }

        var m = /grid-template-columns\s*:\s*([^;]+)/.exec(frame.getAttribute('style') || '');
        if (!m) return;

        var parts = splitCols(m[1]);
        var sidebarPx = pxOf(parts[0]);
        // 第三列是详情栏：0px 或 minmax(0px, 0px) 就是没打开
        var p3 = parts[2] || '0px';
        var detailsOpen = !(p3 === '0px' || /minmax\(\s*0px\s*,\s*0px\s*\)/.test(p3));

        // 阈值 100px：收起是 56px，展开是 280px
        var sidebarOpen = sidebarPx === null
          ? root.classList.contains('dsh-sb-open')
          : sidebarPx > 100;

        var key = sidebarOpen + '/' + detailsOpen;
        if (key === last) return;
        last = key;

        root.classList.toggle('dsh-sb-open', sidebarOpen);
        root.classList.toggle('dsh-dt-open', detailsOpen);
      } catch (e) { }
    }

    sync();
    setInterval(sync, 300);
  } catch (e) { }
})();

/* ============================================================
 * computer use 快捷按钮
 * ------------------------------------------------------------
 * 宿主侧的 computer-use 插件是纯工具插件，没有任何界面，所以手机上
 * 「找不到入口」。这里补一个悬浮按钮：点一下 → 选一个动作 →
 * 自动把对应指令填进输入框并发送。
 *
 * 注意：它不直接遥控电脑，只是把「让智能体去做」变成一次点击。
 * 真正干活的是宿主插件的 screen_capture / mouse / keyboard / window。
 *
 * 想关掉：删掉本段，再把 dsh-ext.css 的第 3 节删掉。
 * ============================================================ */
(function () {
  'use strict';
  try {
    if (window.__dshCUInstalled) return;
    window.__dshCUInstalled = true;

    var BTN_ID = 'dsh-cu-btn';
    var MENU_ID = 'dsh-cu-menu';
    var POS_KEY = 'dsh-cu-pos';

    var ACTIONS = [
      { icon: '\uD83D\uDCF7', label: '截图看看', text: '用 screen_capture 截一张屏，然后把图给我看。' },
      { icon: '\uD83E\uDE9F', label: '开了哪些窗口', text: '用 window 列出当前所有打开的窗口。' },
      { icon: '\u2328\uFE0F', label: '帮我打字…', ask: '要输入的文字',
        make: function (v) { return '在当前前台窗口里输入这段文字：' + v; } },
      { icon: '\uD83D\uDDB1\uFE0F', label: '点一下…', ask: '屏幕坐标，如 800,450',
        make: function (v) { return '用 mouse 在屏幕坐标 ' + v + ' 处点击，然后截图确认。'; } },
      { icon: '\uD83D\uDCAC', label: '自定义指令…', ask: '要发给智能体的话',
        make: function (v) { return v; } }
    ];

    function log() { try { console.log.apply(console, ['[dsh-cu]'].concat([].slice.call(arguments))); } catch (e) { } }

    /* ---------- 与网页交互：找到输入框 / 发送按钮 ---------- */

    function composer() {
      var els = document.querySelectorAll('[contenteditable="true"]');
      for (var i = els.length - 1; i >= 0; i--) {
        if (els[i].offsetParent !== null) return els[i];
      }
      return els.length ? els[els.length - 1] : null;
    }

    function sendButton() {
      var bs = document.querySelectorAll('button');
      for (var i = 0; i < bs.length; i++) {
        var l = (bs[i].getAttribute('aria-label') || '') + ' ' + (bs[i].getAttribute('title') || '');
        if (l.indexOf('发送') !== -1 || /send/i.test(l)) return bs[i];
      }
      return null;
    }

    function caretToEnd(el) {
      try {
        var r = document.createRange();
        r.selectNodeContents(el);
        r.collapse(false);
        var s = window.getSelection();
        s.removeAllRanges();
        s.addRange(r);
      } catch (e) { }
    }

    /** 把文字送进输入框（追加，不覆盖用户已输入的内容）。
     *  DSH 的输入框是 Lexical：它异步重渲染，所以不能同步读 innerText 判断成败；
     *  合成 paste 事件它一定会处理（`execCommand('insertText')` 时灵时不灵），
     *  因此 paste 优先，延迟后再确认，没进去才退回 execCommand。
     *  @returns 目标输入框元素，找不到返回 null */
    function putText(text) {
      var el = composer();
      if (!el) return null;
      try { el.focus(); } catch (e) { }
      caretToEnd(el);
      try {
        var dt = new DataTransfer();
        dt.setData('text/plain', text);
        el.dispatchEvent(new ClipboardEvent('paste', {
          clipboardData: dt, bubbles: true, cancelable: true
        }));
      } catch (e) { }
      return el;
    }

    function composerText() {
      var el = composer();
      return el ? (el.innerText || '').trim() : '';
    }

    function toast(msg) {
      try {
        var t = document.createElement('div');
        t.textContent = msg;
        t.style.cssText = 'position:fixed;left:50%;bottom:200px;transform:translateX(-50%);'
          + 'z-index:2147483600;background:rgba(0,0,0,.84);color:#fff;font-size:13px;'
          + 'padding:9px 16px;border-radius:20px;max-width:80vw;text-align:center;pointer-events:none';
        document.body.appendChild(t);
        setTimeout(function () { try { t.remove(); } catch (e) { } }, 2800);
      } catch (e) { }
    }

    /** 填入并发送。发送按钮在空输入时是 disabled，所以要等文字真正进入后再点。 */
    function submit(text) {
      if (!text) return;
      if (!putText(text)) { toast('没找到输入框，请手动输入'); return; }

      // 等 Lexical 重渲染完再确认（它是异步的）
      setTimeout(function () {
        if (!composerText()) {
          // paste 没生效，退回 execCommand
          try { document.execCommand('insertText', false, text); } catch (e) { }
        }
        setTimeout(function () {
          if (!composerText()) { toast('没填进去，请手动输入'); return; }
          var b = sendButton();
          if (b && !b.disabled) b.click();
          else toast('已填入输入框，请手动点发送');
        }, 220);
      }, 260);
    }

    /* ---------- 菜单 ---------- */

    var docHandler = null;

    function closeMenu() {
      var m = document.getElementById(MENU_ID);
      if (m) m.remove();
      if (docHandler) {
        document.removeEventListener('touchstart', docHandler, true);
        document.removeEventListener('mousedown', docHandler, true);
        docHandler = null;
      }
    }

    function openMenu(anchor) {
      closeMenu();
      var m = document.createElement('div');
      m.id = MENU_ID;

      ACTIONS.forEach(function (a) {
        var item = document.createElement('button');
        item.type = 'button';
        item.className = 'dsh-cu-item';
        item.textContent = a.icon + '  ' + a.label;
        item.addEventListener('click', function (ev) {
          ev.preventDefault();
          ev.stopPropagation();
          if (!a.ask) { closeMenu(); submit(a.text); return; }

          var old = m.querySelector('.dsh-cu-ask');
          if (old) { old.remove(); if (old.dataset.for === a.label) return; }

          var wrap = document.createElement('div');
          wrap.className = 'dsh-cu-ask';
          wrap.dataset.for = a.label;
          var inp = document.createElement('input');
          inp.type = 'text';
          inp.placeholder = a.ask;
          var go = document.createElement('button');
          go.type = 'button';
          go.textContent = '发送';
          function fire() {
            var v = (inp.value || '').trim();
            if (!v) { inp.focus(); return; }
            closeMenu();
            submit(a.make(v));
          }
          go.addEventListener('click', function (e2) { e2.preventDefault(); e2.stopPropagation(); fire(); });
          inp.addEventListener('keydown', function (e2) { if (e2.key === 'Enter') { e2.preventDefault(); fire(); } });
          wrap.appendChild(inp);
          wrap.appendChild(go);
          m.appendChild(wrap);
          setTimeout(function () { inp.focus(); }, 40);
        });
        m.appendChild(item);
      });

      document.body.appendChild(m);

      // 默认贴在按钮上方；上方放不下就放到下方
      var r = anchor.getBoundingClientRect();
      var mw = m.offsetWidth, mh = m.offsetHeight;
      var left = Math.max(8, Math.min(r.right - mw, window.innerWidth - mw - 8));
      var top = r.top - mh - 10;
      if (top < 8) top = Math.min(window.innerHeight - mh - 8, r.bottom + 10);
      m.style.left = left + 'px';
      m.style.top = Math.max(8, top) + 'px';

      docHandler = function (ev) {
        if (m.contains(ev.target)) return;
        closeMenu();
      };
      setTimeout(function () {
        document.addEventListener('touchstart', docHandler, true);
        document.addEventListener('mousedown', docHandler, true);
      }, 0);
    }

    /* ---------- 悬浮按钮 ---------- */

    function applyPos(btn) {
      var pos = null;
      try { pos = JSON.parse(localStorage.getItem(POS_KEY) || 'null'); } catch (e) { }
      if (pos && typeof pos.left === 'number' && typeof pos.top === 'number') {
        btn.style.left = pos.left + 'px';
        btn.style.top = pos.top + 'px';
        btn.style.right = 'auto';
        btn.style.bottom = 'auto';
      } else {
        // 默认放在图片按钮上方（图片按钮是 right:14 / bottom:124）
        btn.style.right = '14px';
        btn.style.bottom = '176px';
      }
    }

    function mount() {
      if (!document.body) return;
      if (document.getElementById(BTN_ID)) return;

      var btn = document.createElement('button');
      btn.id = BTN_ID;
      btn.type = 'button';
      btn.title = 'computer use（可拖动）';
      btn.textContent = '\uD83D\uDDA5';   // 🖥
      applyPos(btn);

      var dragging = false, moved = false, sx = 0, sy = 0, ol = 0, ot = 0;

      function onDown(ev) {
        var p = ev.touches ? ev.touches[0] : ev;
        dragging = true; moved = false;
        sx = p.clientX; sy = p.clientY;
        var r = btn.getBoundingClientRect();
        ol = r.left; ot = r.top;
        btn.style.left = ol + 'px';
        btn.style.top = ot + 'px';
        btn.style.bottom = 'auto';
      }

      function onMove(ev) {
        if (!dragging) return;
        var p = ev.touches ? ev.touches[0] : ev;
        var dx = p.clientX - sx, dy = p.clientY - sy;
        if (Math.abs(dx) > 6 || Math.abs(dy) > 6) moved = true;
        if (!moved) return;
        btn.style.left = Math.max(0, Math.min(window.innerWidth - 44, ol + dx)) + 'px';
        btn.style.top = Math.max(0, Math.min(window.innerHeight - 44, ot + dy)) + 'px';
        if (ev.cancelable) ev.preventDefault();
      }

      function onUp() {
        if (!dragging) return;
        dragging = false;
        if (moved) {
          try {
            localStorage.setItem(POS_KEY, JSON.stringify({
              left: parseFloat(btn.style.left) || 0,
              top: parseFloat(btn.style.top) || 0
            }));
          } catch (e) { }
        }
      }

      btn.addEventListener('touchstart', onDown, { passive: true });
      btn.addEventListener('touchmove', onMove, { passive: false });
      btn.addEventListener('touchend', onUp);
      btn.addEventListener('touchcancel', onUp);
      btn.addEventListener('mousedown', function (ev) {
        onDown(ev);
        document.addEventListener('mousemove', onMove);
        document.addEventListener('mouseup', function up() {
          document.removeEventListener('mousemove', onMove);
          document.removeEventListener('mouseup', up);
          onUp();
        });
      });
      btn.addEventListener('click', function (ev) {
        ev.preventDefault();
        ev.stopPropagation();
        if (moved) return;
        if (document.getElementById(MENU_ID)) closeMenu();
        else openMenu(btn);
      });

      document.body.appendChild(btn);
      log('computer use 按钮已就绪');
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount);
    else mount();
    setTimeout(mount, 1200);
    setTimeout(mount, 3000);

    // 调试用：控制台里可 __dshCU.putText('...') 只填不发，__dshCU.submit('...') 填并发送
    window.__dshCU = {
      putText: putText, submit: submit, composerText: composerText,
      openMenu: openMenu, closeMenu: closeMenu,
      composer: composer, sendButton: sendButton
    };
  } catch (e) { }
})();

