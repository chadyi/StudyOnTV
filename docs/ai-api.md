# 让 AI 帮孩子加题

电视上跑着本地 HTTP 服务（默认端口 `8099`），能用 HTTP 直接写题的 AI Agent 都可以用。

## 哪些 AI 能用

必须是**Agent**，普通对话网页版 AI 不行。

| Agent | 入口 |
|---|---|
| **WorkBuddy** | 直接说「帮我把题目加到这台电视」，给它地址和访问码 |
| **Codex** | 终端里跑，能联网就能写 |
| **Claude Code** | 同上 |
| **Coze**（扣子） | [coze.cn](https://www.coze.cn) → 创建 Bot → 开启「HTTP 请求」插件 |
| **豆包·办公版** | [doubao.com](https://www.doubao.com) → 办公助手 → 工作流 |
| **Dify** | 自部署或 [dify.ai](https://dify.ai) → 创建 Agent → HTTP Request 节点 |
| **n8n** | 自部署 → HTTP Request 节点 + AI 节点 |

> **最省事**：用 WorkBuddy 或 Codex，把下面的提示词粘进去，填上电视地址和访问码。

---

## 给 AI 的提示词（直接复制粘贴）

```
帮我往「题了个题」电视题库里加题。

电视地址：<电视上显示的 IP:端口，例如 192.168.1.20:8099>
访问码：<电视上显示的 6 位数字>

接口说明：
1. 先查现有题目（避免重复）：
   GET http://<地址>/api/questions?limit=500

2. 批量新增：
   POST http://<地址>/api/questions
   Header: Content-Type: application/json
   Header: X-Admin-Token: <访问码>
   Body:
   {
     "items": [
       {"q":"题干","a":["选项A","选项B","选项C","选项D"],"k":0,"tip":"可选解析"},
       ...
     ]
   }
   说明：k 是正确答案的下标，从 0 开始（第1个选项=0，第2个=1）。

出题要求（根据孩子实际情况修改）：
- 小学二年级 100 以内加减法，共 20 题
- 每题 4 个选项，只有一个正确，答案下标必须指向正确项
- 先 GET 看看现有题目，别出重复的
- 出错就把返回 JSON 读一遍再重试
```

---

## 用 WorkBuddy / Codex 的步骤

1. 打开 WorkBuddy 或终端里的 Codex
2. 把下面提示词里的电视地址、访问码填好，整段粘进去
3. 回车，等它自己查题库、发请求、写完

用 Coze 的话：新建 Bot → 左侧「插件」搜 HTTP → 添加「HTTP 请求」→ 把提示词贴进人设。

---

## 直接用命令行验（可选）

```bash
# 查题库
curl "http://192.168.1.20:8099/api/questions?limit=5"

# 加一道题
curl -X POST "http://192.168.1.20:8099/api/questions" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: 123456" \
  -d '{"q":"1 + 1 = ?","a":["1","2","3","4"],"k":1}'
```

---

## 接口一览

| 方法 | 路径 | 需要访问码 | 说明 |
|---|---|---|---|
| GET | `/api/hello` | 否 | 设备信息 |
| GET | `/api/questions` | 否 | 题库（支持 `limit` 参数） |
| POST | `/api/questions` | 是 | 新增单题或批量（带 `items` 数组） |
| DELETE | `/api/questions/{id}` | 是 | 删除 |
| GET | `/api/state` | 是 | 规则 + 今日统计 |
| PUT | `/api/config` | 是 | 改规则 |
| GET | `/api/events` | 是 | 最近事件 |

返回约定：成功 `200` + JSON；访问码错误 `401 {"error":"unauthorized"}`；题目格式错误返回 `{"error":"bad_question","message":"..."}`。
