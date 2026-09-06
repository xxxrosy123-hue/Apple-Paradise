#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


appgate = ROOT / "android/app/src/main/java/dev/linjian/peek/AppGate.java"
rules = ROOT / "android/app/src/main/java/dev/linjian/peek/AppGateDecisionRules.java"
test = ROOT / "android/tests/AppGateStateTransitionTest.java"
node = ROOT / "mcp/server.js"
worker = ROOT / "server/cloudflare-worker/worker.js"

# --- AppGate: one in-process monitor for all app_gate_state_v1 read-modify-write entrypoints.
replace_once(
    appgate,
    '''    private static volatile View overlayView = null;\n    private static volatile WindowManager overlayWindowManager = null;\n\n    public static boolean enabled(Context ctx) { return AppPrefs.get(ctx).getBoolean(KEY_ENABLED, true); }''',
    '''    private static volatile View overlayView = null;\n    private static volatile WindowManager overlayWindowManager = null;\n\n    // Single-process consistency guard: every app_gate_state_v1 read-modify-write entrypoint\n    // is static synchronized, so two Android writers cannot save stale whole-object snapshots over each other.\n    public static boolean enabled(Context ctx) { return AppPrefs.get(ctx).getBoolean(KEY_ENABLED, true); }''',
    "appgate-rmw-comment",
)

for old, new, label in [
    ("public static JSONObject handleCommand(Context ctx, JSONObject cmd)", "public static synchronized JSONObject handleCommand(Context ctx, JSONObject cmd)", "sync-handleCommand"),
    ("private static JSONObject lockApp(Context ctx, JSONObject cmd)", "private static synchronized JSONObject lockApp(Context ctx, JSONObject cmd)", "sync-lockApp"),
    ("private static JSONObject unlockApp(Context ctx, JSONObject cmd, String why)", "private static synchronized JSONObject unlockApp(Context ctx, JSONObject cmd, String why)", "sync-unlockApp"),
    ("private static JSONObject temporaryUnlock(Context ctx, JSONObject cmd)", "private static synchronized JSONObject temporaryUnlock(Context ctx, JSONObject cmd)", "sync-temporaryUnlock"),
    ("private static JSONObject extendLock(Context ctx, JSONObject cmd)", "private static synchronized JSONObject extendLock(Context ctx, JSONObject cmd)", "sync-extendLock"),
    ("private static JSONObject setEmergencyPassphrase(Context ctx, JSONObject cmd)", "private static synchronized JSONObject setEmergencyPassphrase(Context ctx, JSONObject cmd)", "sync-passphrase"),
    ("public static void onForegroundPackage(Context ctx, String pkg)", "public static synchronized void onForegroundPackage(Context ctx, String pkg)", "sync-foreground"),
    ("private static void addForegroundUsage(Context ctx, String pkg, long deltaMs)", "private static synchronized void addForegroundUsage(Context ctx, String pkg, long deltaMs)", "sync-usage"),
    ("public static JSONObject activeLockFor(Context ctx, String pkg, long now)", "public static synchronized JSONObject activeLockFor(Context ctx, String pkg, long now)", "sync-activeLockFor"),
    ("private static void saveLock(Context ctx, JSONObject l)", "private static synchronized void saveLock(Context ctx, JSONObject l)", "sync-saveLock"),
    ("private static boolean isTemporarilyAllowed(Context ctx, JSONObject l, long now, boolean updateSession)", "private static synchronized boolean isTemporarilyAllowed(Context ctx, JSONObject l, long now, boolean updateSession)", "sync-tempAllowed"),
    ("public static boolean tryEmergencyUnlock(Context ctx, String pkg, String passphrase)", "public static synchronized boolean tryEmergencyUnlock(Context ctx, String pkg, String passphrase)", "sync-emergency"),
    ("public static void submitUnlockRequest(final Context ctx, final String pkg, final String reason)", "public static synchronized void submitUnlockRequest(final Context ctx, final String pkg, final String reason)", "sync-request"),
    ("public static JSONObject config(Context ctx)", "public static synchronized JSONObject config(Context ctx)", "sync-config"),
    ("private static void log(Context ctx, String msg)", "private static synchronized void log(Context ctx, String msg)", "sync-log"),
]:
    replace_once(appgate, old, new, label)

