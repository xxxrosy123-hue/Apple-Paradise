#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path, old, new):
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly 1 match, found {count}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def write_new(path, content):
    p = ROOT / path
    if p.exists():
        raise SystemExit(f"{path}: already exists")
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")


write_new("android/app/src/main/java/dev/linjian/peek/TodoStateCore.java", r'''package dev.linjian.peek;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * Todo 的纯 Java 事实与状态转换核心。
 *
 * 不依赖 Android Context，不做主观任务判断；Android TodoState 只负责持久化和命令适配。
 */
final class TodoStateCore {
    static final int SCHEMA_VERSION = 1;
    static final String STATUS_OPEN = "open";
    static final String STATUS_COMPLETED = "completed";

    static final class Patch {
        boolean hasTitle;
        String title;
        boolean hasNote;
        String note;
        boolean hasCategory;
        String category;
        boolean hasPriority;
        String priority;
        boolean hasDeadline;
        long deadlineAtMs;
    }

    static final class Todo {
        String id;
        String title;
        String note;
        String status;
        String category;
        String priority;
        long deadlineAtMs;
        long createdAtMs;
        long updatedAtMs;
        long completedAtMs;

        Todo copy() {
            Todo x = new Todo();
            x.id = id;
            x.title = title;
            x.note = note;
            x.status = status;
            x.category = category;
            x.priority = priority;
            x.deadlineAtMs = deadlineAtMs;
            x.createdAtMs = createdAtMs;
            x.updatedAtMs = updatedAtMs;
            x.completedAtMs = completedAtMs;
            return x;
        }
    }

    private final LinkedHashMap<String, Todo> todos = new LinkedHashMap<>();
    private long stateUpdatedAtMs = 0L;

    static TodoStateCore empty() {
        return new TodoStateCore();
    }

    static TodoStateCore fromJson(String raw) {
        TodoStateCore core = new TodoStateCore();
        if (raw == null || raw.trim().isEmpty()) return core;
        try {
            JSONObject root = new JSONObject(raw);
            core.stateUpdatedAtMs = Math.max(0L, root.optLong("updated_at_ms", 0L));
            JSONArray arr = root.optJSONArray("todos");
            if (arr == null) return core;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                Todo t = readTodo(o);
                if (t == null || core.todos.containsKey(t.id)) continue;
                core.todos.put(t.id, t);
            }
        } catch (Exception ignored) { }
        return core;
    }

    Todo create(String id, String title, String note, String category, String priority, long deadlineAtMs, long nowMs) {
        String cleanId = clean(id);
        String cleanTitle = clean(title);
        if (cleanId.isEmpty()) throw new IllegalArgumentException("id_required");
        if (cleanTitle.isEmpty()) throw new IllegalArgumentException("title_required");
        if (todos.containsKey(cleanId)) throw new IllegalArgumentException("todo_id_exists:" + cleanId);
        Todo t = new Todo();
        t.id = cleanId;
        t.title = cleanTitle;
        t.note = cleanNullable(note);
        t.status = STATUS_OPEN;
        t.category = cleanNullable(category);
        t.priority = normalizePriority(priority);
        t.deadlineAtMs = Math.max(0L, deadlineAtMs);
        t.createdAtMs = nowMs;
        t.updatedAtMs = nowMs;
        t.completedAtMs = 0L;
        todos.put(t.id, t);
        stateUpdatedAtMs = nowMs;
        return t.copy();
    }

    Todo update(String id, Patch patch, long nowMs) {
        Todo t = todos.get(clean(id));
        if (t == null) return null;
        if (patch == null) return t.copy();
        if (patch.hasTitle) {
            String title = clean(patch.title);
            if (title.isEmpty()) throw new IllegalArgumentException("title_required");
            t.title = title;
        }
        if (patch.hasNote) t.note = cleanNullable(patch.note);
        if (patch.hasCategory) t.category = cleanNullable(patch.category);
        if (patch.hasPriority) t.priority = normalizePriority(patch.priority);
        if (patch.hasDeadline) t.deadlineAtMs = Math.max(0L, patch.deadlineAtMs);
        t.updatedAtMs = nowMs;
        stateUpdatedAtMs = nowMs;
        return t.copy();
    }

    Todo complete(String id, long nowMs) {
        Todo t = todos.get(clean(id));
        if (t == null) return null;
        if (!STATUS_COMPLETED.equals(t.status)) {
            t.status = STATUS_COMPLETED;
            t.completedAtMs = nowMs;
            t.updatedAtMs = nowMs;
            stateUpdatedAtMs = nowMs;
        }
        return t.copy();
    }

    Todo reopen(String id, long nowMs) {
        Todo t = todos.get(clean(id));
        if (t == null) return null;
        if (!STATUS_OPEN.equals(t.status) || t.completedAtMs != 0L) {
            t.status = STATUS_OPEN;
            t.completedAtMs = 0L;
            t.updatedAtMs = nowMs;
            stateUpdatedAtMs = nowMs;
        }
        return t.copy();
    }

    Todo get(String id) {
        Todo t = todos.get(clean(id));
        return t == null ? null : t.copy();
    }

    boolean delete(String id, long nowMs) {
        Todo removed = todos.remove(clean(id));
        if (removed != null) stateUpdatedAtMs = nowMs;
        return removed != null;
    }

    JSONArray query(String filter, String id, String category, String status, long nowMs) {
        JSONArray out = new JSONArray();
        String f = clean(filter).toLowerCase(Locale.US);
        if (f.isEmpty()) f = "all";
        String wantedId = clean(id);
        String wantedCategory = clean(category);
        String wantedStatus = normalizeStatusFilter(status);
        for (Todo t : todos.values()) {
            if (!wantedId.isEmpty() && !wantedId.equals(t.id)) continue;
            if (!wantedCategory.isEmpty() && !wantedCategory.equals(t.category)) continue;
            if (!wantedStatus.isEmpty() && !wantedStatus.equals(t.status)) continue;
            if ("open".equals(f) && !STATUS_OPEN.equals(t.status)) continue;
            if ("completed".equals(f) && !STATUS_COMPLETED.equals(t.status)) continue;
            if ("overdue".equals(f) && !isOverdue(t, nowMs)) continue;
            if ("due_today".equals(f) && !isDueToday(t, nowMs)) continue;
            if (!("all".equals(f) || "open".equals(f) || "completed".equals(f) || "overdue".equals(f) || "due_today".equals(f)))
                continue;
            out.put(view(t, nowMs));
        }
        return out;
    }

    JSONObject snapshot(long nowMs) {
        JSONObject out = new JSONObject();
        JSONArray all = query("all", "", "", "", nowMs);
        JSONArray open = query("open", "", "", "", nowMs);
        JSONArray completed = query("completed", "", "", "", nowMs);
        JSONArray overdue = query("overdue", "", "", "", nowMs);
        JSONArray dueToday = query("due_today", "", "", "", nowMs);
        try {
            out.put("schema_version", SCHEMA_VERSION);
            out.put("todos", all);
            out.put("total_count", all.length());
            out.put("open_count", open.length());
            out.put("completed_count", completed.length());
            out.put("overdue_count", overdue.length());
            out.put("due_today_count", dueToday.length());
            out.put("updated_at_ms", stateUpdatedAtMs);
            out.put("updated_at_local", stateUpdatedAtMs > 0 ? formatLocal(stateUpdatedAtMs) : "");
        } catch (Exception ignored) { }
        return out;
    }

    JSONObject toPersistedJson() {
        JSONObject root = new JSONObject();
        JSONArray arr = new JSONArray();
        try {
            for (Todo t : todos.values()) arr.put(baseJson(t));
            root.put("schema_version", SCHEMA_VERSION);
            root.put("updated_at_ms", stateUpdatedAtMs);
            root.put("updated_at_local", stateUpdatedAtMs > 0 ? formatLocal(stateUpdatedAtMs) : "");
            root.put("todos", arr);
        } catch (Exception ignored) { }
        return root;
    }

    int size() { return todos.size(); }

    static boolean isOverdue(Todo t, long nowMs) {
        return t != null && !STATUS_COMPLETED.equals(t.status) && t.deadlineAtMs > 0L && nowMs > t.deadlineAtMs;
    }

    static boolean isDueToday(Todo t, long nowMs) {
        if (t == null || t.deadlineAtMs <= 0L) return false;
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(nowMs);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(t.deadlineAtMs);
        return a.get(Calendar.ERA) == b.get(Calendar.ERA)
                && a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static JSONObject view(Todo t, long nowMs) {
        JSONObject o = baseJson(t);
        try {
            o.put("overdue", isOverdue(t, nowMs));
            o.put("due_today", isDueToday(t, nowMs));
        } catch (Exception ignored) { }
        return o;
    }

    private static JSONObject baseJson(Todo t) {
        JSONObject o = new JSONObject();
        if (t == null) return o;
        try {
            o.put("id", t.id);
            o.put("title", t.title);
            o.put("note", t.note);
            o.put("status", t.status);
            o.put("category", t.category);
            o.put("priority", t.priority);
            o.put("deadline_at_ms", t.deadlineAtMs);
            o.put("deadline_at_local", t.deadlineAtMs > 0 ? formatLocal(t.deadlineAtMs) : "");
            o.put("deadline", t.deadlineAtMs > 0 ? formatLocal(t.deadlineAtMs) : "");
            o.put("created_at_ms", t.createdAtMs);
            o.put("created_at_local", t.createdAtMs > 0 ? formatLocal(t.createdAtMs) : "");
            o.put("created_at", t.createdAtMs > 0 ? formatLocal(t.createdAtMs) : "");
            o.put("updated_at_ms", t.updatedAtMs);
            o.put("updated_at_local", t.updatedAtMs > 0 ? formatLocal(t.updatedAtMs) : "");
            o.put("updated_at", t.updatedAtMs > 0 ? formatLocal(t.updatedAtMs) : "");
            o.put("completed_at_ms", t.completedAtMs);
            o.put("completed_at_local", t.completedAtMs > 0 ? formatLocal(t.completedAtMs) : "");
            o.put("completed_at", t.completedAtMs > 0 ? formatLocal(t.completedAtMs) : "");
        } catch (Exception ignored) { }
        return o;
    }

    private static Todo readTodo(JSONObject o) {
        if (o == null) return null;
        String id = clean(o.optString("id", ""));
        String title = clean(o.optString("title", ""));
        if (id.isEmpty() || title.isEmpty()) return null;
        Todo t = new Todo();
        t.id = id;
        t.title = title;
        t.note = cleanNullable(o.optString("note", ""));
        t.status = STATUS_COMPLETED.equals(o.optString("status", STATUS_OPEN)) ? STATUS_COMPLETED : STATUS_OPEN;
        t.category = cleanNullable(o.optString("category", ""));
        t.priority = normalizePriority(o.optString("priority", "normal"));
        t.deadlineAtMs = Math.max(0L, o.optLong("deadline_at_ms", o.optLong("due_at_ms", 0L)));
        t.createdAtMs = Math.max(0L, o.optLong("created_at_ms", 0L));
        t.updatedAtMs = Math.max(t.createdAtMs, o.optLong("updated_at_ms", t.createdAtMs));
        t.completedAtMs = STATUS_COMPLETED.equals(t.status) ? Math.max(0L, o.optLong("completed_at_ms", 0L)) : 0L;
        return t;
    }

    private static String normalizePriority(String value) {
        String v = clean(value);
        return v.isEmpty() ? "normal" : v;
    }

    private static String normalizeStatusFilter(String value) {
        String v = clean(value).toLowerCase(Locale.US);
        if (STATUS_OPEN.equals(v) || STATUS_COMPLETED.equals(v)) return v;
        return "";
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String cleanNullable(String value) { return value == null ? "" : value.trim(); }
    static String formatLocal(long ms) { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(ms)); }
}
''')

