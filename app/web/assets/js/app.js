





(function () {
  'use strict';

  var D = window.SOTV_DATA;
  var $ = function (id) { return document.getElementById(id); };
  var qsa = function (sel) { return Array.prototype.slice.call(document.querySelectorAll(sel)); };

  var state = {
    screen: 'quiz',
    lockReason: 'quota',
    round: null,
    wordIndex: 0,
    roundsDoneToday: 0,
    lastReward: null
  };

  
  function show(name, params) {
    var el = $('screen-' + name);
    if (!el) { el = $('screen-quiz'); name = 'quiz'; }

    qsa('.screen').forEach(function (s) { s.className = 'screen'; });
    el.className = 'screen active';
    state.screen = name;
    document.body.className = 'on-' + name;

    if (name === 'quiz')  { startRound(); }
    if (name === 'lock')  { renderLock((params && params.reason) || state.lockReason); }
    if (name === 'words') { renderWord(); }
    if (name === 'pair')  { startPair(); }

    focusDefault();
  }

  
  function showEmptyBank() {
    $('qText').innerHTML = '题库还是空的';
    $('qOptions').innerHTML = '';
    $('qDots').innerHTML = '';
    $('qIndex').innerHTML = '0';
    toast('用手机扫码，在管理页里加题或导入题库');
  }

  function startRound() {
    if (!D.bank || !D.bank.length) { showEmptyBank(); return; }
    if (window.SOTV_TRACK) { window.SOTV_TRACK('quiz_start', { level: D.rule.level }); }
    var pool = D.bank.slice(0);
    var uniq = [], seen = {};
    var step = 3;
    for (var i = 0; i < pool.length && uniq.length < D.rule.questionsPerRound; i++) {
      var it = pool[(i * step + 1) % pool.length];
      if (!seen[it.q]) { seen[it.q] = 1; uniq.push(it); }
    }
    state.round = { items: uniq, index: 0, correct: 0, locked: 0, startedAt: Date.now(), results: [] };
    renderQuestion();
  }

  function renderQuestion() {
    var r = state.round, item = r.items[r.index];

    var dots = '';
    for (var i = 0; i < r.items.length; i++) {
      dots += '<span class="dot ' + (i < r.index ? 'done' : (i === r.index ? 'now' : '')) + '"></span>';
    }
    $('qDots').innerHTML = dots;

    $('qIndex').innerHTML = String(r.index + 1);
    $('qText').innerHTML = item.q;

    var letters = ['A', 'B', 'C', 'D', 'E', 'F'];
    var twoCol = item.a.length <= 4;
    var html = '';
    for (var k = 0; k < item.a.length; k++) {
      html += '<div class="option focusable' + (twoCol ? '' : ' wide') + '" data-opt="' + k + '">' +
                '<span class="letter">' + letters[k] + '</span>' +
                '<span class="opt-text">' + item.a[k] + '</span>' +
              '</div>';
    }
    $('qOptions').innerHTML = html;

    if (!twoCol) {
      Array.prototype.slice.call($('qOptions').children).forEach(function (c) {
        c.style.width = '100%';
        c.style.marginRight = '0';
      });
    }
    focusDefault();
  }

  function answer(idx) {
    var r = state.round;
    if (!r || r.locked) { return; }
    r.locked = 1;

    var item = r.items[r.index];
    var opts = qsa('#qOptions .option');
    var right = (idx === item.k);

    r.results.push(right);
    if (right) {
      opts[idx].className += ' right';
      r.correct++;
    } else {
      opts[idx].className += ' wrong';
      opts[item.k].className += ' right';
    }

    window.setTimeout(function () {
      r.locked = 0;
      if (r.index < r.items.length - 1) {
        r.index++;
        renderQuestion();
      } else {
        finishRound();
      }
    }, right ? 520 : 1100);
  }

  function finishRound() {
    var r = state.round;
    var need = Math.ceil(r.items.length * (D.rule.passRatio || 0.6));
    var passed = r.correct >= need;
    var minutes = D.rule.sessionMinutes;

    if (passed) {
      state.roundsDoneToday++;
      updatePill();
      state.lastReward = { minutes: minutes, correct: r.correct, total: r.items.length };
      if (window.SOTV_TRACK) { window.SOTV_TRACK('quiz_pass', { correct: r.correct, total: r.items.length, minutes: minutes }); }
      reportRound(r, true);
      notifyNative('startWatch', [minutes]);
      if (state.roundsDoneToday >= D.rule.roundsPerDay) {
        toast('今天的题全部做完了！已解锁 ' + minutes + ' 分钟，去看吧');
        window.setTimeout(function () { notifyNative('exitToLauncher', []); }, 1800);
      } else {
        toast('答对 ' + r.correct + ' / ' + r.items.length + ' 题 · 已解锁 ' + minutes + ' 分钟电视时间');
        window.setTimeout(function () { notifyNative('exitToLauncher', []); }, 1400);
      }
    } else {
      toast('答对 ' + r.correct + ' / ' + r.items.length + ' 题 · 需要答对 ' + need + ' 题才能解锁');
      if (window.SOTV_TRACK) { window.SOTV_TRACK('quiz_fail', { correct: r.correct, total: r.items.length }); }
      reportRound(r, false);
      window.setTimeout(startRound, 1600); 
    }
  }

  
  var LOCK = {
    quota: {
      icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round">' +
            '<rect x="4" y="10" width="16" height="10" rx="2.6"/><path d="M8 10V7.2a4 4 0 0 1 8 0V10"/>' +
            '<circle cx="12" cy="15" r="1.3" fill="currentColor" stroke="none"/></svg>',
      title: '今天的电视时间<em>用完啦</em>',
      desc: '今天 40 分钟都看完了，再做题目也不能解锁。<br>明天 <b>18:30</b> 再来吧。',
      actions: '<button class="btn btn-primary focusable" data-act="study" data-default="1">我去学习了</button>' +
               '<button class="btn btn-ghost focusable" data-act="tv-off">关闭电视</button>',
      note: '电视已锁定 · 家长可以在手机上随时调整'
    },
    timeup: {
      icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round">' +
            '<circle cx="12" cy="12" r="8.4"/><path d="M12 7.4V12l3.1 1.9"/></svg>',
      title: '这一集<em>先看到这里</em>',
      desc: '做完这组学习任务，可以再看 <b>10 分钟</b>。<br>准备好了就点下面的按钮。',
      actions: '<button class="btn btn-primary focusable" data-act="to-quiz" data-default="1">开始学习任务</button>' +
               '<button class="btn btn-ghost focusable" data-act="study">我去学习了</button>',
      note: '电视已锁定 · 家长端能看到你的选择'
    },
    schedule: {
      icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round">' +
            '<circle cx="12" cy="12" r="4"/><path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6 7 7M17 17l1.4 1.4M18.4 5.6 17 7M7 17l-1.4 1.4"/></svg>',
      title: '还没到<em>看电视的时间</em>',
      desc: '今天的电视时间是 <b>18:30 – 20:00</b>。<br>现在可以先去学一会儿。',
      actions: '<button class="btn btn-primary focusable" data-act="study" data-default="1">我去学习了</button>' +
               '<button class="btn btn-ghost focusable" data-act="tv-off">关闭电视</button>',
      note: '电视已锁定 · 家长可以在手机上调整时段'
    }
  };

  function renderLock(reason) {
    var g = LOCK[reason] || LOCK.quota;
    if (window.SOTV_TRACK) { window.SOTV_TRACK('lock_view', { reason: reason }); }
    state.lockReason = reason;
    $('lockIcon').innerHTML = g.icon;
    $('lockTitle').innerHTML = g.title;
    $('lockDesc').innerHTML = g.desc;
    $('lockActions').innerHTML = g.actions;
    $('lockNote').innerHTML = g.note;
  }

  
  function renderWord() {
    var list = D.words, i = state.wordIndex % list.length;
    var it = list[i];
    $('wIndex').innerHTML = String(i + 1);
    $('wWord').innerHTML = it.w;
    $('wPhon').innerHTML = it.phon;
    $('wMean').innerHTML = it.mean;
    $('wSentence').innerHTML = it.s;
  }

  
  function act(name) {
    if (name === 'study') {
      
      notifyNative('studyStarted', []);
      if (window.SOTV_TRACK) { window.SOTV_TRACK('study_click', {}); }
      toast('已记录：开始学习 · 电视保持锁定');
      return;
    }
    if (name === 'tv-off') {
      notifyNative('exitToLauncher', []);
      return;
    }
    if (name === 'to-quiz' || name === 'quiz') { go('quiz'); return; }
    if (name === 'lock') { go('lock'); return; }
    if (name === 'word-ok') { state.wordIndex++; renderWord(); focusDefault(); return; }
    if (name === 'word-again') { renderWord(); return; }
  }

  function notifyNative(method, args) {
    try {
      if (window.SOTVNative && typeof window.SOTVNative[method] === 'function') {
        window.SOTVNative[method].apply(window.SOTVNative, args || []);
      }
    } catch (e) {  }
  }

  
  function reportRound(r, passed) {
    var api = window.SOTV_API;
    if (!api || !api.online) { return; }
    var items = [];
    for (var i = 0; i < r.items.length; i++) {
      items.push({ qid: r.items[i].id || '', subject: r.items[i].subject, correct: !!r.results[i] });
    }
    api.report('quiz', { items: items, total: r.items.length, correct: r.correct, passed: passed });
    if (passed) {
      api.report('quiz_pass', { total: r.items.length, correct: r.correct, minutes: D.rule.sessionMinutes });
      api.report('watch', { minutes: D.rule.sessionMinutes });
    }
  }

  function renderPair(code) {
    $('pairCode').innerHTML = code || '······';
    $('pairState').innerHTML = code ? '配对后这台电视就交给家长管理了' : '正在获取配对码…';
  }

  function startPair() {
    var native = window.SOTVNative;
    var info = null;
    try { info = native && native.lanInfo ? JSON.parse(native.lanInfo()) : null; } catch (e) { info = null; }

    if (!info || !info.ip) {
      $('pairUrl').innerHTML = '手机浏览器打开电视地址';
      $('pairToken').innerHTML = '—';
      return;
    }
    var url = 'http://' + info.ip + ':' + info.port + '/admin/?h=' + info.ip + ':' + info.port + '&t=' + info.token;
    $('pairUrl').innerHTML = info.ip + ':' + info.port + '/admin/';
    $('pairToken').innerHTML = info.token;

    var box = $('pairQr');
    try {
      var qr = window.qrcode(0, 'M');
      qr.addData(url);
      qr.make();
      box.innerHTML = qr.createImgTag(8, 8);
    } catch (e) {
      box.innerHTML = '<span style="color:#111;font-size:16px">扫码失败，手动输地址</span>';
    }
  }

  
  function updatePill() {
    var left = Math.max(0, D.rule.roundsPerDay - state.roundsDoneToday);
    $('topPill').innerHTML = '答对 ' + D.rule.questionsPerRound + ' 题 · 可看 ' + D.rule.sessionMinutes + ' 分钟（还剩 ' + left + ' 组）';
  }

  function applyConfig(c) {
    if (!c) { return; }
    if (c.dailyMinutes)       { D.rule.dailyMinutes       = +c.dailyMinutes; }
    if (c.sessionMinutes)     { D.rule.sessionMinutes     = +c.sessionMinutes; }
    if (c.questionsPerRound)  { D.rule.questionsPerRound  = +c.questionsPerRound; }
    if (c.roundsPerDay)       { D.rule.roundsPerDay       = +c.roundsPerDay; }
    if (c.windowStart)        { D.rule.windowStart        = c.windowStart; }
    if (c.windowEnd)          { D.rule.windowEnd          = c.windowEnd; }
    if (c.passRatio)          { D.rule.passRatio          = +c.passRatio; }
    $('topPill').innerHTML = '答对 ' + D.rule.questionsPerRound + ' 题 · 可看 ' + D.rule.sessionMinutes + ' 分钟';
    updatePill();
  }

  function initLive() {
    var api = window.SOTV_API;
    if (!api) { return; }
    api.hello(function () {});
    api.loadState(function (err, st) {
      if (!err && st) {
        applyConfig(st.config);
        if (st.today) {
          D.today.usedMinutes = st.today.usedMinutes || 0;
          D.today.answered    = st.today.answered    || 0;
        }
        if (typeof st.roundsDoneToday === 'number') {
          state.roundsDoneToday = st.roundsDoneToday;
          updatePill();
        }
      }
      api.questions(200, null, function (err2, items) {
        if (!err2 && items && items.length) { D.bank = items; }
        if (state.screen === 'quiz') { startRound(); }
      });
    });
    window.setInterval(function () {
      api.loadState(function (err, st) {
        if (err || !st) { return; }
        applyConfig(st.config);
      });
    }, 20000);
  }

  
  var toastTimer = null;
  function toast(text) {
    var el = $('toast');
    if (!el) {
      el = document.createElement('div');
      el.id = 'toast';
      el.className = 'toast';
      document.body.appendChild(el);
    }
    el.innerHTML = text;
    el.className = 'toast show';
    if (toastTimer) { window.clearTimeout(toastTimer); }
    toastTimer = window.setTimeout(function () { el.className = 'toast'; }, 2600);
  }

  
  var FOCUS_CLASS = 'focused';

  function focusables() {
    var scr = document.querySelector('.screen.active');
    if (!scr) { return []; }
    return Array.prototype.slice.call(scr.querySelectorAll('.focusable'));
  }

  function focusIndex() {
    var list = focusables();
    for (var i = 0; i < list.length; i++) {
      if (list[i].className.indexOf(FOCUS_CLASS) >= 0) { return i; }
    }
    return -1;
  }

  function setFocus(idx) {
    var list = focusables();
    if (!list.length) { return; }
    if (idx < 0) { idx = 0; }
    if (idx > list.length - 1) { idx = list.length - 1; }
    for (var i = 0; i < list.length; i++) {
      list[i].className = list[i].className.replace(' ' + FOCUS_CLASS, '').replace(FOCUS_CLASS, '');
    }
    list[idx].className += ' ' + FOCUS_CLASS;
  }

  function focusDefault() {
    var list = focusables();
    if (!list.length) { return; }
    var want = -1;
    for (var i = 0; i < list.length; i++) {
      if (list[i].getAttribute('data-default')) { want = i; break; }
    }
    setFocus(want >= 0 ? want : 0);
  }

  function rectOf(el) {
    var r = el.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  }

  function move(dir) {
    var list = focusables();
    if (!list.length) { return; }
    var cur = focusIndex();
    if (cur < 0) { focusDefault(); return; }

    var c = rectOf(list[cur]);
    var best = -1, bestScore = 1e9;

    for (var i = 0; i < list.length; i++) {
      if (i === cur) { continue; }
      var p = rectOf(list[i]);
      var dx = p.x - c.x, dy = p.y - c.y;
      var primary, secondary;

      if (dir === 'left')  { if (dx > -8) { continue; } primary = -dx; secondary = Math.abs(dy); }
      if (dir === 'right') { if (dx < 8)  { continue; } primary = dx;  secondary = Math.abs(dy); }
      if (dir === 'up')    { if (dy > -8) { continue; } primary = -dy; secondary = Math.abs(dx); }
      if (dir === 'down')  { if (dy < 8)  { continue; } primary = dy;  secondary = Math.abs(dx); }

      var score = primary + secondary * 1.8;
      if (score < bestScore) { bestScore = score; best = i; }
    }
    if (best >= 0) { setFocus(best); }
  }

  function activate() {
    var list = focusables();
    var i = focusIndex();
    if (i < 0) { focusDefault(); return; }
    var el = list[i];
    var opt = el.getAttribute('data-opt');
    if (opt !== null) { answer(parseInt(opt, 10)); return; }
    var goTo = el.getAttribute('data-go');
    if (goTo) { go(goTo); return; }
    act(el.getAttribute('data-act'));
  }

  
  var updateState = null;

  function showUpdate(info) {
    if (!info || !info.latest) { return; }
    updateState = info;
    $('updateText').innerHTML = '发现新版本 v' + info.latest;
    $('updateTip').className = 'update-tip show';
    if (window.SOTV_TRACK) { window.SOTV_TRACK('update_notice', { latest: info.latest, force: false }); }
  }

  window.SOTV_UPDATE = showUpdate;

  function bindUpdate() {
    var close = $('updateClose');
    var go = $('updateGo');
    if (close) {
      close.addEventListener('click', function () {
        $('updateTip').className = 'update-tip';
      });
    }
    if (go) {
      go.addEventListener('click', function () {
        var native = window.SOTVNative;
        if (updateState && native && native.installUpdate) {
          native.installUpdate(updateState.url, updateState.latest, updateState.notes || '');
          if (window.SOTV_TRACK) { window.SOTV_TRACK('update_install_click', { latest: updateState.latest }); }
        } else if (updateState && native && native.openUrl) {
          native.openUrl(updateState.url);
        }
      });
    }
    
    try {
      var bridge = window.SOTVNative;
      if (bridge && bridge.updateInfo) {
        var raw = bridge.updateInfo();
        if (raw) { showUpdate(JSON.parse(raw)); }
      }
    } catch (e) {  }
  }

  
  function go(name, reason) {
    if (!name) { return; }
    if (name === 'lock' && reason) { state.lockReason = reason; }
    location.hash = '#/' + name + (reason ? '?reason=' + reason : '');
  }

  function parseHash() {
    var h = (location.hash || '#/quiz').replace('#/', '');
    var parts = h.split('?');
    var name = parts[0] || 'quiz';
    var params = {};
    if (parts[1]) {
      parts[1].split('&').forEach(function (kv) {
        var p = kv.split('=');
        params[p[0]] = decodeURIComponent(p[1] || '');
      });
    }
    return { name: name, params: params };
  }

  function route() {
    var r = parseHash();
    show(r.name, r.params);
  }

  
  window.TV = {
    move: move,
    select: activate,
    back: function () { if (state.screen !== 'quiz') { go('quiz'); } },
    go: function (name, reason) { go(name, reason); },
    lock: function (reason) { go('lock', reason || 'quota'); },
    state: state
  };

  
  document.addEventListener('keydown', function (e) {
    var k = e.keyCode;
    if (k === 37) { move('left'); }
    else if (k === 39) { move('right'); }
    else if (k === 38) { move('up'); }
    else if (k === 40) { move('down'); }
    else if (k === 13 || k === 23) { activate(); }
    else if (k === 27 || k === 461 || k === 8) { window.TV.back(); }
    else { return; }
    e.preventDefault();
  }, false);

  document.addEventListener('click', function (e) {
    var t = e.target;
    while (t && t !== document.body) {
      if (t.className && String(t.className).indexOf('focusable') >= 0) {
        setFocus(focusables().indexOf(t));
        activate();
        return;
      }
      t = t.parentNode;
    }
  }, false);

  window.addEventListener('hashchange', route, false);

  
  function boot() {
    $('clock').innerHTML = nowHM();
    window.setInterval(function () { $('clock').innerHTML = nowHM(); }, 15000);
    if (!location.hash) { location.hash = '#/quiz'; }
    route();
    initLive();
    bindUpdate();
  }

  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function nowHM() {
    var d = new Date();
    return pad(d.getHours()) + ':' + pad(d.getMinutes());
  }

  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    window.setTimeout(boot, 0);
  } else {
    document.addEventListener('DOMContentLoaded', boot, false);
  }
})();