# --- AppGate: route lock conflict semantics through the pure-Java decision rule.
replace_once(
    appgate,
    '''        if (existing != null && existing.optBoolean("temporary_active", false)) {\n            if (!existing.optBoolean("active", false)) {\n                // 兼容旧数据：门禁已经明确结束时，挂在旧 lock 上的 temporary_* 不再代表当前有效许可。\n                clearTemp(existing);\n                locks(s).put(pkg, existing);\n                save(ctx, s);\n            } else if (temporaryStillValid(existing, now)) {\n                if (!revokeTemporaryAllow) {\n                    JSONObject out = new JSONObject();\n                    long expiresAt = temporaryExpiresAt(existing);\n                    out.put("ok", false);\n                    out.put("error", "conflict_active_permission");\n                    out.put("package", pkg);\n                    out.put("active_permission", temporaryDecisionJson(existing, now));\n                    out.put("result", "conflict_active_permission:" + pkg + (expiresAt > 0 ? " until " + formatLocal(expiresAt) : ""));\n                    out.put("message", "已有仍有效的临时 ALLOW；普通重复锁定不会清除它。只有明确撤销时才传 revoke_temporary_allow=true。");\n                    log(ctx, "拒绝普通重复锁定：" + pkg + " 仍有有效临时放行");\n                    return out;\n                }\n                revokedDecisionId = existing.optString("temporary_decision_id", "");\n                clearTemp(existing);\n                locks(s).put(pkg, existing);\n                save(ctx, s);\n            } else {\n                clearTemp(existing);\n                locks(s).put(pkg, existing);\n                save(ctx, s);\n            }\n        }''',
    '''        if (existing != null && existing.optBoolean("temporary_active", false)) {\n            boolean baseLockActive = existing.optBoolean("active", false);\n            boolean temporaryEffective = baseLockActive && temporaryStillValid(existing, now);\n            AppGateDecisionRules.LockAttempt lockAttempt = AppGateDecisionRules.decideLockAttempt(\n                    baseLockActive, temporaryEffective, revokeTemporaryAllow);\n            if (!baseLockActive) {\n                // 兼容旧数据：门禁已经明确结束时，挂在旧 lock 上的 temporary_* 不再代表当前有效许可。\n                clearTemp(existing);\n                locks(s).put(pkg, existing);\n                save(ctx, s);\n            } else if (lockAttempt == AppGateDecisionRules.LockAttempt.CONFLICT_ACTIVE_PERMISSION) {\n                JSONObject out = new JSONObject();\n                long expiresAt = temporaryExpiresAt(existing);\n                out.put("ok", false);\n                out.put("error", "conflict_active_permission");\n                out.put("package", pkg);\n                out.put("active_permission", temporaryDecisionJson(existing, now));\n                out.put("result", "conflict_active_permission:" + pkg + (expiresAt > 0 ? " until " + formatLocal(expiresAt) : ""));\n                out.put("message", "已有仍有效的临时 ALLOW；普通重复锁定不会清除它。只有具备明确撤销权限时才传 revoke_temporary_allow=true。");\n                log(ctx, "拒绝普通重复锁定：" + pkg + " 仍有有效临时放行");\n                return out;\n            } else {\n                if (lockAttempt == AppGateDecisionRules.LockAttempt.REVOKE_ACTIVE_PERMISSION_AND_LOCK)\n                    revokedDecisionId = existing.optString("temporary_decision_id", "");\n                clearTemp(existing);\n                locks(s).put(pkg, existing);\n                save(ctx, s);\n            }\n        }''',
    "appgate-lock-conflict",
)

# --- AppGate: authoritative/effective views are mutually exclusive by construction.
replace_once(
    appgate,
    '''                JSONObject lockView = lockDecisionJson(l, now);\n                effectiveLocks.put(lockView);\n                if (temporaryStillValid(l, now)) {\n                    JSONObject allowView = temporaryDecisionJson(l, now);\n                    effectiveAllows.put(allowView);\n                    effectiveControls.put(allowView);\n                } else {\n                    effectiveControls.put(lockView);\n                }''',
    '''                boolean temporaryEffective = temporaryStillValid(l, now);\n                AppGateDecisionRules.EffectiveView view = AppGateDecisionRules.composeEffectiveView(true, temporaryEffective);\n                if (view.includeAllow) {\n                    JSONObject allowView = temporaryDecisionJson(l, now);\n                    effectiveAllows.put(allowView);\n                    effectiveControls.put(allowView);\n                } else if (view.includeLock) {\n                    JSONObject lockView = lockDecisionJson(l, now);\n                    effectiveLocks.put(lockView);\n                    effectiveControls.put(lockView);\n                }''',
    "appgate-effective-mutual-exclusion",
)