write_new("android/app/src/main/java/dev/linjian/peek/TodoState.java", r'''package dev.linjian.peek;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.UUID;

/** Android 本地 Todo 事实源。持久化在 linjian_peek / todo_state_v1。 */
public final class TodoState {
    public static final String KEY_STATE = "todo_state_v1";
    public static final String VERSION = "1";

    private TodoState() { }

    private static TodoStateCore load(Context ctx) {
        String raw = AppPrefs.get(ctx).getString(KEY_STATE, "");
        return TodoStateCore.fromJson(raw);
    }

    private static boolean save(Context ctx, TodoStateCore core) {
        return AppPrefs.get(ctx).edit().putString(KEY_STATE, core.toPersistedJson().toString()).commit();
    }

    public static synchronized JSONObject collect(Context ctx) {
        long now = System.currentTimeMillis();
        JSONObject out = load(ctx).snapshot(now);
        try {
            out.put("todo_version", VERSION);
            out.put("source", "android_local");
            out.put("storage", "SharedPreferences(linjian_peek)/" + KEY_STATE);
            out.put("queried_at_ms", now);
            out.put("queried_at_local", TodoStateCore.formatLocal(now));
        } catch (Exception ignored) { }
        return out;
    }

    public static boolean isTodoAction(String action) {
        return "todo_action".equals(action) || "get_todos".equals(action);
    }

    public static synchronized JSONObject handleCommand(Context ctx, JSONObject cmd) {
        JSONObject out = new JSONObject();
        String action = cmd == null ? "" : cmd.optString("action", "");
        try {
            if ("get_todos".equals(action)) return query(ctx, cmd);
            if (!"todo_action".equals(action)) return result(out, false, "unknown_todo_action:" + action);

            String operation = clean(cmd.optString("operation", cmd.optString("op", ""))).toLowerCase(Locale.US);
            if (operation.isEmpty()) return result(out, false, "todo_operation_required");
            long now = System.currentTimeMillis();
            TodoStateCore core = load(ctx);
            TodoStateCore.Todo changed;

            if ("create".equals(operation)) {
                String title = clean(cmd.optString("title", ""));
                if (title.isEmpty()) return result(out, false, "title_required");
                long deadlineAt = deadlineFromCommand(cmd);
                changed = core.create("todo_" + UUID.randomUUID().toString(), title,
                        cmd.optString("note", ""), cmd.optString("category", ""),
                        cmd.optString("priority", "normal"), deadlineAt, now);
            } else {
                String id = clean(cmd.optString("id", cmd.optString("todo_id", "")));
                if (id.isEmpty()) return result(out, false, "todo_id_required");
                if ("update".equals(operation)) {
                    TodoStateCore.Patch patch = patchFromCommand(cmd);
                    changed = core.update(id, patch, now);
                    if (changed == null) return result(out, false, "todo_not_found:" + id);
                } else if ("complete".equals(operation)) {
                    changed = core.complete(id, now);
                    if (changed == null) return result(out, false, "todo_not_found:" + id);
                } else if ("reopen".equals(operation)) {
                    changed = core.reopen(id, now);
                    if (changed == null) return result(out, false, "todo_not_found:" + id);
                } else if ("delete".equals(operation)) {
                    boolean deleted = core.delete(id, now);
                    if (!deleted) return result(out, false, "todo_not_found:" + id);
                    if (!save(ctx, core)) return result(out, false, "todo_persist_failed");
                    out.put("ok", true);
                    out.put("result", "todo_deleted:" + id);
                    out.put("deleted_id", id);
                    out.put("todo_state", core.snapshot(now));
                    return out;
                } else {
                    return result(out, false, "unknown_todo_operation:" + operation);
                }
            }

            if (!save(ctx, core)) return result(out, false, "todo_persist_failed");
            out.put("ok", true);
            out.put("result", "todo_" + operation + ":" + changed.id);
            out.put("todo", todoJson(changed, now));
            out.put("todo_state", core.snapshot(now));
            return out;
        } catch (IllegalArgumentException e) {
            return result(out, false, e.getMessage() == null ? "todo_validation_failed" : e.getMessage());
        } catch (Exception e) {
            return result(out, false, ScreenshotService.shortMsg(e));
        }
    }

    private static JSONObject query(Context ctx, JSONObject cmd) throws Exception {
        long now = System.currentTimeMillis();
        TodoStateCore core = load(ctx);
        String filter = clean(cmd.optString("filter", "all")).toLowerCase(Locale.US);
        if (filter.isEmpty()) filter = "all";
        String id = clean(cmd.optString("id", cmd.optString("todo_id", "")));
        String category = clean(cmd.optString("category", ""));
        String status = clean(cmd.optString("status", ""));
        JSONArray todos = core.query(filter, id, category, status, now);
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("result", "todos:" + todos.length());
        out.put("filter", filter);
        out.put("id", id);
        out.put("category", category);
        out.put("status", status);
        out.put("todos", todos);
        out.put("todo_state", core.snapshot(now));
        out.put("note", "due_today 只表示 deadline 的本地日期是今天；没有 deadline 的 Todo 不会因为‘计划今天做’而自动进入 due_today。");
        return out;
    }

    private static TodoStateCore.Patch patchFromCommand(JSONObject cmd) throws Exception {
        TodoStateCore.Patch patch = new TodoStateCore.Patch();
        if (cmd.has("title")) { patch.hasTitle = true; patch.title = cmd.optString("title", ""); }
        if (cmd.has("note")) { patch.hasNote = true; patch.note = cmd.optString("note", ""); }
        if (cmd.has("category")) { patch.hasCategory = true; patch.category = cmd.optString("category", ""); }
        if (cmd.has("priority")) { patch.hasPriority = true; patch.priority = cmd.optString("priority", ""); }
        if (cmd.optBoolean("clear_deadline", false)) {
            patch.hasDeadline = true;
            patch.deadlineAtMs = 0L;
        } else if (hasDeadlineInput(cmd)) {
            patch.hasDeadline = true;
            patch.deadlineAtMs = deadlineFromCommand(cmd);
        }
        return patch;
    }

    private static boolean hasDeadlineInput(JSONObject cmd) {
        return cmd.has("deadline_at_ms") || cmd.has("deadline_ms") || cmd.has("due_at_ms")
                || cmd.has("deadline") || cmd.has("due_at");
    }

    private static long deadlineFromCommand(JSONObject cmd) throws Exception {
        if (cmd.has("deadline_at_ms")) return Math.max(0L, cmd.optLong("deadline_at_ms", 0L));
        if (cmd.has("deadline_ms")) return Math.max(0L, cmd.optLong("deadline_ms", 0L));
        if (cmd.has("due_at_ms")) return Math.max(0L, cmd.optLong("due_at_ms", 0L));
        String raw = clean(cmd.optString("deadline", cmd.optString("due_at", "")));
        if (raw.isEmpty()) return 0L;
        return parseDeadline(raw);
    }

    static long parseDeadline(String raw) throws Exception {
        String v = clean(raw);
        if (v.matches("\\d{4}-\\d{2}-\\d{2}")) {
            SimpleDateFormat fmt = strictFormat("yyyy-MM-dd");
            Calendar c = Calendar.getInstance();
            c.setTime(fmt.parse(v));
            c.set(Calendar.HOUR_OF_DAY, 23);
            c.set(Calendar.MINUTE, 59);
            c.set(Calendar.SECOND, 59);
            c.set(Calendar.MILLISECOND, 999);
            return c.getTimeInMillis();
        }
        String[] patterns = new String[]{
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mmXXX"
        };
        for (String pattern : patterns) {
            try { return strictFormat(pattern).parse(v).getTime(); }
            catch (ParseException ignored) { }
        }
        throw new IllegalArgumentException("deadline_invalid:" + v);
    }

    private static SimpleDateFormat strictFormat(String pattern) {
        SimpleDateFormat fmt = new SimpleDateFormat(pattern, Locale.US);
        fmt.setLenient(false);
        return fmt;
    }

    private static JSONObject todoJson(TodoStateCore.Todo t, long now) {
        TodoStateCore tmp = TodoStateCore.empty();
        TodoStateCore.Todo copy = t.copy();
        try {
            JSONObject o = new JSONObject();
            o.put("id", copy.id);
            o.put("title", copy.title);
            o.put("note", copy.note);
            o.put("status", copy.status);
            o.put("category", copy.category);
            o.put("priority", copy.priority);
            o.put("deadline_at_ms", copy.deadlineAtMs);
            o.put("deadline_at_local", copy.deadlineAtMs > 0 ? TodoStateCore.formatLocal(copy.deadlineAtMs) : "");
            o.put("created_at_ms", copy.createdAtMs);
            o.put("created_at_local", copy.createdAtMs > 0 ? TodoStateCore.formatLocal(copy.createdAtMs) : "");
            o.put("updated_at_ms", copy.updatedAtMs);
            o.put("updated_at_local", copy.updatedAtMs > 0 ? TodoStateCore.formatLocal(copy.updatedAtMs) : "");
            o.put("completed_at_ms", copy.completedAtMs);
            o.put("completed_at_local", copy.completedAtMs > 0 ? TodoStateCore.formatLocal(copy.completedAtMs) : "");
            o.put("overdue", TodoStateCore.isOverdue(copy, now));
            o.put("due_today", TodoStateCore.isDueToday(copy, now));
            return o;
        } catch (Exception ignored) { return new JSONObject(); }
    }

    public static synchronized String pretty(Context ctx) {
        JSONObject s = collect(ctx);
        return "Todo · 未完成 " + s.optInt("open_count", 0)
                + " · 已完成 " + s.optInt("completed_count", 0)
                + " · 逾期 " + s.optInt("overdue_count", 0);
    }

    private static JSONObject result(JSONObject out, boolean ok, String result) {
        try { out.put("ok", ok).put("result", result); } catch (Exception ignored) { }
        return out;
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }
}
''')

