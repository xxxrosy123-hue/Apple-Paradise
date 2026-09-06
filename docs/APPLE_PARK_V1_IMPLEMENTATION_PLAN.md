# 苹果乐园 V1 施工图

> 仓库：`xxxrosy123-hue/Apple-Paradise`  
> 分支基线：`main`  
> 检查范围：`android/`、`server/`、`mcp/`，并以仓库 `Agent.md`、`.github/skills/apple-park-dev/SKILL.md` 与 Notion《🍎 苹果乐园｜完整产品需求与架构存档》校准产品边界。  
> 本文目的：记录**当前源码真实实现**、V1 可直接复用的链路、缺失能力、具体改造文件和施工顺序。不是愿景稿，也不是泛化架构建议。

---

## 0. 结论先行

当前工程不是从零开始。`AppGate / Focus / Wallet / Guardian Calendar / Android 状态采集 / Server 命令队列 / MCP` 已经形成一条可以复用的完整纵向链路。V1 不应重写通信层，也不应迁移到 Kotlin / Compose / Room。

当前最关键的源码结论：

1. **AppGate 锁定和临时放行都以 Android 本地 SharedPreferences 为事实源。** 具体位于 `linjian_peek` preferences 中的 `app_gate_state_v1` JSON；每个 package 的锁与临时放行字段都嵌在 `locks[package]` 中。门禁 App 列表另存在 `app_gate_apps_lines`。
2. **Focus 也是 Android 本地事实源。** 位于同一个 `linjian_peek` preferences 的 `focus_mode_state_v1`；当前只够表达“当前一次全机专注 + 求助/留言/日志”，**没有真正的历史 Focus Session、分类统计、`todo_id` 关联**。
3. **Todo 当前不存在。** `android` 无 `TodoState`/Todo 页面，`server` 无 Todo action，`mcp` 无 Todo tool，`LifeState` 无 `todo_state`。`WalletState.collect()` 中的 `todo_count` 仅表示“小金库待确认 + 待审批数量”，绝不能作为通用 Todo 引擎复用。
4. **小金库已有独立本地存储。** `WalletState` 使用单独 preferences `linjian_wallet_v1`，`records_json` 是账单/审批的 JSON 数组，预算和规则为同 prefs 的独立 key。
5. **守护日历已有稳定事件模型。** `CalendarState` 事件存在 `linjian_peek` 的 `guard_calendar_events_json` 中，并已有阳历/农历、重复、分组、提醒提前天数、来源等字段。
6. **Android 状态上传链路已经完整。** `CompanionService` 周期执行 `LifeState.collect()`，由 `LifeState` 聚合 AppGate/Focus/Calendar/Wallet 等模块，POST 到 `/api/device/state`；命令执行后还会主动补一次上传。
7. **AppGate / Focus 的强制执行不是服务器做的，而是 Android 无障碍事件驱动。** `ScreenshotService.onAccessibilityEvent()` 在窗口变化时调用 `FocusMode.onForegroundPackage()` 与 `AppGate.onForegroundPackage()`。
8. **远程动作链已经完整。** MCP → `/api/command` → Server 队列 → Android `/api/poll` → `CompanionService.handleCommandBody()` → 对应 State 类 → `/api/device/report` → 状态重新上传 → MCP 查询 command status。
9. **项目目前存在两套 MCP 注册实现，需要同步维护。** 主 Node MCP 在 `mcp/server.js` 通过 `server.tool(...)` 注册；Cloudflare Worker 又在 `server/cloudflare-worker/worker.js` 维护 `MCP_TOOLS` + `callMcpTool()`。V1 新增工具时不能只改其中一处。
10. **AppGate 有一个必须在 V1 前修的跨窗口冲突点。** 当前 `lockApp()` 创建新 lock 后直接 `clearTemp(lock)`，因此另一窗口新下发的普通锁可能抹掉仍有效的临时许可。Notion 产品原则要求“仍有效的明确许可不能因换窗口被普通锁覆盖”。

建议 V1 的工程策略是：

> **保留“Android 本地模块为业务事实源 + Server 为命令传输/最近状态缓存 + MCP 为 AI 读写接口”的现有形态；新增 Todo、Focus 历史会话、AppGate 显式决策层和 `get_current_context` 聚合，不重做底座。**

---

# 1. 当前工程纵向架构

## 1.1 Android 层

关键入口与职责：

| 文件 | 类/模块 | 当前职责 | V1处理 |
|---|---|---|---|
| `android/app/src/main/java/dev/linjian/peek/AppPrefs.java` | `AppPrefs` | 通用 SharedPreferences、Server/Token/device、轮询/上传间隔、已知 App | 直接复用 |
| `.../ScreenshotService.java` | `ScreenshotService` | AccessibilityService、前台包名、屏幕文字/UI节点、截图/点击/输入、无障碍兜底轮询 | 直接复用；作为 AppGate/Focus 执行触发入口 |
| `.../CompanionService.java` | `CompanionService` | 前台服务、轮询命令、命令分发、状态上传、执行结果回传 | 直接复用；新增 Todo action 分发 |
| `.../LifeState.java` | `LifeState` | 聚合设备/生活状态 JSON | 扩展 `todo_state`、Focus 汇总、未来 `current_context` 所需事实 |
| `.../AppGate.java` | `AppGate` | 单 App 锁定、临时放行、解锁请求、无障碍门禁执行 | 复用主体；V1必须升级决策冲突语义 |
| `.../LockActivity.java` | `LockActivity` | 单 App 门禁锁定页 | 直接复用 |
| `.../FocusMode.java` | `FocusMode` | 全机专注当前状态、应急放行、留言/求助 | 复用执行层；新增 session/history/category/todo 关联 |
| `.../FocusLockActivity.java` | `FocusLockActivity` | 全机专注锁定页 | 复用并补入口信息 |
| `.../WalletState.java` | `WalletState` | 预算、账单、通知识别、审批 | 复用并做账单时间字段兼容扩展 |
| `.../WalletActivity.java` | `WalletActivity` | 小金库 UI | 复用，后续从“设置入口”提升为模块页 |
| `.../CalendarState.java` | `CalendarState` | 守护日历事件、农历换算、提醒横幅 | 直接复用 |
| `.../MainActivity.java` | `MainActivity` | 当前主界面/大量设置入口 | V1 需要重组首页与模块入口，不重写底层功能 |

