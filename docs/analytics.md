# 埋点（Google Analytics 4 + Microsoft Clarity）

两个 ID 只写在两个文件里，改完就生效：

- 电视端：`app/web/assets/js/analytics.js`
- 手机端：`admin/assets/analytics.js`

```js
var CONFIG = {
  ga4Id: 'G-XXXXXXXXXX',   // GA4 衡量 ID
  clarityId: 'abcdefghij', // Clarity 项目 ID
  enabled: true,
  debug: false             // true 时只在控制台打印，不上报
};
```

填的是占位符（含 `XXXX` / `abcdef`）时脚本不会加载第三方代码，本地开发不会有请求。

## 事件列表

当前版本只上报以下两个事件：

| 事件 | 触发时机 | 参数 |
|---|---|---|
| `page_view` | 页面加载 | `page`、`title`、`ua_mobile` |
| `js_error` | 前端报错 | `message`（截断 120 字） |

新增埋点在页面中调用 `window.SOTV_TRACK(name, params)`。埋点只发匿名行为数据，**不发题干、不发孩子信息**。