write_new("android/tests/TodoStateBehaviorTest.java", r'''package dev.linjian.peek;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.TimeZone;

/** Pure-Java behavior regression tests for Phase 2 Todo facts. */
public final class TodoStateBehaviorTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        long now = 2_000_000_000_000L;
        long hour = 3_600_000L;

        TodoStateCore core = TodoStateCore.empty();

        // 1. Create with stable unique id and required title.
        TodoStateCore.Todo first = core.create("todo_a", "学习数据结构", "第一章", "考研", "normal", now + hour, now);
        check("todo_a".equals(first.id), "create must preserve stable id");
        check("open".equals(first.status), "new Todo must be open");
        check(core.size() == 1, "create should add one Todo");
        boolean blankRejected = false;
        try { core.create("todo_blank", "   ", "", "", "normal", 0L, now); }
        catch (IllegalArgumentException expected) { blankRejected = true; }
        check(blankRejected, "blank title must be rejected");

        // 2. Update title/category/priority/deadline.
        TodoStateCore.Patch patch = new TodoStateCore.Patch();
        patch.hasTitle = true; patch.title = "学习数据结构第二章";
        patch.hasCategory = true; patch.category = "学习-自定义";
        patch.hasPriority = true; patch.priority = "high";
        patch.hasDeadline = true; patch.deadlineAtMs = now + 2 * hour;
        TodoStateCore.Todo updated = core.update("todo_a", patch, now + 10L);
        check("学习数据结构第二章".equals(updated.title), "title update failed");
        check("学习-自定义".equals(updated.category), "category must remain extensible");
        check("high".equals(updated.priority), "priority update failed");
        check(updated.deadlineAtMs == now + 2 * hour, "deadline update failed");

        // 3. Complete writes completed_at and status.
        TodoStateCore.Todo completed = core.complete("todo_a", now + 20L);
        check("completed".equals(completed.status), "complete must set completed status");
        check(completed.completedAtMs == now + 20L, "complete must write completed_at");

        // 4. Reopen returns open and clears completed_at.
        TodoStateCore.Todo reopened = core.reopen("todo_a", now + 30L);
        check("open".equals(reopened.status), "reopen must return open status");
        check(reopened.completedAtMs == 0L, "reopen must clear completed_at");

        // 6. Open + past deadline is overdue.
        TodoStateCore.Patch overduePatch = new TodoStateCore.Patch();
        overduePatch.hasDeadline = true; overduePatch.deadlineAtMs = now - 1L;
        core.update("todo_a", overduePatch, now + 40L);
        check(TodoStateCore.isOverdue(core.get("todo_a"), now), "open past-deadline Todo must be overdue");
        check(core.query("overdue", "", "", "", now).length() == 1, "overdue query must return open overdue Todo");

        // 7. Completed Todo with past deadline is never overdue.
        core.complete("todo_a", now + 50L);
        check(!TodoStateCore.isOverdue(core.get("todo_a"), now + 60L), "completed Todo must not be overdue");
        check(core.query("overdue", "", "", "", now + 60L).length() == 0, "completed Todo must be absent from overdue query");

        // 8. Multiple ids coexist and never overwrite each other.
        core.create("todo_b", "写教案", "", "工作", "normal", 0L, now + 70L);
        check(core.size() == 2, "multiple Todo ids must coexist");
        check("学习数据结构第二章".equals(core.get("todo_a").title), "second Todo must not overwrite first");
        check("写教案".equals(core.get("todo_b").title), "second Todo missing");

        // due_today means deadline calendar date is today, not merely planned today.
        TodoStateCore.Patch todayPatch = new TodoStateCore.Patch();
        todayPatch.hasDeadline = true; todayPatch.deadlineAtMs = now + 30_000L;
        core.update("todo_b", todayPatch, now + 80L);
        check(TodoStateCore.isDueToday(core.get("todo_b"), now), "same local date deadline must be due_today");

        // 9. Serialize -> read must preserve durable fields and ids.
        String json = core.toPersistedJson().toString();
        TodoStateCore restored = TodoStateCore.fromJson(json);
        check(restored.size() == 2, "round-trip must preserve Todo count");
        TodoStateCore.Todo ra = restored.get("todo_a");
        TodoStateCore.Todo rb = restored.get("todo_b");
        check(ra != null && rb != null, "round-trip must preserve ids");
        check("completed".equals(ra.status), "round-trip must preserve status");
        check(ra.completedAtMs == now + 50L, "round-trip must preserve completed_at");
        check("工作".equals(rb.category), "round-trip must preserve category");
        check(rb.deadlineAtMs == now + 30_000L, "round-trip must preserve deadline");

        // 5. Delete removes only the target id.
        check(restored.delete("todo_a", now + 90L), "delete should report success");
        check(restored.get("todo_a") == null, "deleted Todo must not be queryable");
        check(restored.get("todo_b") != null, "delete must not remove another Todo");

        // Counts must match actual derived collections.
        JSONObject snapshot = restored.snapshot(now);
        JSONArray all = snapshot.getJSONArray("todos");
        check(snapshot.getInt("total_count") == all.length(), "total_count mismatch");
        check(snapshot.getInt("open_count") == restored.query("open", "", "", "", now).length(), "open_count mismatch");
        check(snapshot.getInt("completed_count") == restored.query("completed", "", "", "", now).length(), "completed_count mismatch");
        check(snapshot.getInt("overdue_count") == restored.query("overdue", "", "", "", now).length(), "overdue_count mismatch");

        System.out.println("TodoStateBehaviorTest: PASS");
    }
}
''')

