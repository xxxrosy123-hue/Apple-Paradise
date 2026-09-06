#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match in {path}, got {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"patched {label}: {path}")


# Android: preserve a live ALLOW on ordinary re-lock; inactive locks cannot keep ghost permissions.
replace_once(
    "android/app/src/main/java/dev/linjian/peek/AppGate.java",
'''        boolean revokeTemporaryAllow = cmd.optBoolean("revoke_temporary_allow", cmd.optBoolean("revokeTemporaryAllow", false));
        if (existing != null && existing.optBoolean("temporary_active", false)) {
            if (temporaryStillValid(existing, now)) {
                if (!revokeTemporaryAllow) {
                    JSONObject out = new JSONObject();
                    long expiresAt = temporaryExpiresAt(existing);
                    out.put("ok", false);
                    out.put("error", "conflict_active_permission");
                    out.put("package", pkg);
                    out.put("active_permission", temporaryDecisionJson(existing, now));
                    out.put("result", "conflict_active_permission:" + pkg + (expiresAt > 0 ? " until " + formatLocal(expiresAt) : ""));
                    out.put("message", "已有仍有效的临时 ALLOW；普通重复锁定不会清除它。只有明确撤销时才传 revoke_temporary_allow=true。");
                    log(ctx, "拒绝普通重复锁定：" + pkg + " 仍有有效临时放行");
                    return out;
                }
                String decisionId = existing.optString("temporary_decision_id", "");
                clearTemp(existing);
                locks(s).put(pkg, existing);
                save(ctx, s);
                log(ctx, "明确撤销临时放行：" + pkg + (decisionId.length() > 0 ? " decision=" + decisionId : ""));
            } else {
                clearTemp(existing);
                locks(s).put(pkg, existing);
                save(ctx, s);
            }
        }
''',
'''        boolean revokeTemporaryAllow = cmd.optBoolean("revoke_temporary_allow", cmd.optBoolean("revokeTemporaryAllow", false));
        String revokedDecisionId = "";
        if (existing != null && existing.optBoolean("temporary_active", false)) {
            if (!existing.optBoolean("active", false)) {
                // 兼容旧数据：门禁已经明确结束时，挂在旧 lock 上的 temporary_* 不再代表当前有效许可。
                clearTemp(existing);
                locks(s).put(pkg, existing);
                save(ctx, s);
            } else if (temporaryStillValid(existing, now)) {
                if (!revokeTemporaryAllow) {
                    JSONObject out = new JSONObject();
                    long expiresAt = temporaryExpiresAt(existing);
                    out.put("ok", false);
                    out.put("error", "conflict_active_permission");
                    out.put("package", pkg);
                    out.put("active_permission", temporaryDecisionJson(existing, now));
                    out.put("result", "conflict_active_permission:" + pkg + (expiresAt > 0 ? " until " + formatLocal(expiresAt) : ""));
                    out.put("message", "已有仍有效的临时 ALLOW；普通重复锁定不会清除它。只有明确撤销时才传 revoke_temporary_allow=true。");
                    log(ctx, "拒绝普通重复锁定：" + pkg + " 仍有有效临时放行");
                    return out;
                }
                revokedDecisionId = existing.optString("temporary_decision_id", "");
                clearTemp(existing);
                locks(s).put(pkg, existing);
                save(ctx, s);
            } else {
                clearTemp(existing);
                locks(s).put(pkg, existing);
                save(ctx, s);
            }
        }
''',
    "android-lock-conflict"
)

replace_once(
    "android/app/src/main/java/dev/linjian/peek/AppGate.java",
'''        locks(s).put(pkg, lock); save(ctx, s);
        addGateApp(ctx, lock.optString("app_name", labelOf(ctx, pkg)), pkg);
        log(ctx, "锁定 " + lock.optString("app_name") + " 到 " + lock.optString("locked_until_local") + "：" + lock.optString("reason"));
''',
'''        locks(s).put(pkg, lock); save(ctx, s);
        addGateApp(ctx, lock.optString("app_name", labelOf(ctx, pkg)), pkg);
        if (revokedDecisionId.length() > 0) log(ctx, "明确撤销临时放行：" + pkg + " decision=" + revokedDecisionId);
        else if (revokeTemporaryAllow) log(ctx, "明确撤销临时放行后重新锁定：" + pkg);
        log(ctx, "锁定 " + lock.optString("app_name") + " 到 " + lock.optString("locked_until_local") + "：" + lock.optString("reason"));
''',
    "android-revoke-log"
)

