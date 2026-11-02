/* =========================================================================
   StudyOnTV · 家长管理页（由电视自己托管，手机扫码直连）
   连接方式：电视上显示的「设备地址 + 6 位访问码」，或扫描二维码自动带参打开
   所有数据都在这台电视上，本页面只是一个遥控器
   ========================================================================= */
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var LS_HOST = 'sotv_host', LS_TOKEN = 'sotv_token';
  var state = { host: '', token: '', config: null };

  /* ---------------------------------------------------------------- 工具 */
  function toast(msg) {
    var el = $('toast');
    el.textContent = msg;
    el.className = 'toast show';
    setTimeout(function () { el.className = 'toast'; }, 2200);
  }

  function track(name, params) {
    if (window.SOTV_TRACK) { window.SOTV_TRACK(name, params); }
  }

  function api(path, opts) {
    opts = opts || {};
    var url = 'http://' + state.host + path;
    var headers = { 'Content-Type': 'application/json', 'X-Admin-Token': state.token };
    return fetch(url, {
      method: opts.method || 'GET',
      headers: headers,
      body: opts.body ? JSON.stringify(opts.body) : undefined,
      cache: 'no-store'
    }).then(function (res) {
      if (res.status === 204) { return null; }
      return res.json().catch(function () { return {}; });
    });
  }

  /* ---------------------------------------------------------------- 连接 */
  function connect(host, token, silent) {
    state.host = (host || '').replace(/^https?:\/\//, '').replace(/\/.*$/, '');
    state.token = (token || '').trim();
    if (!state.host || !/^\d{1,3}(\.\d{1,3}){3}(:\d+)?$/.test(state.host)) {
      $('connectErr').textContent = '设备地址格式应该像 192.168.1.20:8099';
      return;
    }
    if (!/^\d{6}$/.test(state.token)) {
      $('connectErr').textContent = '访问码是电视上显示的 6 位数字';
      return;
    }
    $('connectErr').textContent = '连接中…';
    track('connect_attempt', { host: state.host });

    api('/api/hello').then(function (hello) {
      if (!hello || hello.app !== 'StudyOnTV') { throw new Error('not_studyontv'); }
      return api('/api/state').then(function (st) {
        if (!st || st.error) { throw new Error(st && st.error ? st.error : 'no_state'); }
        localStorage.setItem(LS_HOST, state.host);
        localStorage.setItem(LS_TOKEN, state.token);
        $('connectCard').classList.add('hidden');
        $('panel').classList.remove('hidden');
        $('connectErr').textContent = '';
        setChip('已连接 · ' + hello.name, true);
        applyState(st);
        track('connect_success', { host: state.host, version: hello.version });
        loadQuestions();
        loadEvents();
      });
    }).catch(function (e) {
      $('connectErr').textContent = (e && e.message === 'unauthorized')
        ? '访问码不对，看一眼电视上的 6 位数字'
        : '连不上：确认手机和电视在同一个 WiFi，地址填对';
      setChip('未连接', false);
      track('connect_fail', { reason: (e && e.message) || 'unknown' });
    });
  }

  function setChip(text, ok) {
    $('deviceChipText').textContent = text;
    $('deviceChip').className = 'chip' + (ok ? ' ok' : '');
  }

  /* ---------------------------------------------------------------- 渲染 */
  function applyState(st) {
    state.config = st.config || {};
    var c = state.config;
    var today = st.today || {};
    var used = today.usedMinutes || 0;
    $('statRemain').textContent = Math.max(0, (c.dailyMinutes || 40) - used);
    $('statQuestions').textContent = st.questions || 0;
    $('statAnswered').textContent = today.answered || 0;
    $('statAccuracy').textContent = (today.accuracy || 0) + '%';
    $('ruleVersion').textContent = '规则版本 v' + (c.version || 1);

    $('dailyMinutes').value = String(c.dailyMinutes || 40);
    $('sessionMinutes').value = String(c.sessionMinutes || 10);
    $('questionsPerRound').value = String(c.questionsPerRound || 5);
    $('level').value = c.level || '三年级';
    $('windowStart').value = c.windowStart || '18:30';
    $('windowEnd').value = c.windowEnd || '20:00';
  }

  function loadQuestions() {
    api('/api/questions').then(function (data) {
      var items = (data && data.items) || [];
      var rows = items.map(function (q) {
        var ans = (q.a && q.a[q.k]) || '';
        return '<tr>' +
          '<td><span class="badge bg-orange-lt">' + esc(q.subject || '') + '</span></td>' +
          '<td><div class="q-stem">' + esc(q.q) + '</div><div class="muted">' + esc(q.level || '') + '</div></td>' +
          '<td class="d-none d-md-table-cell q-ans">' + esc(ans) + '</td>' +
          '<td class="text-end"><button class="btn btn-sm btn-outline-danger" data-del="' + esc(q.id) + '">删除</button></td>' +
          '</tr>';
      });
      $('qList').innerHTML = rows.length ? rows.join('')
        : '<tr><td colspan="4" class="text-center muted py-4">还没有题目，点右上角「新增题目」</td></tr>';
      track('question_list_view', { count: items.length });
    });
  }

  function loadEvents() {
    api('/api/events?limit=20').then(function (data) {
      var items = (data && data.items) || [];
      if (!items.length) { $('eventList').innerHTML = '<span class="muted">今天还没有记录</span>'; return; }
      $('eventList').innerHTML = items.map(function (e) {
        var t = new Date(e.ts);
        var hh = ('0' + t.getHours()).slice(-2) + ':' + ('0' + t.getMinutes()).slice(-2);
        return '<div class="d-flex justify-content-between border-bottom py-2">' +
          '<span>' + esc(label(e.type)) + '</span><span class="muted">' + hh + '</span></div>';
      }).join('');
    });
  }

  function label(type) {
    return ({
      quiz: '完成一组答题',
      watch: '开始看电视',
      study: '孩子选择去学习',
      lock: '电视锁定',
      question_added: '新增了一道题'
    })[type] || type;
  }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
    });
  }

  /* ---------------------------------------------------------------- 操作 */
  function saveRules() {
    var body = {
      dailyMinutes: Number($('dailyMinutes').value),
      sessionMinutes: Number($('sessionMinutes').value),
      questionsPerRound: Number($('questionsPerRound').value),
      level: $('level').value,
      windowStart: $('windowStart').value,
      windowEnd: $('windowEnd').value
    };
    api('/api/config', { method: 'PUT', body: body }).then(function (res) {
      if (!res || res.error) { toast('保存失败'); track('rule_save_fail', {}); return; }
      applyState({ config: res.config, today: {}, questions: Number($('statQuestions').textContent) });
      toast('已保存，电视端立刻生效');
      track('rule_save_success', body);
    }).catch(function () { toast('连不上电视'); });
  }

  function addQuestion() {
    var options = ($('qOptions').value || '').split('\n').map(function (s) { return s.trim(); }).filter(Boolean);
    var body = {
      q: ($('qStem').value || '').trim(),
      a: options,
      k: Number($('qAnswer').value || 0),
      subject: $('qSubject').value,
      level: $('qLevel').value,
      tip: ($('qTip').value || '').trim()
    };
    if (!body.q || options.length < 2) { toast('题干和至少两个选项要填'); return; }
    api('/api/questions', { method: 'POST', body: body }).then(function (res) {
      if (!res || res.error) { toast(res && res.message ? res.message : '保存失败'); return; }
      toast('已加入题库');
      track('question_add', { subject: body.subject, level: body.level });
      closeModal('qModal');
      loadQuestions();
    }).catch(function () { toast('连不上电视'); });
  }

  function doImport() {
    var text = ($('importText').value || '').trim();
    if (!text) { return; }
    var parsed;
    try { parsed = JSON.parse(text); } catch (e) { toast('JSON 格式不对'); return; }
    var body = Array.isArray(parsed) ? { items: parsed } : parsed;
    api('/api/questions', { method: 'POST', body: body }).then(function (res) {
      if (!res || res.error) { toast('导入失败'); return; }
      toast('导入了 ' + (res.added || 1) + ' 道题');
      track('question_import', { count: res.added || 1 });
      closeModal('importModal');
      loadQuestions();
    }).catch(function () { toast('连不上电视'); });
  }

  function deleteQuestion(id) {
    if (!window.confirm('删除这道题？')) { return; }
    api('/api/questions/' + encodeURIComponent(id), { method: 'DELETE' }).then(function (res) {
      toast(res && res.ok ? '已删除' : '删除失败');
      track('question_delete', { id: id });
      loadQuestions();
    });
  }

  /* ---------------------------------------------------------------- 弹窗 */
  function openModal(id) { $(id).classList.add('show'); $(id).style.display = 'block'; }
  function closeModal(id) { $(id).classList.remove('show'); $(id).style.display = 'none'; }

  /* ---------------------------------------------------------------- 启动 */
  document.addEventListener('DOMContentLoaded', function () {
    var params = new URLSearchParams(location.search);
    var host = params.get('h') || localStorage.getItem(LS_HOST) || '';
    var token = params.get('t') || localStorage.getItem(LS_TOKEN) || '';

    $('hostInput').value = host;
    $('tokenInput').value = token;

    $('connectBtn').addEventListener('click', function () {
      connect($('hostInput').value, $('tokenInput').value);
    });
    $('saveRulesBtn').addEventListener('click', saveRules);
    $('refreshQsBtn').addEventListener('click', loadQuestions);
    $('addBtn').addEventListener('click', function () { openModal('qModal'); });
    $('importBtn').addEventListener('click', function () { openModal('importModal'); });
    $('saveQuestionBtn').addEventListener('click', addQuestion);
    $('doImportBtn').addEventListener('click', doImport);
    $('qList').addEventListener('click', function (e) {
      var id = e.target.getAttribute && e.target.getAttribute('data-del');
      if (id) { deleteQuestion(id); }
    });
    document.querySelectorAll('[data-bs-dismiss="modal"]').forEach(function (btn) {
      btn.addEventListener('click', function () { closeModal(btn.closest('.modal').id); });
    });

    track('page_view', { page: 'admin' });
    if (host && token) { connect(host, token, true); }
  });
})();