replace_once(
    "android/app/src/main/java/dev/linjian/peek/LifeState.java",
    '            state.put("focus_mode", FocusMode.config(ctx));\n            state.put("cycle_state", CycleState.collect(ctx));',
    '            state.put("focus_mode", FocusMode.config(ctx));\n            state.put("todo_state", TodoState.collect(ctx));\n            state.put("cycle_state", CycleState.collect(ctx));'
)
replace_once(
    "android/app/src/main/java/dev/linjian/peek/LifeState.java",
    '            sb.append("\\n\\n").append(FocusMode.pretty(ctx));\n            sb.append("\\n\\n").append(NowState.pretty(ctx));',
    '            sb.append("\\n\\n").append(FocusMode.pretty(ctx));\n            sb.append("\\n\\n").append(TodoState.pretty(ctx));\n            sb.append("\\n\\n").append(NowState.pretty(ctx));'
)

replace_once(
    "android/app/src/main/java/dev/linjian/peek/CompanionService.java",
    '            if (FocusMode.isFocusAction(action)) {',
    '''            if (TodoState.isTodoAction(action)) {
                JSONObject rr = TodoState.handleCommand(ctx, cmd);
                boolean ok = rr.optBoolean("ok", false);
                String result = rr.toString();
                DebugState.append(ctx, "执行 Todo 命令 " + action + "：" + rr.optString("result", result));
                try { reportCommand(ctx, serverUrl, token, id, ok, result); uploadStateThrottled(serverUrl, token, ctx, true); } catch (Exception ignored) { }
                return;
            }
            if (FocusMode.isFocusAction(action)) {'''
)