### 当前实际执行关系

```text
Android Window Change
        │
        ▼
ScreenshotService.onAccessibilityEvent()
        ├── ActivityEventStore.recordForegroundChange()
        ├── FocusMode.onForegroundPackage()
        │      └── FocusLockActivity / 全机拦截
        └── AppGate.onForegroundPackage()
               └── LockActivity / Overlay / strict HOME
```

这一条是**本地强制执行链**，不要搬到 Server。Server 不应该判断“用户是否该玩”；AI判断，Android执行确定性规则。

---

## 1.2 Server 层

当前有两种后端实现。

### A. Render / Python

主文件：`server/linjian_server.py`

职责：

- `ALLOWED_ACTIONS` 白名单；
- `/api/command` 建立 command；
- `/api/poll` 给指定 device 下发 pending command；
- `/api/device/report` 接收 Android 执行结果；
- `/api/device/state` 接收/读取最近状态；
- `/api/appgate/unlock_request` 接收解锁申请；
- activity / companion 等辅助状态。

当前 `device_states` 是 `State.device_states: dict[str, dict]`，即**进程内缓存**。Render 进程重启后最近状态会消失，手机下一次上传后恢复。V1 不应把它误当长期业务数据库。

### B. Cloudflare Worker / D1

主文件：

- `server/cloudflare-worker/worker.js`
- `server/cloudflare-worker/schema.sql`

D1 已有：

```sql
device_state(device_id, state_json, updated_at)
commands(id, device_id, command_json, status, ...)
activity_events(...)
companion_state(...)
unlock_requests(...)
```

Worker 的 `/api/device/state` 会把整包 `state_json` `INSERT OR REPLACE` 到 D1。因此 Cloudflare 版本的“最近状态”可持久化，但仍然是**设备状态快照缓存**，不是 Todo/Wallet 等领域数据的第二事实源。

### V1 后端原则

- **业务事实继续以 Android 本地模块为事实源。**
- Server 负责：命令、最近快照、有限活动日志、跨窗口需要的当前有效事实镜像。
- V1 不为 Todo 再单独造一套 Server 数据库，避免 Android/Server 双主冲突。
- 如果未来需要多设备或 Web 编辑，再单独做同步协议；V1 不提前过度设计。

---

## 1.3 MCP 层：实际有两个注册点

### Node MCP（主实现）

文件：`mcp/server.js`

- `new McpServer({ name: "掌心窗", ... })`
- 大量 `server.tool("...", ...)` 直接注册工具。
- Focus 工具被刻意靠前注册，避免客户端只抓前若干 schema：
  - `get_focus_status`
  - `start_focus_mode`
  - `end_focus_mode`
  - `set_focus_plan`
  - `reply_focus_request`
  - `approve_focus_unlock`
  - `deny_focus_unlock`
- Wallet/Takeout 通过 `registerWalletTakeoutTools(server, { includeUnified: true })` 批量注册。
- 守护日历通过 `get_guardian_calendar` / `add_guardian_calendar_event` 等工具注册。
- AppGate 通过 `screen_break_app`、`temporary_screen_break_release`、`get_lock_state` 等工具注册。
- 通用远程命令通过 `send_phone_command` / `postCommand` / `waitCommand` 走现有 command 队列。

### Cloudflare Worker 内置 MCP

文件：`server/cloudflare-worker/worker.js`

- 工具 schema：`const MCP_TOOLS = [...]`
- JSON-RPC 入口：`handleMcp()` / `handleMcpMessage()`
- `tools/list` 返回 `MCP_TOOLS`
- `tools/call` → `callMcpTool(name,args,env)`

**V1 新增 Todo / current-context 工具时，两套 MCP 都要同步。** 如果部署上最终只保留一种 MCP，需要另开清理任务；V1 先保证兼容，不偷偷删除已有路径。

---

# 2. AppGate：当前锁定与临时放行真实存储

## 2.1 SharedPreferences 位置

`AppPrefs.PREFS = "linjian_peek"`

`AppGate` 使用：

```text
SharedPreferences: linjian_peek
├── app_gate_enabled          boolean
├── app_gate_apps_lines       string（alias|package 每行一条）
└── app_gate_state_v1         string(JSON)
```

`app_gate_state_v1` 默认结构：

```json
{
  "locks": {},
  "requests": [],
  "logs": []
}
```

锁按 package 存放：

```json
{
  "locks": {
    "com.example.app": {
      "package": "com.example.app",
      "app_name": "某App",
      "active": true,
      "locked_until_ms": 0,
      "locked_until_local": "",
      "mode": "medium",
      "reason": "",
      "message": "",
      "created_at_ms": 0,
      "emergency_unlock_minutes": 5,
      "emergency_hash": "optional",

      "temporary_active": true,
      "temporary_type": "real_time|foreground_usage|one_time",
      "temporary_started_at_ms": 0,
      "temporary_until_ms": 0,
      "temporary_window_until_ms": 0,
      "temporary_allowed_ms": 0,
      "temporary_used_ms": 0,
      "temporary_session_started_ms": 0,
      "temporary_one_time_used": false
    }
  },
  "requests": [],
  "logs": []
}
```

