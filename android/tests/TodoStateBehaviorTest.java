package dev.linjian.peek;

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
