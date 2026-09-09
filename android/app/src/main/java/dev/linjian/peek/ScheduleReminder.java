package dev.linjian.peek;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import org.json.JSONObject;

/** Local, inexact Schedule reminders. No AI polling, exact-alarm privilege, or life-maintenance engine. */
public final class ScheduleReminder extends BroadcastReceiver {
    private static final String ACTION="dev.linjian.peek.SCHEDULE_REMINDER";
    private static final String PREF="schedule_reminder_delivery_v1";
    private static final long HORIZON=45L*86400000L;
    private static final long RESCAN=24L*3600000L;
    private static final int REQUEST_CODE=39172;

    private static PendingIntent pending(Context ctx,String token,long fire) {
        Intent i=new Intent(ctx,ScheduleReminder.class).setAction(ACTION);
        i.putExtra("token",token); i.putExtra("fire_at_ms",fire);
        return PendingIntent.getBroadcast(ctx,REQUEST_CODE,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private static JSONObject state(Context ctx) {
        return ScheduleReminderCore.fromJson(AppPrefs.get(ctx).getString(PREF,""));
    }
    private static void save(Context ctx,JSONObject state) {
        if(!AppPrefs.get(ctx).edit().putString(PREF,state.toString()).commit())
            throw new IllegalStateException("schedule_reminder_state_failed");
    }
    private static JSONObject currentReminder(Context ctx,JSONObject pending) {
        if(pending==null) return null;
        return ScheduleCore.reminder(ScheduleState.reminderById(ctx,pending.optString("id")));
    }
    private static void schedule(Context ctx,long at,String token,long originalFire) {
        AlarmManager am=(AlarmManager)ctx.getSystemService(Context.ALARM_SERVICE);
        if(am==null) return;
        PendingIntent pi=pending(ctx,token,originalFire);
        if(Build.VERSION.SDK_INT>=23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi);
        else am.set(AlarmManager.RTC_WAKEUP,at,pi);
    }
    /** All entry points use one monitor; ScheduleState never calls this while holding its own lock. */
    public static synchronized void reschedule(Context ctx) {
        try {
            long now=System.currentTimeMillis(); JSONObject d=state(ctx);
            JSONObject stored=ScheduleReminderCore.pending(d);
            JSONObject best=ScheduleReminderCore.selectNext(d,currentReminder(ctx,stored),
                    ScheduleState.reminderCandidates(ctx,now,now+HORIZON),now);
            save(ctx,d);
            // A daily local rescan discovers new recurrence dates and survives ordinary process death.
            long next=Math.min(now+RESCAN,best==null?Long.MAX_VALUE:Math.max(now+1000,best.optLong("fire_at_ms")));
            String token=best!=null&&best.optLong("fire_at_ms")<=now+RESCAN?best.optString("token"):"rescan";
            schedule(ctx,next,token,best==null?next:best.optLong("fire_at_ms"));
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
            long now=System.currentTimeMillis(); JSONObject d=state(ctx);
            JSONObject stored=ScheduleReminderCore.pending(d);
            JSONObject current=currentReminder(ctx,stored);
            boolean accepted=ScheduleReminderCore.acceptDelivery(d,token,current,now);
            save(ctx,d);
            if(accepted) {
                String title=current.optString("title","日程");
                String time=ScheduleCore.format(current.optLong("start_at_ms"),"HH:mm",java.util.TimeZone.getDefault());
                CompanionService.showReminderNotification(ctx,"日程 · "+title,"计划开始："+time);
            }
        } catch(Exception e) { DebugState.append(ctx,"日程提醒异常："+e.getMessage()); }
        finally { reschedule(ctx); }
    }
}