临时放行不是单独表，而是直接写回对应 lock 对象。

## 2.2 当前临时放行行为

`temporaryUnlock()` 支持：

- `real_time`：现实时间到点失效；
- `foreground_usage`：按实际前台使用时间计；
- `one_time`：一次性窗口；
- `max_window_minutes`：最长许可窗口。

`onForegroundPackage()` 每次前台 App 变化会检查 `isTemporarilyAllowed()`，有效则不弹门禁，无效则进入锁定 UI。

## 2.3 当前必须修的冲突问题

`lockApp()` 新建一个 `JSONObject lock` 后直接执行：

```java
clearTemp(lock);
locks(s).put(pkg, lock);
```

这意味着当前 lock 是“整条替换”，而不是“基于现有决策做冲突合并”。多 AI 窗口时：

```text
窗口 A：temporary_unlock_app -> 有效到 21:30
窗口 B：普通 lock_app -> 新 lock 替换旧 lock + clearTemp
结果：A 的仍有效许可被抹掉
```

这与苹果乐园“共享正式事实层”要求冲突。

## 2.4 V1 改造：从 lock 对象升级为有效决策语义

V1 不必立即重写成数据库。可在现有 `locks[pkg]` 中兼容增加显式决策字段，先解决跨窗口冲突：

```json
{
  "decision": "LOCK|ALLOW",
  "decision_id": "gate_xxx",
  "decision_source": "user|ai|local_rule",
  "approved_by": "user|companion|system",
  "decision_reason": "",
  "decision_purpose": "",
  "decision_started_at_ms": 0,
  "decision_expires_at_ms": 0,
  "decision_priority": "explicit_user|explicit_allow|focus_rule|normal_ai"
}
```

兼容策略：

1. 老字段继续读取，避免升级后把现有锁全丢掉。
2. 新命令写入新 decision 字段，同时保留旧字段给 UI/旧 MCP。
3. `lockApp()` 在覆盖前先读取当前 lock：
   - 若有**未过期明确 ALLOW**，普通 AI `LOCK` 返回 `conflict_active_permission`，不得清掉许可；
   - 用户明确新决定可覆盖；
   - 强制 Focus 与 AppGate 的关系通过明确来源/优先级处理，不靠调用先后碰运气。
4. 临时许可到期后 `config()` / 前台检查时清理 effective state；过期许可不进入 `get_current_context`。
5. 新 MCP 放行工具描述必须明确“根据当前任务/专注/日程/既有规则判断，不得仅因用户请求就无脑批准；换窗口不代表规则失效”。

### 要改的文件

- `android/.../AppGate.java`
- `android/.../LifeState.java`（输出 effective decision）
- `mcp/server.js`（工具 schema/描述/冲突返回）
- `server/linjian_server.py`（如增加 action 名）
- `server/cloudflare-worker/worker.js`（ALLOWED_ACTIONS + MCP_TOOLS/callMcpTool）
- 无需新增 AppGate Server 数据表；现有快照足够 V1 共享当前事实。

---

# 3. Focus：当前数据结构与 V1 缺口

## 3.1 当前存储

```text
SharedPreferences: linjian_peek
└── focus_mode_state_v1  string(JSON)
```

当前默认字段：

```json
{
  "focus_version": "0.3.8.4-public-focus",
  "enabled": false,
  "active": false,
  "mode": "strict",
  "scope": "full_phone",
  "managed_by_ai": true,
  "goal": "先离开手机",
  "message": "...",
  "message_source": "default|ai|manual",
  "started_at_ms": 0,
  "started_at_local": "",
  "until_ms": 0,
  "until_local": "",
  "temporary_until_ms": 0,
  "emergency_total": 1,
  "emergency_used": 0,
  "emergency_minutes": 1,
  "requests": [],
  "messages": [],
  "logs": []
}
```

`config()` 额外计算：

- `remaining_ms`
- `temporary_active`
- `temporary_remaining_ms`
- `emergency_remaining`

每条 message：

```json
{
  "role": "ai|user",
  "text": "",
  "important": false,
  "time_ms": 0,
  "time": ""
}
```

每条 log：`time_ms / time / message`。

## 3.2 当前能做什么

- 开启/结束全机专注；
- 保存当前 plan；
- 求助/留言；
- AI批准或拒绝临时放行；
- 离线窄应急；
- 前台 App 切换时强制回 `FocusLockActivity`。

## 3.3 当前不具备什么

产品 V1 需要但源码尚没有：

- 独立 Focus Session 历史；
- `category`；
- `todo_id`；
- `actual_duration_ms`；
- 今日/本周/本月/今年统计；
- 同一个 Todo 多次 Focus 的累计；
- 明确的结束原因/完成度历史；
- 与时间轴 Actual Block 的可靠连接。

Notion 已明确每次 Focus 至少要有 `category / start_at / end_at / actual_duration / optional todo_id`，且**分类与颜色尚未最终定稿**，所以 V1 数据模型必须允许自由分类，不写死“生活/考研/工作”枚举。

## 3.4 V1 推荐兼容模型

保留 `focus_mode_state_v1` 作为**当前执行状态**，新增独立历史 preferences：

```text
SharedPreferences: apple_park_focus_v1
├── sessions_json
└── categories_json
```

Session：

