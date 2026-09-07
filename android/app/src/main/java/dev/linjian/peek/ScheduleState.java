package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.TimeZone;

/** Android-owned Schedule persistence. All whole-object mutations use this class monitor. */
public final class ScheduleState {
    public static final String KEY_STATE = "schedule_state_v1";
    private ScheduleState() { }
    private static ScheduleCore load(Context ctx) {
        requireLock();
        return ScheduleCore.fromJson(AppPrefs.get(ctx).getString(KEY_STATE,""));
    }
    private static void requireLock() {
        if(!Thread.holdsLock(ScheduleState.class)) throw new IllegalStateException("schedule_state_lock_required");
    }
    private static void save(Context ctx,ScheduleCore core) {
        requireLock();
        if(!AppPrefs.get(ctx).edit().putString(KEY_STATE,core.persisted().toString()).commit())
            throw new IllegalStateException("schedule_persist_failed");
    }
    private static JSONObject history(Context ctx) { return FocusMode.completedSessionsSnapshot(ctx); }
    /** Explicit first-use initialization, never performed by an unavailable-state read. */
    public static synchronized void initialize(Context ctx) {
        if(AppPrefs.get(ctx).contains(KEY_STATE)) { load(ctx); return; }
        ScheduleCore core=ScheduleCore.empty();
        core.initialize(System.currentTimeMillis());
        // Persist a valid empty state; a missing key must remain distinguishable from empty.
        save(ctx,core);
    }
    private static String text(JSONObject o,String key) { return ScheduleCore.clean(o.optString(key,"")); }
    private static JSONObject result(boolean ok,String message) { return ScheduleCore.obj("ok",ok,"result",message); }
    public static boolean isScheduleAction(String action) { return "schedule_action".equals(action)||"get_schedule".equals(action); }
    private static void validateTodo(Context ctx,JSONObject input) {
        if(!input.has("todo_id")) return;
        String id=text(input,"todo_id"); if(id.isEmpty()) return;
        if(TodoState.findByIdForFocus(ctx,id)==null) throw ScheduleCore.bad("todo_not_found:"+id);
    }
    private static void validateActualSource(JSONObject input,String operation) {
        if(!"actual".equals(text(input,"kind"))) return;
        String source=text(input,"source");
        if(source.equals("focus_session")) throw ScheduleCore.bad("schedule_focus_source_is_read_only");
        if(!input.optBoolean("user_confirmed",false)) throw ScheduleCore.bad("schedule_actual_requires_explicit_confirmation");
        if(source.equals("user") || source.isEmpty()) {
            if(!"user".equals(text(input,"actor"))) throw ScheduleCore.bad("schedule_actual_user_source_requires_user_actor");
            return;
        }
        if(!source.equals("ai_confirmed") || !input.optBoolean("confirmed_by_user",false))
            throw ScheduleCore.bad("schedule_actual_requires_explicit_confirmation");
    }
    private static JSONObject safePatch(JSONObject cmd) {
        JSONObject patch=new JSONObject();
        for(String key:new String[]{"title","note","category","color","start_at_ms","end_at_ms","todo_id","reminder_minutes_before","repeat"})
            if(cmd.has(key)) ScheduleCore.put(patch,key,cmd.opt(key));
        return patch;
    }
    private static JSONObject input(JSONObject cmd) {
        JSONObject b=safePatch(cmd);
        if(cmd.has("kind")) ScheduleCore.put(b,"kind",cmd.optString("kind"));
        return b;
    }
    private static long[] range(JSONObject cmd) {
        TimeZone zone=ScheduleCore.zone(text(cmd,"timezone"));
        if(cmd.has("from_ms")||cmd.has("to_ms")) {
            long from=cmd.optLong("from_ms",0),to=cmd.optLong("to_ms",0);
            if(from<=0||to<=from) throw ScheduleCore.bad("schedule_query_range_invalid");
            return new long[]{from,to};
        }
        String date=text(cmd,"date"); if(date.isEmpty()) date=ScheduleCore.date(System.currentTimeMillis(),zone);
        String view=text(cmd,"view");
        if(!view.isEmpty()&&!view.equals("day")&&!view.equals("week")) throw ScheduleCore.bad("schedule_view_invalid");
        return "week".equals(view)?ScheduleCore.weekRange(date,zone):ScheduleCore.dayRange(date,zone);
    }
    public static JSONObject handleCommand(Context ctx,JSONObject cmd) {
        if(cmd==null) return result(false,"schedule_command_required");
        String action=text(cmd,"action"),op=text(cmd,"operation").toLowerCase(Locale.US);
        try {
            if("get_schedule".equals(action)) {
                JSONObject facts=null; String projectionError="";
                try { facts=history(ctx); } catch(Exception e) { projectionError="focus_history_unavailable"; }
                synchronized(ScheduleState.class) {
                    if(!AppPrefs.get(ctx).contains(KEY_STATE)) return ScheduleCore.obj("ok",false,"available",false,"source","android_local","result","schedule_not_initialized");
                    ScheduleCore core=load(ctx);
                    JSONObject out;
                    String wanted=text(cmd,"block_id"); if(wanted.isEmpty()) wanted=text(cmd,"id");
                    if(!wanted.isEmpty()) {
                        JSONObject b=core.get(wanted,facts);
                        out=ScheduleCore.obj("ok",true,"available",true,"source","android_local",
                                "result","schedule_queried","schema_version",ScheduleCore.VERSION,
                                "updated_at_ms",core.updatedAt(),"queried_at_ms",System.currentTimeMillis(),
                                "block",b==null?JSONObject.NULL:b);
                    } else out=query(core,cmd,facts);
                    if(!projectionError.isEmpty()) { ScheduleCore.put(out,"degraded",true);ScheduleCore.put(out,"focus_projection_available",false);ScheduleCore.put(out,"focus_projection_error",projectionError); }
                    return out;
                }
            }
            if(!"schedule_action".equals(action)) return result(false,"unknown_schedule_action:"+action);
            if(op.equals("create") || op.equals("update") || op.equals("delete") || op.equals("restore")) {
                if(op.equals("create")) validateActualSource(cmd,op);
                if(op.equals("create") || op.equals("update")) validateTodo(ctx,cmd);
                JSONObject facts=history(ctx);
                JSONObject out;
                synchronized(ScheduleState.class) {
                    ScheduleCore core=load(ctx); long now=System.currentTimeMillis();
                    String id=text(cmd,"block_id"); if(id.isEmpty()) id=text(cmd,"id");
                    if(op.equals("create")) {
                        if(!id.isEmpty() && !id.startsWith("schedule_")) throw ScheduleCore.bad("schedule_id_invalid");
                        if(id.isEmpty()) id=ScheduleCore.newId();
                        String source=text(cmd,"source");
                        if(source.isEmpty()) source="ai".equals(text(cmd,"actor"))?"ai":"user";
                        if(!source.equals("user")&&!source.equals("ai")&&!source.equals("ai_confirmed")) throw ScheduleCore.bad("schedule_source_invalid");
                        if("actual".equals(text(cmd,"kind")) && cmd.optLong("end_at_ms",0)>now+5000L) throw ScheduleCore.bad("schedule_actual_cannot_end_in_future");
                        JSONObject b=core.create(input(cmd),id,now,source);
                        out=ScheduleCore.obj("ok",true,"result","schedule_created","block",b);
                    } else {
                        if(id.isEmpty()) throw ScheduleCore.bad("schedule_id_required");
                        if(op.equals("restore")) out=core.restore(id,now,facts);
                        else if(op.equals("delete")) {
                            if(!"this".equals(text(cmd,"scope")) && !"all".equals(text(cmd,"scope"))) throw ScheduleCore.bad("schedule_delete_scope_required");
                            JSONObject prior=core.get(id,facts);
                            if(prior!=null && prior.has("repeat") && !"all".equals(text(cmd,"scope"))) throw ScheduleCore.bad("schedule_series_requires_all_scope");
                            if(prior!=null && prior.has("series_id") && "all".equals(text(cmd,"scope"))) throw ScheduleCore.bad("schedule_occurrence_requires_this_scope");
                            out=core.delete(id,now,facts);
                        } else {
                            JSONObject prior=core.get(id,facts);
                            if(prior==null) throw ScheduleCore.bad("schedule_not_found:"+id);
                            String scope=text(cmd,"scope");
                            if(prior.has("repeat") && !"all".equals(scope)) throw ScheduleCore.bad("schedule_series_requires_all_scope");
                            if(prior.has("series_id") && !"this".equals(scope)) throw ScheduleCore.bad("schedule_occurrence_requires_this_scope");
                            if(prior.optString("kind").equals("actual") && !cmd.optBoolean("user_confirmed",false)) throw ScheduleCore.bad("schedule_actual_edit_requires_user_confirmation");
                            if("actual".equals(prior.optString("kind")) && cmd.optLong("end_at_ms",prior.optLong("end_at_ms"))>now+5000L) throw ScheduleCore.bad("schedule_actual_cannot_end_in_future");
                            JSONObject b=core.update(id,safePatch(cmd),now,cmd.optBoolean("user_confirmed",false),facts,"ai".equals(text(cmd,"actor"))?"ai_confirmed":"user");
                            out=ScheduleCore.obj("ok",true,"result","schedule_updated","block",b);
                        }
                        ScheduleCore.put(out,"ok",true);
                        if(!out.has("result")) ScheduleCore.put(out,"result","schedule_"+op);
                    }
                    save(ctx,core);
                    ScheduleCore.put(out,"schema_version",ScheduleCore.VERSION);
                    ScheduleCore.put(out,"source","android_local");
                    ScheduleCore.put(out,"updated_at_ms",core.updatedAt());
                }
                ScheduleReminder.reschedule(ctx);
                return out;
            }
            return result(false,"unknown_schedule_operation:"+op);
        } catch(Exception e) { return result(false,e.getMessage()==null?"schedule_operation_failed":e.getMessage()); }
    }
    private static JSONObject query(ScheduleCore core,JSONObject cmd,JSONObject history) {
        long[] r=range(cmd);
        JSONObject out=core.query(r[0],r[1],text(cmd,"kind"),text(cmd,"todo_id"),cmd.optInt("offset",0),cmd.optInt("limit",0),history);
        ScheduleCore.put(out,"ok",true); ScheduleCore.put(out,"available",true); ScheduleCore.put(out,"result","schedule_queried");
        ScheduleCore.put(out,"source","android_local"); ScheduleCore.put(out,"updated_at_ms",core.updatedAt());
        ScheduleCore.put(out,"queried_at_ms",System.currentTimeMillis());
        ScheduleCore.put(out,"timezone",ScheduleCore.zone(text(cmd,"timezone")).getID());
        ScheduleCore.put(out,"storage","SharedPreferences(linjian_peek)/"+KEY_STATE);
        return out;
    }
    /** Missing or corrupted state is unavailable, never a fabricated empty day. */
    public static JSONObject collect(Context ctx) {
        try {
            JSONObject facts=null; String projectionError="";
            try { facts=history(ctx); } catch(Exception e) { projectionError="focus_history_unavailable"; }
            synchronized(ScheduleState.class) {
                SharedPreferences prefs=AppPrefs.get(ctx);
                if(!prefs.contains(KEY_STATE)) return ScheduleCore.obj("available",false,"source","android_local","reason","schedule_not_initialized");
                ScheduleCore core=load(ctx);
                JSONObject out=core.summary(System.currentTimeMillis(),TimeZone.getDefault(),facts);
                if(!projectionError.isEmpty()) { ScheduleCore.put(out,"degraded",true);ScheduleCore.put(out,"focus_projection_available",false);ScheduleCore.put(out,"focus_projection_error",projectionError); }
                else ScheduleCore.put(out,"focus_projection_available",true);
                return out;
            }
        } catch(Exception e) { return ScheduleCore.obj("available",false,"degraded",true,"source","android_local","error",e.getMessage()==null?"schedule_unavailable":e.getMessage()); }
    }
    public static JSONObject queryLocal(Context ctx,String date,boolean week,String kind,int offset,int limit) {
        JSONObject cmd=ScheduleCore.obj("action","get_schedule","date",date,"view",week?"week":"day","kind",kind,"offset",offset,"limit",limit);
        return handleCommand(ctx,cmd);
    }
    public static JSONObject nextReminder(Context ctx,long now,long horizon) {
        try {
            synchronized(ScheduleState.class) {
                if(!AppPrefs.get(ctx).contains(KEY_STATE)) return null;
                return load(ctx).nextReminder(now,horizon,null);
            }
        } catch(Exception e) { return null; }
    }
    public static JSONArray reminderCandidates(Context ctx,long from,long to) {
        synchronized(ScheduleState.class) {
            if(!AppPrefs.get(ctx).contains(KEY_STATE)) return new JSONArray();
            return load(ctx).reminders(from,to);
        }
    }
    public static JSONObject reminderById(Context ctx,String id) {
        try {
            synchronized(ScheduleState.class) {
                ScheduleCore core=load(ctx);
                JSONObject b=core.get(id,null);
                return b==null?null:ScheduleCore.copy(b);
            }
        } catch(Exception e) { return null; }
    }
    public static String pretty(Context ctx) {
        JSONObject s=collect(ctx); if(!s.optBoolean("available",false)) return "时间轴 · 尚未建立或暂不可用";
        JSONObject current=s.optJSONObject("current_plan"),next=s.optJSONObject("next_plan");
        return "时间轴 · 当前："+(current==null?"无":current.optString("title"))+"；下一项："+(next==null?"无":next.optString("title"));
    }
}
