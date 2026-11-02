




(function () {
  'use strict';

  function request(method, path, body, cb) {
    var xhr = new XMLHttpRequest();
    xhr.open(method, path, true);
    xhr.timeout = 4000;
    xhr.setRequestHeader('Content-Type', 'application/json');
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4) { return; }
      if (xhr.status === 204) { return cb(null, { unchanged: true }); }
      if (xhr.status < 200 || xhr.status >= 300) { return cb(new Error('http_' + xhr.status)); }
      var data = null;
      try { data = JSON.parse(xhr.responseText); } catch (e) { return cb(new Error('bad_json')); }
      cb(null, data);
    };
    xhr.onerror = function () { cb(new Error('network')); };
    xhr.ontimeout = function () { cb(new Error('timeout')); };
    xhr.send(body ? JSON.stringify(body) : null);
  }

  var API = {
    online: false,
    info: null,

    hello: function (cb) {
      request('GET', '/api/hello', null, function (err, data) {
        if (err) { return cb(err); }
        API.online = true;
        API.info = data;
        cb(null, data);
      });
    },

    
    loadState: function (cb) {
      request('GET', '/api/state', null, function (err, data) {
        if (err) { return cb(err); }
        API.online = true;
        cb(null, data);
      });
    },

    
    questions: function (limit, subject, cb) {
      var qs = '/api/questions?limit=' + (limit || 5) + (subject ? '&subject=' + encodeURIComponent(subject) : '');
      request('GET', qs, null, function (err, data) {
        if (err) { return cb(err); }
        cb(null, (data && data.items) || []);
      });
    },

    
    report: function (type, payload) {
      var body = payload || {};
      body.type = type;
      request('POST', '/api/event', body, function () {});
    }
  };

  window.SOTV_API = API;
})();