```json
{
  "id": "focus_xxx",
  "category_id": "cat_xxx",
  "category_name_snapshot": "考研",
  "todo_id": "optional",
  "goal": "",
  "source": "manual|mcp|todo",
  "managed_by_ai": true,
  "started_at_ms": 0,
  "planned_end_at_ms": 0,
  "ended_at_ms": 0,
  "actual_duration_ms": 0,
  "status": "active|completed|cancelled",
  "end_reason": "",
  "created_at_ms": 0,
  "updated_at_ms": 0
}
```

规则：

- `start_focus_mode` 成功时创建 session，同时把 `session_id/category_id/todo_id` 写入当前 `focus_mode_state_v1`；
- `end_focus_mode` 以实际结束时刻结算；
- 临时应急放行是否计入 actual_duration 要在实现时统一：V1 推荐 `actual_duration = session wall-clock - temporary unlock intervals`，同时保留原始开始/结束；
- 每个 Todo 可通过 `todo_id` 聚合多条 session；
- `LifeState.focus_mode` 返回当前执行态 + today/week summary，不倒出全部 sessions；
- 历史通过专用 MCP `list_focus_sessions` 查询。

---

# 4. Todo：当前完全缺失，V1 必须新增

## 4.1 当前源码验证

当前仓库中：

- 无 `TodoState.java`；
- 无 Todo Activity；
- `MainActivity` 无通用 Todo 入口/数据；
- `LifeState.collect()` 无 `todo_state`；
- `CompanionService` 无 Todo command 分发；
- `server/linjian_server.py` 的 `ALLOWED_ACTIONS` 无 Todo；
- `server/cloudflare-worker/worker.js` 的 `ALLOWED_ACTIONS` / `MCP_TOOLS` 无 Todo；
- `mcp/server.js` 无 Todo tool。

**不要把 `WalletState.todo_count` 误接成 Todo。** 那只是钱包待处理数量。

## 4.2 产品边界

V1 使用统一任务引擎，不拆“Todo”和“日常”两套系统。

当前已确定的最低字段：

- 标题；
- 分类；
- 截止时间；
- 状态；
- 提醒；
- 是否重复；
- 可选关联目标。

同时为了支持 Todo → Focus → Time Axis，V1 数据模型应预留稳定 ID 与实例/模板关系。

分类、优先级、重复/延期细则仍未定稿，因此 schema 要兼容扩展，UI 不应先锁死。

## 4.3 新增 Android 文件

建议：

- `android/app/src/main/java/dev/linjian/peek/TodoState.java` —— 数据、命令、汇总；
- `android/app/src/main/java/dev/linjian/peek/TodoActivity.java` —— V1 使用页；
- 可选 `TodoReminderReceiver.java` / 本地提醒帮助类 —— 只有在本轮同时落提醒时新增；
- 不引入 Room/Compose/Hilt。

## 4.4 V1 Todo 存储

建议独立 preferences，避免继续把 `linjian_peek` 膨胀成所有业务数据的大 JSON：

```text
SharedPreferences: apple_park_todo_v1
├── tasks_json
├── templates_json
└── categories_json
```

Task 实例推荐：

```json
{
  "id": "todo_xxx",
  "title": "",
  "note": "",
  "category_id": "",
  "goal_id": "optional",
  "template_id": "optional",

  "status": "pending|in_progress|completed|cancelled|review_needed",
  "due_at_ms": 0,
  "remind_at_ms": 0,
  "completed_at_ms": 0,

  "repeat": false,
  "repeat_rule": "",

  "source": "manual|mcp|template",
  "created_by": "user|companion|system",
  "created_at_ms": 0,
  "updated_at_ms": 0
}
```

V1 暂不强制加入复杂 priority 算法；若 UI 需要优先级，使用可选字段而非硬性业务逻辑。

## 4.5 `TodoState.collect()` 应返回当前事实，不返回全库

```json
{
  "updated_at_ms": 0,
  "today_total": 0,
  "today_completed": 0,
  "today_remaining": 0,
  "overdue_count": 0,
  "review_needed_count": 0,
  "current_todo": {},
  "next_todos": [],
  "attention_items": []
}
```

全部明细由 `list_todos` 查询，不塞进每 10 秒上传的 LifeState。

## 4.6 Todo Android command

最低动作集：

- `get_todo_state`
- `list_todos`
- `upsert_todo`
- `complete_todo`
- `set_current_todo`
- `delete_todo`
- `start_focus_for_todo` 可不做独立 action：MCP 先读 Todo 后调用现有 `start_focus_mode(todo_id=...)` 即可。

`CompanionService` 新增 `isTodoAction(action)` 分支，模式照抄 Calendar/Wallet：

```text
TodoState.handleCommand()
  -> reportCommand()
  -> uploadStateThrottled(..., force=true)
```

---

# 5. 小金库：当前数据结构与可复用点

## 5.1 存储位置

`WalletState.java`：

```text
SharedPreferences: linjian_wallet_v1
├── records_json
├── monthly_budget
├── approval_threshold
├── auto_mode
├── deep_night
└── category_limits
```

`MAX_RECORDS = 520`。

## 5.2 Record 当前实际语义

不同来源/动作会写入字段子集，核心包括：

```json
{
  "id": "uuid",
  "amount": 0,
  "type": "expense|income|saved|approval_request|...",
  "status": "confirmed|pending|ignored|approval_pending|approval_approved|approval_rejected|approval_delayed|...",
  "category": "",
  "merchant": "",
  "note": "",
  "source": "manual|notification|mcp|...",
  "requester_role": "user|companion",
  "decision": "",
  "approval_message": "",
  "created_at_ms": 0,
  "updated_at_ms": 0
}
```

通知自动识别还会产生：

- `source_app`
- `source_package`
- `source_key`
- `confidence`

`collect(month)` 已能生成：

