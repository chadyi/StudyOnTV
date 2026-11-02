#!/usr/bin/env node
/**
 * StudyOnTV 本地测试服务（零依赖）
 *   GET  /                → 电视端 UI (app/web)
 *   GET  /admin/          → 家长端 H5 (app/admin)
 *   GET  /api/state       → 当日状态（规则 / 用量 / 统计）
 *   GET  /api/questions   → 题库（按年龄段、科目下发）
 *   POST /api/session     → 上报一次答题/观看/学习记录
 *
 * 用法： node app/server/server.js [port]
 */
const http = require("http");
const fs = require("fs");
const path = require("path");

const PORT = Number(process.argv[2] || 8080);
const ROOT = path.resolve(__dirname, "..");

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "application/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".svg": "image/svg+xml",
  ".woff2": "font/woff2",
  ".ttf": "font/ttf"
};

/* ---------------------------------------------------------------- 内存数据 */
const state = {
  device: { id: "TV-8F3A21", name: "客厅电视", bound: true },
  child: { name: "小满", age: 6 },
  rule: {
    dailyMinutes: 40,
    sessionMinutes: 10,
    questionsPerRound: 5,
    windowStart: "18:30",
    windowEnd: "20:00",
    subjects: ["数学", "语文", "英语", "常识"],
    level: "一年级"
  },
  today: { usedMinutes: 20, learnedMinutes: 12, answered: 12, accuracy: 92, streak: 6 },
  logs: []
};

const bank = JSON.parse(fs.readFileSync(path.join(__dirname, "bank.json"), "utf8"));

/* ---------------------------------------------------------------- 静态文件 */
function serveStatic(res, urlPath) {
  let rel = urlPath === "/" || urlPath === "" ? "/web/index.html" : urlPath;
  if (rel.indexOf("/admin") === 0) {
    rel = rel.replace("/admin", "/admin") + (rel.endsWith("/") ? "index.html" : "");
  } else if (!/^\/(web|admin)\//.test(rel)) {
    rel = "/web" + rel;
  }

  const file = path.join(ROOT, path.normalize(rel).replace(/^(\.\.[/\\])+/, ""));
  if (!file.startsWith(ROOT) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
    res.end("404 " + urlPath);
    return;
  }
  const ext = path.extname(file).toLowerCase();
  const body = fs.readFileSync(file);
  res.writeHead(200, {
    "Content-Type": MIME[ext] || "application/octet-stream",
    "Cache-Control": "no-cache",
    "Access-Control-Allow-Origin": "*"
  });
  res.end(body);
}

/* ---------------------------------------------------------------- 服务 */
http.createServer((req, res) => {
  const u = new URL(req.url, "http://localhost");

  if (u.pathname === "/api/state") {
    const body = JSON.stringify(state);
    res.writeHead(200, { "Content-Type": "application/json; charset=utf-8", "Access-Control-Allow-Origin": "*" });
    res.end(body);
    return;
  }

  if (u.pathname === "/api/questions") {
    res.writeHead(200, { "Content-Type": "application/json; charset=utf-8", "Access-Control-Allow-Origin": "*" });
    res.end(JSON.stringify({ items: bank }));
    return;
  }

  if (u.pathname === "/api/session" && req.method === "POST") {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      try {
        const ev = JSON.parse(body || "{}");
        ev.at = new Date().toISOString();
        state.logs.unshift(ev);
        if (ev.type === "watch") state.today.usedMinutes = Math.min(state.rule.dailyMinutes, state.today.usedMinutes + (ev.minutes || 0));
        if (ev.type === "study") state.today.learnedMinutes += 1;
        res.writeHead(200, { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" });
        res.end(JSON.stringify({ ok: true }));
      } catch (e) {
        res.writeHead(400); res.end("bad json");
      }
    });
    return;
  }

  serveStatic(res, u.pathname);
}).listen(PORT, "0.0.0.0", () => {
  console.log("StudyOnTV dev server → http://localhost:" + PORT + "/");
  console.log("家长端 H5        → http://localhost:" + PORT + "/admin/");
});
