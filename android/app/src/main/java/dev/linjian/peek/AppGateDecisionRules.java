package dev.linjian.peek;

/**
 * AppGate 纯确定性状态规则。
 *
 * 这里只处理已经存在的 LOCK / ALLOW 的 TTL、冲突与当前有效视图合成；
 * 不判断用户是否应该被批准放行，也不替 AI / 用户做主观权限判断。
 */
final class AppGateDecisionRules {
    private AppGateDecisionRules() { }

    enum LockAttempt {
        APPLY_LOCK,
        CONFLICT_ACTIVE_PERMISSION,
        REVOKE_ACTIVE_PERMISSION_AND_LOCK
    }

    static final class EffectiveView {
        final String decision;
        final boolean includeLock;
        final boolean includeAllow;

        EffectiveView(String decision, boolean includeLock, boolean includeAllow) {
            this.decision = decision;
            this.includeLock = includeLock;
            this.includeAllow = includeAllow;
        }
    }

    static LockAttempt decideLockAttempt(
            boolean baseLockActive,
            boolean temporaryAllowEffective,
            boolean revokeTemporaryAllow) {
        if (baseLockActive && temporaryAllowEffective) {
            return revokeTemporaryAllow
                    ? LockAttempt.REVOKE_ACTIVE_PERMISSION_AND_LOCK
                    : LockAttempt.CONFLICT_ACTIVE_PERMISSION;
        }
        return LockAttempt.APPLY_LOCK;
    }

    static EffectiveView composeEffectiveView(boolean baseLockActive, boolean temporaryAllowEffective) {
        if (!baseLockActive) return new EffectiveView("", false, false);
        if (temporaryAllowEffective) return new EffectiveView("ALLOW", false, true);
        return new EffectiveView("LOCK", true, false);
    }

    static boolean isTemporaryAllowEffective(
            boolean active,
            String type,
            long nowMs,
            long untilMs,
            long windowUntilMs,
            long allowedMs,
            long usedMs,
            long sessionStartedMs,
            boolean oneTimeUsed) {
        if (!active) return false;
        String normalized = type == null || type.trim().isEmpty() ? "real_time" : type.trim();
        long hardWindow = windowUntilMs > 0 ? windowUntilMs : untilMs;
        if (hardWindow <= 0 || nowMs >= hardWindow) return false;

        if ("foreground_usage".equals(normalized)) {
            if (allowedMs <= 0) return false;
            long liveMs = sessionStartedMs > 0 ? Math.max(0L, nowMs - sessionStartedMs) : 0L;
            return Math.max(0L, usedMs) + liveMs < allowedMs;
        }
        if ("one_time".equals(normalized)) return !oneTimeUsed;
        return untilMs > 0 && nowMs < untilMs;
    }

    static long effectiveExpiryMs(String type, long untilMs, long windowUntilMs) {
        String normalized = type == null || type.trim().isEmpty() ? "real_time" : type.trim();
        if ("real_time".equals(normalized)) return untilMs;
        return windowUntilMs > 0 ? windowUntilMs : untilMs;
    }
}