- `spent / income / remaining / saved_estimate`
- `pending_count`
- `approval_count / approval_pending_count`
- `category_totals`
- `recent_records`
- `pending_records`
- `approval_records`
- `month_summaries`
- `rules`

## 5.3 V1 复用与最小补强

Wallet 不需要重写。

产品档案要求区分：

- `created_at`：记录被录入的时间；
- `occurred_at` / `transaction_at`：真实交易发生时间。

因此 V1 只需要做向后兼容扩展：

```json
{
  "created_at_ms": 0,
  "occurred_at_ms": 0,
  "occurred_date_local": "YYYY-MM-DD"
}
```

老记录缺 `occurred_at_ms` 时可以显示“发生时间未知/按录入时间展示兼容”，**不要后台凭空伪造真实交易时间**。

V1 首页/`get_current_context` 只返回今日/本月摘要和待处理数；完整账单继续通过 Wallet 专用工具读。

---

# 6. 守护日历：当前数据结构与可复用点

## 6.1 存储位置

```text
SharedPreferences: linjian_peek
└── guard_calendar_events_json  string(JSON array)
```

## 6.2 Event 当前结构

```json
{
  "id": "cal_xxx",
  "title": "",
  "date_type": "solar|lunar",
  "repeat_type": "yearly|none",
  "group": "our_days|user|companion|festival|study|project|life|...",
  "note": "",
  "remind_days_before": 3,
  "banner_enabled": true,
  "created_by": "user|companion|system",
  "updated_at": "",

  "solar_date": "YYYY-MM-DD|MM-DD",
  "lunar_month": 0,
  "lunar_day": 0,
  "lunar_is_leap": false,
  "date": ""
}
```

读取时会为旧事件补稳定 id；Occurrence 层会额外计算：

- `group_label`
- 当年实际 `date`
- `days_left / days_text`
- `lunar_label`
- `banner_text`
- `builtin`

内置节日与自定义事件被统一成 Occurrence 输出。

## 6.3 V1 处理

守护日历已经够用，**不要改成 Todo 或系统 Calendar 的替代品**。

- 纪念日/节日/倒数日：继续 CalendarState；
- 有完成状态、执行动作、可进入 Focus 的事项：Todo；
- 未来有具体时间块：Time Axis / Schedule；
- V1 首页只读取 Calendar 最近 1～3 个重要日子。

---

# 7. Android → Server 状态上传完整链路

## 7.1 周期上传

```text
CompanionService.onStartCommand()
  -> startPolling()
  -> pollLoop()
      ├── uploadStateThrottled(serverUrl, token, ctx, false)
      │     ├── LifeState.collect(ctx)
      │     ├── GuidianState.evaluate(...)
      │     ├── LifeState.collect(ctx) 再取一次
      │     ├── POST /api/device/state
      │     ├── ActiveReminder.evaluate(...)
      │     └── HomeMode.evaluate(...)
      └── GET /api/poll?device_id=...
```

`AppPrefs.STATE_UPLOAD_INTERVAL_MS = 10000`，非 force 情况约 10 秒限频。

## 7.2 `LifeState.collect()` 当前聚合

已经包含：

- device/time/timezone
- battery/charging/network/screen
- `current_package/current_app`
- accessibility/usage stats
- weather
- known apps
- `app_gate`
- `focus_mode`
- cycle
- `calendar_state`
- `wallet_state`
- takeout
- guidian
- media/now state

V1 直接增加：

```java
state.put("todo_state", TodoState.collect(ctx));
```

并把 Focus 的今日/本周摘要放进 `focus_mode` 或另设 `focus_summary`。

## 7.3 命令执行后的强制刷新

`CompanionService.handleCommandBody()` 当前对 Calendar/Focus/AppGate/Wallet 等都会：

```text
DomainState.handleCommand()
 -> reportCommand(...)
 -> uploadStateThrottled(...)
```

因此 Todo 完成、修改、切换 current todo 后也应沿用同一模式，让不同 AI 窗口尽快看到新事实。

## 7.4 Server 接收

### Render Python

```text
POST /api/device/state
 -> read JSON
 -> device_id
 -> data.updated_at = now_iso()
 -> state.device_states[device_id] = data
```

### Cloudflare Worker

```text
POST /api/device/state
 -> saveDeviceState()
 -> INSERT OR REPLACE device_state(device_id,state_json,updated_at)
```

### MCP 读取

Node MCP 当前多个工具直接读取：

```text
GET /api/life_state?device_id=...
或 GET /api/device/state?device_id=...
```

再从 `state.focus_mode / calendar_state / wallet_state / app_gate` 中抽取模块。

---

# 8. MCP → Android 命令完整链路

```text
AI tool call
  │
  ▼
mcp/server.js server.tool(...)
  │  postCommand()/gateCommand()/runWalletCommand()
  ▼
POST Server /api/command
  │
  ▼
Server command queue (pending)
  │
  ▼
Android CompanionService
GET /api/poll?device_id=...
  │
  ▼
handleCommandBody()
  ├── CalendarState.handleCommand()
  ├── FocusMode.handleCommand()
  ├── AppGate.handleCommand()
  ├── WalletState.handleCommand()
  └── [V1] TodoState.handleCommand()
  │
  ├── POST /api/device/report
  └── POST /api/device/state（刷新事实）
  │
  ▼
MCP waitCommand()/command status
```

这一链路已经足够给 V1 所有“手机本地事实修改”复用。

---

# 9. V1 新核心：`get_current_context`

这是 V1 最值得新增的 MCP 聚合接口。

## 9.1 原则

- 返回**当前决策相关事实**，不是数据库 dump；
- 历史由模块工具读；
- 已过期临时决定不返回为有效；
- 每个模块尽可能带 `updated_at / source / stale`；
- Apple Paradise 只列事实，不写“你今天表现差”这类 AI 判断。