replace_once(
    "android/app/src/main/java/dev/linjian/peek/AppGate.java",
'''        JSONObject s = state(ctx); JSONObject l = locks(s).optJSONObject(pkg);
        if (l != null) { l.put("active", false); l.put("unlocked_at_ms", System.currentTimeMillis()); l.put("unlock_reason", why); }
        save(ctx, s); log(ctx, "解除门禁：" + pkg + "（" + why + "）");
''',
'''        JSONObject s = state(ctx); JSONObject l = locks(s).optJSONObject(pkg);
        if (l != null) {
            l.put("active", false);
            l.put("unlocked_at_ms", System.currentTimeMillis());
            l.put("unlock_reason", why);
            // unlock_app/end_screen_break 是明确结束当前门禁；关联的临时 ALLOW 也随这次控制一起结束。
            clearTemp(l);
        }
        save(ctx, s); log(ctx, "解除门禁：" + pkg + "（" + why + "）");
''',
    "android-unlock-clears-allow"
)

replace_once(
    "android/app/src/main/java/dev/linjian/peek/AppGate.java",
'''            if (l == null || !l.optBoolean("temporary_active", false)) continue;
            if (!temporaryStillValid(l, now)) {
''',
'''            if (l == null || !l.optBoolean("temporary_active", false)) continue;
            if (!l.optBoolean("active", false) || !temporaryStillValid(l, now)) {
''',
    "android-cleanup-inactive-allow"
)

# Render/Node MCP: make the AI-side precondition explicit and expose deterministic revoke + decision metadata.
replace_once(
    "mcp/server.js",
'''  server.tool("screen_break_app", "屏幕休息：让指定 App 暂停一段时间。适合小红书/抖音等容易一刷很久的入口；当用户刷太久、眼睛酸还想继续、说“我就不/不要你管/还没玩够/继续看”等嘴硬或拖延表达时可调用。必须有时长，到点自动恢复；语气是照顾和带回，不是惩罚。", {
    app: z.string().default("").describe("应用昵称，例如 小红书；也可留空直接传 package"),
    package: z.string().default("").describe("App 包名，例如 com.xingin.xhs"),
    duration_minutes: z.number().min(0.1).max(10080).default(30).describe("锁定多少分钟，支持任意时长；到点自动解锁"),
    mode: z.string().default("medium").describe("light/medium/strict；strict 会先拉回桌面再显示锁定页"),
    reason: z.string().default("陪伴对象先把这扇门关一会儿。"),
    message: z.string().default("先回来找我，不准一个人刷太久。"),
    emergency_passphrase: z.string().default("").describe("紧急口令，由陪伴对象设置后告诉用户；手机端只存 hash"),
    emergency_unlock_minutes: z.number().int().min(1).max(60).default(5),
    device_id: z.string().default(DEFAULT_DEVICE),
    wait_seconds: z.number().int().min(3).max(20).default(8)
  }, async ({ app = "", package: pkg = "", duration_minutes = 30, mode = "medium", reason, message, emergency_passphrase = "", emergency_unlock_minutes = 5, device_id = DEFAULT_DEVICE, wait_seconds = 8 }) => {
    const locked_until_ms = Date.now() + Math.round(duration_minutes * 60000);
    const response = await gateCommand({ action: "screen_break_app", app, package: pkg, device_id, locked_until_ms, duration_minutes, mode, reason, message, emergency_passphrase, emergencyPassphrase: emergency_passphrase, emergency_unlock_minutes, emergencyUnlockMinutes: emergency_unlock_minutes, payload: { app, package: pkg, locked_until_ms, duration_minutes, mode, reason, message, emergency_passphrase, emergencyPassphrase: emergency_passphrase, emergency_unlock_minutes, emergencyUnlockMinutes: emergency_unlock_minutes } }, wait_seconds);
''',
'''  server.tool("screen_break_app", "屏幕休息：让指定 App 暂停一段时间。普通重复锁定不得覆盖仍有效的临时 ALLOW；只有已经明确决定撤销当前许可时才传 revoke_temporary_allow=true。必须有时长，到点自动恢复；语气是照顾和带回，不是惩罚。", {
    app: z.string().default("").describe("应用昵称，例如 小红书；也可留空直接传 package"),
    package: z.string().default("").describe("App 包名，例如 com.xingin.xhs"),
    duration_minutes: z.number().min(0.1).max(10080).default(30).describe("锁定多少分钟，支持任意时长；到点自动解锁"),
    mode: z.string().default("medium").describe("light/medium/strict；strict 会先拉回桌面再显示锁定页"),
    reason: z.string().default("陪伴对象先把这扇门关一会儿。"),
    message: z.string().default("先回来找我，不准一个人刷太久。"),
    revoke_temporary_allow: z.boolean().default(false).describe("仅当明确要撤销当前仍有效的临时 ALLOW 并立即恢复锁定时设为 true；普通重复 lock 必须保持 false"),
    emergency_passphrase: z.string().default("").describe("紧急口令，由陪伴对象设置后告诉用户；手机端只存 hash"),
    emergency_unlock_minutes: z.number().int().min(1).max(60).default(5),
    device_id: z.string().default(DEFAULT_DEVICE),
    wait_seconds: z.number().int().min(3).max(20).default(8)
  }, async ({ app = "", package: pkg = "", duration_minutes = 30, mode = "medium", reason, message, revoke_temporary_allow = false, emergency_passphrase = "", emergency_unlock_minutes = 5, device_id = DEFAULT_DEVICE, wait_seconds = 8 }) => {
    const locked_until_ms = Date.now() + Math.round(duration_minutes * 60000);
    const response = await gateCommand({ action: "screen_break_app", app, package: pkg, device_id, locked_until_ms, duration_minutes, mode, reason, message, revoke_temporary_allow, emergency_passphrase, emergencyPassphrase: emergency_passphrase, emergency_unlock_minutes, emergencyUnlockMinutes: emergency_unlock_minutes, payload: { app, package: pkg, locked_until_ms, duration_minutes, mode, reason, message, revoke_temporary_allow, emergency_passphrase, emergencyPassphrase: emergency_passphrase, emergency_unlock_minutes, emergencyUnlockMinutes: emergency_unlock_minutes } }, wait_seconds);
''',
    "node-screen-break"
)

