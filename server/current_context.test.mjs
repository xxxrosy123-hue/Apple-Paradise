import assert from "node:assert/strict";
import { composeCurrentContext, CURRENT_CONTEXT_SCHEMA_VERSION, CURRENT_CONTEXT_STALE_AFTER_MS, CURRENT_CONTEXT_FUTURE_SKEW_TOLERANCE_MS, CURRENT_CONTEXT_TODO_LIST_LIMIT } from "./current_context.mjs";

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


test("missing Todo is unavailable, not empty", () => {
  const s = baseState(); delete s.todo_state;
  const t = composeCurrentContext(s, { nowMs: NOW }).todo;
  assert.equal(t.available, false);
  assert.equal(Object.prototype.hasOwnProperty.call(t, "open_count"), false);
});

test("missing AppGate is unavailable, not authoritative empty", () => {
  const s = baseState(); delete s.app_gate;
  const g = composeCurrentContext(s, { nowMs: NOW }).app_gate;
  assert.equal(g.available, false); assert.equal(g.authoritative, false);
  assert.equal(Object.prototype.hasOwnProperty.call(g, "effective_controls"), false);
});

test("missing Focus is unavailable, not inactive", () => {
  const s = baseState(); delete s.focus_mode;
  const f = composeCurrentContext(s, { nowMs: NOW }).focus;
  assert.equal(f.available, false);
  assert.equal(Object.prototype.hasOwnProperty.call(f, "active"), false);
});

test("partial LifeState marks snapshot degraded and preserves successful modules", () => {
  const s = baseState(); s.error = "wallet_collect_failed"; delete s.focus_mode;
  const c = composeCurrentContext(s, { nowMs: NOW });
  assert.equal(c.freshness.snapshot.partial, true);
  assert.equal(c.freshness.snapshot.degraded, true);
  assert.equal(c.freshness.snapshot.error, "wallet_collect_failed");
  assert.equal(c.freshness.snapshot.stale, false);
  assert.equal(c.todo.available, true); assert.equal(c.app_gate.available, true);
  assert.equal(c.focus.available, false);
  assert.equal(Object.prototype.hasOwnProperty.call(c.focus, "active"), false);
});

test("future timestamp with material clock skew is stale", () => {
  const s = baseState(); s.updated_at_ms = NOW + 60 * 60 * 1000;
  const f = composeCurrentContext(s, { nowMs: NOW }).freshness.snapshot;
  assert.equal(f.stale, true); assert.equal(f.age_ms, null); assert.equal(f.clock_skew, true);
  assert.equal(f.future_by_ms, 60 * 60 * 1000);
});

test("small future timestamp tolerance does not create clock-skew failure", () => {
  const s = baseState(); s.updated_at_ms = NOW + CURRENT_CONTEXT_FUTURE_SKEW_TOLERANCE_MS;
  const f = composeCurrentContext(s, { nowMs: NOW }).freshness.snapshot;
  assert.equal(f.stale, false); assert.equal(f.age_ms, 0); assert.equal(f.clock_skew, false);
});

test("legacy AppGate arrays are diagnostic only and never re-arbitrated", () => {
  const s = baseState();
  s.app_gate = { enabled: true,
    effective_locks: [{ decision: "LOCK", package: "com.x", app_name: "X" }],
    effective_allows: [{ decision: "ALLOW", package: "com.x", app_name: "X", remaining_ms: 5000 }] };
  const c = composeCurrentContext(s, { nowMs: NOW });
  const g = c.app_gate;
  assert.equal(g.available, false); assert.equal(g.authoritative, false); assert.equal(g.degraded, true);
  assert.equal(Object.prototype.hasOwnProperty.call(g, "effective_controls"), false);
  assert.equal(g.diagnostic_effective_locks.length, 1); assert.equal(g.diagnostic_effective_allows.length, 1);
  assert.equal(c.attention_items.some(x => x.type === "temporary_allow_active"), false);
});

test("Todo bounded lists stay within limit", () => {
  const s = baseState();
  s.todo_state.todos = Array.from({ length: 20 }, (_, i) => todo(`late-${i}`, "open", { deadline: NOW - 1000 - i, dueToday: true, overdue: true }));
  const t = composeCurrentContext(s, { nowMs: NOW }).todo;
  assert.ok(t.overdue_todos.length <= CURRENT_CONTEXT_TODO_LIST_LIMIT);
  assert.ok(t.open_due_today.length <= CURRENT_CONTEXT_TODO_LIST_LIMIT);
});

test("fresh snapshot has no state_stale attention", () => {
  const c = composeCurrentContext(baseState(), { nowMs: NOW });
  assert.equal(c.freshness.snapshot.stale, false);
  assert.equal(c.attention_items.some(x => x.type === "state_stale"), false);
});

test("temporary allow attention never exposes negative expires_in_ms", () => {
  const s = baseState();
  s.app_gate.effective_controls = [{ decision: "ALLOW", package: "com.x", remaining_ms: -500, expires_at_ms: NOW + 5000 }];
  const item = composeCurrentContext(s, { nowMs: NOW }).attention_items.find(x => x.type === "temporary_allow_active");
  assert.ok(item); assert.ok(item.expires_in_ms >= 0);
});

test("missing updated_at is stale", () => {
  const s = baseState(); delete s.updated_at_ms;
  const f = composeCurrentContext(s, { nowMs: NOW }).freshness.snapshot;
  assert.equal(f.stale, true); assert.equal(f.age_ms, null); assert.equal(f.clock_skew, false);
});

test("active Focus exposes only current Todo-session linkage", () => {
  const s = baseState();
  s.focus_mode = { active: true, session_id: "focus_123", todo_id: "todo_abc", category: "考研",
    scope: "full_phone", started_at_ms: NOW - 5000, until_ms: NOW + 60000, temporary_active: false };
  const f = composeCurrentContext(s, { nowMs: NOW }).focus;
  assert.equal(f.available, true); assert.equal(f.active, true);
  assert.equal(f.session_id, "focus_123"); assert.equal(f.todo_id, "todo_abc"); assert.equal(f.category, "考研");
  assert.equal(Object.prototype.hasOwnProperty.call(f, "sessions"), false);
});

console.log("CurrentContextBehaviorTest: ALL PASS");