## 9.2 V1 最小返回结构

```json
{
  "device_id": "android-phone",
  "generated_at": "",
  "device": {
    "local_time": "",
    "local_date": "",
    "current_app": "",
    "current_package": "",
    "battery_percent": 0,
    "charging": false,
    "screen_time_today_minutes": 0
  },
  "app_gate": {
    "effective_locks": [],
    "effective_allows": [],
    "pending_requests": [],
    "conflicts": []
  },
  "todo": {
    "today_total": 0,
    "today_completed": 0,
    "current_todo": {},
    "overdue_count": 0,
    "next_todos": []
  },
  "focus": {
    "active": false,
    "session_id": "",
    "category": "",
    "todo_id": "",
    "started_at_ms": 0,
    "remaining_ms": 0,
    "today_duration_ms": 0,
    "week_duration_ms": 0
  },
  "wallet": {
    "today_spent": 0,
    "month_spent": 0,
    "month_budget": 0,
    "remaining": 0,
    "pending_count": 0
  },
  "calendar": {
    "nearest": []
  },
  "attention_items": [],
  "freshness": {}
}
```

V1 可以先在 MCP 层基于最近 `life_state` 组装，不必立刻新增 Server endpoint。等字段稳定后再决定是否把 `current_context` 下沉成后端只读 endpoint。

## 9.3 新工具位置

Node：`mcp/server.js`

- 新 `server.tool("get_current_context", ...)`
- 建议靠前注册，与 Focus/current-state 工具放在前部。

Cloudflare：`server/cloudflare-worker/worker.js`

- `MCP_TOOLS` 增 `get_current_context`
- `callMcpTool()` 增对应分支

---

# 10. 可直接复用 / 需扩展 / 必须新增

## 10.1 可直接复用

| 能力 | 复用代码 |
|---|---|
| Android 通用配置 | `AppPrefs.java` |
| 无障碍前台 App 感知 | `ScreenshotService.java` |
| AppGate 锁定 UI/前台拦截 | `AppGate.java` + `LockActivity.java` |
| Focus 全机锁 UI/前台拦截 | `FocusMode.java` + `FocusLockActivity.java` |
| Android 前台服务/轮询 | `CompanionService.java` |
| 生活状态聚合框架 | `LifeState.java` |
| 小金库 | `WalletState.java` + `WalletActivity.java` |
| 守护日历 | `CalendarState.java` |
| Render 命令队列 | `server/linjian_server.py` |
| CF D1 命令/状态 | `worker.js` + `schema.sql` |
| Node MCP transport/helper | `mcp/server.js` 的 `linjianFetch/postCommand/waitCommand` 等 |

## 10.2 复用但要扩展

| 模块 | 改造 |
|---|---|
| AppGate | 增 effective decision/source/reason/TTL/conflict；普通 lock 不得覆盖仍有效明确 allow |
| Focus | 当前执行态保留；新增 session 历史、category、todo_id、统计 |
| LifeState | 增 `todo_state`，输出更精简的 current facts/freshness |
| CompanionService | 增 Todo action routing |
| MainActivity/UI | 从“功能设置列表”重组为苹果乐园首页与独立模块入口 |
| Wallet | 增真实交易时间 `occurred_at`，保留 created_at |
| MCP | 增 Todo tools + `get_current_context`，双实现同步 |
| Server action whitelist | 增 Todo action 名；通信协议本身不改 |

## 10.3 必须新增

- `TodoState.java`
- `TodoActivity.java`
- Focus session 持久化（可先放 `FocusMode` 内 helper，后续再抽类）
- Todo MCP 工具组
- `get_current_context`
- AppGate 决策冲突兼容逻辑
- 首页“当前生活”聚合展示层

V1 **不建议新增**：Room、Compose、Kotlin、复杂服务端 policy engine、服务器第二份 Todo 主库、全天截图数据库。

---

# 11. 具体实施顺序

## Phase 0 — 基线锁定与回归清单

先不改行为，建立当前回归点：

1. AppGate 锁/解锁/三种临时放行；
2. 临时许可退出重进仍有效；
3. Focus 开启/结束/应急；
4. Calendar 增删改；
5. Wallet 增账/审批；
6. Android 状态上传可在 Server 读到；
7. MCP 下发 command，Android report completed；
8. Android build 脚本能通过。

## Phase 1 — AppGate 决策冲突修复

优先原因：这是多 AI 窗口共享事实层的正确性基础。

修改：

- `AppGate.lockApp()`：禁止普通锁覆盖未过期明确 allow；
- `temporaryUnlock()`：补 reason/source/approved_by/decision_id；
- `config()`：输出 effective locks/allows，只保留当前有效决定；
- MCP 临时放行工具描述补“不盲批”约束；
- 添加冲突测试：窗口 A allow → 窗口 B 普通 lock → 应返回 conflict，allow 保持有效。

## Phase 2 — Todo 本地引擎

新增 `TodoState.java`：

- schema + migration/version；
- CRUD；
- today/overdue/current/review summary；
- 重复字段只先存，不提前做复杂重复调度；
- 所有写操作有 stable id、source、updated_at。

然后新增 `TodoActivity.java`，先完成可用的列表/新增/完成/编辑，不追求最终视觉一次到位。

## Phase 3 — Todo 接入 LifeState 与 Android 命令分发

修改：

- `LifeState.collect()`：`todo_state`；
- `CompanionService.handleCommandBody()`：Todo action；
- command 完成后 force 状态上传；
- MainActivity 增 Todo 使用入口。

完成后，即使 MCP 还没加，Server 最近状态已经能跨窗口看到 Todo 摘要。

