#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def replace_once(path, old, new):
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly 1 match in {path}, found {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")

replace_once(
    "android/app/src/main/java/dev/linjian/peek/TodoStateCore.java",
    '''    JSONArray query(String filter, String id, String category, String status, long nowMs) {\n        JSONArray out = new JSONArray();\n        String f = clean(filter).toLowerCase(Locale.US);\n        if (f.isEmpty()) f = "all";\n        String wantedId = clean(id);\n        String wantedCategory = clean(category);\n        String wantedStatus = normalizeStatusFilter(status);\n''',
    '''    JSONArray query(String filter, String id, String category, String status, long nowMs) {\n        JSONArray out = new JSONArray();\n        String f = normalizeFilter(filter);\n        String wantedId = clean(id);\n        String wantedCategory = clean(category);\n        String wantedStatus = normalizeStatusFilter(status);\n'''
)

replace_once(
    "android/app/src/main/java/dev/linjian/peek/TodoStateCore.java",
    '''    private static String normalizeStatusFilter(String value) {\n        String v = clean(value).toLowerCase(Locale.US);\n        if (STATUS_OPEN.equals(v) || STATUS_COMPLETED.equals(v)) return v;\n        return "";\n    }\n''',
    '''    private static String normalizeFilter(String value) {\n        String v = clean(value).toLowerCase(Locale.US);\n        if (v.isEmpty()) return "all";\n        if ("all".equals(v) || STATUS_OPEN.equals(v) || STATUS_COMPLETED.equals(v)\n                || "overdue".equals(v) || "due_today".equals(v)) return v;\n        throw new IllegalArgumentException("invalid_filter:" + v);\n    }\n\n    private static String normalizeStatusFilter(String value) {\n        String v = clean(value).toLowerCase(Locale.US);\n        if (v.isEmpty()) return "";\n        if (STATUS_OPEN.equals(v) || STATUS_COMPLETED.equals(v)) return v;\n        throw new IllegalArgumentException("invalid_status:" + v);\n    }\n'''
)

replace_once(
    "android/tests/TodoStateBehaviorTest.java",
    '''        core.update("todo_b", todayPatch, now + 80L);\n        check(TodoStateCore.isDueToday(core.get("todo_b"), now), "same local date deadline must be due_today");\n\n        // 9. Serialize -> read must preserve durable fields and ids.\n''',
    '''        core.update("todo_b", todayPatch, now + 80L);\n        check(TodoStateCore.isDueToday(core.get("todo_b"), now), "same local date deadline must be due_today");\n\n        // Query contract closeout: valid enums succeed; invalid values fail explicitly.\n        check(core.query("due_today", "", "", "", now).length() >= 1, "due_today filter must be accepted");\n\n        boolean invalidFilterRejected = false;\n        try { core.query("due-today", "", "", "", now); }\n        catch (IllegalArgumentException expected) {\n            invalidFilterRejected = "invalid_filter:due-today".equals(expected.getMessage());\n        }\n        check(invalidFilterRejected, "invalid filter must fail with invalid_filter");\n\n        check(core.query("all", "", "", "open", now).length() == 1, "status=open must filter to open Todos");\n        check(core.query("all", "", "", "completed", now).length() == 1, "status=completed must filter to completed Todos");\n        check(core.query("all", "", "", "", now).length() == 2, "missing status must mean no status filter");\n\n        boolean invalidStatusRejected = false;\n        try { core.query("all", "", "", "done", now); }\n        catch (IllegalArgumentException expected) {\n            invalidStatusRejected = "invalid_status:done".equals(expected.getMessage());\n        }\n        check(invalidStatusRejected, "invalid status must fail with invalid_status");\n\n        // 9. Serialize -> read must preserve durable fields and ids.\n'''
)

replace_once(
    "server/cloudflare-worker/worker.js",
    '''  { name: "get_todos", description: "读取苹果乐园 Android 本地持久化的 Todo 正式事实。Todo 为所有聊天窗口共享；filter 支持 all/open/completed/overdue/due_today，due_today 仅表示 deadline 日期是今天。", inputSchema: obj({ filter: str("all"), id: str(undefined), category: str(undefined), status: str(undefined), device_id: str(DEFAULT_DEVICE), wait_seconds: int(8) }) },\n  { name: "todo_action", description: "创建、修改、完成、重新打开或删除 Todo 正式共享事实。写操作会改变所有聊天窗口之后读到的状态；不得仅因当前窗口主观猜测任务已完成就擅自 complete/delete，只有用户明确表达或可靠事实足以确认时才修改。", inputSchema: obj({ operation: str(undefined), id: str(undefined), title: str(undefined), note: str(undefined), category: str(undefined), priority: str(undefined), deadline_at_ms: num(undefined), deadline: str(undefined), clear_deadline: bool(false), device_id: str(DEFAULT_DEVICE), wait_seconds: int(8) }, ["operation"]) },\n''',
    '''  { name: "get_todos", description: "读取苹果乐园 Android 本地持久化的 Todo 正式事实。Todo 为所有聊天窗口共享；filter 支持 all/open/completed/overdue/due_today，due_today 仅表示 deadline 日期是今天。", inputSchema: obj({ filter: { type: "string", enum: ["all", "open", "completed", "overdue", "due_today"], default: "all" }, id: str(undefined), category: str(undefined), status: { type: "string", enum: ["open", "completed"] }, device_id: str(DEFAULT_DEVICE), wait_seconds: int(8) }) },\n  { name: "todo_action", description: "创建、修改、完成、重新打开或删除 Todo 正式共享事实。写操作会改变所有聊天窗口之后读到的状态；不得仅因当前窗口主观猜测任务已完成就擅自 complete/delete，只有用户明确表达或可靠事实足以确认时才修改。", inputSchema: obj({ operation: { type: "string", enum: ["create", "update", "complete", "reopen", "delete"] }, id: str(undefined), title: str(undefined), note: str(undefined), category: str(undefined), priority: str(undefined), deadline_at_ms: num(undefined), deadline: str(undefined), clear_deadline: bool(false), device_id: str(DEFAULT_DEVICE), wait_seconds: int(8) }, ["operation"]) },\n'''
)

print("Phase 2 Todo closeout patch applied")
