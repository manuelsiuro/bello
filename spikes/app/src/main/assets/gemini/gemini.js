// Gemini Web automation (signed out). Injected before each ask; idempotent.
// Selectors are isolated here so they can be updated without code changes.
window.bello = window.bello || {};
(function (b) {
  var SEL = {
    editor: 'rich-textarea .ql-editor[contenteditable="true"], div.ql-editor[contenteditable="true"], [contenteditable="true"][role="textbox"]',
    send: 'button.send-button, button[aria-label*="Envoyer"], button[aria-label*="Send"]',
    stop: 'button[aria-label*="Interrompre"], button[aria-label*="Arrêter"], button[aria-label*="Stop"], .stop-icon',
    response: 'model-response',
    pending: 'pending-request',
    responseText: 'message-content .markdown-main-panel, message-content .markdown, message-content',
    noise: '.attachment-container, card, weather-card, sources-carousel-inline, sources-carousel, source-footnote, source-inline-chip, .source-chip, button, mat-icon, script, style'
  };
  var DISMISS = /^(pas maintenant|non merci|plus tard|refuser|tout refuser|ignorer|fermer|ok|j'ai compris|not now|no thanks|reject all|dismiss|got it)$/i;

  function log(m) { try { Bello.log(String(m)); } catch (e) {} }
  function done(id, obj) { try { Bello.result(id, JSON.stringify(obj)); } catch (e) {} }

  b.dismissDialogs = function () {
    var n = 0;
    document.querySelectorAll('button, [role="button"]').forEach(function (el) {
      var t = (el.innerText || el.getAttribute('aria-label') || '').trim();
      if (DISMISS.test(t) && el.offsetParent !== null) { el.click(); n++; log('dismissed: ' + t); }
    });
    return n;
  };

  b.blocked = function () {
    var t = (document.body && document.body.innerText || '').slice(0, 5000);
    return /unusual traffic|trafic inhabituel|recaptcha|je ne suis pas un robot|not a robot/i.test(t);
  };

  // Speakable text only: main markdown panel, without source chips/footnotes/widgets.
  function speakable(el) {
    var clone = el.cloneNode(true);
    clone.querySelectorAll(SEL.noise).forEach(function (n) { n.remove(); });
    var out = [];
    (function walk(node) {
      node.childNodes.forEach(function (c) {
        if (c.nodeType === 3) { out.push(c.nodeValue); return; }
        if (c.nodeType !== 1) return;
        var tag = c.tagName;
        if (tag === 'LI') out.push('\n- ');
        walk(c);
        if (/^(P|DIV|LI|H[1-6]|TR|BR|UL|OL|TABLE|BLOCKQUOTE)$/.test(tag)) out.push('\n');
      });
    })(clone);
    return out.join('').replace(/[ \t\u00a0]+/g, ' ').replace(/ *\n */g, '\n').replace(/\n{2,}/g, '\n').trim();
  }

  function lastResponseText() {
    var rs = document.querySelectorAll(SEL.response);
    if (!rs.length) return { count: 0, text: '' };
    var last = rs[rs.length - 1];
    var el = last.querySelector(SEL.responseText) || last;
    return { count: rs.length, text: speakable(el) };
  }

  // Signed out: the side-nav "Nouvelle discussion" opens a confirmation dialog whose
  // own "Nouvelle discussion" button (inside gem-button) actually resets the chat.
  b.newChat = function () {
    var confirm = function () {
      var btns = document.querySelectorAll('gem-button button, mat-dialog-container button');
      for (var i = 0; i < btns.length; i++) {
        if (/nouvelle discussion|new chat/i.test(btns[i].innerText || '') && btns[i].offsetParent) { btns[i].click(); return true; }
      }
      return false;
    };
    if (confirm()) return 'confirm';
    var nav = document.querySelector('a.mat-mdc-list-item[aria-label="Nouvelle discussion"], a[aria-label="Nouvelle discussion"], a[aria-label="New chat"]');
    if (nav) nav.click();
    setTimeout(confirm, 300);
    setTimeout(confirm, 900);
    return nav ? 'nav' : 'none';
  };

  b.ask = function (prompt, id, newChat) {
    var waited = 0, t0Chat = Date.now();
    if (newChat && !document.querySelectorAll(SEL.response).length && !document.querySelector(SEL.pending)) {
      newChat = false; // already an empty chat
    }
    if (newChat) {
      var clicked = b.newChat();
      log('new chat clicked=' + clicked);
      // Wait until the previous conversation is gone before typing.
      waited = -1;
    }
    (function waitEditor() {
      if (waited < 0) {
        var busy = document.querySelectorAll(SEL.response).length || document.querySelector(SEL.pending);
        if (busy && Date.now() - t0Chat < 10000) return setTimeout(waitEditor, 150);
        if (busy) return done(id, { error: 'new chat did not reset' });
        log('chat reset after ' + (Date.now() - t0Chat) + 'ms');
        waited = 0;
      }
      if (b.blocked()) return done(id, { error: 'blocked/captcha' });
      b.dismissDialogs();
      var ed = document.querySelector(SEL.editor);
      if (!ed) {
        if ((waited += 250) > 20000) return done(id, { error: 'editor not found' });
        return setTimeout(waitEditor, 250);
      }
      if (waited) log('editor ready after ' + waited + 'ms');
      send(prompt, id, ed);
    })();
  };

  function send(prompt, id, editor) {
    var before = lastResponseText().count;
    editor.focus();
    document.execCommand('selectAll', false, null);
    document.execCommand('insertText', false, prompt);
    var t0 = Date.now();

    setTimeout(function () {
      var send = document.querySelector(SEL.send);
      if (send && !send.disabled && send.getAttribute('aria-disabled') !== 'true') {
        send.click(); log('clicked send');
      } else {
        editor.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
        log('pressed enter (send ' + (send ? 'disabled' : 'missing') + ')');
      }
      var lastText = '', stable = 0, firstTokenMs = -1;
      var timer = setInterval(function () {
        var elapsed = Date.now() - t0;
        if (b.blocked()) { clearInterval(timer); return done(id, { error: 'blocked/captcha' }); }
        var r = lastResponseText();
        var generating = !!document.querySelector(SEL.stop) || !!document.querySelector(SEL.pending);
        if (r.count > before && r.text) {
          if (firstTokenMs < 0) firstTokenMs = elapsed;
          if (r.text === lastText && !generating) stable++; else stable = 0;
          lastText = r.text;
          if (stable >= 2) {
            clearInterval(timer);
            return done(id, { text: r.text, firstTokenMs: firstTokenMs, totalMs: elapsed });
          }
        }
        if (elapsed > 60000) {
          clearInterval(timer);
          done(id, { error: 'timeout responses=' + r.count + ' before=' + before + ' generating=' + generating });
        }
      }, 500);
    }, 400);
  }
})(window.bello);