replace_once(
    "server/linjian_server.py",
    '"get_calendar_state", "upsert_calendar_event", "add_calendar_event", "delete_calendar_event",',
    '"get_calendar_state", "upsert_calendar_event", "add_calendar_event", "delete_calendar_event", "todo_action", "get_todos",'
)

replace_once(
    "mcp/server.js",
    '    "get_focus_status", "start_focus_mode", "end_focus_mode", "set_focus_plan", "reply_focus_request", "approve_focus_unlock", "deny_focus_unlock", "request_focus_unlock", "create_focus_request",',
    '    "todo_action", "get_todos",\n    "get_focus_status", "start_focus_mode", "end_focus_mode", "set_focus_plan", "reply_focus_request", "approve_focus_unlock", "deny_focus_unlock", "request_focus_unlock", "create_focus_request",'
)

node_anchor = '''

  // v0.3.8.2：专注模式工具靠前注册，避免部分客户端只读取前若干个 schema 时漏掉接口。
'''
node_insert = r'''

  async function runTodoCommand(action, args = {}, waitSeconds = DEFAULT_COMMAND_WAIT_SECONDS) {
    const device_id = args.device_id || DEFAULT_DEVICE;
    const clean = { ...args };
    delete clean.device_id;
    delete clean.wait_seconds;
    const queued = await postCommand({ action, device_id, ...clean, payload: { ...clean } });
    const id = queued?.command?.id;
    const observed = id ? await waitCommand(id, waitSeconds) : null;
    const command = observed?.command || queued?.command || null;
    return {
      ok: command?.status === "completed",
      queued,
      observed_status: command,
      phone_result: parsePhoneResult(command),
      source_of_truth: "android_local:todo_state_v1",
      note: "Todo 正式事实保存在手机本地；Server 只传输命令和保存最近 LifeState 快照。"
    };
  }

  server.tool("get_todos", "读取苹果乐园 Android 本地持久化的 Todo 正式事实。Todo 是所有聊天窗口共享的真实任务状态；filter 支持 all/open/completed/overdue/due_today。due_today 仅表示 deadline 的本地日期是今天，不等于‘计划今天做’。", {
    filter: z.enum(["all", "open", "completed", "overdue", "due_today"]).default("all"),
    id: z.string().optional(),
    category: z.string().optional(),
    status: z.enum(["open", "completed"]).optional(),
    device_id: z.string().default(DEFAULT_DEVICE),
    wait_seconds: z.number().int().min(3).max(20).default(8)
  }, async (args) => textResult(await runTodoCommand("get_todos", args, args.wait_seconds)));

  server.tool("todo_action", "创建、修改、完成、重新打开或删除苹果乐园 Todo。Todo 是用户当前真实任务的正式共享事实；这些写操作会改变所有聊天窗口之后读到的状态。不得仅因当前聊天窗口主观猜测‘应该做完了’就擅自 complete/delete；只有用户明确表达或可靠事实足以确认时才执行状态修改。", {
    operation: z.enum(["create", "update", "complete", "reopen", "delete"]),
    id: z.string().optional().describe("update/complete/reopen/delete 必填；create 由手机生成稳定唯一 id"),
    title: z.string().optional().describe("create 必填；update 仅在明确要改标题时传"),
    note: z.string().optional(),
    category: z.string().optional().describe("自由分类字符串，不限制为固定枚举"),
    priority: z.string().optional().describe("最小优先级字段；默认 normal，数据层允许以后扩展"),
    deadline_at_ms: z.number().nonnegative().optional(),
    deadline: z.string().optional().describe("可用 yyyy-MM-dd、yyyy-MM-dd HH:mm 或带时区 ISO 时间；纯日期按本地当天 23:59:59.999"),
    clear_deadline: z.boolean().optional().describe("明确清除 deadline 时传 true"),
    device_id: z.string().default(DEFAULT_DEVICE),
    wait_seconds: z.number().int().min(3).max(20).default(8)
  }, async (args) => textResult(await runTodoCommand("todo_action", args, args.wait_seconds)));
'''
replace_once("mcp/server.js", node_anchor, node_insert + node_anchor)

