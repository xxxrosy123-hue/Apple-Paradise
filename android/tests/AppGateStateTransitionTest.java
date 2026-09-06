package dev.linjian.peek;

import java.util.ArrayList;
import java.util.List;

/** Behavioral regression tests for AppGate V1 P0 cross-window state transitions. */
public final class AppGateStateTransitionTest {
    private static final String PKG = "com.example.app";

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Fixture {
        boolean lockActive;
        long lockUntilMs;
        boolean temporaryActive;
        long temporaryUntilMs;
        String lastResult = "";

        void initialLock(long nowMs, long durationMs) {
            lockActive = true;
            lockUntilMs = nowMs + durationMs;
            clearTemporary();
            lastResult = "locked";
        }

        void temporaryAllow(long nowMs, long durationMs) {
            check(baseLockActive(nowMs), "temporary allow requires an active base lock");
            temporaryActive = true;
            temporaryUntilMs = nowMs + durationMs;
            lastResult = "temporary_unlocked";
        }

        void lock(long nowMs, long durationMs, boolean revokeTemporaryAllow) {
            boolean baseActive = baseLockActive(nowMs);
            boolean allowEffective = temporaryEffective(nowMs);
            AppGateDecisionRules.LockAttempt attempt = AppGateDecisionRules.decideLockAttempt(
                    baseActive, allowEffective, revokeTemporaryAllow);
            if (attempt == AppGateDecisionRules.LockAttempt.CONFLICT_ACTIVE_PERMISSION) {
                lastResult = "conflict_active_permission";
                return;
            }
            if (temporaryActive) clearTemporary();
            lockActive = true;
            lockUntilMs = nowMs + durationMs;
            lastResult = "locked";
        }

        boolean baseLockActive(long nowMs) {
            return lockActive && nowMs < lockUntilMs;
        }

        boolean temporaryEffective(long nowMs) {
            return baseLockActive(nowMs) && AppGateDecisionRules.isTemporaryAllowEffective(
                    temporaryActive, "real_time", nowMs, temporaryUntilMs, temporaryUntilMs,
                    Math.max(0L, temporaryUntilMs - nowMs), 0L, 0L, false);
        }

        AppGateDecisionRules.EffectiveView effectiveView(long nowMs) {
            return AppGateDecisionRules.composeEffectiveView(baseLockActive(nowMs), temporaryEffective(nowMs));
        }

        List<String> effectiveLocks(long nowMs) {
            List<String> out = new ArrayList<>();
            if (effectiveView(nowMs).includeLock) out.add(PKG);
            return out;
        }

        List<String> effectiveAllows(long nowMs) {
            List<String> out = new ArrayList<>();
            if (effectiveView(nowMs).includeAllow) out.add(PKG);
            return out;
        }

        void clearTemporary() {
            temporaryActive = false;
            temporaryUntilMs = 0L;
        }
    }

    private static void assertEffective(Fixture f, long nowMs, String decision, int lockCount, int allowCount) {
        AppGateDecisionRules.EffectiveView view = f.effectiveView(nowMs);
        List<String> locks = f.effectiveLocks(nowMs);
        List<String> allows = f.effectiveAllows(nowMs);
        check(decision.equals(view.decision), "expected effective decision " + decision + " but was " + view.decision);
        check(locks.size() == lockCount, "effective_lock_count must equal effective_locks length");
        check(allows.size() == allowCount, "effective_allow_count must equal effective_allows length");
        check(!(locks.contains(PKG) && allows.contains(PKG)), "effective_locks and effective_allows must be mutually exclusive");
    }

    public static void main(String[] args) {
        long now = 1_000_000L;
        long hour = 3_600_000L;
        long tenMinutes = 600_000L;

        // Case A: LOCK -> ALLOW -> ordinary LOCK must conflict and preserve ALLOW.
        Fixture a = new Fixture();
        a.initialLock(now, hour);
        a.temporaryAllow(now, tenMinutes);
        a.lock(now + 60_000L, hour, false);
        check("conflict_active_permission".equals(a.lastResult), "ordinary re-lock must return conflict_active_permission");
        check(a.temporaryEffective(now + 60_000L), "ordinary re-lock must preserve active ALLOW");
        assertEffective(a, now + 60_000L, "ALLOW", 0, 1);

        // Case B: explicit revoke -> new LOCK becomes authoritative.
        Fixture b = new Fixture();
        b.initialLock(now, hour);
        b.temporaryAllow(now, tenMinutes);
        b.lock(now + 60_000L, hour, true);
        check("locked".equals(b.lastResult), "authorized revoke should apply new LOCK");
        check(!b.temporaryActive, "authorized revoke must clear old ALLOW");
        assertEffective(b, now + 60_000L, "LOCK", 1, 0);

        // Case C: ALLOW TTL expiry naturally reveals the still-active base LOCK.
        Fixture c = new Fixture();
        c.initialLock(now, hour);
        c.temporaryAllow(now, tenMinutes);
        assertEffective(c, now + tenMinutes - 1L, "ALLOW", 0, 1);
        assertEffective(c, now + tenMinutes, "LOCK", 1, 0);

        // Case D: counts are derived from the mutually-exclusive effective collections.
        check(c.effectiveLocks(now + tenMinutes).size() == 1, "Case D lock count mismatch");
        check(c.effectiveAllows(now + tenMinutes).size() == 0, "Case D allow count mismatch");

        System.out.println("AppGateStateTransitionTest: PASS");
    }
}
