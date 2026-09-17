// Bello face controller. Called from Kotlin via FaceView (window.bello.*); calls back via BelloNative.
(function () {
  'use strict';

  var STATES = ['idle', 'listening', 'thinking', 'speaking', 'happy', 'confused', 'sad', 'alert', 'sleepy'];
  // Idle CPU is proportional to how often something animates: every animated frame repaints the
  // face on this device (no GPU compositing in WebView 95 through the rounded clips). Keep idle
  // life sparse and short — see docs/feasibility-results.md, SP-06.
  var BLINK_MIN_MS = 4500, BLINK_SPREAD_MS = 4500;
  var GLANCE_MIN_MS = 18000, GLANCE_SPREAD_MS = 17000, GLANCE_HOLD_MS = 1400;
  var SUBTITLE_TIMEOUT_MS = 12000;
  var LONG_PRESS_MS = 2000;

  var body = document.body;
  var pupil = document.getElementById('pupil');
  var stage = document.getElementById('stage');
  var subtitles = document.getElementById('subtitles');
  var userEl = document.getElementById('user');
  var answerEl = document.getElementById('answer');
  var clockEl = document.getElementById('clock');

  var state = 'idle';
  var subtitleTimer = null;

  function native(name, arg) {
    try {
      if (window.BelloNative && BelloNative[name]) {
        arg === undefined ? BelloNative[name]() : BelloNative[name](String(arg));
      }
    } catch (e) { /* page opened outside the app */ }
  }

  function setState(next) {
    if (STATES.indexOf(next) < 0) return;
    state = next;
    body.className = next;
    if (next !== 'idle') pupil.style.transform = '';
  }

  // --- Idle life: event-driven, no continuous animation loops -------------------------------
  function scheduleBlink() {
    setTimeout(function () {
      if (state !== 'sleepy') {
        body.classList.add('blink');
        setTimeout(function () { body.classList.remove('blink'); }, 140);
      }
      scheduleBlink();
    }, BLINK_MIN_MS + Math.random() * BLINK_SPREAD_MS);
  }

  function scheduleGlance() {
    setTimeout(function () {
      if (state === 'idle') {
        var x = Math.round((Math.random() * 2 - 1) * 34);
        var y = Math.round((Math.random() * 2 - 1) * 18);
        pupil.style.transform = 'translate(' + x + 'px,' + y + 'px)';
        setTimeout(function () { if (state === 'idle') pupil.style.transform = ''; }, GLANCE_HOLD_MS);
      }
      scheduleGlance();
    }, GLANCE_MIN_MS + Math.random() * GLANCE_SPREAD_MS);
  }

  // --- Clock, updated once per minute ----------------------------------------------------------
  function two(n) { return (n < 10 ? '0' : '') + n; }
  function tickClock() {
    var d = new Date();
    clockEl.textContent = two(d.getHours()) + ':' + two(d.getMinutes());
    setTimeout(tickClock, (60 - d.getSeconds()) * 1000 - d.getMilliseconds() + 50);
  }

  // --- Subtitles -------------------------------------------------------------------------------
  function touchSubtitles() {
    subtitles.classList.remove('hidden');
    clearTimeout(subtitleTimer);
    subtitleTimer = setTimeout(function () { subtitles.classList.add('hidden'); }, SUBTITLE_TIMEOUT_MS);
  }
  function showUser(text) { userEl.textContent = text || ''; touchSubtitles(); }
  function showAnswer(text) { answerEl.textContent = text || ''; touchSubtitles(); }
  function clearSubtitles() { userEl.textContent = ''; answerEl.textContent = ''; }

  // --- Touch: tap and long press ---------------------------------------------------------------
  var pressTimer = null;
  var longPressed = false;
  function pressStart(e) {
    longPressed = false;
    clearTimeout(pressTimer);
    pressTimer = setTimeout(function () { longPressed = true; native('onLongPress'); }, LONG_PRESS_MS);
    if (e.cancelable) e.preventDefault();
  }
  function pressEnd() {
    clearTimeout(pressTimer);
    if (!longPressed) native('onTap');
  }
  document.addEventListener('touchstart', pressStart, { passive: false });
  document.addEventListener('touchend', pressEnd);
  document.addEventListener('touchcancel', function () { clearTimeout(pressTimer); });
  document.addEventListener('mousedown', function (e) { if (!('ontouchstart' in window)) pressStart(e); });
  document.addEventListener('mouseup', function () { if (!('ontouchstart' in window)) pressEnd(); });

  window.bello = {
    setState: setState,
    showUser: showUser,
    showAnswer: showAnswer,
    clearSubtitles: clearSubtitles,
    getState: function () { return state; }
  };

  tickClock();
  scheduleBlink();
  scheduleGlance();
  native('onReady');
})();
