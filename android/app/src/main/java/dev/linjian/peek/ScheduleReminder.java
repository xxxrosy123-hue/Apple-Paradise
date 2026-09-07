package dev.linjian.peek;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local, inexact Schedule reminders. No AI polling, exact-alarm privilege, or life-maintenance engine. */
public final class ScheduleReminder extends BroadcastReceiver {
    private static final String ACTION="dev.linjian.peek.SCHEDULE_REMINDER";
    private static final String PREF="schedule_reminder_delivery_v1";
    private static final String RECEIPTS="receipts";
    private static final long LATE_WINDOW=15*60000L;
    private static final long HORIZON=45L*86400000L;
    private static final long RESCAN=24L*3600000L;
    private static final int REQUEST_CODE=39172;

    private static PendingIntent pending(Context ctx,String token,long fire) {
        Intent i=new Intent(ctx,ScheduleReminder.class).setAction(ACTION);
        i.putExtra("token",token); i.putExtra("fire_at_ms",fire);
        return PendingIntent.getBroadcast(ctx,REQUEST_CODE,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private static JSONObject receipts(Context ctx) {
        try { return new JSONObject(AppPrefs.get(ctx).getString(PREF,"")); }
        catch(Exception e) { return ScheduleCore.obj("receipts",new JSONArray()); }
    }
    private static boolean contains(JSONArray a,String token) {
        for(int i=0;i<a.length();i++) { JSONObject r=a.optJSONObject(i); if(r!=null&&token.equals(r.optString("token"))) return true; }
        return false;
    }
    private static void prune(JSONArray a,long now) {
        for(int i=a.length()-1;i>=0;i--) { JSONObject r=a.optJSONObject(i); if(r==null||r.optLong("at_ms",0)<now-30L*86400000L) a.remove(i); }
        while(a.length()>2048) a.remove(0);
    }
    private static void schedule(Context ctx,long at,String token) {
        AlarmManager am=(AlarmManager)ctx.getSystemService(Context.ALARM_SERVICE);
        if(am==null) return;
        PendingIntent pi=pending(ctx,token,at);
        if(Build.VERSION.SDK_INT>=23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi);
        else am.set(AlarmManager.RTC_WAKEUP,at,pi);
    }
    /** All entry points use one monitor; ScheduleState never calls this while holding its own lock. */
    public static synchronized void reschedule(Context ctx) {
        try {
            long now=System.currentTimeMillis(); JSONObject d=receipts(ctx); JSONArray seen=d.optJSONArray(RECEIPTS); if(seen==null) seen=new JSONArray();
            JSONObject best=null;
            JSONArray candidates=ScheduleState.reminderCandidates(ctx,now-LATE_WINDOW,now+HORIZON);
            for(int i=0;i<candidates.length();i++) {
                JSONObject r=candidates.optJSONObject(i); if(r==null||contains(seen,r.optString("token"))) continue;
                long fire=r.optLong("fire_at_ms"); if(fire<now-LATE_WINDOW) continue;
                if(best==null||fire<best.optLong("fire_at_ms")) best=r;
            }
            // A daily local rescan discovers new recurrence dates and survives ordinary process death.
            long next=Math.min(now+RESCAN,best==null?Long.MAX_VALUE:Math.max(now+1000,best.optLong("fire_at_ms")));
            String token=best!=null&&best.optLong("fire_at_ms")<=now+RESCAN?best.optString("token"):"rescan";
            schedule(ctx,next,token);
        } catch(Exception e) { DebugState.append(ctx,"日程提醒重建失败："+e.getMessage()); }
    }
    @Override public void onReceive(Context ctx,Intent intent) {
        if(intent==null) return;
        if(!ACTION.equals(intent.getAction())) { reschedule(ctx); return; }
        String token=intent.getStringExtra("token");
        if(token==null||token.equals("rescan")) { reschedule(ctx); return; }
        deliver(ctx,token);
    }
    private static synchronized void deliver(Context ctx,String token) {
        try {
            long now=System.currentTimeMillis(); JSONObject d=receipts(ctx); JSONArray seen=d.optJSONArray(RECEIPTS); if(seen==null) seen=new JSONArray();
            if(contains(seen,token)) { reschedule(ctx); return; }
            JSONArray candidates=ScheduleState.reminderCandidates(ctx,now-LATE_WINDOW,now+1000);
            JSONObject match=null;
            for(int i=0;i<candidates.length();i++) { JSONObject r=candidates.optJSONObject(i); if(r!=null&&token.equals(r.optString("token"))) { match=r; break; } }
            if(match!=null) {
                seen.put(ScheduleCore.obj("token",token,"at_ms",now)); prune(seen,now);
                ScheduleCore.put(d,RECEIPTS,seen);
                if(!AppPrefs.get(ctx).edit().putString(PREF,d.toString()).commit()) throw new IllegalStateException("schedule_reminder_receipt_failed");
                String title=match.optString("title","日程");
                String time=ScheduleCore.format(match.optLong("start_at_ms"),"HH:mm",java.util.TimeZone.getDefault());
                CompanionService.showReminderNotification(ctx,"日程 · "+title,"计划开始："+time);
            }
        } catch(Exception e) { DebugState.append(ctx,"日程提醒异常："+e.getMessage()); }
        finally { reschedule(ctx); }
    }
}
