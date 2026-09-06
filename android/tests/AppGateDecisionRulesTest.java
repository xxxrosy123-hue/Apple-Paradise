package dev.linjian.peek;

/** Pure-Java regression checks for AppGate V1 P0 TTL semantics. */
public final class AppGateDecisionRulesTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        long now = 1_000_000L;

        check(AppGateDecisionRules.isTemporaryAllowEffective(
                true, "real_time", now, now + 600_000L, now + 600_000L,
                600_000L, 0L, 0L, false),
                "real_time allow should remain active before expiry");

        check(!AppGateDecisionRules.isTemporaryAllowEffective(
                true, "real_time", now, now, now,
                600_000L, 0L, 0L, false),
                "real_time allow must expire exactly at expiry");

        check(AppGateDecisionRules.isTemporaryAllowEffective(
                true, "foreground_usage", now, now + 600_000L, now + 1_800_000L,
                600_000L, 300_000L, 0L, false),
                "foreground_usage allow should remain active while usage budget remains");

        check(!AppGateDecisionRules.isTemporaryAllowEffective(
                true, "foreground_usage", now, now + 600_000L, now + 1_800_000L,
                600_000L, 600_000L, 0L, false),
                "foreground_usage allow must expire when usage budget is consumed");

        check(AppGateDecisionRules.isTemporaryAllowEffective(
                true, "one_time", now, now + 600_000L, now + 1_800_000L,
                600_000L, 0L, 0L, false),
                "one_time allow should be active before first use");

        check(!AppGateDecisionRules.isTemporaryAllowEffective(
                true, "one_time", now, now + 600_000L, now + 1_800_000L,
                600_000L, 0L, 0L, true),
                "one_time allow must expire after use");

        check(AppGateDecisionRules.effectiveExpiryMs("real_time", 123L, 456L) == 123L,
                "real_time expiry must use temporary_until_ms");
        check(AppGateDecisionRules.effectiveExpiryMs("foreground_usage", 123L, 456L) == 456L,
                "foreground_usage expiry must use hard window");
        check(AppGateDecisionRules.effectiveExpiryMs("one_time", 123L, 456L) == 456L,
                "one_time expiry must use hard window");

        System.out.println("AppGateDecisionRulesTest: PASS");
    }
}