# --- Pure Java decision composition used by AppGate and tests.
rules.write_text('''package dev.linjian.peek;\n\n/**\n * AppGate 纯确定性状态规则。\n *\n * 这里只处理已经存在的 LOCK / ALLOW 的 TTL、冲突与当前有效视图合成；\n * 不判断用户是否应该被批准放行，也不替 AI / 用户做主观权限判断。\n */\nfinal class AppGateDecisionRules {\n    private AppGateDecisionRules() { }\n\n    enum LockAttempt {\n        APPLY_LOCK,\n        CONFLICT_ACTIVE_PERMISSION,\n        REVOKE_ACTIVE_PERMISSION_AND_LOCK\n    }\n\n    static final class EffectiveView {\n        final String decision;\n        final boolean includeLock;\n        final boolean includeAllow;\n\n        EffectiveView(String decision, boolean includeLock, boolean includeAllow) {\n            this.decision = decision;\n            this.includeLock = includeLock;\n            this.includeAllow = includeAllow;\n        }\n    }\n\n    static LockAttempt decideLockAttempt(\n            boolean baseLockActive,\n            boolean temporaryAllowEffective,\n            boolean revokeTemporaryAllow) {\n        if (baseLockActive && temporaryAllowEffective) {\n            return revokeTemporaryAllow\n                    ? LockAttempt.REVOKE_ACTIVE_PERMISSION_AND_LOCK\n                    : LockAttempt.CONFLICT_ACTIVE_PERMISSION;\n        }\n        return LockAttempt.APPLY_LOCK;\n    }\n\n    static EffectiveView composeEffectiveView(boolean baseLockActive, boolean temporaryAllowEffective) {\n        if (!baseLockActive) return new EffectiveView("", false, false);\n        if (temporaryAllowEffective) return new EffectiveView("ALLOW", false, true);\n        return new EffectiveView("LOCK", true, false);\n    }\n\n    static boolean isTemporaryAllowEffective(\n            boolean active,\n            String type,\n            long nowMs,\n            long untilMs,\n            long windowUntilMs,\n            long allowedMs,\n            long usedMs,\n            long sessionStartedMs,\n            boolean oneTimeUsed) {\n        if (!active) return false;\n        String normalized = type == null || type.trim().isEmpty() ? "real_time" : type.trim();\n        long hardWindow = windowUntilMs > 0 ? windowUntilMs : untilMs;\n        if (hardWindow <= 0 || nowMs >= hardWindow) return false;\n\n        if ("foreground_usage".equals(normalized)) {\n            if (allowedMs <= 0) return false;\n            long liveMs = sessionStartedMs > 0 ? Math.max(0L, nowMs - sessionStartedMs) : 0L;\n            return Math.max(0L, usedMs) + liveMs < allowedMs;\n        }\n        if ("one_time".equals(normalized)) return !oneTimeUsed;\n        return untilMs > 0 && nowMs < untilMs;\n    }\n\n    static long effectiveExpiryMs(String type, long untilMs, long windowUntilMs) {\n        String normalized = type == null || type.trim().isEmpty() ? "real_time" : type.trim();\n        if ("real_time".equals(normalized)) return untilMs;\n        return windowUntilMs > 0 ? windowUntilMs : untilMs;\n    }\n}\n''', encoding="utf-8")