## Phase 4 — Todo MCP + `get_current_context`

Node `mcp/server.js`：

- `get_todo_state`
- `list_todos`
- `upsert_todo`
- `complete_todo`
- `set_current_todo`
- `delete_todo`
- `get_current_context`

Cloudflare Worker 同步 `MCP_TOOLS` / `callMcpTool()` / `ALLOWED_ACTIONS`。

Render Python 同步 `ALLOWED_ACTIONS`。

优先让 `get_current_context` 读 cache，避免每次都等手机实时命令导致 MCP 超时。

## Phase 5 — Focus Session + Todo ↔ Focus

修改 `FocusMode`：

- start 时创建 session；
- 接受 `category_id/category/todo_id/source`；
- end 时落 actual duration；
- 提供今日/本周聚合；
- `start_focus_mode` MCP schema 增 `todo_id/category`；
- Todo 页面“一键专注”调用现有 Focus start 入口，并把 todo/category 带入。

此时 AI 能看到“Todo没完成，但已经真实专注了多久”。

## Phase 6 — Wallet 时间语义补强

- 新记录写 `occurred_at_ms`（明确知道时才写）；
- 老记录兼容；
- 日级汇总；
- current context 返回今日 + 本月摘要。

## Phase 7 — 苹果乐园首页 V1

最后再重组首页，因为前面事实层不完整时先做 UI 会返工。

首页不是功能网格，最低展示：

- 红/青苹果共同标记 + 苹果乐园 + 永不打烊；
- 当前时间/天气/昼夜；
- 当前 Todo / 下一件事；
- 今日 Todo 完成度；
- 当前/今日专注；
- 最近守护日子；
- 小金库今日/本月摘要；
- 1～2 条 factual `attention_items`；
- “哥哥来过”只来自真实 MCP/companion activity；
- 小纸条只保存真实 AI 写入，保留历史。

设置入口继续放 Server/MCP/权限/门禁规则，不把设置当首页使用入口。

---

# 12. MCP / Server Todo action 对照表

| 产品动作 | Android action | Node MCP | CF MCP | Server whitelist |
|---|---|---|---|---|
| 当前 Todo 摘要 | `get_todo_state` | `get_todo_state` | 同名 | Python + Worker |
| 查询任务 | `list_todos` | `list_todos` | 同名 | Python + Worker |
| 新增/修改 | `upsert_todo` | `upsert_todo` | 同名 | Python + Worker |
| 完成 | `complete_todo` | `complete_todo` | 同名 | Python + Worker |
| 当前任务 | `set_current_todo` | `set_current_todo` | 同名 | Python + Worker |
| 删除 | `delete_todo` | `delete_todo` | 同名 | Python + Worker |
| 当前全局上下文 | 无需 Android command，读最近 LifeState | `get_current_context` | 同名 | 可先不加 endpoint |

V1 不要另造 `/api/todo/*` REST CRUD；继续利用通用 `/api/command`，减少跨实现重复逻辑。

---

# 13. 数据一致性与迁移规则

1. **Android 本地是 AppGate/Focus/Todo/Wallet/Calendar 的业务事实源。**
2. Server `device_state` 是最近授权快照，不反向自动覆盖手机本地数据。
3. 所有可跨窗口影响后续判断的当前决定必须持久化，不只放聊天上下文。
4. 临时决定必须有 TTL/expiry；读取 current context 时过滤过期项。
5. 操作日志有上限，不永久堆积。
6. SharedPreferences JSON 结构升级要有版本或缺字段补默认，不做破坏性清空。
7. Todo/Focus/Wallet 每条实体要有稳定 id。
8. 手动修正的事实优先于例行自动同步；未来时间轴/实际块尤其要遵守。
9. Server 不做主观“该不该玩/该不该休息”的判断。

---

# 14. 测试矩阵 / Definition of Done

## AppGate

- [ ] 锁定到期自动失效。
- [ ] `real_time` 临时放行跨窗口/退出重进仍有效直到 expiry。
- [ ] `foreground_usage` 只累计真实前台使用。
- [ ] `one_time` 不被新窗口重置。
- [ ] 有效明确 ALLOW 存在时，普通 AI LOCK 返回 `conflict_active_permission`。
- [ ] 用户明确新决定可覆盖旧决定。
- [ ] `get_current_context` 不返回已过期 permission。

## Focus

- [ ] 开始 Focus 后当前状态与 session 同时建立。
- [ ] 结束后 session 落 `ended_at/actual_duration`。
- [ ] 带 `todo_id` 的多次 session 可正确累计。
- [ ] 今日/本周统计不重复计数。
- [ ] 应急放行后的 actual duration 规则一致。
- [ ] FocusLockActivity 原强制行为不回归。

## Todo

- [ ] 新增、编辑、完成、删除后重启 App 数据仍在。
- [ ] today / overdue / review_needed 正确。
- [ ] stable id 不因重启变化。
- [ ] current Todo 切换后 LifeState 立即刷新。
- [ ] Todo 一键 Focus 带正确 `todo_id/category`。
- [ ] 分类未知/新增不会因为硬编码枚举失败。

## Wallet

- [ ] 老记录可读取。
- [ ] 新记录 created 与 occurred 时间分离。
- [ ] 不知道实际交易时间时不伪造 occurred_at。
- [ ] 通知识别和审批旧流程不回归。

## Calendar

- [ ] 阳历/农历/每年重复逻辑不回归。
- [ ] 旧事件 id 迁移仍稳定。
- [ ] Calendar 与 Todo 不混用状态语义。

## 通信/MCP

