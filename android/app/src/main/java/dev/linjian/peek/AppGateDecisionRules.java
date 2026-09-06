package dev.linjian.peek;

/**
 * AppGate 纯确定性 TTL 规则。
 *
 * 这里只判断“一个已经存在的临时 ALLOW 在给定时刻是否仍然有效”，
 * 不判断用户是否应该被批准放行；批准与否仍由 AI / 用户根据上下文决定。
 */
final class AppGateDecisionRules {
    private AppGateDecisionRules() { }

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