replace_once(
    "mcp/server.js",
'''  server.tool("temporary_screen_break_release", "屏幕休息：临时放行一个正在休息中的 App；退出重进不会刷新时间；可选择现实时间或前台实际使用时间。", {
    app: z.string().default(""), package: z.string().default(""),
    minutes: z.number().min(0.1).max(240).default(10),
    allow_type: z.string().default("real_time").describe("real_time=从允许后连续倒计时；foreground_usage=只扣前台实际使用时长；one_time=只允许一次"),
    max_window_minutes: z.number().min(1).max(480).default(30),
    device_id: z.string().default(DEFAULT_DEVICE), wait_seconds: z.number().int().min(3).max(20).default(8)
  }, async ({ app = "", package: pkg = "", minutes = 10, allow_type = "real_time", max_window_minutes = 30, device_id = DEFAULT_DEVICE, wait_seconds = 8 }) => {
    return gateCommand({ action: "temporary_screen_break_release", app, package: pkg, device_id, minutes, allowed_minutes: minutes, allow_type, max_window_minutes, payload: { app, package: pkg, minutes, allowed_minutes: minutes, allow_type, max_window_minutes } }, wait_seconds);
  });
''',
'''  server.tool("temporary_screen_break_release", "屏幕休息：临时放行一个正在休息中的 App；退出重进不会刷新时间。请根据用户当前是否确实需要使用该 App、当前任务/专注/日程状态以及苹果乐园中的既有规则判断是否放行；不得仅因用户提出请求就无脑批准临时放行。换聊天窗口不代表原有管理原则失效，也不能因为当前窗口缺少历史就默认放行。", {
    app: z.string().default(""), package: z.string().default(""),
    minutes: z.number().min(0.1).max(240).default(10),
    allow_type: z.string().default("real_time").describe("real_time=从允许后连续倒计时；foreground_usage=只扣前台实际使用时长；one_time=只允许一次"),
    max_window_minutes: z.number().min(1).max(480).default(30),
    reason: z.string().default("").describe("批准本次临时放行的事实理由；不要伪造，不清楚就留空"),
    source: z.string().default("mcp").describe("决定来源，例如 mcp/chatgpt/manual"),
    approved_by: z.string().default("companion").describe("做出本次明确批准的主体标识"),
    purpose: z.string().default("").describe("可选：本次许可用途/类型"),
    device_id: z.string().default(DEFAULT_DEVICE), wait_seconds: z.number().int().min(3).max(20).default(8)
  }, async ({ app = "", package: pkg = "", minutes = 10, allow_type = "real_time", max_window_minutes = 30, reason = "", source = "mcp", approved_by = "companion", purpose = "", device_id = DEFAULT_DEVICE, wait_seconds = 8 }) => {
    return gateCommand({ action: "temporary_screen_break_release", app, package: pkg, device_id, minutes, allowed_minutes: minutes, allow_type, max_window_minutes, reason, source, approved_by, purpose, payload: { app, package: pkg, minutes, allowed_minutes: minutes, allow_type, max_window_minutes, reason, source, approved_by, purpose } }, wait_seconds);
  });
''',
    "node-temporary-release"
)

