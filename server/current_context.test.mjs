import assert from "node:assert/strict";
import { composeCurrentContext, CURRENT_CONTEXT_SCHEMA_VERSION, CURRENT_CONTEXT_STALE_AFTER_MS, CURRENT_CONTEXT_TODO_LIST_LIMIT } from "./current_context.mjs";

const NOW = 1_800_000_000_000;
const TODAY = "2027-01-15";
const TODAY_DEADLINE = NOW + 60_000;

function baseState() {
  return {
    updated_at_ms: NOW, local_date: TODAY, local_time: "12:00", timezone: "Asia/Tokyo",
    device_id: "android-phone", current_app: "Test App", current_package: "com.test.app",
    battery_percent: 80, charging: false, network_type: "wifi", usage_permission_ready: true,
    screen_time_today_minutes: 120, top_apps_today: [],
    app_gate: { enabled: true, effective_controls: [], effective_locks: [], effective_allows: [] },
    focus_mode: { active: false }, todo_state: { updated_at_ms: NOW, todos: [] }
  };
}

function todo(id, status = "open", { deadline = 0, dueToday = false, overdue = false } = {}) {
  return { id, title: `Todo ${id}`, status, priority: "normal", deadline_at_ms: deadline,
    deadline_at_local: deadline ? `${TODAY} 13:00:00` : "", due_today: dueToday, overdue };
}

function test(name, fn) {
  try { fn(); console.log(`PASS ${name}`); }
  catch (error) { console.error(`FAIL ${name}`); throw error; }
}

test("authoritative ALLOW suppresses conflicting LOCK", () => {
  const s = baseState();
  s.app_gate = { enabled: true,
    effective_controls: [{ decision: "ALLOW", package: "com.x", app_name: "X", reason: "explicit", source: "mcp", approved_by: "ai", type: "real_time", purpose: "break", expires_at_ms: NOW + 60000, remaining_ms: 60000 }],
    effective_locks: [{ decision: "LOCK", package: "com.x", app_name: "X" }],
    effective_allows: [{ decision: "ALLOW", package: "com.x", app_name: "X" }] };
  const c = composeCurrentContext(s, { nowMs: NOW, source: "android_fresh_command", transport: "direct_command" });
  assert.equal(c.app_gate.effective_controls.length, 1);
  assert.equal(c.app_gate.effective_controls[0].decision, "ALLOW");
  assert.equal(c.app_gate.effective_locks.length, 0);
  assert.equal(c.app_gate.effective_allows.length, 1);
  assert.equal(c.app_gate.effective_allows[0].approved_by, "ai");
});

test("expired ALLOW state restores LOCK", () => {
  const s = baseState();
  s.app_gate.effective_controls = [{ decision: "LOCK", package: "com.x", app_name: "X", expires_at_ms: NOW + 60000 }];
  const c = composeCurrentContext(s, { nowMs: NOW });
  assert.equal(c.app_gate.effective_allow_count, 0); assert.equal(c.app_gate.effective_lock_count, 1);
  assert.equal(c.app_gate.effective_controls[0].decision, "LOCK");
});

test("open Todo count", () => {
  const s = baseState(); s.todo_state.todos = [todo("a"), todo("b"), todo("c", "completed")];
  assert.equal(composeCurrentContext(s, { nowMs: NOW }).todo.open_count, 2);
});

test("overdue Todo count", () => {
  const s = baseState();
  s.todo_state.todos = [todo("a", "open", { deadline: NOW - 1000, overdue: true }), todo("b"), todo("c", "completed", { deadline: NOW - 1000, overdue: false })];
  const t = composeCurrentContext(s, { nowMs: NOW }).todo;
  assert.equal(t.overdue_count, 1); assert.deepEqual(t.overdue_todos.map(x => x.id), ["a"]);
});

test("completed Todo excluded from open and overdue", () => {
  const s = baseState(); s.todo_state.todos = [todo("done", "completed", { deadline: NOW - 1000, overdue: true })];
  const t = composeCurrentContext(s, { nowMs: NOW }).todo;
  assert.equal(t.open_count, 0); assert.equal(t.overdue_count, 0); assert.equal(t.relevant_open_todos.length, 0);
});

