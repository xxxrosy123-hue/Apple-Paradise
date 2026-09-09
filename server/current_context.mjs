export const CURRENT_CONTEXT_SCHEMA_VERSION = 1;
export const CURRENT_CONTEXT_STALE_AFTER_MS = 30000;
export const CURRENT_CONTEXT_FUTURE_SKEW_TOLERANCE_MS = 5000;
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
  const futureByMs = timestamp === null ? null : timestamp - nowMs;
  const clockSkew = futureByMs !== null && futureByMs > CURRENT_CONTEXT_FUTURE_SKEW_TOLERANCE_MS;
  const ageMs = timestamp === null || clockSkew ? null : Math.max(0, nowMs - timestamp);
  const out = {
    source,
    transport,
    updated_at_ms: timestamp,
    age_ms: ageMs,
    stale_after_ms: staleAfterMs,
    stale: ageMs === null || ageMs >= staleAfterMs,
    clock_skew: clockSkew
  };
  if (clockSkew) out.future_by_ms = futureByMs;
  return out;
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
  if (!isObject(appGate) || !Array.isArray(appGate.effective_controls)) return null;
  const controls = [];
  for (const item of appGate.effective_controls) {
    const control = sanitizeControl(item);
    if (!control) return null;
    controls.push(control);
  }
  return controls;
}

