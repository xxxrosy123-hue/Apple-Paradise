from pathlib import Path
import re

ROOT = Path('android/app/src/main/java/dev/linjian/peek')
p = ROOT / 'FocusMode.java'
s = p.read_text(encoding='utf-8')


def once(old, new, label):
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one anchor, found {count}')
    s = s.replace(old, new, 1)


# Every persistent current/history access must occur under the same class monitor.
# Helpers deliberately assert ownership instead of acquiring short-lived locks:
# synchronizing load() and save() separately would NOT fix stale whole-object RMW.
once('    private static JSONObject defaultState() {', '''    /** The single-process owner of both Focus JSON keys is FocusMode.class. */
    private static void requireStateLock() {
        if (!Thread.holdsLock(FocusMode.class))
            throw new IllegalStateException("focus_state_lock_required");
    }

    private static JSONObject defaultState() {''', 'lock contract')
for signature in [
    'private static JSONObject state(Context ctx)',
    'private static void save(Context ctx, JSONObject s)',
    'private static JSONObject history(Context ctx)',
    'private static void saveBoth(Context ctx, JSONObject current, JSONObject history)',
    'private static JSONObject start(Context ctx, JSONObject cmd)',
    'private static JSONObject setPlan(Context ctx, JSONObject cmd)',
    'private static JSONObject end(Context ctx, String reason)',
    'private static JSONObject querySessions(Context ctx, JSONObject cmd)',
    'private static JSONObject createRequest(Context ctx, String reason)',
    'private static JSONObject reply(Context ctx, String message, boolean approved)',
    'private static JSONObject deny(Context ctx, String message)',
    'private static JSONObject approve(Context ctx, JSONObject cmd)',
    'private static boolean isActiveRaw(Context ctx, JSONObject s, long now)',
    'private static JSONObject settleExpired(Context ctx, JSONObject s, long now)',
]:
    pattern = re.compile(r'(^    ' + re.escape(signature) + r'(?: throws Exception)? \{\n)', re.M)
    if len(pattern.findall(s)) != 1:
        raise SystemExit('missing/duplicate guarded method: ' + signature)
    s = pattern.sub(lambda m: m.group(1) + '        requireStateLock();\n', s, count=1)

# Dispatch mutates under the monitor; device/UI side effects occur after it is released.
once('    public static synchronized JSONObject handleCommand(Context ctx, JSONObject cmd) {', '''    public static JSONObject handleCommand(Context ctx, JSONObject cmd) {
        JSONObject out = handleCommandLocked(ctx, cmd);
        if (!out.optBoolean("ok", false) || cmd == null) return out;
        String action = cmd.optString("action", "");
        String result = out.optString("result", "");
        if (("start_focus_mode".equals(action) || "enable_focus_mode".equals(action))
                && result.startsWith("focus_started")) {
            // A later end/new start may have won after the persistence lock was released.
            JSONObject current = config(ctx);
            if (current.optBoolean("active", false)
                    && out.optString("session_id", "").equals(current.optString("session_id", ""))) {
                forceShowLockActivity(ctx);
                ScreenshotService svc = ScreenshotService.getInstance();
                if (cmd.optBoolean("screen_off", false) && svc != null) svc.doLockScreen();
            }
        } else if (("approve_focus_unlock".equals(action) || "temporary_focus_unlock".equals(action))
                && result.startsWith("focus_temporary_unlocked:")) {
            ScreenshotService svc = ScreenshotService.getInstance();
            if (svc != null) svc.doHome();
        }
        return out;
    }

    private static synchronized JSONObject handleCommandLocked(Context ctx, JSONObject cmd) {
        requireStateLock();''', 'locked dispatcher')
once('''        save(ctx, s);
        forceShowLockActivity(ctx);
        ScreenshotService svc = ScreenshotService.getInstance();
        if (cmd.optBoolean("screen_off", false) && svc != null) svc.doLockScreen();

        JSONObject out = new JSONObject();''', '''        save(ctx, s);

        JSONObject out = new JSONObject();''', 'start external effects')
once('''        save(ctx, s);
        ScreenshotService svc = ScreenshotService.getInstance();
        if (svc != null) svc.doHome();
        return put(new JSONObject(), true, "focus_temporary_unlocked:" + minutes + "min");''', '''        save(ctx, s);
        return put(new JSONObject(), true, "focus_temporary_unlocked:" + minutes + "min");''', 'temporary release external effect')

# This was the concrete unprotected UI writer: the complete request/message/log RMW
# must share the same monitor as manual/TTL settlement, not only save() itself.
once('    public static void submitContactMessage(Context ctx, String text) {',
     '    public static synchronized void submitContactMessage(Context ctx, String text) {', 'UI writer')

# PackageManager and Activity launch are not persistent-state operations. Keep them
# outside the monitor, preserving the existing throttle and lock-page semantics.
pattern = re.compile(r'    public static synchronized void onForegroundPackage\(Context ctx, String pkg\) \{.*?\n    \}\n\n    /\*\*\n     \* 远程/MCP', re.S)
replacement = '''    public static void onForegroundPackage(Context ctx, String pkg) {
        try {
            if (pkg == null || pkg.trim().length() == 0) return;
            String p = pkg.trim();
            if (SELF_PACKAGE.equals(p) || isProtectedPackage(ctx, p)) return;
            boolean show;
            synchronized (FocusMode.class) {
                long now = System.currentTimeMillis();
                JSONObject s = state(ctx);
                if (!isActiveRaw(ctx, s, now)) return;
                if (now < s.optLong("temporary_until_ms", 0L)) return;
                if (isLockActivityVisible()) return;
                if (now - lastLockAt < 1800) return;
                lastLockAt = now;
                show = true;
            }
            // Never launch an Activity or invoke external UI callbacks under the state lock.
            if (show) startLockActivity(ctx);
        } catch (Exception e) { DebugState.append(ctx, "专注模式前台检查异常：" + ScreenshotService.shortMsg(e)); }
    }

    /**
     * 远程/MCP'''
if len(pattern.findall(s)) != 1:
    raise SystemExit('onForegroundPackage block not unique')
s = pattern.sub(lambda m: replacement, s, count=1)

# Fail closed if any new direct persistence access is added without an audited owner.
expected = {
    'state': 10,  # not used for counts: all accesses are checked structurally below
}
assert s.count('KEY_STATE') == 4, 'unexpected current-state key access'
assert s.count('KEY_SESSIONS') == 4, 'unexpected history key access'
for name in ['state', 'save', 'history', 'saveBoth']:
    assert s.count('requireStateLock();') >= 18

p.write_text(s, encoding='utf-8')
print('Phase 4 closeout: production FocusMode synchronization patch applied')