replace_once(
    "mcp/server.js",
'''  server.tool("lock_app", "应用门禁：旧版兼容工具名。锁定/暂停指定 App 一段时间，等同 screen_break_app。", {
    app: z.string().default(""),
    package: z.string().default(""),
    duration_minutes: z.number().min(0.1).max(10080).default(30),
    mode: z.string().default("medium"),
    reason: z.string().default("陪伴对象先把这扇门关一会儿。"),
    message: z.string().default("先回来找我，不准一个人刷太久。"),
    emergency_passphrase: z.string().default(""),
    emergency_unlock_minutes: z.number().int().min(1).max(60).default(5),
    device_id: z.string().default(DEFAULT_DEVICE),
    wait_seconds: z.number().int().min(3).max(20).default(8)
  }, async ({ app = "", package: pkg = "", duration_minutes = 30, mode = "medium", reason, message, emergency_passphrase = "", emergency_unlock_minutes = 5, device_id = DEFAULT_DEVICE, wait_seconds = 8 }) => {
    const locked_until_ms = Date.now() + Math.round(duration_minutes * 60000);
    const response = await gateCommand({ action: "lock_app", app, package: pkg, device_id, locked_until_ms, duration_minutes, mode, reason, message, emergency_passphrase, emergencyPassphrase: emergency_passphrase, emergency_unlock_minutes, emergencyUnlockMinutes: emergency_unlock_minutes, payload: { app, package: pkg, locked_until_ms, duration_minutes, mode, reason, message, emergency_passphrase, emergencyPassphrase: emergency_passphrase, emergency_unlock_minutes, emergencyUnlockMinutes: emergency_unlock_minutes } }, wait_seconds);
''',
'''  server.tool("lock_app", "应用门禁：旧版兼容工具名。普通重复锁定不得覆盖仍有效的临时 ALLOW；只有明确撤销当前许可时才传 revoke_temporary_allow=true。", {
    app: z.string().default(""),
    package: z.string().default(""),
    duration_minutes: z.number().min(0.1).max(10080).default(30),
    mode: z.string().default("medium"),
    reason: z.string().default("陪伴对象先把这扇门关一会儿。"),
    message: z.string().default("先回来找我，不准一个人刷太久。"),
    revoke_temporary_allow: z.boolean().default(false),
    emergency_passphrase: z.string().default(""),
    emergency_unlock_minutes: z.number().int().min(1).max(60).default(5),
    device_id: z.string().default(DEFAULT_DEVICE),
    wait_seconds: z.number().int().min(3).max(20).default(8)
  }, async ({ app = "", package: pkg = "", duration_minutes = 30, mode = "medium", reason, message, revoke_temporary_allow = false, emergency_passphrase = "", emergency_unlock_minutes = 5, device_id = DEFAULT_DEVICE, wait_seconds = 8 }) => {
    const locked_until_ms = Date.now() + Math.round(duration_minutes * 60000);
    const response = await gateCommand({ action: "lock_app", app, package: pkg, device_id, locked_until_ms, duration_minutes, mode, reason, message, revoke_temporary_allow, emergency_passphrase, emergencyPassphrase: emergency_passphrase, emergency_unlock_minutes, emergencyUnlockMinutes: emergency_unlock_minutes, payload: { app, package: pkg, locked_until_ms, duration_minutes, mode, reason, message, revoke_temporary_allow, emergency_passphrase, emergencyPassphrase: emergency_passphrase, emergency_unlock_minutes, emergencyUnlockMinutes: emergency_unlock_minutes } }, wait_seconds);
''',
    "node-lock-alias"
)