test("due_today differs from open_due_today", () => {
  const s = baseState();
  s.todo_state.todos = [todo("open", "open", { deadline: TODAY_DEADLINE, dueToday: true }), todo("done", "completed", { deadline: TODAY_DEADLINE, dueToday: true })];
  const t = composeCurrentContext(s, { nowMs: NOW }).todo;
  assert.equal(t.due_today_count, 2); assert.equal(t.open_due_today_count, 1); assert.deepEqual(t.open_due_today.map(x => x.id), ["open"]);
});

test("completed deadline today counts due_today only", () => {
  const s = baseState(); s.todo_state.todos = [todo("done", "completed", { deadline: TODAY_DEADLINE, dueToday: true })];
  const t = composeCurrentContext(s, { nowMs: NOW }).todo;
  assert.equal(t.due_today_count, 1); assert.equal(t.open_due_today_count, 0);
});

test("no deadline Todo never due_today", () => {
  const s = baseState(); s.todo_state.todos = [{ ...todo("no-deadline"), due_today: true, deadline_at_ms: 0, deadline_at_local: "" }];
  const t = composeCurrentContext(s, { nowMs: NOW }).todo;
  assert.equal(t.due_today_count, 0); assert.equal(t.open_due_today_count, 0);
});

test("Todo history is bounded and not dumped", () => {
  const s = baseState();
  s.todo_state.todos = [...Array.from({ length: 40 }, (_, i) => todo(`done-${i}`, "completed")), ...Array.from({ length: 20 }, (_, i) => todo(`open-${i}`, "open"))];
  const c = composeCurrentContext(s, { nowMs: NOW });
  assert.equal(Object.prototype.hasOwnProperty.call(c.todo, "todos"), false);
  assert.equal(c.todo.relevant_open_todos.length, CURRENT_CONTEXT_TODO_LIST_LIMIT);
  assert.equal(c.todo.relevant_open_todos.some(x => x.status === "completed"), false);
  assert.equal(JSON.stringify(c.todo).includes("done-39"), false);
});

test("freshness stale boundary", () => {
  const fresh = baseState(); fresh.updated_at_ms = NOW - CURRENT_CONTEXT_STALE_AFTER_MS + 1;
  assert.equal(composeCurrentContext(fresh, { nowMs: NOW }).freshness.snapshot.stale, false);
  const stale = baseState(); stale.updated_at_ms = NOW - CURRENT_CONTEXT_STALE_AFTER_MS;
  const c = composeCurrentContext(stale, { nowMs: NOW });
  assert.equal(c.freshness.snapshot.stale, true); assert.equal(c.freshness.snapshot.age_ms, CURRENT_CONTEXT_STALE_AFTER_MS);
});

test("attention_items are objective structured facts", () => {
  const s = baseState(); s.updated_at_ms = NOW - CURRENT_CONTEXT_STALE_AFTER_MS;
  s.todo_state.todos = [todo("late", "open", { deadline: NOW - 1000, overdue: true })];
  s.focus_mode = { active: true, temporary_active: false };
  s.app_gate.effective_controls = [{ decision: "ALLOW", package: "com.x", remaining_ms: 5000, expires_at_ms: NOW + 5000 }];
  const items = composeCurrentContext(s, { nowMs: NOW }).attention_items;
  assert.deepEqual(items.map(x => x.type), ["todo_overdue", "temporary_allow_active", "focus_active", "state_stale"]);
  const text = JSON.stringify(items);
  for (const banned of ["懒", "批评", "应该", "必须学习", "娱乐", "偷懒"]) assert.equal(text.includes(banned), false);
});

test("schema_version stable and parseable", () => {
  const c = composeCurrentContext(baseState(), { nowMs: NOW });
  assert.equal(c.schema_version, CURRENT_CONTEXT_SCHEMA_VERSION); assert.equal(JSON.parse(JSON.stringify(c)).schema_version, 1);
});

console.log("CurrentContextBehaviorTest: ALL PASS");
