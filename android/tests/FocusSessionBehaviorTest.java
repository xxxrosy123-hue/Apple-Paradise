package dev.linjian.peek;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.TimeZone;

/** Pure-Java Phase 4 behavior tests using the production FocusSessionCore. */
public final class FocusSessionBehaviorTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static JSONObject todoJson(TodoStateCore core, String id, long now) {
        JSONArray arr = core.query("all", id, "", "", now);
        return arr.length() == 0 ? null : arr.optJSONObject(0);
    }

    private static JSONObject current() throws Exception {
        JSONObject s = new JSONObject();
        s.put("active", false);
        s.put("temporary_until_ms", 0L);
        return s;
    }

    private static JSONObject start(JSONObject current, String sessionId, String todoId, String category,
                                    JSONObject todo, long start, long until) {
        FocusSessionCore.Binding binding = FocusSessionCore.resolveBinding(todoId, category, todo);
        FocusSessionCore.begin(current, sessionId, binding, start, until);
        return current;
    }

    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        long base = 2_100_000_000_000L;
        long minute = 60_000L;

        TodoStateCore todos = TodoStateCore.empty();
        todos.create("todo_a", "学习408", "", "考研", "normal", 0L, base);
        todos.create("todo_b", "写教案", "", "工作", "normal", 0L, base + 1L);
        todos.create("todo_done", "已完成事项", "", "生活", "normal", 0L, base + 2L);
        todos.complete("todo_done", base + 3L);

        // 1. Ordinary unbound Focus remains valid.
        JSONObject h1 = FocusSessionCore.emptyHistory();
        JSONObject c1 = start(current(), "focus_unbound", "", "生活", null, base, base + 25 * minute);
        check(c1.optBoolean("active"), "unbound Focus must start");
        check("".equals(c1.optString("todo_id", "")), "unbound Focus must keep empty todo_id");
        check("生活".equals(c1.optString("category")), "unbound Focus category missing");

        // 2. Focus can bind a real open Todo, defaulting category from Todo when omitted.
        JSONObject c2 = start(current(), "focus_a1", "todo_a", "", todoJson(todos, "todo_a", base), base, base + 25 * minute);
        check("todo_a".equals(c2.optString("todo_id")), "open Todo binding failed");
        check("考研".equals(c2.optString("category")), "Todo category should be a default candidate");

        // Explicit Focus category may differ from Todo category.
        JSONObject c2b = start(current(), "focus_a_custom", "todo_a", "工作", todoJson(todos, "todo_a", base), base, base + minute);
        check("工作".equals(c2b.optString("category")), "Focus category must remain independently editable");

        // 3. Missing todo_id target is rejected.
        boolean missingRejected = false;
        try { FocusSessionCore.resolveBinding("todo_missing", "", null); }
        catch (IllegalArgumentException expected) { missingRejected = "todo_not_found:todo_missing".equals(expected.getMessage()); }
        check(missingRejected, "missing Todo must be rejected explicitly");

        // 4. Completed Todo is rejected and never reopened.
        boolean completedRejected = false;
        try { FocusSessionCore.resolveBinding("todo_done", "", todoJson(todos, "todo_done", base + 4L)); }
        catch (IllegalArgumentException expected) { completedRejected = "todo_not_open:todo_done".equals(expected.getMessage()); }
        check(completedRejected, "completed Todo must be rejected for a new binding");
        check("completed".equals(todos.get("todo_done").status), "binding validation must not reopen Todo");

        // 5 + 6. Ending once creates exactly one session with the correct todo_id.
        JSONObject endedA1 = FocusSessionCore.finish(c2, h1, base + 25 * minute, "manual_end");
        check(endedA1 != null, "Focus end must produce a session");
        check(FocusSessionCore.historySize(h1) == 1, "one Focus end must append one session");
        check("todo_a".equals(endedA1.optString("todo_id")), "session must persist todo_id");
        check(endedA1.optLong("duration_ms") == 25 * minute, "session duration must derive from start/end");

        // 7. Same Todo can accumulate two distinct sessions and derived total is their sum.
        JSONObject c3 = start(current(), "focus_a2", "todo_a", "考研", todoJson(todos, "todo_a", base), base + 30 * minute, base + 70 * minute);
        FocusSessionCore.finish(c3, h1, base + 70 * minute, "manual_end");
        JSONObject aSummary = FocusSessionCore.query(h1, "todo_a", 8);
        check(aSummary.optInt("session_count") == 2, "same Todo must retain multiple Focus sessions");
        check(aSummary.optLong("total_duration_ms") == 65 * minute, "Todo Focus total must be derived SUM of sessions");

        // 8. Todo A / B sessions never mix.
        JSONObject c4 = start(current(), "focus_b1", "todo_b", "工作", todoJson(todos, "todo_b", base), base + 80 * minute, base + 90 * minute);
        FocusSessionCore.finish(c4, h1, base + 90 * minute, "manual_end");
        JSONObject bSummary = FocusSessionCore.query(h1, "todo_b", 8);
        check(bSummary.optInt("session_count") == 1, "Todo B should have one session");
        check(bSummary.optLong("total_duration_ms") == 10 * minute, "Todo B total mixed with another Todo");
        check(FocusSessionCore.query(h1, "todo_a", 8).optInt("session_count") == 2, "Todo A count changed after Todo B Focus");

        // 9. Focus ending does not mutate Todo completion semantics.
        check("open".equals(todos.get("todo_a").status), "Focus must never auto-complete Todo A");
        check("open".equals(todos.get("todo_b").status), "Focus must never auto-complete Todo B");

        // 10. Temporary release is not a Focus-session end.
        JSONObject hTemp = FocusSessionCore.emptyHistory();
        JSONObject cTemp = start(current(), "focus_temp", "todo_a", "考研", todoJson(todos, "todo_a", base), base, base + 60 * minute);
        cTemp.put("temporary_until_ms", base + 5 * minute);
        check(FocusSessionCore.settleIfExpired(cTemp, hTemp, base + 10 * minute) == null, "temporary release must not settle an unexpired Focus");
        check(cTemp.optBoolean("active"), "temporary release must keep Focus active");
        check(FocusSessionCore.historySize(hTemp) == 0, "temporary release must not append history");

        // 11 + 12. TTL expiry seals once at the scheduled TTL; repeated cleanup is idempotent.
        JSONObject hTtl = FocusSessionCore.emptyHistory();
        JSONObject cTtl = start(current(), "focus_ttl", "todo_a", "考研", todoJson(todos, "todo_a", base), base, base + 30 * minute);
        JSONObject ttl = FocusSessionCore.settleIfExpired(cTtl, hTtl, base + 45 * minute);
        check(ttl != null, "expired Focus must settle");
        check(ttl.optLong("ended_at_ms") == base + 30 * minute, "lazy cleanup must use scheduled TTL as factual end");
        check(ttl.optLong("duration_ms") == 30 * minute, "TTL duration must not inflate until cleanup read");
        check(FocusSessionCore.settleIfExpired(cTtl, hTtl, base + 60 * minute) == null, "repeated cleanup must be no-op");
        check(FocusSessionCore.historySize(hTtl) == 1, "repeated cleanup must not duplicate a session");

        // Explicit repeated finish is also idempotent after current active state is cleared.
        check(FocusSessionCore.finish(cTtl, hTtl, base + 60 * minute, "manual_end") == null, "finished Focus must not settle twice");
        check(FocusSessionCore.historySize(hTtl) == 1, "repeat finish duplicated session");

        // 13. Session history survives JSON serialize -> deserialize.
        String persistedHistory = h1.toString();
        JSONObject restoredHistory = FocusSessionCore.normalizeHistory(new JSONObject(persistedHistory));
        check(FocusSessionCore.query(restoredHistory, "todo_a", 8).optInt("session_count") == 2, "history round-trip lost sessions");
        check(FocusSessionCore.query(restoredHistory, "todo_a", 8).optLong("total_duration_ms") == 65 * minute, "history round-trip changed total duration");

        // 14. Active Focus metadata survives normal state serialization/reload.
        JSONObject active = start(current(), "focus_restart", "todo_b", "工作", todoJson(todos, "todo_b", base), base + 100 * minute, base + 130 * minute);
        JSONObject restoredActive = new JSONObject(active.toString());
        check(restoredActive.optBoolean("active"), "active Focus restart recovery lost active flag");
        check("focus_restart".equals(restoredActive.optString("session_id")), "active Focus restart recovery lost session_id");
        check("todo_b".equals(restoredActive.optString("todo_id")), "active Focus restart recovery lost todo_id");
        check("工作".equals(restoredActive.optString("category")), "active Focus restart recovery lost category");

        // 15. todo_id query returns only matching sessions.
        JSONObject onlyA = FocusSessionCore.query(h1, "todo_a", 20);
        JSONArray recentA = onlyA.getJSONArray("recent_sessions");
        for (int i = 0; i < recentA.length(); i++) {
            check("todo_a".equals(recentA.getJSONObject(i).optString("todo_id")), "todo_id query leaked another Todo session");
        }

        // 16. API/query is bounded while aggregate still uses full long-term history.
        JSONObject hMany = FocusSessionCore.emptyHistory();
        for (int i = 0; i < 30; i++) {
            JSONObject c = start(current(), "focus_many_" + i, "todo_a", "考研", todoJson(todos, "todo_a", base), base + i * 2 * minute, base + (i * 2 + 1) * minute);
            FocusSessionCore.finish(c, hMany, base + (i * 2 + 1) * minute, "manual_end");
        }
        JSONObject bounded = FocusSessionCore.query(hMany, "todo_a", 5);
        check(bounded.optInt("session_count") == 30, "bounded query must aggregate over full history");
        check(bounded.optLong("total_duration_ms") == 30 * minute, "bounded query total must not truncate history");
        check(bounded.getJSONArray("recent_sessions").length() == 5, "bounded query must not dump all history");
        check(FocusSessionCore.historySize(hMany) == 30, "local history must not be truncated to API limit");

        System.out.println("FocusSessionBehaviorTest: PASS");
    }
}
