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

    function tryDismiss() {
      try {
        var text = document.body ? (document.body.innerText || '') : '';
        if (text.indexOf('内测声明') === -1) return;
        var btns = document.querySelectorAll('button');
        for (var i = 0; i < btns.length; i++) {
          if ((btns[i].textContent || '').trim() === '继续') {
            btns[i].click();
            return;
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