# --- Real state-transition regression fixture using the same decision functions AppGate calls.
test.write_text('''package dev.linjian.peek;\n\nimport java.util.ArrayList;\nimport java.util.List;\n\n/** Behavioral regression tests for AppGate V1 P0 cross-window state transitions. */\npublic final class AppGateStateTransitionTest {\n    private static final String PKG = "com.example.app";\n\n    private static void check(boolean condition, String message) {\n        if (!condition) throw new AssertionError(message);\n    }\n\n    private static final class Fixture {\n        boolean lockActive;\n        long lockUntilMs;\n        boolean temporaryActive;\n        long temporaryUntilMs;\n        String lastResult = "";\n\n        void initialLock(long nowMs, long durationMs) {\n            lockActive = true;\n            lockUntilMs = nowMs + durationMs;\n            clearTemporary();\n            lastResult = "locked";\n        }\n\n        void temporaryAllow(long nowMs, long durationMs) {\n            check(baseLockActive(nowMs), "temporary allow requires an active base lock");\n            temporaryActive = true;\n            temporaryUntilMs = nowMs + durationMs;\n            lastResult = "temporary_unlocked";\n        }\n\n        void lock(long nowMs, long durationMs, boolean revokeTemporaryAllow) {\n            boolean baseActive = baseLockActive(nowMs);\n            boolean allowEffective = temporaryEffective(nowMs);\n            AppGateDecisionRules.LockAttempt attempt = AppGateDecisionRules.decideLockAttempt(\n                    baseActive, allowEffective, revokeTemporaryAllow);\n            if (attempt == AppGateDecisionRules.LockAttempt.CONFLICT_ACTIVE_PERMISSION) {\n                lastResult = "conflict_active_permission";\n                return;\n            }\n            if (temporaryActive) clearTemporary();\n            lockActive = true;\n            lockUntilMs = nowMs + durationMs;\n            lastResult = "locked";\n        }\n\n        boolean baseLockActive(long nowMs) {\n            return lockActive && nowMs < lockUntilMs;\n        }\n\n        boolean temporaryEffective(long nowMs) {\n            return baseLockActive(nowMs) && AppGateDecisionRules.isTemporaryAllowEffective(\n                    temporaryActive, "real_time", nowMs, temporaryUntilMs, temporaryUntilMs,\n                    Math.max(0L, temporaryUntilMs - nowMs), 0L, 0L, false);\n        }\n\n        AppGateDecisionRules.EffectiveView effectiveView(long nowMs) {\n            return AppGateDecisionRules.composeEffectiveView(baseLockActive(nowMs), temporaryEffective(nowMs));\n        }\n\n        List<String> effectiveLocks(long nowMs) {\n            List<String> out = new ArrayList<>();\n            if (effectiveView(nowMs).includeLock) out.add(PKG);\n            return out;\n        }\n\n        List<String> effectiveAllows(long nowMs) {\n            List<String> out = new ArrayList<>();\n            if (effectiveView(nowMs).includeAllow) out.add(PKG);\n            return out;\n        }\n\n        void clearTemporary() {\n            temporaryActive = false;\n            temporaryUntilMs = 0L;\n        }\n    }\n\n    private static void assertEffective(Fixture f, long nowMs, String decision, int lockCount, int allowCount) {\n        AppGateDecisionRules.EffectiveView view = f.effectiveView(nowMs);\n        List<String> locks = f.effectiveLocks(nowMs);\n        List<String> allows = f.effectiveAllows(nowMs);\n        check(decision.equals(view.decision), "expected effective decision " + decision + " but was " + view.decision);\n        check(locks.size() == lockCount, "effective_lock_count must equal effective_locks length");\n        check(allows.size() == allowCount, "effective_allow_count must equal effective_allows length");\n        check(!(locks.contains(PKG) && allows.contains(PKG)), "effective_locks and effective_allows must be mutually exclusive");\n    }\n\n    public static void main(String[] args) {\n        long now = 1_000_000L;\n        long hour = 3_600_000L;\n        long tenMinutes = 600_000L;\n\n        // Case A: LOCK -> ALLOW -> ordinary LOCK must conflict and preserve ALLOW.\n        Fixture a = new Fixture();\n        a.initialLock(now, hour);\n        a.temporaryAllow(now, tenMinutes);\n        a.lock(now + 60_000L, hour, false);\n        check("conflict_active_permission".equals(a.lastResult), "ordinary re-lock must return conflict_active_permission");\n        check(a.temporaryEffective(now + 60_000L), "ordinary re-lock must preserve active ALLOW");\n        assertEffective(a, now + 60_000L, "ALLOW", 0, 1);\n\n        // Case B: explicit revoke -> new LOCK becomes authoritative.\n        Fixture b = new Fixture();\n        b.initialLock(now, hour);\n        b.temporaryAllow(now, tenMinutes);\n        b.lock(now + 60_000L, hour, true);\n        check("locked".equals(b.lastResult), "authorized revoke should apply new LOCK");\n        check(!b.temporaryActive, "authorized revoke must clear old ALLOW");\n        assertEffective(b, now + 60_000L, "LOCK", 1, 0);\n\n        // Case C: ALLOW TTL expiry naturally reveals the still-active base LOCK.\n        Fixture c = new Fixture();\n        c.initialLock(now, hour);\n        c.temporaryAllow(now, tenMinutes);\n        assertEffective(c, now + tenMinutes - 1L, "ALLOW", 0, 1);\n        assertEffective(c, now + tenMinutes, "LOCK", 1, 0);\n\n        // Case D: counts are derived from the mutually-exclusive effective collections.\n        check(c.effectiveLocks(now + tenMinutes).size() == 1, "Case D lock count mismatch");\n        check(c.effectiveAllows(now + tenMinutes).size() == 0, "Case D allow count mismatch");\n\n        System.out.println("AppGateStateTransitionTest: PASS");\n    }\n}\n''', encoding="utf-8")

