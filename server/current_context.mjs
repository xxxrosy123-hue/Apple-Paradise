export const CURRENT_CONTEXT_SCHEMA_VERSION = 1;
export const CURRENT_CONTEXT_STALE_AFTER_MS = 30000;
export const CURRENT_CONTEXT_TODO_LIST_LIMIT = 8;

function isObject(value) {
  return value && typeof value === "object" && !Array.isArray(value);
}

function numberOrNull(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function positiveNumberOrNull(value) {
  const n = numberOrNull(value);
  return n !== null && n > 0 ? n : null;
}

function put(out, key, value) {
  if (value === undefined || value === null || value === "") return;
  out[key] = value;
}

function copyKnown(source, keys) {
  const out = {};
  for (const key of keys) {
    if (source && Object.prototype.hasOwnProperty.call(source, key)) put(out, key, source[key]);
  }
  return out;
}

function freshnessEntry(updatedAtMs, nowMs, source, transport, staleAfterMs) {
  const timestamp = positiveNumberOrNull(updatedAtMs);
  const ageMs = timestamp === null ? null : Math.max(0, nowMs - timestamp);
  return {
    source,
    transport,
    updated_at_ms: timestamp,
    age_ms: ageMs,
    stale_after_ms: staleAfterMs,
    stale: ageMs === null || ageMs >= staleAfterMs
  };
}

function normalizeDecision(decision) {
  return String(decision || "").trim().toUpperCase();
}

function sanitizeControl(raw) {
  if (!isObject(raw)) return null;
  const decision = normalizeDecision(raw.decision);
  const pkg = String(raw.package || "").trim();
  if (!pkg || (decision !== "ALLOW" && decision !== "LOCK")) return null;
  const out = copyKnown(raw, [
    "decision", "package", "app_name", "decision_id", "reason", "source", "approved_by",
    "type", "purpose", "mode", "created_at_ms", "created_at_local", "start_at_ms",
    "expires_at_ms", "expires_at_local", "remaining_ms", "allowed_ms", "used_ms", "active"
  ]);
  out.decision = decision;
  out.package = pkg;
  return out;
}

function authoritativeControls(appGate) {
  if (!isObject(appGate)) return [];
  const explicit = Array.isArray(appGate.effective_controls) ? appGate.effective_controls : null;
  const source = explicit || [
    ...(Array.isArray(appGate.effective_allows) ? appGate.effective_allows : []),
    ...(Array.isArray(appGate.effective_locks) ? appGate.effective_locks : [])
  ];
  const byPackage = new Map();
  for (const item of source) {
    const control = sanitizeControl(item);
    if (!control) continue;
    const previous = byPackage.get(control.package);
    if (!previous || (control.decision === "ALLOW" && previous.decision !== "ALLOW")) {
      byPackage.set(control.package, control);
    }
  }
  return Array.from(byPackage.values());
}

function compactTodo(raw) {
  if (!isObject(raw)) return null;
  const id = String(raw.id || "").trim();
  const title = String(raw.title || "").trim();
  if (!id || !title) return null;
  const out = copyKnown(raw, [
    "id", "title", "status", "category", "priority", "deadline_at_ms", "deadline_at_local",
    "updated_at_ms", "overdue", "due_today"
  ]);
  out.id = id;
  out.title = title;
  out.status = String(raw.status || "open") === "completed" ? "completed" : "open";
  return out;
}

function todoDueToday(todo, localDate) {
  if (!todo) return false;
  const deadlineMs = positiveNumberOrNull(todo.deadline_at_ms);
  if (deadlineMs === null) return false;
  if (typeof todo.due_today === "boolean") return todo.due_today;
  const local = String(todo.deadline_at_local || todo.deadline || "");
  return Boolean(localDate && local.startsWith(localDate));
}

function todoOverdue(todo, nowMs) {
  if (!todo || String(todo.status || "open") !== "open") return false;
  if (typeof todo.overdue === "boolean") return todo.overdue;
  const deadlineMs = positiveNumberOrNull(todo.deadline_at_ms);
  return deadlineMs !== null && nowMs > deadlineMs;
}

function sortRelevantTodos(a, b) {
  const ao = a.overdue ? 1 : 0;
  const bo = b.overdue ? 1 : 0;
  if (ao !== bo) return bo - ao;
  const ad = a.due_today ? 1 : 0;
  const bd = b.due_today ? 1 : 0;
  if (ad !== bd) return bd - ad;
  const adeadline = positiveNumberOrNull(a.deadline_at_ms);
  const bdeadline = positiveNumberOrNull(b.deadline_at_ms);
  if (adeadline !== null || bdeadline !== null) {
    if (adeadline === null) return 1;
    if (bdeadline === null) return -1;
    if (adeadline !== bdeadline) return adeadline - bdeadline;
  }
  const au = numberOrNull(a.updated_at_ms) || 0;
  const bu = numberOrNull(b.updated_at_ms) || 0;
  return bu - au || String(a.id).localeCompare(String(b.id));
}

function composeTodo(todoState, localDate, nowMs) {
  const rawTodos = isObject(todoState) && Array.isArray(todoState.todos) ? todoState.todos : [];
  const all = rawTodos.map(compactTodo).filter(Boolean).map((todo) => {
    todo.due_today = todoDueToday(todo, localDate);
    todo.overdue = todoOverdue(todo, nowMs);
    return todo;
  });
  const open = all.filter((todo) => todo.status === "open");
  const overdue = open.filter((todo) => todo.overdue).sort(sortRelevantTodos);
  const dueToday = all.filter((todo) => todo.due_today);
  const openDueToday = open.filter((todo) => todo.due_today).sort(sortRelevantTodos);
  const relevant = [...open].sort(sortRelevantTodos);
  const out = {
    open_count: open.length,
    overdue_count: overdue.length,
    due_today_count: dueToday.length,
    open_due_today_count: openDueToday.length,
    overdue_todos: overdue.slice(0, CURRENT_CONTEXT_TODO_LIST_LIMIT),
    open_due_today: openDueToday.slice(0, CURRENT_CONTEXT_TODO_LIST_LIMIT),
    relevant_open_todos: relevant.slice(0, CURRENT_CONTEXT_TODO_LIST_LIMIT)
  };
  const updated = positiveNumberOrNull(todoState?.updated_at_ms);
  if (updated !== null) out.state_updated_at_ms = updated;
  return out;
}

function composeDevice(state) {
  const out = {};
  put(out, "local_time", state.local_time);
  put(out, "local_date", state.local_date);
  put(out, "timezone", state.timezone);
  put(out, "device_id", state.device_id);
  put(out, "foreground_app", state.current_app);
  put(out, "foreground_package", state.current_package);
  const battery = numberOrNull(state.battery_percent);
  if (battery !== null && battery >= 0) out.battery_percent = battery;
  if (typeof state.charging === "boolean") out.charging = state.charging;
  put(out, "charging_type", state.charging_type);
  put(out, "network", state.network_type);
  if (typeof state.screen_on === "boolean") out.screen_on = state.screen_on;
  return out;
}

function composeAppGate(appGate) {
  const controls = authoritativeControls(appGate);
  const allows = controls.filter((control) => control.decision === "ALLOW");
  const locks = controls.filter((control) => control.decision === "LOCK");
  const out = {
    effective_controls: controls,
    effective_locks: locks,
    effective_allows: allows,
    effective_lock_count: locks.length,
    effective_allow_count: allows.length
  };
  if (typeof appGate?.enabled === "boolean") out.enabled = appGate.enabled;
  return out;
}

function composeFocus(raw) {
  const focus = isObject(raw) ? raw : {};
  const out = { active: Boolean(focus.active) };
  if (typeof focus.enabled === "boolean") out.enabled = focus.enabled;
  for (const key of [
    "goal", "reason", "scope", "managed_by_ai", "started_at_ms", "started_at_local",
    "until_ms", "until_local", "remaining_ms", "temporary_active", "temporary_until_ms",
    "temporary_remaining_ms", "emergency_remaining"
  ]) {
    if (Object.prototype.hasOwnProperty.call(focus, key)) put(out, key, focus[key]);
  }
  return out;
}

function composeUsage(state) {
  const ready = Boolean(state.usage_permission_ready);
  const out = { available: ready, permission_ready: ready };
  if (!ready) return out;
  const screenMinutes = numberOrNull(state.screen_time_today_minutes);
  if (screenMinutes !== null && screenMinutes >= 0) out.today_screen_time_minutes = screenMinutes;
  const unlocks = numberOrNull(state.unlock_count_today);
  if (unlocks !== null && unlocks >= 0) out.unlock_count_today = unlocks;
  put(out, "last_unlock_at", state.last_unlock_at);
  const top = Array.isArray(state.top_apps_today) ? state.top_apps_today : [];
  out.top_apps = top.slice(0, 5).map((item) => copyKnown(item, ["app", "package", "minutes"]));
  return out;
}

function composeAttention({ appGate, todo, focus, freshness }) {
  const items = [];
  if (todo.overdue_count > 0) items.push({ type: "todo_overdue", count: todo.overdue_count });
  for (const allow of appGate.effective_allows) {
    const remaining = numberOrNull(allow.remaining_ms);
    const expiresAt = positiveNumberOrNull(allow.expires_at_ms);
    const item = { type: "temporary_allow_active", package: allow.package };
    if (remaining !== null) item.expires_in_ms = Math.max(0, remaining);
    else if (expiresAt !== null) item.expires_at_ms = expiresAt;
    items.push(item);
  }
  if (focus.active) {
    const item = { type: "focus_active" };
    if (typeof focus.temporary_active === "boolean") item.temporary_release_active = focus.temporary_active;
    items.push(item);
  }
  if (freshness.stale) items.push({ type: "state_stale", age_ms: freshness.age_ms, stale_after_ms: freshness.stale_after_ms });
  return items;
}

export function composeCurrentContext(lifeState, options = {}) {
  const state = isObject(lifeState) ? lifeState : {};
  const nowMs = numberOrNull(options.nowMs) ?? Date.now();
  const source = String(options.source || "android_state_upload");
  const transport = String(options.transport || "server_cache");
  const staleAfterMs = positiveNumberOrNull(options.staleAfterMs) ?? CURRENT_CONTEXT_STALE_AFTER_MS;
  const stateUpdatedAt = positiveNumberOrNull(state.updated_at_ms);
  const freshness = freshnessEntry(stateUpdatedAt, nowMs, source, transport, staleAfterMs);

  const device = composeDevice(state);
  const appGate = composeAppGate(state.app_gate);
  const todo = composeTodo(state.todo_state, String(state.local_date || ""), nowMs);
  const focus = composeFocus(state.focus_mode);
  const usage = composeUsage(state);
  const attentionItems = composeAttention({ appGate, todo, focus, freshness });

  return {
    schema_version: CURRENT_CONTEXT_SCHEMA_VERSION,
    generated_at_ms: nowMs,
    device,
    app_gate: appGate,
    todo,
    focus,
    usage,
    attention_items: attentionItems,
    freshness: {
      snapshot: freshness,
      device: { ...freshness },
      app_gate: { ...freshness },
      todo: { ...freshness, ...(todo.state_updated_at_ms ? { data_updated_at_ms: todo.state_updated_at_ms } : {}) },
      focus: { ...freshness },
      usage: { ...freshness }
    }
  };
}
