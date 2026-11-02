#!/usr/bin/env node
/**
 * 把裸截图装进设备外框（电视 / 手机），导出到 docs/screenshots/
 * 用法: node scripts/make-shots.mjs <输入目录>
 *
 * - 电视截图（1920x1080）→ 电视外框 + 底座
 * - 手机截图（780x1688） → 手机外框 + 刘海
 * 依赖：本机 Chrome 无头模式（不引入任何 npm 包）
 */
import { readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join, resolve, dirname, basename, extname } from "node:path";
import { pathToFileURL } from "node:url";

const SRC = resolve(process.argv[2] || "shots");
const OUT = resolve("docs/screenshots");
if (!existsSync(OUT)) mkdirSync(OUT, { recursive: true });

const chromeCandidates = [
  process.env.CHROME_PATH,
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  "/usr/bin/google-chrome",
];
const chrome = chromeCandidates.filter(Boolean).find((p) => existsSync(p));
if (!chrome) {
  console.error("找不到 Chrome，无法合成截图");
  process.exit(1);
}

const job = (type, src, out, w, h) => {
  const file = join(SRC, src);
  if (!existsSync(file)) {
    console.warn("跳过（文件不存在）: " + src);
    return;
  }
  const b64 = readFileSync(file).toString("base64");
  const mime = extname(src) === ".png" ? "image/png" : "image/jpeg";
  const img = `data:${mime};base64,${b64}`;

  const html = type === "tv" ? tvHtml(img) : phoneHtml(img);
  const tmp = join(tmpdir(), "frame-" + basename(out) + ".html");
  writeFileSync(tmp, html, "utf8");

  execFileSync(chrome, [
    "--headless=new",
    "--disable-gpu",
    "--hide-scrollbars",
    "--default-background-color=00000000",
    "--force-device-scale-factor=2",
    `--window-size=${w},${h}`,
    `--screenshot=${join(OUT, out)}`,
    pathToFileURL(tmp).href,
  ], { stdio: "ignore" });
  console.log("✓ docs/screenshots/" + out);
};

/* ---------------------------------------------------------------- 电视外框 */
const tvHtml = (img) => `<!doctype html><html><head><meta charset="utf-8"><style>
  html,body{margin:0;background:transparent;overflow:hidden}
  .stage{width:1400px;height:1000px;display:flex;align-items:flex-start;justify-content:center;padding-top:36px;box-sizing:border-box;position:relative}
  .tv{position:relative;width:1320px;padding:20px;border-radius:22px;
      background:linear-gradient(160deg,#3A3A42,#141418 45%,#0C0C0F);
      box-shadow:0 40px 80px rgba(0,0,0,.55), inset 0 0 0 1px rgba(255,255,255,.14);}
  .tv:after{content:"";position:absolute;left:26px;right:26px;top:8px;height:2px;border-radius:2px;
      background:linear-gradient(90deg,rgba(255,255,255,0),rgba(255,255,255,.35),rgba(255,255,255,0));}
  .screen{border-radius:8px;overflow:hidden;background:#000;box-shadow:inset 0 0 0 1px rgba(255,255,255,.06);}
  .screen img{display:block;width:100%;height:auto}
  .neck{position:absolute;left:50%;top:100%;width:120px;height:46px;transform:translateX(-50%);
      background:linear-gradient(180deg,#242429,#131316);}
  .base{position:absolute;left:50%;top:calc(100% + 44px);width:340px;height:16px;transform:translateX(-50%);
      border-radius:10px;background:linear-gradient(180deg,#2C2C33,#15151A);
      box-shadow:0 18px 30px rgba(0,0,0,.45);}
</style></head><body>
<div class="stage"><div class="tv">
  <div class="screen"><img src="${img}" alt=""></div>
  <div class="neck"></div><div class="base"></div>
</div></div></body></html>`;

/* ---------------------------------------------------------------- 手机外框 */
const phoneHtml = (img) => `<!doctype html><html><head><meta charset="utf-8"><style>
  html,body{margin:0;background:transparent;overflow:hidden}
  .stage{width:520px;height:1700px;display:flex;align-items:flex-start;justify-content:center;padding-top:14px;box-sizing:border-box}
  .phone{position:relative;width:390px;border-radius:52px;padding:14px;
      background:linear-gradient(160deg,#33333B,#101014 60%);
      box-shadow:0 34px 60px rgba(0,0,0,.5), inset 0 0 0 1px rgba(255,255,255,.16);}
  .screen{border-radius:40px;overflow:hidden;background:#000;position:relative}
  .screen img{display:block;width:100%;height:auto}
  .btn{position:absolute;right:-3px;width:3px;border-radius:3px;background:#3A3A42}
  .btn1{top:180px;height:64px}
  .btn2{top:258px;height:64px}
  .btn-l{position:absolute;left:-3px;top:196px;width:3px;height:48px;border-radius:3px;background:#3A3A42}
</style></head><body>
<div class="stage"><div class="phone">
  <div class="screen"><img src="${img}" alt=""></div>
  <div class="btn btn1"></div><div class="btn btn2"></div><div class="btn-l"></div>
</div></div></body></html>`;

/* ---------------------------------------------------------------- 任务列表 */
job("tv",    "tv-quiz.png",        "tv-quiz.png",        1400, 1000);
job("tv",    "tv-pair.png",        "tv-pair.png",        1400, 1000);
job("tv",    "tv-lock-quota.png",  "tv-lock-quota.png",  1400, 1000);
job("tv",    "tv-lock-timeup.png", "tv-lock-timeup.png", 1400, 1000);
job("phone", "phone-rules.png",    "parent-rules.png",   520,  1700);
job("phone", "phone-bank.png",     "parent-bank.png",    520,  1700);
job("phone", "phone-log.png",      "parent-log.png",     520,  1700);
