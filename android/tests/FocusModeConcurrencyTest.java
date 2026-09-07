package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real production FocusMode + AppPrefs + TodoState, with an in-memory Android
 * SharedPreferences adapter. Controlled threads exercise the actual RMW paths;
 * this does NOT assert physical-disk durability or Android process recovery.
 */
public final class FocusModeConcurrencyTest {
    private static final MemoryPrefs prefs = new MemoryPrefs();
    private static final Context ctx = new Context() {
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            if (!"linjian_peek".equals(name)) throw new AssertionError(name);
            return prefs;
        }
    };
    private static final long MINUTE = 60000L;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static JSONObject object(String raw) throws Exception { return new JSONObject(raw); }
    private static JSONObject current() throws Exception { return object(prefs.getString(FocusMode.KEY_STATE, "{}")); }
    private static JSONObject history() throws Exception { return object(prefs.getString(FocusMode.KEY_SESSIONS, "{\"sessions\":[]}")); }
    private static int count() throws Exception { return history().getJSONArray("sessions").length(); }
    private static JSONObject onlySession() throws Exception {
        check(count() == 1, "expected exactly one completed session");
        return history().getJSONArray("sessions").getJSONObject(0);
    }
    private static void reset() {
        prefs.hook = null;
        prefs.edit().clear().commit();
        prefs.resetCounters();
    }
    private static String seed(long started, long until, String id) throws Exception {
        JSONObject s = new JSONObject();
        s.put("active", false);
        s.put("emergency_total", 3);
        s.put("emergency_used", 0);
        s.put("emergency_minutes", 1);
        FocusSessionCore.begin(s, id, new FocusSessionCore.Binding("", "考研"), started, until);
        prefs.edit().putString(FocusMode.KEY_STATE, s.toString()).commit();
        prefs.resetCounters();
        return id;
    }
    private static JSONObject command(String action) throws Exception { return new JSONObject().put("action", action); }
    private static JSONObject call(String action) throws Exception { return FocusMode.handleCommand(ctx, command(action)); }
    private static void consistent() throws Exception {
        JSONObject s = current();
        JSONArray sessions = history().getJSONArray("sessions");
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.getJSONObject(i);
            String id = session.getString("session_id");
            check(ids.add(id), "duplicate session_id: " + id);
            check("completed".equals(session.getString("status")), "unsealed history record");
            check(!s.optBoolean("active") || !id.equals(s.optString("session_id")), "completed session revived");
        }
    }
    private static boolean containsMessage(String text) throws Exception {
        JSONArray arr = current().optJSONArray("messages");
        if (arr == null) return false;
        for (int i = 0; i < arr.length(); i++)
            if (text.equals(arr.getJSONObject(i).optString("text"))) return true;
        return false;
    }

    interface ReadHook { void read(String key, String value); }
    static final class MemoryPrefs implements SharedPreferences {
        private final Map<String, String> data = new HashMap<>();
        volatile ReadHook hook;
        int pairCommits;
        int migrationWrites;
        @Override public String getString(String key, String def) {
            String value;
            synchronized (this) { value = data.getOrDefault(key, def); }
            ReadHook h = hook;
            if (h != null) h.read(key, value);
            return value;
        }
        @Override public synchronized int getInt(String key, int def) {
            try { return Integer.parseInt(data.get(key)); } catch (Exception e) { return def; }
        }
        @Override public synchronized boolean contains(String key) { return data.containsKey(key); }
        @Override public Editor edit() { return new Edit(); }
        synchronized void resetCounters() { pairCommits = 0; migrationWrites = 0; }
        final class Edit implements Editor {
            final Map<String, String> changes = new HashMap<>();
            boolean clear;
            @Override public Editor putString(String key, String value) { changes.put(key, value); return this; }
            @Override public Editor putInt(String key, int value) { return putString(key, String.valueOf(value)); }
            @Override public Editor remove(String key) { changes.put(key, null); return this; }
            @Override public Editor clear() { clear = true; return this; }
            @Override public boolean commit() {
                synchronized (MemoryPrefs.this) {
                    String previous = data.get(FocusMode.KEY_STATE);
                    if (clear) data.clear();
                    for (Map.Entry<String, String> e : changes.entrySet()) {
                        if (e.getValue() == null) data.remove(e.getKey());
                        else data.put(e.getKey(), e.getValue());
                    }
                    if (changes.containsKey(FocusMode.KEY_SESSIONS)) {
                        check(changes.containsKey(FocusMode.KEY_STATE), "history committed without current state");
                        pairCommits++;
                    }
                    try {
                        if (previous != null && changes.containsKey(FocusMode.KEY_STATE)) {
                            JSONObject before = object(previous);
                            JSONObject after = object(data.get(FocusMode.KEY_STATE));
                            if (before.optBoolean("active") && before.optString("session_id", "").isEmpty()
                                    && after.optBoolean("active") && !after.optString("session_id", "").isEmpty()) migrationWrites++;
                        }
                        if (data.containsKey(FocusMode.KEY_STATE) && data.containsKey(FocusMode.KEY_SESSIONS)) {
                            JSONObject s = object(data.get(FocusMode.KEY_STATE));
                            JSONArray arr = object(data.get(FocusMode.KEY_SESSIONS)).getJSONArray("sessions");
                            for (int i = 0; i < arr.length(); i++)
                                check(!s.optBoolean("active") || !s.optString("session_id").equals(arr.getJSONObject(i).optString("session_id")), "inconsistent atomic commit");
                        }
                    } catch (Exception e) { throw new AssertionError(e); }
                    return true;
                }
            }
            @Override public void apply() { commit(); }
        }
    }

    static final class ReadGate implements ReadHook {
        final String threadName;
        final AtomicBoolean once = new AtomicBoolean();
        final CountDownLatch read = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        ReadGate(String threadName) { this.threadName = threadName; }
        @Override public void read(String key, String value) {
            if (!FocusMode.KEY_STATE.equals(key) || !threadName.equals(Thread.currentThread().getName()) || !once.compareAndSet(false, true)) return;
            read.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("read gate timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
    }
    static final class Task<T> {
        final AtomicReference<Thread> thread = new AtomicReference<>();
        final CountDownLatch started = new CountDownLatch(1);
        final FutureTask<T> future;
        Task(String name, java.util.concurrent.Callable<T> action) {
            future = new FutureTask<>(() -> {
                Thread.currentThread().setName(name);
                thread.set(Thread.currentThread());
                started.countDown();
                return action.call();
            });
            new Thread(future, name).start();
        }
        T get() throws Exception { return future.get(10, TimeUnit.SECONDS); }
    }
    private static void await(CountDownLatch latch) throws Exception {
        check(latch.await(5, TimeUnit.SECONDS), "latch timeout");
    }
    private static void blockedBy(Task<?> contender, Task<?> owner) throws Exception {
        await(contender.started);
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            Thread t = contender.thread.get();
            ThreadInfo info = t == null ? null : mx.getThreadInfo(t.getId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED
                    && info.getLockOwnerId() == owner.thread.get().getId()) return;
            if (contender.future.isDone()) throw new AssertionError("contender completed while stale reader was paused");
            Thread.sleep(2);
        }
        throw new AssertionError("contender did not block on the owning state monitor");
    }
    private static void interleave(String label, boolean ttl) throws Exception {
        reset();
        long now = System.currentTimeMillis();
        String id = seed(now - 2 * MINUTE, ttl ? now - MINUTE : now + MINUTE, "focus_" + label);
        ReadGate gate = new ReadGate("ui");
        prefs.hook = gate;
        Task<Void> ui = new Task<>("ui", () -> { FocusMode.submitContactMessage(ctx, label); return null; });
        Task<JSONObject> settlement = null;
        try {
            await(gate.read);
            settlement = new Task<>("settlement", () -> ttl ? FocusMode.config(ctx) : call("end_focus_mode"));
            blockedBy(settlement, ui);
        } finally { gate.release.countDown(); }
        ui.get();
        settlement.get();
        prefs.hook = null;
        check(containsMessage(label), "UI message lost: " + label);
        check(!current().optBoolean("active"), "stale UI write revived " + id);
        check(id.equals(onlySession().getString("session_id")), "settled wrong session");
        check(prefs.pairCommits == 1, "settlement must use one paired commit");
        consistent();
    }
    private static void twoMessages() throws Exception {
        reset();
        long now = System.currentTimeMillis();
        seed(now, now + MINUTE, "focus_messages");
        ReadGate gate = new ReadGate("ui"); prefs.hook = gate;
        Task<Void> first = new Task<>("ui", () -> { FocusMode.submitContactMessage(ctx, "first"); return null; });
        Task<Void> second = null;
        try {
            await(gate.read);
            second = new Task<>("other-ui", () -> { FocusMode.submitContactMessage(ctx, "second"); return null; });
            blockedBy(second, first);
        } finally { gate.release.countDown(); }
        first.get(); second.get(); prefs.hook = null;
        check(containsMessage("first") && containsMessage("second"), "ordinary UI RMW lost an update");
        check(current().getJSONArray("requests").length() == 2, "request RMW lost an update");
        check(current().optBoolean("active"), "messages must not end Focus");
        consistent();
    }
    private static void repeatedSettlement() throws Exception {
        reset(); long now = System.currentTimeMillis();
        String id = seed(now - 2 * MINUTE, now - MINUTE, "focus_repeated");
        CountDownLatch go = new CountDownLatch(1);
        List<Task<JSONObject>> tasks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            final boolean read = i % 2 == 0;
            tasks.add(new Task<>("settle-" + i, () -> { go.await(); return read ? FocusMode.config(ctx) : call("end_focus_mode"); }));
        }
        go.countDown();
        for (Task<JSONObject> task : tasks) task.get();
        check(count() == 1 && id.equals(onlySession().getString("session_id")), "concurrent settlement duplicated history");
        FocusMode.submitContactMessage(ctx, "after end");
        check(!current().optBoolean("active"), "post-settlement message revived current");
        check(count() == 1 && prefs.pairCommits == 1, "repeated settlement must be idempotent");
        check(onlySession().getLong("duration_ms") == MINUTE, "TTL used lazy-read time");
        consistent();
    }
    private static void migration() throws Exception {
        reset(); long now = System.currentTimeMillis();
        seed(now, now + MINUTE, "will_be_removed");
        JSONObject old = current(); old.remove("session_id");
        prefs.edit().putString(FocusMode.KEY_STATE, old.toString()).commit(); prefs.resetCounters();
        ReadGate gate = new ReadGate("migration"); prefs.hook = gate;
        Task<JSONObject> first = new Task<>("migration", () -> FocusMode.config(ctx));
        Task<JSONObject> second = null;
        try {
            await(gate.read);
            second = new Task<>("other-reader", () -> FocusMode.config(ctx));
            blockedBy(second, first);
        } finally { gate.release.countDown(); }
        String a = first.get().getString("session_id");
        String b = second.get().getString("session_id"); prefs.hook = null;
        check(!a.isEmpty() && a.equals(b), "legacy migration created multiple session IDs");
        check(a.equals(FocusMode.config(ctx).getString("session_id")), "migration ID not durable in adapter");
        check(prefs.migrationWrites == 1, "legacy migration persisted more than once");
        check(count() == 0, "migration must not settle an unexpired Focus");
        consistent();
    }
    private static void temporaryReleaseAndPair() throws Exception {
        reset(); long now = System.currentTimeMillis();
        JSONObject create = TodoState.handleCommand(ctx, new JSONObject().put("action", "todo_action").put("operation", "create").put("title", "学习408").put("category", "考研"));
        check(create.optBoolean("ok"), "real Todo creation failed");
        String todoId = create.getJSONObject("todo").getString("id");
        JSONObject started = FocusMode.handleCommand(ctx, command("start_focus_mode").put("todo_id", todoId).put("duration_minutes", 30).put("screen_off", true));
        check(started.optBoolean("ok"), "real Focus start failed: " + started);
        String id = started.getString("session_id");
        check(FocusMode.offlineEmergencyUnlock(ctx, "urgent"), "offline release failed");
        check(id.equals(current().getString("session_id")) && count() == 0, "offline release settled session");
        JSONObject approved = FocusMode.handleCommand(ctx, command("approve_focus_unlock").put("minutes", 1));
        check(approved.optBoolean("ok"), "remote release failed");
        check(id.equals(current().getString("session_id")) && count() == 0, "remote release settled session");
        JSONObject ended = call("end_focus_mode");
        check(ended.optBoolean("ok"), "real Focus end failed");
        check(id.equals(onlySession().getString("session_id")), "start/end changed identity");
        check(todoId.equals(onlySession().getString("todo_id")), "Todo binding changed");
        check("open".equals(TodoState.findByIdForFocus(ctx, todoId).getString("status")), "Focus changed Todo completion");
        check(prefs.pairCommits == 1, "manual settlement must atomically commit current + history");
        JSONObject summary = FocusMode.handleCommand(ctx, command("get_focus_sessions").put("todo_id", todoId));
        check(summary.getInt("session_count") == 1, "history query lost the completed session");
        consistent();
    }
    private static void helperContract() throws Exception {
        Method state = FocusMode.class.getDeclaredMethod("state", Context.class);
        state.setAccessible(true);
        try {
            state.invoke(null, ctx);
            throw new AssertionError("unlocked private state access was allowed");
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalStateException
                    && "focus_state_lock_required".equals(expected.getCause().getMessage()), "incorrect lock guard");
        }
    }
    public static void main(String[] args) throws Exception {
        interleave("ttl-ui", true);
        interleave("manual-ui", false);
        twoMessages();
        repeatedSettlement();
        migration();
        temporaryReleaseAndPair();
        helperContract();
        System.out.println("FocusModeConcurrencyTest: PASS (production RMW + in-memory SharedPreferences adapter)");
    }
}
