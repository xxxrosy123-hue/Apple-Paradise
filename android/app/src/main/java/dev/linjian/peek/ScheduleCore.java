package dev.linjian.peek;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

/** Phase 5 facts. No Android dependencies, lifecycle inference or Todo mutations. */
final class ScheduleCore {
    static final int VERSION = 1;
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;
    static final long DAY_MS = 86400000L;
    private JSONObject document;

    private ScheduleCore(JSONObject document) { this.document = document; }
    static ScheduleCore empty() {
        return new ScheduleCore(obj("schema_version", VERSION, "updated_at_ms", 0L,
                "blocks", new JSONArray(), "overrides", new JSONArray(), "tombstones", new JSONArray()));
    }
    static ScheduleCore fromJson(String raw) {
        if (clean(raw).isEmpty()) return empty();
        try {
            JSONObject d = new JSONObject(raw);
            if (d.optInt("schema_version", 0) != VERSION) throw bad("schedule_schema_unsupported");
            for (String key : new String[]{"blocks", "overrides", "tombstones"})
                if (!(d.opt(key) instanceof JSONArray)) throw bad("schedule_invalid_" + key);
            ScheduleCore core = new ScheduleCore(d);
            core.checkIntegrity();
            return core;
        } catch (org.json.JSONException e) { throw bad("schedule_json_invalid"); }
    }
    JSONObject persisted() { return copy(document); }
    long updatedAt() { return document.optLong("updated_at_ms", 0); }
    void initialize(long now) { if(updatedAt()==0) touch(now); }
    private void touch(long now) { put(document, "updated_at_ms", now); }
    static JSONObject obj(Object... pairs) {
        JSONObject o = new JSONObject();
        for (int i=0;i<pairs.length;i+=2) put(o, (String)pairs[i], pairs[i+1]);
        return o;
    }
    static void put(JSONObject o, String key, Object value) {
        try { o.put(key,value); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    static JSONObject copy(JSONObject o) {
        if (o == null) return null;
        try { return new JSONObject(o.toString()); }
        catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }
    static String clean(String s) { return s == null ? "" : s.trim(); }
    static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
    private static JSONArray arr(JSONObject o, String key) {
        JSONArray value=o.optJSONArray(key);
        if(value==null) throw bad("schedule_invalid_"+key);
        return value;
    }
    private static JSONObject find(JSONArray a, String id) {
        for(int i=0;i<a.length();i++) { JSONObject o=a.optJSONObject(i); if(o!=null && id.equals(o.optString("id"))) return o; }
        return null;
    }
    private static void replace(JSONArray a, JSONObject value) {
        String id=value.optString("id");
        for(int i=0;i<a.length();i++) {
            JSONObject o=a.optJSONObject(i);
            if(o!=null && id.equals(o.optString("id"))) {
                try { a.put(i,value); }
                catch (org.json.JSONException e) { throw new IllegalStateException(e); }
                return;
            }
        }
        a.put(value);
    }
    private static boolean remove(JSONArray a, String id) {
        for(int i=0;i<a.length();i++) { JSONObject o=a.optJSONObject(i); if(o!=null && id.equals(o.optString("id"))) { a.remove(i); return true; } }
        return false;
    }
    private void checkIntegrity() {
        Set<String> ids=new HashSet<>();
        for(String key:new String[]{"blocks","overrides"}) {
            JSONArray a=arr(document,key);
            for(int i=0;i<a.length();i++) {
                JSONObject b=a.optJSONObject(i);
                if(b==null || clean(b.optString("id")).isEmpty() || !ids.add(b.optString("id"))) throw bad("schedule_duplicate_or_invalid_id");
                validateBlock(b);
                if("overrides".equals(key) && clean(b.optString("source_link")).isEmpty()) throw bad("schedule_override_invalid");
            }
        }
        for(int i=0;i<arr(document,"tombstones").length();i++) {
            JSONObject t=arr(document,"tombstones").optJSONObject(i);
            if(t==null || clean(t.optString("id")).isEmpty()) throw bad("schedule_tombstone_invalid");
        }
    }
    static String newId() { return "schedule_"+UUID.randomUUID(); }
    static String focusId(String sessionId) {
        return "actual_"+UUID.nameUUIDFromBytes(("focus_session:"+sessionId).getBytes(StandardCharsets.UTF_8));
    }
    static String occurrenceId(String seriesId,String localDate) { return "occ_"+seriesId+"_"+localDate.replace("-", ""); }
    static TimeZone zone(String id) {
        String wanted=clean(id);
        if(wanted.isEmpty()) return TimeZone.getDefault();
        TimeZone z=TimeZone.getTimeZone(wanted);
        if(!z.getID().equals(wanted) && !"GMT".equals(wanted) && !wanted.matches("GMT[+-]\\d{1,2}:\\d{2}")) throw bad("schedule_timezone_invalid");
        return z;
    }
    static String date(long ms,TimeZone zone) { return format(ms,"yyyy-MM-dd",zone); }
    static String format(long ms,String pattern,TimeZone zone) {
        SimpleDateFormat f=new SimpleDateFormat(pattern,Locale.US); f.setTimeZone(zone); return f.format(new Date(ms));
    }
    static long localMidnight(String date,TimeZone zone) { return localTime(date,"00:00",zone); }
    static long localTime(String date,String time,TimeZone zone) {
        SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US); f.setLenient(false); f.setTimeZone(zone);
        try { Date d=f.parse(date+" "+time); if(d==null || !f.format(d).equals(date+" "+time)) throw bad("schedule_local_time_invalid"); return d.getTime(); }
        catch(ParseException e) { throw bad("schedule_local_time_invalid"); }
    }
    static String addDays(String date,int days) {
        Calendar c=Calendar.getInstance(TimeZone.getTimeZone("UTC"),Locale.US); c.setTimeInMillis(localMidnight(date,TimeZone.getTimeZone("UTC"))); c.add(Calendar.DATE,days); return format(c.getTimeInMillis(),"yyyy-MM-dd",c.getTimeZone());
    }
    static int weekday(String date) {
        Calendar c=Calendar.getInstance(TimeZone.getTimeZone("UTC"),Locale.US); c.setTimeInMillis(localMidnight(date,c.getTimeZone())); return (c.get(Calendar.DAY_OF_WEEK)+5)%7+1;
    }
    static long[] dayRange(String date,TimeZone zone) { return new long[]{localMidnight(date,zone),localMidnight(addDays(date,1),zone)}; }
    static long[] weekRange(String date,TimeZone zone) {
        String monday=addDays(date,1-weekday(date)); return new long[]{localMidnight(monday,zone),localMidnight(addDays(monday,7),zone)};
    }
    static boolean overlap(long start,long end,long from,long to) { return start<to && end>from; }
    private static void validateRange(JSONObject b) {
        long start=b.optLong("start_at_ms",0),end=b.optLong("end_at_ms",0);
        if(start<=0 || end<=start) throw bad("schedule_invalid_time_range");
        if(end-start>366L*DAY_MS) throw bad("schedule_block_too_long");
    }
    private static JSONObject normalizedRepeat(JSONObject input,long start,long end) {
        JSONObject r=copy(input);
        String frequency=clean(r.optString("frequency","none")).toLowerCase(Locale.US);
        if(!frequency.equals("daily") && !frequency.equals("weekly")) throw bad("schedule_repeat_invalid");
        TimeZone z=zone(r.optString("timezone",TimeZone.getDefault().getID()));
        String anchor=clean(r.optString("anchor_date",date(start,z)));
        localMidnight(anchor,z);
        String startTime=clean(r.optString("start_time",format(start,"HH:mm",z)));
        String endTime=clean(r.optString("end_time",format(end,"HH:mm",z)));
        int endOffset=r.optInt("end_day_offset",date(end,z).equals(date(start,z))?0:daysBetween(date(start,z),date(end,z)));
        if(endOffset<0 || endOffset>366) throw bad("schedule_repeat_end_offset_invalid");
        long anchorStart=localTime(anchor,startTime,z);
        long anchorEnd=localTime(addDays(anchor,endOffset),endTime,z);
        if(anchorEnd<=anchorStart || anchorEnd-anchorStart>366L*DAY_MS) throw bad("schedule_invalid_time_range");
        JSONArray weekdays=new JSONArray(); Set<Integer> seen=new HashSet<>();
        JSONArray requested=r.optJSONArray("weekdays");
        if(requested!=null) for(int i=0;i<requested.length();i++) { int w=requested.optInt(i,0); if(w<1||w>7) throw bad("schedule_weekday_invalid"); if(seen.add(w)) weekdays.put(w); }
        if(frequency.equals("weekly") && weekdays.length()==0) weekdays.put(weekday(anchor));
        String until=clean(r.optString("until_date",""));
        if(!until.isEmpty()) { localMidnight(until,z); if(until.compareTo(anchor)<0) throw bad("schedule_repeat_until_invalid"); }
        return obj("frequency",frequency,"timezone",z.getID(),"anchor_date",anchor,"start_time",startTime,"end_time",endTime,"end_day_offset",endOffset,"weekdays",weekdays,"until_date",until);
    }
    private static int daysBetween(String a,String b) {
        return (int)((localMidnight(b,TimeZone.getTimeZone("UTC"))-localMidnight(a,TimeZone.getTimeZone("UTC")))/DAY_MS);
    }
    static void validateBlock(JSONObject b) {
        String kind=clean(b.optString("kind"));
        if(!kind.equals("plan")&&!kind.equals("actual")) throw bad("schedule_kind_invalid");
        if(clean(b.optString("id")).isEmpty()) throw bad("schedule_id_required");
        if(clean(b.optString("title")).isEmpty()) throw bad("schedule_title_required");
        validateRange(b);
        if(clean(b.optString("title")).length()>240 || b.optString("note","").length()>4000 || clean(b.optString("category")).length()>80) throw bad("schedule_text_too_long");
        String color=clean(b.optString("color",""));
        if(!color.isEmpty()&&!color.matches("#[0-9a-fA-F]{6}")) throw bad("schedule_color_invalid");
        JSONObject repeat=b.optJSONObject("repeat");
        if(repeat!=null && !"none".equals(repeat.optString("frequency","none"))) {
            if(!kind.equals("plan") || !clean(b.optString("series_id")).isEmpty()) throw bad("schedule_repeat_plan_only");
            normalizedRepeat(repeat,b.optLong("start_at_ms"),b.optLong("end_at_ms"));
        }
        if(kind.equals("actual") && hasRepeat(b)) throw bad("schedule_repeat_plan_only");
        if(kind.equals("actual")) {
            String source=clean(b.optString("source")),sid=clean(b.optString("focus_session_id"));
            if(!source.equals("user")&&!source.equals("ai_confirmed")&&!source.equals("focus_session")) throw bad("schedule_actual_source_invalid");
            if(source.equals("focus_session") && (sid.isEmpty()||!b.optString("source_link","").equals("focus_session:"+sid))) throw bad("schedule_focus_link_invalid");
            if(!source.equals("focus_session")&&!sid.isEmpty()) throw bad("schedule_focus_link_invalid");
        }
        if(kind.equals("plan") && !clean(b.optString("focus_session_id")).isEmpty()) throw bad("schedule_plan_has_focus_source");
        if(!clean(b.optString("focus_session_id")).isEmpty() && !kind.equals("actual")) throw bad("schedule_focus_actual_only");
    }
    private static JSONObject normalizeBlock(JSONObject input,String id,long now,String kind,String source,JSONObject previous) {
        JSONObject b=previous==null?new JSONObject():copy(previous);
        for(String key:new String[]{"title","note","category","color","start_at_ms","end_at_ms","todo_id","reminder_minutes_before"})
            if(input.has(key)) put(b,key,input.opt(key));
        put(b,"id",id); put(b,"kind",kind);
        if(previous==null) { put(b,"created_at_ms",now); put(b,"source",source); }
        put(b,"updated_at_ms",now);
        if(!b.has("note")) put(b,"note","");
        if(!b.has("category")) put(b,"category","");
        if(!b.has("color")) put(b,"color","");
        if(!b.has("todo_id")) put(b,"todo_id","");
        if(b.has("reminder_minutes_before")) { int reminder=b.optInt("reminder_minutes_before",-2); if(reminder < -1 || reminder > 43200) throw bad("schedule_reminder_invalid"); }
        if(kind.equals("plan")) {
            b.remove("focus_session_id");
            // A generated occurrence retains its immutable series/date provenance.
            // Removing source_link here made a valid single-occurrence edit unreadable after persistence.
            if(clean(b.optString("series_id")).isEmpty()) b.remove("source_link");
            b.remove("user_overridden");
            if(input.has("repeat")) {
                JSONObject r=input.optJSONObject("repeat");
                if(r==null || "none".equals(r.optString("frequency","none"))) b.remove("repeat");
                else put(b,"repeat",normalizedRepeat(r,b.optLong("start_at_ms"),b.optLong("end_at_ms")));
            }
        } else { b.remove("repeat"); b.remove("reminder_minutes_before"); }
        validateBlock(b);
        return b;
    }
    private static JSONObject merge(JSONObject previous,JSONObject patch) {
        JSONObject out=copy(previous);
        for(java.util.Iterator<String> it=patch.keys();it.hasNext();) { String k=it.next(); put(out,k,patch.opt(k)); }
        return out;
    }
    private static void protectFields(JSONObject input) {
        for(String key:new String[]{"id","source","source_link","focus_session_id","series_id","occurrence_date","created_at_ms","updated_at_ms","user_overridden","override_source","override_at_ms"})
            if(input.has(key)) throw bad("schedule_immutable_field:"+key);
    }
    private void ensureUnique(String id) {
        if(find(arr(document,"blocks"),id)!=null || find(arr(document,"overrides"),id)!=null || tombstoned(id)) throw bad("schedule_id_exists");
    }
    private boolean tombstoned(String id) { return find(arr(document,"tombstones"),id)!=null; }
    private void tombstone(String id,String source,long now) {
        replace(arr(document,"tombstones"),obj("id",id,"source",source,"deleted_at_ms",now,"user_overridden",true));
    }
    JSONObject get(String id,JSONObject focusHistory) {
        String wanted=clean(id); if(wanted.isEmpty()) return null;
        JSONObject b=find(arr(document,"blocks"),wanted); if(b!=null) return copy(b);
        if(tombstoned(wanted)) return null;
        b=find(arr(document,"overrides"),wanted); if(b!=null) return copy(b);
        if(wanted.startsWith("occ_")) {
            for(JSONObject series:allBlocks()) {
                JSONObject r=series.optJSONObject("repeat"); if(r==null) continue;
                String prefix="occ_"+series.optString("id")+"_";
                if(wanted.startsWith(prefix)) {
                    String suffix=wanted.substring(prefix.length());
                    if(!suffix.matches("\\d{8}")) return null;
                    String date=suffix.substring(0,4)+"-"+suffix.substring(4,6)+"-"+suffix.substring(6,8);
                    return occurrence(series,date);
                }
            }
        }
        JSONArray sessions=focusHistory==null?new JSONArray():focusHistory.optJSONArray("sessions");
        if(sessions!=null) for(int i=0;i<sessions.length();i++) {
            JSONObject s=sessions.optJSONObject(i); if(s!=null && wanted.equals(focusId(s.optString("session_id")))) return focusProjection(s);
        }
        return null;
    }
    private List<JSONObject> allBlocks() {
        List<JSONObject> list=new ArrayList<>(); JSONArray a=arr(document,"blocks");
        for(int i=0;i<a.length();i++) if(a.optJSONObject(i)!=null) list.add(a.optJSONObject(i));
        return list;
    }
    JSONObject create(JSONObject input,String id,long now,String source) {
        protectFields(input); ensureUnique(id);
        String kind=clean(input.optString("kind","plan"));
        if(!kind.equals("plan")&&!kind.equals("actual")) throw bad("schedule_kind_invalid");
        if(kind.equals("actual") && !source.equals("user")&&!source.equals("ai_confirmed")) throw bad("schedule_actual_source_invalid");
        JSONObject b=normalizeBlock(input,id,now,kind,source,null);
        arr(document,"blocks").put(b); touch(now); return copy(b);
    }
    JSONObject update(String id,JSONObject patch,long now,boolean userEdit,JSONObject focusHistory) {
        return update(id,patch,now,userEdit,focusHistory,"user");
    }
    JSONObject update(String id,JSONObject patch,long now,boolean userEdit,JSONObject focusHistory,String editorSource) {
        protectFields(patch);
        if(patch.has("kind")) throw bad("schedule_immutable_field:kind");
        JSONObject prior=get(id,focusHistory);
        if(prior==null) throw bad("schedule_not_found:"+id);
        JSONObject template=find(arr(document,"blocks"),id);
        if(prior.has("repeat") && (patch.has("start_at_ms") || patch.has("end_at_ms")) && !patch.has("repeat")) throw bad("schedule_repeat_update_requires_rule");
        if(prior.optString("kind").equals("actual") && !userEdit) throw bad("schedule_actual_edit_requires_user_confirmation");
        JSONObject b=normalizeBlock(patch,id,now,prior.optString("kind"),prior.optString("source"),prior);
        if(prior.optString("kind").equals("actual")) {
            put(b,"user_overridden",true);put(b,"override_source",editorSource);put(b,"override_at_ms",now);
        } else if(prior.has("series_id")) {
            put(b,"user_overridden",userEdit);put(b,"override_source",editorSource);put(b,"override_at_ms",now);
        }
        if(template!=null) replace(arr(document,"blocks"),b);
        else replace(arr(document,"overrides"),b);
        touch(now); return copy(b);
    }
    JSONObject delete(String id,long now,JSONObject focusHistory) {
        JSONObject prior=get(id,focusHistory);
        if(prior==null) throw bad("schedule_not_found:"+id);
        remove(arr(document,"blocks"),id); remove(arr(document,"overrides"),id);
        // Keep a durable exclusion even for source-linked or generated occurrence records.
        tombstone(id,prior.optString("source","user"),now); touch(now);
        return obj("deleted_id",id,"deleted",true);
    }
    JSONObject restore(String id,long now,JSONObject history) {
        boolean removed=remove(arr(document,"tombstones"),id);
        removed=remove(arr(document,"overrides"),id)||removed;
        if(!removed) throw bad("schedule_override_not_found:"+id);
        if(get(id,history)==null) throw bad("schedule_restore_source_not_found:"+id);
        touch(now); return obj("restored_id",id);
    }
    static JSONObject focusProjection(JSONObject session) {
        if(session==null || !"completed".equals(session.optString("status"))) return null;
        String sid=clean(session.optString("session_id"));
        long start=session.optLong("started_at_ms",0),end=session.optLong("ended_at_ms",0);
        if(sid.isEmpty()||start<=0||end<=start) return null;
        return obj("id",focusId(sid),"kind","actual","title","专注", "note","",
                "category",session.optString("category",""),"color","", "start_at_ms",start,"end_at_ms",end,
                "todo_id",session.optString("todo_id",""),"focus_session_id",sid,"source","focus_session",
                "source_link","focus_session:"+sid,"created_at_ms",end,"updated_at_ms",end,"user_overridden",false);
    }
    /** Completed Focus history is read-only; user corrections and deletes win on projection. */
    private List<JSONObject> projected(JSONObject history) {
        List<JSONObject> out=new ArrayList<>();
        JSONArray sessions=history==null?null:history.optJSONArray("sessions");
        if(sessions==null) return out;
        Set<String> seen=new HashSet<>();
        for(int i=0;i<sessions.length();i++) {
            JSONObject p=focusProjection(sessions.optJSONObject(i));
            if(p!=null && seen.add(p.optString("id")) && !tombstoned(p.optString("id")) && find(arr(document,"overrides"),p.optString("id"))==null) out.add(p);
        }
        return out;
    }
    private static boolean hasRepeat(JSONObject b) {
        JSONObject r=b.optJSONObject("repeat"); return r!=null&&!"none".equals(r.optString("frequency","none"));
    }
    private static JSONObject occurrence(JSONObject series,String d) {
        JSONObject r=series.optJSONObject("repeat"); if(r==null) return null;
        String anchor=r.optString("anchor_date"),until=r.optString("until_date","");
        if(d.compareTo(anchor)<0 || (!until.isEmpty()&&d.compareTo(until)>0)) return null;
        if("weekly".equals(r.optString("frequency"))) {
            JSONArray days=r.optJSONArray("weekdays"); boolean match=false;
            if(days!=null) for(int i=0;i<days.length();i++) if(days.optInt(i)==weekday(d)) match=true;
            if(!match) return null;
        }
        TimeZone z=zone(r.optString("timezone"));
        // Wall-clock recurrence: each local date is resolved separately. No fixed 24h increments.
        long start=localTime(d,r.optString("start_time"),z);
        long end=localTime(addDays(d,r.optInt("end_day_offset",0)),r.optString("end_time"),z);
        if(end<=start) return null;
        JSONObject o=copy(series); o.remove("repeat");
        put(o,"id",occurrenceId(series.optString("id"),d)); put(o,"series_id",series.optString("id"));
        put(o,"occurrence_date",d); put(o,"start_at_ms",start); put(o,"end_at_ms",end);
        put(o,"source","recurrence"); put(o,"source_link","schedule_series:"+series.optString("id")+":"+d);
        return o;
    }
    private List<JSONObject> candidates(long from,long to,JSONObject history) {
        List<JSONObject> result=new ArrayList<>(); Set<String> seen=new HashSet<>();
        for(JSONObject b:allBlocks()) {
            if(!hasRepeat(b)) {
                if(!tombstoned(b.optString("id"))&&overlap(b.optLong("start_at_ms"),b.optLong("end_at_ms"),from,to)&&seen.add(b.optString("id"))) result.add(copy(b));
                continue;
            }
            JSONObject r=b.optJSONObject("repeat"); if(r==null) continue;
            TimeZone z=zone(r.optString("timezone"));
            // Include occurrence starts before the range for long/cross-midnight blocks.
            int back=Math.max(1,r.optInt("end_day_offset",0)+1);
            String first=date(from,z),last=date(to-1,z);
            for(String d=addDays(first,-back);d.compareTo(last)<=0;d=addDays(d,1)) {
                JSONObject o=occurrence(b,d);
                if(o==null||tombstoned(o.optString("id"))||find(arr(document,"overrides"),o.optString("id"))!=null) continue;
                if(overlap(o.optLong("start_at_ms"),o.optLong("end_at_ms"),from,to)&&seen.add(o.optString("id"))) result.add(o);
            }
        }
        for(JSONObject b:projected(history)) if(overlap(b.optLong("start_at_ms"),b.optLong("end_at_ms"),from,to)&&seen.add(b.optString("id"))) result.add(b);
        JSONArray overrides=arr(document,"overrides");
        for(int i=0;i<overrides.length();i++) {
            JSONObject b=overrides.optJSONObject(i);
            if(b!=null&&!tombstoned(b.optString("id"))&&overlap(b.optLong("start_at_ms"),b.optLong("end_at_ms"),from,to)&&seen.add(b.optString("id"))) result.add(copy(b));
        }
        result.sort(Comparator.comparingLong((JSONObject b)->b.optLong("start_at_ms")).thenComparing(b->b.optString("id")));
        return result;
    }
    JSONObject query(long from,long to,String kind,String todoId,int offset,int requestedLimit,JSONObject history) {
        if(from<=0||to<=from) throw bad("schedule_query_range_invalid");
        if(to-from>366L*DAY_MS) throw bad("schedule_query_range_too_large");
        if(offset<0) throw bad("schedule_offset_invalid");
        if(!clean(kind).isEmpty()&&!kind.equals("all")&&!kind.equals("plan")&&!kind.equals("actual")) throw bad("schedule_kind_invalid");
        int limit=requestedLimit<=0?DEFAULT_LIMIT:Math.min(MAX_LIMIT,requestedLimit);
        List<JSONObject> matching=new ArrayList<>();
        for(JSONObject b:candidates(from,to,history)) {
            if(!clean(kind).isEmpty()&&!kind.equals("all")&&!kind.equals(b.optString("kind"))) continue;
            if(!clean(todoId).isEmpty()&&!todoId.equals(b.optString("todo_id"))) continue;
            matching.add(b);
        }
        JSONArray out=new JSONArray(); for(int i=offset;i<matching.size()&&out.length()<limit;i++) out.put(copy(matching.get(i)));
        return obj("schema_version",VERSION,"range_start_ms",from,"range_end_ms",to,"total_count",matching.size(),"returned_count",out.length(),"limit",limit,"offset",offset,"has_more",((long)offset+out.length())<matching.size(),"next_offset",((long)offset+out.length())<matching.size()?offset+out.length():JSONObject.NULL,"blocks",out);
    }
    JSONObject summary(long now,TimeZone zone,JSONObject history) {
        long[] day=dayRange(date(now,zone),zone);
        List<JSONObject> list=candidates(day[0],day[1],history);
        JSONObject current=null,next=null,actual=null; JSONArray remaining=new JSONArray(); int count=0;
        for(JSONObject b:list) {
            long start=b.optLong("start_at_ms"),end=b.optLong("end_at_ms");
            if("plan".equals(b.optString("kind"))) {
                if(start<=now&&end>now&&(current==null||start>current.optLong("start_at_ms"))) current=b;
                if(start>now&&(next==null||start<next.optLong("start_at_ms"))) next=b;
                if(end>now) { count++; if(remaining.length()<6) remaining.put(compact(b)); }
            } else if(start<=now&&end>now&&(actual==null||start>actual.optLong("start_at_ms"))) actual=b;
        }
        return obj("schema_version",VERSION,"available",true,"source","android_local","updated_at_ms",updatedAt(),
                "queried_at_ms",now,"timezone",zone.getID(),"current_plan",current==null?JSONObject.NULL:compact(current),"next_plan",next==null?JSONObject.NULL:compact(next),"remaining_plan_count",count,"remaining_plans",remaining,"current_actual",actual==null?JSONObject.NULL:compact(actual));
    }
    static JSONObject compact(JSONObject b) {
        if(b==null) return null;
        JSONObject o=new JSONObject();
        for(String key:new String[]{"id","kind","title","category","color","start_at_ms","end_at_ms","todo_id","focus_session_id","source","source_link","user_overridden","override_source","override_at_ms","series_id","occurrence_date"}) if(b.has(key)) put(o,key,b.opt(key));
        return o;
    }
    JSONArray reminders(long from,long to) {
        JSONArray out=new JSONArray();
        for(JSONObject b:candidates(from-MAX_REMINDER_LEAD_MS,to+1,null)) {
            if(!"plan".equals(b.optString("kind"))) continue;
            int minutes=b.optInt("reminder_minutes_before",-1); if(minutes<0) continue;
            long fire=b.optLong("start_at_ms")-minutes*60000L;
            if(fire<from||fire>to) continue;
            out.put(obj("id",b.optString("id"),"title",b.optString("title"),"start_at_ms",b.optLong("start_at_ms"),"fire_at_ms",fire,"token",b.optString("id")+":"+fire));
        }
        return out;
    }
    JSONObject nextReminder(long now,long horizon,JSONObject history) {
        JSONObject best=null;
        for(JSONObject b:candidates(now-MAX_REMINDER_LEAD_MS,horizon,history)) {
            if(!"plan".equals(b.optString("kind"))) continue;
            int minutes=b.optInt("reminder_minutes_before",-1); if(minutes<0) continue;
            long fire=b.optLong("start_at_ms")-minutes*60000L;
            if(fire<=now||fire>horizon||tombstoned(b.optString("id"))) continue;
            if(best==null||fire<best.optLong("fire_at_ms")) best=obj("id",b.optString("id"),"title",b.optString("title"),"start_at_ms",b.optLong("start_at_ms"),"fire_at_ms",fire,"token",b.optString("id")+":"+fire);
        }
        return best;
    }
    static final long MAX_REMINDER_LEAD_MS=30L*DAY_MS;
}
