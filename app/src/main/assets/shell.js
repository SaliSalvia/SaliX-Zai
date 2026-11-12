/* SaliX-Z shell injection — adds mobile-friendly preview/copy actions to
 * code blocks produced by the models inside chat.z.ai, without touching the
 * site's own behaviour. Everything runs defensively: any failure is silent.
 */
(function () {
  'use strict';
  if (window.__SALIX_SHELL__) return;
  window.__SALIX_SHELL__ = true;

  /* ── styles ─────────────────────────────────────────────────────── */
  var css = [
    'html{-webkit-text-size-adjust:100%}',
    '*{-webkit-tap-highlight-color:transparent}',
    'pre.salix-pre{position:relative !important;padding-top:36px !important}',
    '.salix-actions{position:absolute;top:6px;right:8px;display:flex;gap:6px;z-index:6;direction:rtl}',
    '.salix-btn{background:rgba(108,75,224,.95);color:#fff;border:0;border-radius:8px;',
    '  padding:5px 12px;font:600 11px/1.2 -apple-system,"Segoe UI",Tahoma,sans-serif;',
    '  cursor:pointer;box-shadow:0 2px 8px rgba(0,0,0,.35)}',
    '.salix-btn.ghost{background:rgba(20,17,31,.88);color:#B9B4C1;',
    '  border:1px solid #393344;box-shadow:none}',
    '.salix-btn:active{transform:scale(.94)}'
  ].join('\n');

  function injectStyle() {
    try {
      if (document.getElementById('salix-style')) return;
      var st = document.createElement('style');
      st.id = 'salix-style';
      st.textContent = css;
      (document.head || document.documentElement).appendChild(st);
    } catch (e) {}
  }

  /* ── language detection ─────────────────────────────────────────── */
  function detectLang(pre) {
    try {
      var code = pre.querySelector('code');
      if (code) {
        var m = (code.className || '').match(/language-([\w+#.-]+)/);
        if (m) return m[1].toLowerCase();
      }
      var m2 = (pre.className || '').match(/language-([\w+#.-]+)/);
      if (m2) return m2[1].toLowerCase();
    } catch (e) {}
    return '';
  }

  /* ── decorate every code block once ─────────────────────────────── */
  function decorate() {
    injectStyle();
    var pres = document.querySelectorAll('pre');
    for (var i = 0; i < pres.length; i++) {
      var pre = pres[i];
      if (pre.getAttribute('data-salix')) continue;
      var codeEl = pre.querySelector('code');
      if (!codeEl) continue;
      pre.setAttribute('data-salix', '1');
      pre.classList.add('salix-pre');

      var bar = document.createElement('div');
      bar.className = 'salix-actions';

      var pv = document.createElement('button');
      pv.className = 'salix-btn';
      pv.textContent = 'پیش‌نمایش';
      pv.onclick = function (ev) {
        ev.stopPropagation(); ev.preventDefault();
        try {
          var p = this.closest ? this.closest('pre') : null;
          var c = p ? p.querySelector('code') : codeEl;
          SaliXBridge.openCode(c.textContent, detectLang(p || pre));
        } catch (e) {}
        return false;
      };

      var cp = document.createElement('button');
      cp.className = 'salix-btn ghost';
      cp.textContent = 'کپی';
      cp.onclick = function (ev) {
        ev.stopPropagation(); ev.preventDefault();
        try {
          var p = this.closest ? this.closest('pre') : null;
          var c = p ? p.querySelector('code') : codeEl;
          SaliXBridge.copyText(c.textContent);
        } catch (e) {}
        return false;
      };

      bar.appendChild(pv);
      bar.appendChild(cp);
      pre.appendChild(bar);
    }
  }

  /* ── observe streaming updates (debounced) ──────────────────────── */
  var t = null;
  function schedule() {
    if (t) clearTimeout(t);
    t = setTimeout(function () { t = null; decorate(); }, 350);
  }

  try {
    var boot = function () {
      decorate();
      var mo = new MutationObserver(schedule);
      mo.observe(document.body, { childList: true, subtree: true });
    };
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', boot);
    } else {
      boot();
    }
  } catch (e) {}
})();
