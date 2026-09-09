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
    focus_mode: { active: false }, todo_state: { updated_at_ms: NOW, todos: [] },
    schedule_state: { available:true,source:"android_local",updated_at_ms:NOW,queried_at_ms:NOW,current_plan:null,next_plan:null,
      today_remaining_plan_count:0,today_remaining_plans:[],remaining_plan_count:0,remaining_plans:[],remaining_scope:"today",
      next_plan_search:{from_ms:NOW,to_ms:NOW+8*86400000,future_local_days:7,bounded:true,status:"none_within_window"},
      current_actual:null,focus_projection_available:true }
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



test("Schedule missing is unavailable, not an empty day", () => {
  const s=baseState();delete s.schedule_state;
  const c=composeCurrentContext(s,{nowMs:NOW});
  assert.equal(c.schedule.available,false);assert.equal(c.freshness.schedule.stale,true);
  assert.equal(c.freshness.snapshot.partial,true);assert.equal("remaining_plan_count" in c.schedule,false);
});
test("Schedule current/next/remaining and actual stay separate and bounded", () => {
  const s=baseState();const block=(id,kind,start,end)=>({id,kind,title:id,start_at_ms:start,end_at_ms:end,todo_id:"todo-a",source:"user"});
  s.schedule_state.current_plan=block("p","plan",NOW-1000,NOW+1000);
  s.schedule_state.next_plan=block("n","plan",NOW+2000,NOW+3000);
  s.schedule_state.next_plan_search={from_ms:NOW,to_ms:NOW+8*86400000,future_local_days:7,bounded:true,status:"found"};
  s.schedule_state.current_actual=block("a","actual",NOW-500,NOW+500);
  s.schedule_state.today_remaining_plans=Array.from({length:30},(_,i)=>block("p"+i,"plan",NOW+(i+1)*1000,NOW+(i+1)*1000+500));
  s.schedule_state.today_remaining_plan_count=30;s.schedule_state.blocks=Array.from({length:100},(_,i)=>block("history"+i,"actual",NOW-i*1000,NOW-i*1000+500));
  const c=composeCurrentContext(s,{nowMs:NOW});
  assert.equal(c.schedule.current_plan.id,"p");assert.equal(c.schedule.next_plan.id,"n");assert.equal(c.schedule.current_actual.id,"a");
  assert.equal(c.schedule.today_remaining_plan_count,30);assert.equal(c.schedule.today_remaining_plans.length,6);
  assert.equal(c.schedule.remaining_scope,"today");assert.equal(c.schedule.next_plan_search.status,"found");
  assert.equal(JSON.stringify(c.schedule).includes("history99"),false);assert.equal("blocks" in c.schedule,false);
  assert.equal(c.todo.open_count,0);assert.equal(c.schedule.current_plan.todo_id,"todo-a");
});
test("Schedule next plan can be tomorrow while today remaining is empty", () => {
  const s=baseState();const tomorrow={id:"tomorrow",kind:"plan",title:"明天",start_at_ms:NOW+20*3600000,end_at_ms:NOW+21*3600000,source:"user"};
  s.schedule_state.next_plan=tomorrow;s.schedule_state.next_plan_search={from_ms:NOW,to_ms:NOW+8*86400000,future_local_days:7,bounded:true,status:"found"};
  const c=composeCurrentContext(s,{nowMs:NOW});
  assert.equal(c.schedule.current_plan,null);assert.equal(c.schedule.today_remaining_plan_count,0);
  assert.equal(c.schedule.next_plan.id,"tomorrow");assert.equal(c.schedule.next_plan_available,true);
});
test("Schedule no next plan is explicitly bounded", () => {
  const c=composeCurrentContext(baseState(),{nowMs:NOW});
  assert.equal(c.schedule.next_plan,null);assert.equal(c.schedule.next_plan_search.status,"none_within_window");
  assert.equal(c.schedule.next_plan_search.bounded,true);assert.equal(c.schedule.next_plan_available,true);
});
test("Schedule missing next search metadata does not claim global absence", () => {
  const s=baseState();delete s.schedule_state.next_plan_search;
  const c=composeCurrentContext(s,{nowMs:NOW});
  assert.equal(c.schedule.available,true);assert.equal(c.schedule.degraded,true);assert.equal(c.schedule.next_plan_available,false);
  assert.equal(c.schedule.next_plan_search.status,"unavailable");assert.equal(c.freshness.snapshot.partial,true);
});
test("Schedule degraded source preserves other successful modules", () => {
  const s=baseState();s.schedule_state.degraded=true;s.schedule_state.focus_projection_available=false;
  const c=composeCurrentContext(s,{nowMs:NOW});assert.equal(c.schedule.available,true);assert.equal(c.schedule.degraded,true);
  assert.equal(c.freshness.snapshot.partial,true);assert.equal(c.todo.available,true);assert.equal(c.app_gate.available,true);
});
test("Schedule source freshness uses its actual read timestamp", () => {
  const s=baseState();s.schedule_state.queried_at_ms=NOW-60000;
  const c=composeCurrentContext(s,{nowMs:NOW});assert.equal(c.schedule.stale,true);assert.equal(c.freshness.schedule.stale,true);
  assert.equal(c.freshness.snapshot.stale,false);
});
test("Ongoing Focus is a read-only observation, not a completed actual", () => {
  const s=baseState();s.focus_mode={active:true,session_id:"X",started_at_ms:NOW-60000,goal:"学习",todo_id:"todo-a",category:"考研"};
  const c=composeCurrentContext(s,{nowMs:NOW});assert.equal(c.schedule.current_actual.ongoing,true);
  assert.equal(c.schedule.current_actual.focus_session_id,"X");assert.equal("end_at_ms" in c.schedule.current_actual,false);
  const old=baseState();old.updated_at_ms=NOW-60000;old.schedule_state.queried_at_ms=NOW-60000;old.focus_mode=s.focus_mode;
  const cached=composeCurrentContext(old,{nowMs:NOW});assert.equal(cached.schedule.current_actual.observed_at_ms,NOW-60000);
});
test("Malformed Schedule facts are not silently converted to empty", () => {
  const s=baseState();s.schedule_state.current_plan={id:"bad",kind:"plan",start_at_ms:NOW,end_at_ms:NOW-1};
  const c=composeCurrentContext(s,{nowMs:NOW});assert.equal(c.schedule.available,false);assert.equal(c.schedule.degraded,true);
});

console.log("CurrentContextBehaviorTest: ALL PASS");
