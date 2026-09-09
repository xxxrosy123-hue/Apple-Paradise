package dev.linjian.peek;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.TimeZone;
import java.util.HashSet;
import java.util.Set;

/** Real production ScheduleCore, no Android or simulated lifecycle implementation. */
public final class ScheduleBehaviorTest {
    static final TimeZone TOKYO=TimeZone.getTimeZone("Asia/Tokyo");
    static final TimeZone NEW_YORK=TimeZone.getTimeZone("America/New_York");
    static long t(String d,String hm) { return ScheduleCore.localTime(d,hm,TOKYO); }
    static JSONObject o(Object... p) { return ScheduleCore.obj(p); }
    static void yes(boolean v,String message) { if(!v) throw new AssertionError(message); }
    static void eq(Object a,Object b,String message) { if(!a.equals(b)) throw new AssertionError(message+": "+a+" != "+b); }
    static void fails(Runnable r,String expected) { try { r.run(); throw new AssertionError("accepted:"+expected); } catch(IllegalArgumentException e) { yes(e.getMessage().contains(expected),e.getMessage()); } }
    static JSONObject block(String kind,String title,long start,long end) { return o("kind",kind,"title",title,"start_at_ms",start,"end_at_ms",end); }
    static JSONObject record(String id,long start,long end,String todo) { return o("session_id",id,"started_at_ms",start,"ended_at_ms",end,"duration_ms",end-start,"status","completed","todo_id",todo,"category","考研"); }
    static JSONObject history(JSONObject... s) { JSONArray a=new JSONArray(); for(JSONObject x:s)a.put(x); return o("schema_version",1,"sessions",a); }
    static JSONArray query(ScheduleCore c,long from,long to,JSONObject h) { return c.query(from,to,"all","",0,500,h).getJSONArray("blocks"); }
    static JSONObject find(JSONArray a,String id) { for(int i=0;i<a.length();i++){ JSONObject b=a.getJSONObject(i);if(id.equals(b.optString("id")))return b;}return null; }
    static int count(JSONArray a,String kind) { int n=0;for(int i=0;i<a.length();i++)if(kind.equals(a.getJSONObject(i).optString("kind")))n++;return n; }
    static void testCrudAndSeparation() {
        ScheduleCore c=ScheduleCore.empty(); long a=t("2026-09-07","09:00"),b=t("2026-09-07","10:00");
        JSONObject p=c.create(block("plan","学习",a,b),"schedule_p",1,"user");
        JSONObject actual=c.create(block("actual","学习",a+1200000,b+300000),"schedule_a",2,"user");
        eq(c.persisted().getJSONArray("blocks").length(),2,"separate records");
        c.update("schedule_p",o("title","数据结构"),3,false,null);
        eq(c.get("schedule_a",null).getString("title"),"学习","actual not overwritten");
        c.update("schedule_a",o("end_at_ms",b),4,true,null);
        yes(c.get("schedule_a",null).optBoolean("user_overridden"),"actual edit flag");
        c.delete("schedule_p",5,null); yes(c.get("schedule_p",null)==null,"plan deleted");
        fails(()->c.restore("schedule_p",6,null),"restore_not_supported");
        c.delete("schedule_a",6,null); yes(c.get("schedule_a",null)==null,"actual deleted");
        fails(()->c.create(block("actual","bad",b,a),"schedule_bad",7,"user"),"invalid_time_range");
        fails(()->c.create(block("plan","bad",a,a),"schedule_bad2",7,"user"),"invalid_time_range");
    }
    static void testTimeAndBinding() {
        ScheduleCore c=ScheduleCore.empty(); long a=t("2026-09-07","23:30"),b=t("2026-09-08","07:30");
        c.create(block("plan","睡觉",a,b),"schedule_sleep",1,"user");
        eq(query(c,t("2026-09-07","00:00"),t("2026-09-08","00:00"),null).length(),1,"first sleep day");
        eq(query(c,t("2026-09-08","00:00"),t("2026-09-09","00:00"),null).length(),1,"second sleep day");
        eq(c.persisted().getJSONArray("blocks").length(),1,"sleep is one fact");
        JSONObject p=block("plan","数据结构",t("2026-09-07","09:00"),t("2026-09-07","10:00"));ScheduleCore.put(p,"todo_id","todo_A");
        c.create(p,"schedule_1",2,"user");c.create(p,"schedule_2",3,"user");
        eq(c.query(t("2026-09-07","00:00"),t("2026-09-08","00:00"),"plan","todo_A",0,100,null).getInt("total_count"),2,"many plans per Todo");
        long[] w=ScheduleCore.weekRange("2026-09-09",TOKYO);eq(ScheduleCore.date(w[0],TOKYO),"2026-09-07","Monday");eq(ScheduleCore.date(w[1],TOKYO),"2026-09-14","exclusive Monday");
        eq(ScheduleCore.date(ScheduleCore.localMidnight("2026-09-07",TOKYO),TimeZone.getTimeZone("UTC")),"2026-09-06","absolute timezone");
        long[] dst=ScheduleCore.dayRange("2026-11-01",NEW_YORK);eq(dst[1]-dst[0],25L*3600000,"DST local day");
    }
    static void testFocusProjection() {
        ScheduleCore c=ScheduleCore.empty();long a=t("2026-09-07","09:00"),b=t("2026-09-07","10:00");
        JSONObject s=record("X",a,b,"todo_A"),h=history(s,s);String id=ScheduleCore.focusId("X");
        eq(query(c,a-1,b+1,h).length(),1,"source ID dedupe");
        c.update(id,o("start_at_ms",a+600000,"end_at_ms",b-300000),2,true,h);
        JSONObject after=find(query(c,a-1,b+1,h),id);
        eq(after.getLong("start_at_ms"),a+600000,"user start wins");
        eq(after.getLong("end_at_ms"),b-300000,"user end wins");
        eq(s.getLong("duration_ms"),3600000L,"Focus source untouched");
        eq(query(c,a-1,b+1,h).length(),1,"repeat sync does not duplicate");
        fails(()->c.update(id,o("title","automatic"),3,false,h),"requires_user_confirmation");
        c.delete(id,4,h);eq(query(c,a-1,b+1,h).length(),0,"deleted source does not resurrect");
        c.restore(id,5,h);eq(query(c,a-1,b+1,h).length(),1,"explicit reset projects original");
        eq(c.get(id,h).getLong("start_at_ms"),a,"original still authoritative");
        eq(ScheduleCore.focusId("X"),ScheduleCore.focusId("X"),"stable focus identity");
    }
    static void testRecurrence() {
        ScheduleCore c=ScheduleCore.empty();long start=t("2026-09-07","23:30"),end=t("2026-09-08","07:30");
        JSONObject p=block("plan","睡觉",start,end);
        ScheduleCore.put(p,"repeat",o("frequency","daily","timezone","Asia/Tokyo","until_date","2026-09-10"));
        c.create(p,"schedule_r",1,"user");
        long[] week=ScheduleCore.weekRange("2026-09-09",TOKYO);
        JSONArray occurrences=query(c,week[0],week[1],null);eq(occurrences.length(),4,"daily recurrence and cross midnight overlap");
        String id=ScheduleCore.occurrenceId("schedule_r","2026-09-08");
        JSONObject override=c.update(id,o("title","晚睡", "start_at_ms",t("2026-09-08","23:45")),2,false,null);
        eq(override.getString("series_id"),"schedule_r","single occurrence link");
        eq(override.getString("source_link"),"schedule_series:schedule_r:2026-09-08","occurrence provenance retained");
        ScheduleCore reloaded=ScheduleCore.fromJson(c.persisted().toString());
        eq(reloaded.get(id,null).getString("title"),"晚睡","edited occurrence survives persistence round-trip");
        eq(find(query(c,week[0],week[1],null),id).getString("title"),"晚睡","override visible");
        eq(query(c,week[0],week[1],null).length(),4,"no duplicate occurrence");
        c.delete(ScheduleCore.occurrenceId("schedule_r","2026-09-09"),3,null);
        eq(query(c,week[0],week[1],null).length(),3,"single exclusion");
        c.update("schedule_r",o("title","睡眠计划"),4,false,null);
        eq(find(query(c,week[0],week[1],null),id).getString("title"),"晚睡","all update preserves edited occurrence");
        c.delete("schedule_r",5,null);
        eq(query(c,week[0],week[1],null).length(),1,"series deletion preserves explicit occurrence edit");
        ScheduleCore other=ScheduleCore.empty();
        JSONObject weekly=block("plan","上课",t("2026-09-07","09:00"),t("2026-09-07","10:00"));
        ScheduleCore.put(weekly,"repeat",o("frequency","weekly","timezone","Asia/Tokyo","weekdays",new JSONArray("[1,3,5]")));
        other.create(weekly,"schedule_weekly",1,"user");
        eq(query(other,week[0],week[1],null).length(),3,"specified weekdays");
        long ny=ScheduleCore.localTime("2026-10-31","09:00",NEW_YORK);
        JSONObject daily=block("plan","09:00",ny,ny+3600000);
        ScheduleCore.put(daily,"repeat",o("frequency","daily","timezone","America/New_York","until_date","2026-11-02"));
        other.create(daily,"schedule_dst",2,"user");
        long[] range=ScheduleCore.dayRange("2026-11-01",NEW_YORK);
        JSONObject occurrence=find(query(other,range[0],range[1],null),ScheduleCore.occurrenceId("schedule_dst","2026-11-01"));
        eq(ScheduleCore.format(occurrence.getLong("start_at_ms"),"HH:mm",NEW_YORK),"09:00","DST wall clock");
        eq(occurrence.getLong("start_at_ms")-ny,25L*3600000,"not fixed 24h");
    }
    static void testDstResolutionAndReminders() {
        long gap=ScheduleCore.localTime("2026-03-08","02:30",NEW_YORK);
        eq(ScheduleCore.format(gap,"yyyy-MM-dd HH:mm",NEW_YORK),"2026-03-08 03:30","gap shifts by transition width");
        long fold=ScheduleCore.localTime("2026-11-01","01:30",NEW_YORK);
        eq(NEW_YORK.getOffset(fold),-4*3600000,"fold chooses earlier daylight offset");

        ScheduleCore c=ScheduleCore.empty();
        long start=ScheduleCore.localTime("2026-03-07","02:30",NEW_YORK);
        long end=ScheduleCore.localTime("2026-03-07","04:00",NEW_YORK);
        JSONObject gapSeries=block("plan","DST gap",start,end);
        ScheduleCore.put(gapSeries,"reminder_minutes_before",30);
        ScheduleCore.put(gapSeries,"repeat",o("frequency","daily","timezone","America/New_York","until_date","2026-03-09"));
        c.create(gapSeries,"schedule_gap",1,"user");
        long[] gapDay=ScheduleCore.dayRange("2026-03-08",NEW_YORK);
        JSONObject occurrence=find(query(c,gapDay[0],gapDay[1],null),ScheduleCore.occurrenceId("schedule_gap","2026-03-08"));
        yes(occurrence!=null,"gap occurrence does not break day query");
        eq(ScheduleCore.format(occurrence.getLong("start_at_ms"),"HH:mm",NEW_YORK),"03:30","gap occurrence shifted");
        eq(occurrence.getString("occurrence_date"),"2026-03-08","gap provenance date retained");
        eq(occurrence.getString("source_link"),"schedule_series:schedule_gap:2026-03-08","gap provenance link retained");
        eq(c.reminders(gapDay[0],gapDay[1]).length(),1,"gap reminder rebuild survives");

        long nightStart=ScheduleCore.localTime("2026-10-31","23:30",NEW_YORK);
        long nightEnd=ScheduleCore.localTime("2026-11-01","01:30",NEW_YORK);
        JSONObject night=block("plan","跨午夜",nightStart,nightEnd);
        ScheduleCore.put(night,"repeat",o("frequency","daily","timezone","America/New_York","until_date","2026-11-02"));
        c.create(night,"schedule_fold_night",2,"user");
        long[] foldDay=ScheduleCore.dayRange("2026-11-01",NEW_YORK);
        JSONObject folded=find(query(c,foldDay[0],foldDay[1],null),ScheduleCore.occurrenceId("schedule_fold_night","2026-10-31"));
        yes(folded!=null&&folded.getLong("end_at_ms")>folded.getLong("start_at_ms"),"fold cross-midnight remains valid");
    }
    static void testSerializationAndPagination() {
        ScheduleCore c=ScheduleCore.empty();long a=t("2026-09-07","09:00");Set<String> ids=new HashSet<>();
        for(int i=0;i<650;i++) { String id="schedule_"+i;ids.add(id);c.create(block("plan","B"+i,a+i*1000,a+i*1000+500),id,i+1,"user"); }
        eq(ids.size(),650,"unique IDs");
        String raw=c.persisted().toString();ScheduleCore restored=ScheduleCore.fromJson(raw);
        eq(restored.persisted().getJSONArray("blocks").length(),650,"formal history unbounded");
        JSONObject first=restored.query(a,a+3600000,"all","",0,100,null);
        eq(first.getInt("total_count"),650,"total count");eq(first.getJSONArray("blocks").length(),100,"bounded response");
        int count=0;for(int offset=0;offset<650;offset+=100) count+=restored.query(a,a+3600000,"all","",offset,100,null).getJSONArray("blocks").length();
        eq(count,650,"pagination reaches all");
        fails(()->restored.create(block("plan","duplicate",a,a+1000),"schedule_0",700,"user"),"id_exists");
    }
    static void testIdempotencyLedgerBounded() {
        ScheduleCore core=ScheduleCore.empty();long start=t("2026-09-07","09:00");
        for(int i=0;i<ScheduleCore.MAX_IDEMPOTENCY_RECORDS+20;i++)
            core.createIdempotent(block("plan","Intent "+i,start+i*1000,start+i*1000+500),"",i+1,"ai","create-key-"+i);
        eq(core.persisted().getJSONArray("idempotency").length(),ScheduleCore.MAX_IDEMPOTENCY_RECORDS,"idempotency ledger bounded");
        ScheduleCore restored=ScheduleCore.fromJson(core.persisted().toString());
        eq(restored.persisted().getJSONArray("idempotency").length(),ScheduleCore.MAX_IDEMPOTENCY_RECORDS,"bounded ledger persists");
    }
    static void testContext() {
        ScheduleCore c=ScheduleCore.empty();long now=t("2026-09-07","09:30");
        c.create(block("plan","当前",now-1800000,now+1800000),"schedule_now",1,"user");
        c.create(block("plan","下一项",now+3600000,now+7200000),"schedule_next",2,"user");
        for(int i=0;i<20;i++)c.create(block("plan","以后"+i,now+10800000+i*1000,now+10800000+i*1000+500),"schedule_future"+i,i+3,"user");
        c.create(block("actual","记录",now-300000,now+300000),"schedule_actual",50,"user");
        JSONObject summary=c.summary(now,TOKYO,null);
        eq(summary.getJSONObject("current_plan").getString("id"),"schedule_now","current plan");
        eq(summary.getJSONObject("next_plan").getString("id"),"schedule_next","next plan");
        eq(summary.getJSONObject("current_actual").getString("id"),"schedule_actual","actual present");
        yes(summary.getJSONArray("today_remaining_plans").length()<=6,"today summary bounded");
        eq(summary.getString("remaining_scope"),"today","legacy remaining scope explicit");
        eq(summary.getJSONObject("next_plan_search").getString("status"),"found","next search found");
        yes(!summary.has("blocks")&&!summary.has("sessions"),"no history dump");

        ScheduleCore tomorrow=ScheduleCore.empty();long late=t("2026-09-07","23:00");
        tomorrow.create(block("plan","已经结束",late-7200000,late-3600000),"schedule_ended",1,"user");
        tomorrow.create(block("plan","明天",t("2026-09-08","09:00"),t("2026-09-08","10:00")),"schedule_tomorrow",2,"user");
        JSONObject nextDay=tomorrow.summary(late,TOKYO,null);
        yes(nextDay.isNull("current_plan"),"no current plan after today's last item");
        eq(nextDay.getJSONArray("today_remaining_plans").length(),0,"today remaining stays today");
        eq(nextDay.getJSONObject("next_plan").getString("id"),"schedule_tomorrow","next plan crosses local day");
        JSONObject empty=ScheduleCore.empty().summary(late,TOKYO,null);
        yes(empty.isNull("next_plan"),"no plan in bounded window");
        eq(empty.getJSONObject("next_plan_search").getString("status"),"none_within_window","absence remains bounded");
    }
    public static void main(String[] args) {
        testCrudAndSeparation();testTimeAndBinding();testFocusProjection();testRecurrence();testDstResolutionAndReminders();testSerializationAndPagination();testIdempotencyLedgerBounded();testContext();
        System.out.println("ScheduleBehaviorTest: PASS (production core, CRUD, timezones, recurrence, source override, pagination, context)");
    }
}