- [ ] Android 每 10 秒左右上传 LifeState；写动作后强制刷新。
- [ ] Render `/api/device/state` 可读最新快照。
- [ ] Cloudflare D1 可读最新快照。
- [ ] Node MCP 和 CF MCP 都列出新增 Todo/current-context 工具。
- [ ] MCP command → poll → Android execute → report → status 全链路成功。
- [ ] `get_current_context` 默认走缓存，不因手机冷启动轻易超过平台工具时限。

## Android

- [ ] 继续 Java 11 / native Android；不引入 Kotlin/Compose/Room/Hilt。
- [ ] `android/build.sh`（或仓库现有构建路径）通过。
- [ ] Accessibility/通知/前台服务行为真机 smoke test。
- [ ] 交互控件有可理解 label，触摸目标原则上 ≥48dp。

---

# 15. 第一批实际代码改动清单

如果下一步开始施工，建议第一批 PR/提交只做以下内容，不同时重做首页：

### Commit A — AppGate decision consistency

修改：

- `android/app/src/main/java/dev/linjian/peek/AppGate.java`
- `android/app/src/main/java/dev/linjian/peek/LifeState.java`
- `mcp/server.js`
- `server/linjian_server.py`
- `server/cloudflare-worker/worker.js`

目标：跨窗口有效许可不被普通锁覆盖；current state 能看见 effective decision。

### Commit B — Todo local core

新增：

- `android/app/src/main/java/dev/linjian/peek/TodoState.java`
- `android/app/src/main/java/dev/linjian/peek/TodoActivity.java`

修改：

- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/java/dev/linjian/peek/MainActivity.java`
- `android/app/src/main/java/dev/linjian/peek/LifeState.java`

目标：先让 Todo 在手机本地完整可用并进入 life state。

### Commit C — Todo remote/MCP

修改：

- `android/app/src/main/java/dev/linjian/peek/CompanionService.java`
- `server/linjian_server.py`
- `server/cloudflare-worker/worker.js`
- `mcp/server.js`

目标：AI 可跨窗口读取/修改同一份 Todo，并得到 command report。

### Commit D — Focus session linkage

修改：

- `FocusMode.java`
- `FocusLockActivity.java`（仅必要 UI 字段）
- `LifeState.java`
- `mcp/server.js`
- `worker.js`

目标：Focus 形成可统计 session，并与 Todo 关联。

### Commit E — current context + 首页

最后建立 `get_current_context` 与首页聚合 UI。此时事实层已稳定，避免首页先做后返工。

---

# 16. 当前技术债与 V1 风险

1. **两套 MCP schema 漂移风险。** Node MCP 与 CF Worker MCP 都注册工具，任何新 action 必须双改双测。
2. **两套 Server action 白名单漂移。** Python 与 Worker 都维护 ALLOWED_ACTIONS。
3. **版本字符串散落。** Android/Server/MCP 多处硬编码 `0.3.8.4` 类版本，长期易漂移；V1 可以顺手统一常量，但不要为此做大迁移。
4. **SharedPreferences 大 JSON 是 whole-object read/modify/write。** V1 数据量尚可，但 Wallet/Todo/Focus history 必须有上限/归档意识，避免无限增长。
5. **Render device state 易失。** 这是快照缓存可接受，但 UI/MCP 必须有 freshness；不能因为 Render 重启后短暂空状态就推断“用户没有 Todo/没有锁”。
6. **AppGate 当前覆盖式 lock 破坏有效临时许可。** 这是 V1 P0 正确性问题。
7. **Focus 当前只有当前态，没有历史事实。** 不补 session 就无法支持“已经真实投入多久”的管理逻辑。
8. **Wallet 的创建时间被当作交易时间使用的历史兼容问题。** 新 schema 要区分 occurred_at，老数据不可伪造。
9. **LifeState 整包上传会随模块扩张。** `get_current_context` 应做摘要，history 不塞进 LifeState。
10. **不要让 Server 长成判断引擎。** 苹果乐园记录事实和执行规则，主观判断留给 AI。

---

# 17. V1 完成后的目标数据流

```text
                         ┌──────────────────────┐
                         │     AI / ChatGPT      │
                         │ get_current_context   │
                         │ Todo / Focus / Gate   │
                         └──────────┬───────────┘
                                    │ MCP
                  ┌─────────────────┴─────────────────┐
                  │                                   │
        mcp/server.js                        CF Worker /mcp
        server.tool()                        MCP_TOOLS/callMcpTool
                  │                                   │
                  └─────────────────┬─────────────────┘
                                    │
                             /api/command
                                    │
                         ┌──────────▼──────────┐
                         │ Server Command Queue │
                         └──────────┬──────────┘
                                    │ /api/poll
                         ┌──────────▼──────────┐
                         │ CompanionService     │
                         ├─────────────────────┤
                         │ AppGate              │
                         │ FocusMode + Sessions │
                         │ TodoState             │
                         │ WalletState           │
                         │ CalendarState         │
                         └──────────┬──────────┘
                                    │
            ┌───────────────────────┴───────────────────────┐
            │                                               │
 ScreenshotService / Accessibility               LifeState.collect()
 前台感知 + 本地确定性执行                         当前事实摘要
            │                                               │
            └───────────────────────┬───────────────────────┘
                                    │ POST /api/device/state
                         ┌──────────▼──────────┐
                         │ Recent State Cache   │
                         │ Render RAM / CF D1   │
                         └─────────────────────┘
```

最终原则保持不变：

> **苹果乐园负责记录事实和执行规则，AI负责理解事实和做判断。**

V1 的重点不是再加一堆按钮，而是把当前已有的门禁、专注、小金库、日历和新增 Todo 接到同一套可靠事实链上，让任何 AI 窗口看到的是同一个“现在”。