replace_once(
    "server/cloudflare-worker/worker.js",
    '  "trigger_guidian", "mark_guidian_returned", "get_calendar_state", "upsert_calendar_event", "add_calendar_event", "delete_calendar_event",',
    '  "trigger_guidian", "mark_guidian_returned", "get_calendar_state", "upsert_calendar_event", "add_calendar_event", "delete_calendar_event", "todo_action", "get_todos",'
)

cf_tools_anchor = '  { name: "get_guardian_calendar", description: "读取守护日历/纪念日状态。", inputSchema: obj({ device_id: str(DEFAULT_DEVICE) }) },\n'
cf_tools_insert = r'''  { name: "get_todos", description: "读取苹果乐园 Android 本地持久化的 Todo 正式事实。Todo 为所有聊天窗口共享；filter 支持 all/open/completed/overdue/due_today，due_today 仅表示 deadline 日期是今天。", inputSchema: obj({ filter: str("all"), id: str(undefined), category: str(undefined), status: str(undefined), device_id: str(DEFAULT_DEVICE), wait_seconds: int(8) }) },
  { name: "todo_action", description: "创建、修改、完成、重新打开或删除 Todo 正式共享事实。写操作会改变所有聊天窗口之后读到的状态；不得仅因当前窗口主观猜测任务已完成就擅自 complete/delete，只有用户明确表达或可靠事实足以确认时才修改。", inputSchema: obj({ operation: str(undefined), id: str(undefined), title: str(undefined), note: str(undefined), category: str(undefined), priority: str(undefined), deadline_at_ms: num(undefined), deadline: str(undefined), clear_deadline: bool(false), device_id: str(DEFAULT_DEVICE), wait_seconds: int(8) }, ["operation"]) },
'''
replace_once("server/cloudflare-worker/worker.js", cf_tools_anchor, cf_tools_anchor + cf_tools_insert)

cf_case_anchor = '''    case "get_guardian_calendar": {
      const s = await state();
      return mcpText({ ok: true, device_id, calendar_state: s?.state?.calendar_state || s?.state?.guardian_calendar || s?.state?.calendar || null, raw: s });
    }
'''
cf_case_insert = r'''    case "get_todos": {
      const payload = withoutKeys(args, ["device_id", "wait_seconds"]);
      return observed({ action: "get_todos", ...payload, payload }, args.wait_seconds ?? 8);
    }
    case "todo_action": {
      const payload = withoutKeys(args, ["device_id", "wait_seconds"]);
      return observed({ action: "todo_action", ...payload, payload }, args.wait_seconds ?? 8);
    }
'''
replace_once("server/cloudflare-worker/worker.js", cf_case_anchor, cf_case_anchor + cf_case_insert)

print("Phase 2 Todo patch applied")
