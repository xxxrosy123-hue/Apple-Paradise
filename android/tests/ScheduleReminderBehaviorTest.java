package dev.linjian.peek;

import org.json.JSONArray;
import org.json.JSONObject;

/** Pure production reminder-state tests; AlarmManager/Doze still require device verification. */
public final class ScheduleReminderBehaviorTest {
    static void yes(boolean value,String message) { if(!value) throw new AssertionError(message); }
    static JSONObject reminder(String id,long fire) {
        return ScheduleCore.obj("id",id,"title",id,"start_at_ms",fire+60000,"fire_at_ms",fire,"token",id+":"+fire);
    }
    static void testPersistedLateDelivery() {
        long now=System.currentTimeMillis(); JSONObject state=ScheduleReminderCore.empty();
        JSONObject expected=reminder("schedule_a",now+1000); JSONArray future=new JSONArray().put(expected);
        JSONObject selected=ScheduleReminderCore.selectNext(state,null,future,now);
        yes(ScheduleReminderCore.sameReminder(selected,expected),"future reminder selected");
        JSONObject restarted=ScheduleReminderCore.fromJson(state.toString());
        JSONObject pending=ScheduleReminderCore.pending(restarted);
        yes(ScheduleReminderCore.acceptDelivery(restarted,pending.optString("token"),expected,
                pending.optLong("fire_at_ms")+20*60000L),"persisted reminder delivered after normal idle delay");
        yes(ScheduleReminderCore.pending(restarted)==null,"pending cleared before notification");
        yes(!ScheduleReminderCore.acceptDelivery(restarted,expected.optString("token"),expected,now+30*60000L),"duplicate broadcast suppressed");
    }
    static void testLateBoundAndNoBacklogBurst() {
        long now=System.currentTimeMillis(); JSONObject state=ScheduleReminderCore.empty();
        JSONObject old=reminder("schedule_old",now-ScheduleReminderCore.MAX_LATE_MS-1);
        ScheduleCore.put(state,ScheduleReminderCore.PENDING,old);
        yes(ScheduleReminderCore.selectNext(state,old,new JSONArray(),now)==null,"expired pending discarded");
        yes(!ScheduleReminderCore.acceptDelivery(state,old.optString("token"),old,now),"expired reminder not delivered");
        JSONObject recentButUntracked=reminder("schedule_untracked",now-60000);
        yes(ScheduleReminderCore.selectNext(ScheduleReminderCore.empty(),null,new JSONArray().put(recentButUntracked),now)==null,
                "newly discovered past reminders do not form a restart backlog");
    }
    static void testEditDeleteAndReceiptsBounded() {
        long now=System.currentTimeMillis(); JSONObject state=ScheduleReminderCore.empty();
        JSONObject original=reminder("schedule_edit",now+60000);
        ScheduleReminderCore.selectNext(state,null,new JSONArray().put(original),now);
        JSONObject edited=reminder("schedule_edit",now+120000);
        ScheduleReminderCore.selectNext(state,edited,new JSONArray().put(edited),now);
        yes(ScheduleReminderCore.sameReminder(ScheduleReminderCore.pending(state),edited),"edit replaces persisted token");
        yes(!ScheduleReminderCore.acceptDelivery(state,original.optString("token"),original,now+70000),"old edited token rejected");
        yes(ScheduleReminderCore.sameReminder(ScheduleReminderCore.pending(state),edited),"old broadcast does not cancel edited reminder");
        ScheduleReminderCore.selectNext(state,null,new JSONArray(),now);
        yes(ScheduleReminderCore.pending(state)==null,"delete clears pending reminder");

        JSONArray receipts=state.getJSONArray(ScheduleReminderCore.RECEIPTS);
        for(int i=0;i<ScheduleReminderCore.MAX_RECEIPTS+20;i++) receipts.put(ScheduleCore.obj("token","t"+i,"at_ms",now));
        ScheduleReminderCore.prune(receipts,now);
        yes(receipts.length()==ScheduleReminderCore.MAX_RECEIPTS,"receipt history bounded");
    }
    public static void main(String[] args) {
        testPersistedLateDelivery();testLateBoundAndNoBacklogBurst();testEditDeleteAndReceiptsBounded();
        System.out.println("ScheduleReminderBehaviorTest: PASS (pending persistence, bounded late delivery, edit/delete, duplicate and receipt bounds)");
    }
}