replace_once(
    "mcp/server.js",
'''  server.tool("temporary_unlock_app", "应用门禁：旧版兼容工具名。临时放行一个正在门禁中的 App。", {
    app: z.string().default(""), package: z.string().default(""),
    minutes: z.number().min(0.1).max(240).default(10),
    allow_type: z.string().default("real_time"),
    max_window_minutes: z.number().min(1).max(480).default(30),
    device_id: z.string().default(DEFAULT_DEVICE), wait_seconds: z.number().int().min(3).max(20).default(8)
  }, async ({ app = "", package: pkg = "", minutes = 10, allow_type = "real_time", max_window_minutes = 30, device_id = DEFAULT_DEVICE, wait_seconds = 8 }) => {
    return gateCommand({ action: "temporary_unlock_app", app, package: pkg, device_id, minutes, allowed_minutes: minutes, allow_type, max_window_minutes, payload: { app, package: pkg, minutes, allowed_minutes: minutes, allow_type, max_window_minutes } }, wait_seconds);
  });
''',
'''  server.tool("temporary_unlock_app", "应用门禁：旧版兼容工具名。请根据用户当前是否确实需要使用该 App、当前任务/专注/日程状态以及苹果乐园中的既有规则判断是否放行；不得仅因用户提出请求就无脑批准临时放行。换聊天窗口不代表原有管理原则失效，也不能因为当前窗口缺少历史就默认放行。", {
    app: z.string().default(""), package: z.string().default(""),
    minutes: z.number().min(0.1).max(240).default(10),
    allow_type: z.string().default("real_time"),
    max_window_minutes: z.number().min(1).max(480).default(30),
    reason: z.string().default(""),
    source: z.string().default("mcp"),
    approved_by: z.string().default("companion"),
    purpose: z.string().default(""),
    device_id: z.string().default(DEFAULT_DEVICE), wait_seconds: z.number().int().min(3).max(20).default(8)
  }, async ({ app = "", package: pkg = "", minutes = 10, allow_type = "real_time", max_window_minutes = 30, reason = "", source = "mcp", approved_by = "companion", purpose = "", device_id = DEFAULT_DEVICE, wait_seconds = 8 }) => {
    return gateCommand({ action: "temporary_unlock_app", app, package: pkg, device_id, minutes, allowed_minutes: minutes, allow_type, max_window_minutes, reason, source, approved_by, purpose, payload: { app, package: pkg, minutes, allowed_minutes: minutes, allow_type, max_window_minutes, reason, source, approved_by, purpose } }, wait_seconds);
  });
''',
    "node-temporary-alias"
)