# --- Node/Render MCP authority boundary.
node_authority = "revoke_temporary_allow=true 仅用于用户本人新的明确撤销决定，或已经定义且优先级更高的明确规则要求撤销当前许可；不得仅因另一个 AI 窗口主观判断‘现在不该使用该 App’就撤销仍然有效的明确许可。换聊天窗口不代表原有许可失效。"
replace_once(
    node,
    'server.tool("screen_break_app", "屏幕休息：让指定 App 暂停一段时间。普通重复锁定不得覆盖仍有效的临时 ALLOW；只有已经明确决定撤销当前许可时才传 revoke_temporary_allow=true。必须有时长，到点自动恢复；语气是照顾和带回，不是惩罚。", {',
    f'server.tool("screen_break_app", "屏幕休息：让指定 App 暂停一段时间。普通重复锁定不得覆盖仍有效的临时 ALLOW；{node_authority} 必须有时长，到点自动恢复；语气是照顾和带回，不是惩罚。", {{',
    "node-screen-break-authority",
)
replace_once(
    node,
    'revoke_temporary_allow: z.boolean().default(false).describe("仅当明确要撤销当前仍有效的临时 ALLOW 并立即恢复锁定时设为 true；普通重复 lock 必须保持 false"),',
    f'revoke_temporary_allow: z.boolean().default(false).describe("{node_authority} 普通重复 lock 必须保持 false"),',
    "node-screen-break-param-authority",
)
replace_once(
    node,
    'server.tool("lock_app", "应用门禁：旧版兼容工具名。普通重复锁定不得覆盖仍有效的临时 ALLOW；只有明确撤销当前许可时才传 revoke_temporary_allow=true。", {',
    f'server.tool("lock_app", "应用门禁：旧版兼容工具名。普通重复锁定不得覆盖仍有效的临时 ALLOW；{node_authority}", {{',
    "node-lock-app-authority",
)
replace_once(
    node,
    'revoke_temporary_allow: z.boolean().default(false),\n    emergency_passphrase: z.string().default(""),',
    f'revoke_temporary_allow: z.boolean().default(false).describe("{node_authority} 普通重复 lock 必须保持 false"),\n    emergency_passphrase: z.string().default(""),',
    "node-lock-app-param-authority",
)

# Ensure the existing non-blind-approval contract remains present in both temporary allow aliases.
node_text = node.read_text(encoding="utf-8")
required_allow_contract = "不得仅因用户提出请求就无脑批准临时放行。换聊天窗口不代表原有管理原则失效，也不能因为当前窗口缺少历史就默认放行。"
if node_text.count(required_allow_contract) < 2:
    raise SystemExit("node temporary allow contract was unexpectedly lost")

# --- Cloudflare Worker authority boundary. Worker has screen_break_app as the corresponding lock tool.
worker_authority = "revoke_temporary_allow=true 仅用于用户本人新的明确撤销决定，或已经定义且优先级更高的明确规则要求撤销当前许可；不得仅因另一个 AI 窗口主观判断‘现在不该使用该 App’就撤销仍然有效的明确许可。换聊天窗口不代表原有许可失效。"
replace_once(
    worker,
    '{ name: "screen_break_app", description: "对指定 App 开启门禁/屏幕休息。普通重复锁定不得覆盖仍有效的临时 ALLOW；只有明确撤销当前许可时才传 revoke_temporary_allow=true。", inputSchema:',
    f'{{ name: "screen_break_app", description: "对指定 App 开启门禁/屏幕休息。普通重复锁定不得覆盖仍有效的临时 ALLOW；{worker_authority}", inputSchema:',
    "worker-screen-break-authority",
)
worker_text = worker.read_text(encoding="utf-8")
if required_allow_contract not in worker_text:
    raise SystemExit("worker temporary allow contract was unexpectedly lost")

print("V1 P0 closeout patch applied successfully")
