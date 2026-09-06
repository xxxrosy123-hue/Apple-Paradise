package dev.linjian.peek;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Pure-Java Focus session fact core for Phase 4.
 * Current Focus state and completed session history remain separate JSON objects;
 * aggregate duration is always derived from completed session records.
 */
final class FocusSessionCore {
    static final int SCHEMA_VERSION = 1;
    static final int DEFAULT_QUERY_LIMIT = 8;
    static final int MAX_QUERY_LIMIT = 20;
    static final String STATUS_COMPLETED = "completed";

    static final class Binding {
        final String todoId;
        final String category;
        Binding(String todoId, String category) {
            this.todoId = clean(todoId);
            this.category = clean(category);
        }
    }

    private FocusSessionCore() { }

    static JSONObject emptyHistory() {
        JSONObject out = new JSONObject();
        try {
            out.put("schema_version", SCHEMA_VERSION);
            out.put("sessions", new JSONArray());
        } catch (Exception ignored) { }
        return out;
    }

    static JSONObject normalizeHistory(JSONObject raw) {
        JSONObject out = raw == null ? emptyHistory() : raw;
        try {
            out.put("schema_version", SCHEMA_VERSION);
            if (!(out.opt("sessions") instanceof JSONArray)) out.put("sessions", new JSONArray());
        } catch (Exception ignored) { }
        return out;
    }

    static Binding resolveBinding(String todoId, String requestedCategory, JSONObject todo) {
        String id = clean(todoId);
        String category = clean(requestedCategory);
        if (id.isEmpty()) return new Binding("", category);
        if (todo == null || !id.equals(clean(todo.optString("id", "")))) {
            throw new IllegalArgumentException("todo_not_found:" + id);
        }
        if (!"open".equals(clean(todo.optString("status", "")))) {
            throw new IllegalArgumentException("todo_not_open:" + id);
        }
        if (category.isEmpty()) category = clean(todo.optString("category", ""));
        return new Binding(id, category);
    }

    static void begin(JSONObject current, String sessionId, Binding binding, long startedAtMs, long untilMs) {
        if (current == null) throw new IllegalArgumentException("focus_state_required");
        if (current.optBoolean("active", false)) throw new IllegalStateException("focus_already_active");
        String id = clean(sessionId);
        if (id.isEmpty()) throw new IllegalArgumentException("session_id_required");
        Binding b = binding == null ? new Binding("", "") : binding;
        try {
            current.put("active", true);
            current.put("session_id", id);
            current.put("todo_id", b.todoId);
            current.put("category", b.category);
            current.put("started_at_ms", Math.max(0L, startedAtMs));
            current.put("started_at_local", formatLocal(startedAtMs));
            current.put("until_ms", Math.max(0L, untilMs));
            current.put("until_local", untilMs > 0L ? formatLocal(untilMs) : "");
            current.remove("ended_at_ms");
            current.remove("ended_at_local");
        } catch (Exception e) {
            throw new IllegalStateException("focus_begin_failed", e);
        }
    }

