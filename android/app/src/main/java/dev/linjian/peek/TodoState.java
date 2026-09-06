package dev.linjian.peek;

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