# Cloudflare MCP registration + call adapter must expose the same semantics.
replace_once(
    "server/cloudflare-worker/worker.js",
'''  { name: "screen_break_app", description: "对指定 App 开启门禁/屏幕休息，支持紧急口令。", inputSchema: obj({ app: str(""), package: str(""), duration_minutes: num(30), mode: str("medium"), reason: str(""), message: str("回到掌心窗。"), emergency_passphrase: str(""), emergency_unlock_minutes: int(5), device_id: str(DEFAULT_DEVICE), wait_seconds: int(8) }) },
  { name: "temporary_screen_break_release", description: "临时放行一个正在休息中的 App。", inputSchema: obj({ app: str(""), package: str(""), minutes: num(10), allow_type: str("real_time"), max_window_minutes: num(30), device_id: str(DEFAULT_DEVICE), wait_seconds: int(8) }) },
''',
'''  { name: "screen_break_app", description: "对指定 App 开启门禁/屏幕休息。普通重复锁定不得覆盖仍有效的临时 ALLOW；只有明确撤销当前许可时才传 revoke_temporary_allow=true。", inputSchema: obj({ app: str(""), package: str(""), duration_minutes: num(30), mode: str("medium"), reason: str(""), message: str("回到掌心窗。"), revoke_temporary_allow: bool(false), emergency_passphrase: str(""), emergency_unlock_minutes: int(5), device_id: str(DEFAULT_DEVICE), wait_seconds: int(8) }) },
  { name: "temporary_screen_break_release", description: "临时放行一个正在休息中的 App。请根据用户当前是否确实需要使用该 App、当前任务/专注/日程状态以及苹果乐园中的既有规则判断是否放行；不得仅因用户提出请求就无脑批准临时放行。换聊天窗口不代表原有管理原则失效，也不能因为当前窗口缺少历史就默认放行。", inputSchema: obj({ app: str(""), package: str(""), minutes: num(10), allow_type: str("real_time"), max_window_minutes: num(30), reason: str(""), source: str("mcp"), approved_by: str("companion"), purpose: str(""), device_id: str(DEFAULT_DEVICE), wait_seconds: int(8) }) },
''',
    "worker-mcp-schema"
)

replace_once(
    "server/cloudflare-worker/worker.js",
'''    case "screen_break_app": {
      const app = args.app || ""; const pkg = packageFor(app, args.package || ""); const duration = Number(args.duration_minutes || 30); const locked_until_ms = Date.now() + Math.round(duration * 60000);
      const payload = { app, package: pkg, duration_minutes: duration, locked_until_ms, mode: args.mode || "medium", reason: args.reason || "", message: args.message || "回到掌心窗。", emergency_passphrase: args.emergency_passphrase || "", emergencyPassphrase: args.emergency_passphrase || "", emergency_unlock_minutes: args.emergency_unlock_minutes ?? 5, emergencyUnlockMinutes: args.emergency_unlock_minutes ?? 5 };
      return observed({ action: "screen_break_app", ...payload, payload }, args.wait_seconds ?? 8);
    }
    case "temporary_screen_break_release": {
      const app = args.app || ""; const pkg = packageFor(app, args.package || ""); const payload = { app, package: pkg, minutes: Number(args.minutes || 10), allowed_minutes: Number(args.minutes || 10), allow_type: args.allow_type || "real_time", max_window_minutes: Number(args.max_window_minutes || 30) };
      return observed({ action: "temporary_screen_break_release", ...payload, payload }, args.wait_seconds ?? 8);
    }
''',
'''    case "screen_break_app": {
      const app = args.app || ""; const pkg = packageFor(app, args.package || ""); const duration = Number(args.duration_minutes || 30); const locked_until_ms = Date.now() + Math.round(duration * 60000);
      const payload = { app, package: pkg, duration_minutes: duration, locked_until_ms, mode: args.mode || "medium", reason: args.reason || "", message: args.message || "回到掌心窗。", revoke_temporary_allow: Boolean(args.revoke_temporary_allow), emergency_passphrase: args.emergency_passphrase || "", emergencyPassphrase: args.emergency_passphrase || "", emergency_unlock_minutes: args.emergency_unlock_minutes ?? 5, emergencyUnlockMinutes: args.emergency_unlock_minutes ?? 5 };
      return observed({ action: "screen_break_app", ...payload, payload }, args.wait_seconds ?? 8);
    }
    case "temporary_screen_break_release": {
      const app = args.app || ""; const pkg = packageFor(app, args.package || ""); const payload = { app, package: pkg, minutes: Number(args.minutes || 10), allowed_minutes: Number(args.minutes || 10), allow_type: args.allow_type || "real_time", max_window_minutes: Number(args.max_window_minutes || 30), reason: args.reason || "", source: args.source || "mcp", approved_by: args.approved_by || "companion", purpose: args.purpose || "" };
      return observed({ action: "temporary_screen_break_release", ...payload, payload }, args.wait_seconds ?? 8);
    }
''',
    "worker-mcp-call"
)

print("V1 P0 patch applied successfully")