    /** Returns the newly sealed session, or null when no active session was eligible. */
    static JSONObject finish(JSONObject current, JSONObject history, long endedAtMs, String endReason) {
        if (current == null || !current.optBoolean("active", false)) return null;
        JSONObject h = normalizeHistory(history);
        String sessionId = clean(current.optString("session_id", ""));
        if (sessionId.isEmpty()) throw new IllegalStateException("active_focus_missing_session_id");
        long startedAtMs = Math.max(0L, current.optLong("started_at_ms", 0L));
        long end = Math.max(startedAtMs, endedAtMs);
        JSONObject existing = findSession(h, sessionId);
        JSONObject session = existing;
        try {
            if (session == null) {
                session = new JSONObject();
                session.put("session_id", sessionId);
                session.put("todo_id", clean(current.optString("todo_id", "")));
                session.put("category", clean(current.optString("category", "")));
                session.put("started_at_ms", startedAtMs);
                session.put("started_at_local", startedAtMs > 0L ? formatLocal(startedAtMs) : "");
                session.put("ended_at_ms", end);
                session.put("ended_at_local", formatLocal(end));
                session.put("duration_ms", Math.max(0L, end - startedAtMs));
                session.put("status", STATUS_COMPLETED);
                session.put("end_reason", clean(endReason).isEmpty() ? "ended" : clean(endReason));
                h.getJSONArray("sessions").put(session);
            }
            current.put("active", false);
            current.put("temporary_until_ms", 0L);
            current.put("ended_at_ms", end);
            current.put("ended_at_local", formatLocal(end));
            current.put("last_session_id", sessionId);
            current.remove("session_id");
            current.remove("todo_id");
            current.remove("category");
        } catch (Exception e) {
            throw new IllegalStateException("focus_finish_failed", e);
        }
        return session;
    }

    /** TTL uses the scheduled until_ms as the factual end, not the later lazy-read time. */
    static JSONObject settleIfExpired(JSONObject current, JSONObject history, long nowMs) {
        if (current == null || !current.optBoolean("active", false)) return null;
        long until = current.optLong("until_ms", 0L);
        if (until <= 0L || nowMs < until) return null;
        return finish(current, history, until, "ttl_expired");
    }

    static JSONObject query(JSONObject history, String todoId, int requestedLimit) {
        JSONObject h = normalizeHistory(history);
        String wantedTodo = clean(todoId);
        int limit = requestedLimit <= 0 ? DEFAULT_QUERY_LIMIT : Math.min(MAX_QUERY_LIMIT, requestedLimit);
        JSONArray sessions = h.optJSONArray("sessions");
        if (sessions == null) sessions = new JSONArray();

        int count = 0;
        long total = 0L;
        long lastAt = 0L;
        List<JSONObject> matching = new ArrayList<>();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session == null || !STATUS_COMPLETED.equals(session.optString("status", STATUS_COMPLETED))) continue;
            if (!wantedTodo.isEmpty() && !wantedTodo.equals(clean(session.optString("todo_id", "")))) continue;
            count++;
            total += Math.max(0L, session.optLong("duration_ms", 0L));
            lastAt = Math.max(lastAt, session.optLong("ended_at_ms", 0L));
            matching.add(session);
        }

        JSONArray recent = new JSONArray();
        for (int i = matching.size() - 1; i >= 0 && recent.length() < limit; i--) {
            try { recent.put(new JSONObject(matching.get(i).toString())); }
            catch (Exception ignored) { }
        }
        JSONObject out = new JSONObject();
        try {
            out.put("schema_version", SCHEMA_VERSION);
            out.put("todo_id", wantedTodo);
            out.put("session_count", count);
            out.put("total_duration_ms", total);
            out.put("last_focus_at_ms", lastAt);
            out.put("last_focus_at_local", lastAt > 0L ? formatLocal(lastAt) : "");
            out.put("recent_sessions", recent);
            out.put("returned_count", recent.length());
            out.put("limit", limit);
        } catch (Exception ignored) { }
        return out;
    }

    static JSONObject findSession(JSONObject history, String sessionId) {
        String wanted = clean(sessionId);
        if (wanted.isEmpty()) return null;
        JSONArray sessions = normalizeHistory(history).optJSONArray("sessions");
        if (sessions == null) return null;
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject s = sessions.optJSONObject(i);
            if (s != null && wanted.equals(clean(s.optString("session_id", "")))) return s;
        }
        return null;
    }

    static int historySize(JSONObject history) {
        JSONArray sessions = normalizeHistory(history).optJSONArray("sessions");
        return sessions == null ? 0 : sessions.length();
    }

    static String formatLocal(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(ms));
    }

    static String clean(String value) { return value == null ? "" : value.trim(); }
}