function diagnosticControls(raw) {
  if (!Array.isArray(raw)) return [];
  return raw.map(sanitizeControl).filter(Boolean);
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
  const updated = positiveNumberOrNull(todoState?.updated_at_ms);
  if (!isObject(todoState) || !Array.isArray(todoState.todos)) {
    const unavailable = { available: false };
    if (isObject(todoState)) unavailable.degraded = true;
    if (updated !== null) unavailable.state_updated_at_ms = updated;
    return unavailable;
  }
  const all = todoState.todos.map(compactTodo).filter(Boolean).map((todo) => {
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
    available: true,
    open_count: open.length,
    overdue_count: overdue.length,
    due_today_count: dueToday.length,
    open_due_today_count: openDueToday.length,
    overdue_todos: overdue.slice(0, CURRENT_CONTEXT_TODO_LIST_LIMIT),
    open_due_today: openDueToday.slice(0, CURRENT_CONTEXT_TODO_LIST_LIMIT),
    relevant_open_todos: relevant.slice(0, CURRENT_CONTEXT_TODO_LIST_LIMIT)
  };
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
  if (!isObject(appGate)) return { available: false, authoritative: false };
  const controls = authoritativeControls(appGate);
  if (controls === null) {
    const out = { available: false, authoritative: false, degraded: true };
    if (typeof appGate.enabled === "boolean") out.enabled = appGate.enabled;
    if (Array.isArray(appGate.effective_locks)) out.diagnostic_effective_locks = diagnosticControls(appGate.effective_locks);
    if (Array.isArray(appGate.effective_allows)) out.diagnostic_effective_allows = diagnosticControls(appGate.effective_allows);
    return out;
  }
  const allows = controls.filter((control) => control.decision === "ALLOW");
  const locks = controls.filter((control) => control.decision === "LOCK");
  const out = {
    available: true,
    authoritative: true,
    effective_controls: controls,
    effective_locks: locks,
    effective_allows: allows,
    effective_lock_count: locks.length,
    effective_allow_count: allows.length
  };
  if (typeof appGate.enabled === "boolean") out.enabled = appGate.enabled;
  return out;
}

function composeFocus(raw) {
  if (!isObject(raw) || typeof raw.active !== "boolean") {
    return { available: false, ...(isObject(raw) ? { degraded: true } : {}) };
  }
  const out = { available: true, active: raw.active };
  if (typeof raw.enabled === "boolean") out.enabled = raw.enabled;
  for (const key of [
    "goal", "reason", "scope", "managed_by_ai", "session_id", "todo_id", "category", "started_at_ms", "started_at_local",
    "until_ms", "until_local", "remaining_ms", "temporary_active", "temporary_until_ms",
    "temporary_remaining_ms", "emergency_remaining"
  ]) {
    if (Object.prototype.hasOwnProperty.call(raw, key)) put(out, key, raw[key]);
  }
  return out;
}

export const CURRENT_CONTEXT_SCHEDULE_LIST_LIMIT = 6;

function compactScheduleBlock(raw) {
  if (!isObject(raw) || !String(raw.id || "").trim() || !["plan", "actual"].includes(raw.kind)) return null;
  const start = positiveNumberOrNull(raw.start_at_ms);
  const end = positiveNumberOrNull(raw.end_at_ms);
  // An ongoing Focus observation has a reliable start but no sealed actual end.
  if (start === null || (end === null && raw.ongoing !== true) || (end !== null && end <= start)) return null;
  return copyKnown(raw, ["id", "kind", "title", "category", "color", "start_at_ms", "end_at_ms", "todo_id", "focus_session_id", "source", "source_link", "user_overridden", "override_source", "override_at_ms", "series_id", "occurrence_date", "ongoing", "observed_at_ms"]);
}

function compactNextPlanSearch(raw, next) {
  if (!isObject(raw) || raw.bounded !== true || !["found", "none_within_window"].includes(raw.status)) return null;
  const from = positiveNumberOrNull(raw.from_ms);
  const to = positiveNumberOrNull(raw.to_ms);
  const days = numberOrNull(raw.future_local_days);
  if (from === null || to === null || to <= from || days === null || days < 1 || days > 31) return null;
  if (raw.status === "found" && (!next || next.start_at_ms < from || next.start_at_ms >= to)) return null;
  if (raw.status === "none_within_window" && next) return null;
  return { from_ms:from, to_ms:to, future_local_days:days, bounded:true, status:raw.status };
}

function composeSchedule(raw, focus, observedAtMs) {
  const remainingSource = Array.isArray(raw?.today_remaining_plans) ? raw.today_remaining_plans : raw?.remaining_plans;
  if (!isObject(raw) || raw.available !== true || !Array.isArray(remainingSource))
    return { available: false, ...(isObject(raw) ? { degraded: true, ...(raw.error ? {error:String(raw.error)} : {}) } : {}) };
  const current = compactScheduleBlock(raw.current_plan);
  const rawNext = compactScheduleBlock(raw.next_plan);
  const search = compactNextPlanSearch(raw.next_plan_search, rawNext);
  const next = search ? rawNext : null;
  const remaining = remainingSource.map(compactScheduleBlock).filter(x => x && x.kind === "plan").slice(0, CURRENT_CONTEXT_SCHEDULE_LIST_LIMIT);
  let actual = compactScheduleBlock(raw.current_actual);
  if (!actual && focus.available && focus.active && String(focus.session_id || "").trim() && positiveNumberOrNull(focus.started_at_ms) !== null && focus.started_at_ms <= observedAtMs) {
    // Read-only observation of the ongoing Focus, not a completed actual block.
    actual = { id:"ongoing:"+focus.session_id, kind:"actual", source:"focus_session", focus_session_id:focus.session_id,
      title:focus.goal || "专注中", category:focus.category || "", todo_id:focus.todo_id || "",
      start_at_ms:focus.started_at_ms, ongoing:true, observed_at_ms:observedAtMs };
  }
  const invalid = (raw.current_plan != null && (!current || current.kind !== "plan")) ||
    (raw.next_plan != null && (!rawNext || rawNext.kind !== "plan")) ||
    (raw.current_actual != null && (!compactScheduleBlock(raw.current_actual) || raw.current_actual.kind !== "actual")) ||
    remainingSource.some(x => !compactScheduleBlock(x) || x.kind !== "plan");
  if (invalid) return {available:false,degraded:true,error:"schedule_summary_invalid"};
  const out = { available:true, source:String(raw.source || "android_local"),
    current_plan:current, today_remaining_plans:remaining,
    today_remaining_plan_count:Math.max(0,Number(raw.today_remaining_plan_count ?? raw.remaining_plan_count)||0),
    remaining_plans:remaining, remaining_plan_count:Math.max(0,Number(raw.today_remaining_plan_count ?? raw.remaining_plan_count)||0),
    remaining_scope:"today", next_plan:next,
    next_plan_available:Boolean(search), next_plan_search:search || {status:"unavailable",bounded:true}, current_actual:actual,
    focus_projection_available:raw.focus_projection_available !== false };
  const updated=positiveNumberOrNull(raw.updated_at_ms);
  if(updated!==null)out.state_updated_at_ms=updated;
  const queried=positiveNumberOrNull(raw.queried_at_ms);
  if(queried!==null)out.queried_at_ms=queried;
  if(raw.degraded===true || raw.focus_projection_available===false || !search)out.degraded=true;
  if(!search)out.next_plan_search_error="missing_or_invalid_search_metadata";
  if(raw.focus_projection_error)out.focus_projection_error=String(raw.focus_projection_error);
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
  if (todo.available && todo.overdue_count > 0) items.push({ type: "todo_overdue", count: todo.overdue_count });
  const effectiveAllows = appGate.available ? appGate.effective_allows : [];
  for (const allow of effectiveAllows) {
    const remaining = numberOrNull(allow.remaining_ms);
    const expiresAt = positiveNumberOrNull(allow.expires_at_ms);
    const item = { type: "temporary_allow_active", package: allow.package };
    if (remaining !== null) item.expires_in_ms = Math.max(0, remaining);
    else if (expiresAt !== null) item.expires_at_ms = expiresAt;
    items.push(item);
  }
  if (focus.available && focus.active) {
    const item = { type: "focus_active" };
    if (typeof focus.temporary_active === "boolean") item.temporary_release_active = focus.temporary_active;
    items.push(item);
  }
  if (freshness.stale) {
    const item = { type: "state_stale", age_ms: freshness.age_ms, stale_after_ms: freshness.stale_after_ms };
    if (freshness.clock_skew) item.clock_skew = true;
    items.push(item);
  }
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
  const scheduleObservedAt = positiveNumberOrNull(state.schedule_state?.queried_at_ms) ?? stateUpdatedAt ?? nowMs;
  const schedule = composeSchedule(state.schedule_state, focus, scheduleObservedAt);
  const scheduleFreshness = freshnessEntry(schedule.queried_at_ms ?? state.schedule_state?.queried_at_ms ?? null, nowMs, schedule.source || source, transport, staleAfterMs);
  schedule.stale = !schedule.available || scheduleFreshness.stale;
  const snapshotPartial = Boolean(state.error) || !appGate.available || !todo.available || !focus.available || !schedule.available || schedule.degraded === true;
  const attentionItems = composeAttention({ appGate, todo, focus, freshness });
  const snapshotFreshness = { ...freshness, partial: snapshotPartial, degraded: snapshotPartial };
  if (state.error) snapshotFreshness.error = String(state.error);

  return {
    schema_version: CURRENT_CONTEXT_SCHEMA_VERSION,
    generated_at_ms: nowMs,
    device,
    app_gate: appGate,
    todo,
    focus,
    schedule,
    usage,
    attention_items: attentionItems,
    freshness: {
      snapshot: snapshotFreshness,
      device: { ...freshness },
      app_gate: { ...freshness, available: appGate.available },
      todo: { ...freshness, available: todo.available, ...(todo.state_updated_at_ms ? { data_updated_at_ms: todo.state_updated_at_ms } : {}) },
      focus: { ...freshness, available: focus.available },
      schedule: { ...scheduleFreshness, available: schedule.available, degraded: schedule.degraded === true, ...(schedule.state_updated_at_ms ? { data_updated_at_ms: schedule.state_updated_at_ms } : {}), ...(schedule.queried_at_ms ? { data_queried_at_ms: schedule.queried_at_ms } : {}) },
      usage: { ...freshness, available: usage.available }
    }
  };
}
