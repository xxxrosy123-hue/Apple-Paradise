package dev.linjian.peek;

import org.json.JSONArray;
import org.json.JSONObject;

/** Pure state machine for one persisted inexact Schedule reminder. */
final class ScheduleReminderCore {
    static final int VERSION=2;
    static final long MAX_LATE_MS=90L*60000L;
    static final long RECEIPT_RETENTION_MS=30L*86400000L;
    static final int MAX_RECEIPTS=2048;
    static final String RECEIPTS="receipts";
    static final String PENDING="pending";

    private ScheduleReminderCore() { }

    static JSONObject empty() {
        return ScheduleCore.obj("schema_version",VERSION,RECEIPTS,new JSONArray());
    }

    static JSONObject fromJson(String raw) {
        JSONObject state;
        try { state=ScheduleCore.clean(raw).isEmpty()?empty():new JSONObject(raw); }
        catch(Exception e) { state=empty(); }
        if(state.optJSONArray(RECEIPTS)==null) ScheduleCore.put(state,RECEIPTS,new JSONArray());
        ScheduleCore.put(state,"schema_version",VERSION);
        prune(state.optJSONArray(RECEIPTS),System.currentTimeMillis());
        return state;
    }

    static boolean contains(JSONArray receipts,String token) {
        for(int i=0;i<receipts.length();i++) {
            JSONObject receipt=receipts.optJSONObject(i);
            if(receipt!=null&&token.equals(receipt.optString("token"))) return true;
        }
        return false;
    }

    static void prune(JSONArray receipts,long now) {
        for(int i=receipts.length()-1;i>=0;i--) {
            JSONObject receipt=receipts.optJSONObject(i);
            if(receipt==null||receipt.optLong("at_ms",0)<now-RECEIPT_RETENTION_MS) receipts.remove(i);
        }
        while(receipts.length()>MAX_RECEIPTS) receipts.remove(0);
    }

    static JSONObject pending(JSONObject state) { return state.optJSONObject(PENDING); }

    static boolean sameReminder(JSONObject a,JSONObject b) {
        return a!=null&&b!=null&&!ScheduleCore.clean(a.optString("token")).isEmpty()&&
                a.optString("token").equals(b.optString("token"));
    }
    private static boolean laterThan(JSONObject a,JSONObject b) {
        return b==null||a.optLong("fire_at_ms",Long.MAX_VALUE)<b.optLong("fire_at_ms",Long.MAX_VALUE);
    }

    /**
     * Select one alarm. Only the already-persisted pending reminder may be caught up late;
     * newly discovered past reminders are never replayed in a burst after a long outage.
     */
    static JSONObject selectNext(JSONObject state,JSONObject currentPendingReminder,JSONArray futureCandidates,long now) {
        JSONArray receipts=state.optJSONArray(RECEIPTS); prune(receipts,now);
        JSONObject best=null,stored=pending(state);
        if(sameReminder(stored,currentPendingReminder)&&!contains(receipts,stored.optString("token"))) {
            long fire=stored.optLong("fire_at_ms",0);
            if(fire>0&&fire>=now-MAX_LATE_MS) best=ScheduleCore.copy(stored);
        }
        for(int i=0;i<futureCandidates.length();i++) {
            JSONObject candidate=futureCandidates.optJSONObject(i);
            if(candidate==null||candidate.optLong("fire_at_ms",0)<=now||contains(receipts,candidate.optString("token"))) continue;
            if(laterThan(candidate,best)) best=ScheduleCore.copy(candidate);
        }
        if(best==null) state.remove(PENDING); else ScheduleCore.put(state,PENDING,ScheduleCore.copy(best));
        return best;
    }

    /** Validate, receipt and clear a delivery before the notification side effect. */
    static boolean acceptDelivery(JSONObject state,String token,JSONObject currentReminder,long now) {
        JSONArray receipts=state.optJSONArray(RECEIPTS);
        prune(receipts,now);
        JSONObject stored=pending(state);
        if(contains(receipts,token)) return false;
        if(stored==null||!token.equals(stored.optString("token"))) return false;
        if(!sameReminder(stored,currentReminder)) {
            state.remove(PENDING); return false;
        }
        long fire=stored.optLong("fire_at_ms",0);
        if(now<fire) return false;
        if(fire<=0||now-fire>MAX_LATE_MS) { state.remove(PENDING); return false; }
        receipts.put(ScheduleCore.obj("token",token,"at_ms",now)); prune(receipts,now);
        state.remove(PENDING);
        return true;
    }
}
