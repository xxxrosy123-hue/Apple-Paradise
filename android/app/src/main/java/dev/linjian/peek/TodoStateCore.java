package dev.linjian.peek;

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
        String f = normalizeFilter(filter);
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

    private static String normalizeFilter(String value) {
        String v = clean(value).toLowerCase(Locale.US);
        if (v.isEmpty()) return "all";
        if ("all".equals(v) || STATUS_OPEN.equals(v) || STATUS_COMPLETED.equals(v)
                || "overdue".equals(v) || "due_today".equals(v)) return v;
        throw new IllegalArgumentException("invalid_filter:" + v);
    }

    private static String normalizeStatusFilter(String value) {
        String v = clean(value).toLowerCase(Locale.US);
        if (v.isEmpty()) return "";
        if (STATUS_OPEN.equals(v) || STATUS_COMPLETED.equals(v)) return v;
        throw new IllegalArgumentException("invalid_status:" + v);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String cleanNullable(String value) { return value == null ? "" : value.trim(); }
    static String formatLocal(long ms) { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(ms)); }
}
