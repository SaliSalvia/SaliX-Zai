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
    'pre.salix-pre{position:relative !important;padding-top:38px !important}',
    '.salix-actions{position:absolute;top:7px;right:8px;display:flex;gap:6px;',
    '  z-index:6;direction:rtl;opacity:.94}',
    '.salix-btn{display:inline-flex;align-items:center;gap:4px;',
    '  background:rgba(108,75,224,.95);color:#fff;border:0;border-radius:9px;',
    '  padding:6px 12px;font:600 11px/1.35 -apple-system,"Segoe UI",Tahoma,sans-serif;',
    '  cursor:pointer;box-shadow:0 2px 10px rgba(0,0,0,.4);',
    '  -webkit-backdrop-filter:blur(6px);backdrop-filter:blur(6px);',
    '  transition:transform .12s ease,background .12s ease}',
    '.salix-btn.ghost{background:rgba(20,17,31,.9);color:#C9C4D2;',
    '  border:1px solid #3B3448;box-shadow:none}',
    '.salix-btn:active{transform:scale(.94)}',
    '.salix-btn.done{background:rgba(79,201,139,.95);border-color:transparent;color:#08110C}'
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

  function codeOf(pre) {
    try {
      var code = pre.querySelector('code');
      return code ? code.textContent : '';
    } catch (e) {
      return '';
    }
  }

  function makeButton(label, ghost) {
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'salix-btn' + (ghost ? ' ghost' : '');
    b.textContent = label;
    b.setAttribute('aria-label', label);
    return b;
  }

  /* ── decorate every code block once ─────────────────────────────── */
  function decorate() {
    injectStyle();
    var pres = document.querySelectorAll('pre');
    for (var i = 0; i < pres.length; i++) {
      var pre = pres[i];
      if (pre.getAttribute('data-salix')) continue;
      if (!pre.querySelector('code')) continue;
      pre.setAttribute('data-salix', '1');
      pre.classList.add('salix-pre');

      var bar = document.createElement('div');
      bar.className = 'salix-actions';

      var preview = makeButton('پیش‌نمایش', false);
      preview.setAttribute('data-salix-action', 'preview');
      var copy = makeButton('کپی', true);
      copy.setAttribute('data-salix-action', 'copy');

      bar.appendChild(preview);
      bar.appendChild(copy);
      pre.appendChild(bar);
    }
  }

  /* ── one delegated handler for the whole page ───────────────────── */
  document.addEventListener('click', function (ev) {
    var target = ev.target;
    var btn = target && target.closest ? target.closest('.salix-btn') : null;
    if (!btn) return;
    ev.preventDefault();
    ev.stopPropagation();

    var pre = btn.closest('pre');
    if (!pre) return;
    var text = codeOf(pre);
    var action = btn.getAttribute('data-salix-action');

    if (action === 'preview') {
      try {
        SaliXBridge.openCode(text, detectLang(pre));
      } catch (e) {}
      return;
    }

    if (action === 'copy') {
      try {
        SaliXBridge.copyText(text);
        var original = btn.textContent;
        btn.classList.add('done');
        btn.textContent = 'کپی شد';
        setTimeout(function () {
          btn.classList.remove('done');
          btn.textContent = original;
        }, 1400);
      } catch (e) {}
    }
  }, true);

  /* ── observe streaming updates (debounced) ──────────────────────── */
  var pending = 0;
  function schedule() {
    if (pending) return;
    pending = requestAnimationFrame(function () {
      pending = 0;
      decorate();
    });
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
