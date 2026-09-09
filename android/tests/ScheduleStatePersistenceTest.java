package dev.linjian.peek;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real ScheduleState/Core, TodoState/Core, FocusMode/Core and AppPrefs.
 * Only Android APIs and local reminders are replaced. Not disk/process instrumentation.
 */
public final class ScheduleStatePersistenceTest {
    private static final MemoryPrefs prefs=new MemoryPrefs();
    private static final Context ctx=new Context(){@Override public SharedPreferences getSharedPreferences(String name,int mode){if(!"linjian_peek".equals(name))throw new AssertionError(name);return prefs;}};
    static void yes(boolean v,String s){if(!v)throw new AssertionError(s);}
    static JSONObject o(Object...pairs){return ScheduleCore.obj(pairs);}
    static long now(){return System.currentTimeMillis();}
    static JSONObject command(String op){return o("action","schedule_action","operation",op,"actor","user","user_confirmed",true);}
    static JSONObject call(JSONObject c){return ScheduleState.handleCommand(ctx,c);}
    static JSONObject ok(JSONObject c){JSONObject r=call(c);yes(r.optBoolean("ok"),r.toString());return r;}
    static JSONObject create(String kind,String title,long start,long end){JSONObject c=command("create");ScheduleCore.put(c,"kind",kind);ScheduleCore.put(c,"title",title);ScheduleCore.put(c,"start_at_ms",start);ScheduleCore.put(c,"end_at_ms",end);return c;}
    static JSONObject aiCreate(String title,long start,long end,String key){JSONObject c=create("plan",title,start,end);ScheduleCore.put(c,"actor","ai");ScheduleCore.put(c,"source","ai");if(key!=null)ScheduleCore.put(c,"idempotency_key",key);return c;}
    static JSONObject state(){return new JSONObject(prefs.getString(ScheduleState.KEY_STATE,"{}"));}
    static void reset(){prefs.hook=null;prefs.edit().clear().commit();prefs.failNext=false;prefs.commitCount=0;}
    static JSONObject query(){return ScheduleState.queryLocal(ctx,ScheduleCore.date(now(),java.util.TimeZone.getDefault()),false,"all",0,500);}
    static final class MemoryPrefs implements SharedPreferences {
        private final Map<String,String> data=new HashMap<>();volatile ReadHook hook;volatile boolean failNext;volatile int commitCount;
        @Override public String getString(String key,String def){String raw; synchronized(this){raw=data.getOrDefault(key,def);}ReadHook h=hook;if(h!=null)h.read(key,raw);return raw;}
        @Override public synchronized int getInt(String key,int def){try{return Integer.parseInt(data.get(key));}catch(Exception e){return def;}}
        @Override public synchronized boolean contains(String key){return data.containsKey(key);}
        @Override public Editor edit(){return new Edit();}
        final class Edit implements Editor {
            final Map<String,String> changes=new HashMap<>();boolean clear;
            @Override public Editor putString(String key,String value){changes.put(key,value);return this;}
            @Override public Editor putInt(String key,int value){return putString(key,String.valueOf(value));}
            @Override public Editor remove(String key){changes.put(key,null);return this;}
            @Override public Editor clear(){clear=true;return this;}
            @Override public boolean commit(){synchronized(MemoryPrefs.this){if(failNext){failNext=false;return false;}if(clear)data.clear();for(Map.Entry<String,String> e:changes.entrySet())if(e.getValue()==null)data.remove(e.getKey());else data.put(e.getKey(),e.getValue());commitCount++;return true;}}
            @Override public void apply(){commit();}
        }
    }
    interface ReadHook{void read(String key,String raw);}
    static final class Gate implements ReadHook {
        final String name;final AtomicBoolean once=new AtomicBoolean();final CountDownLatch read=new CountDownLatch(1),release=new CountDownLatch(1);
        Gate(String name){this.name=name;}
        @Override public void read(String key,String raw){if(!key.equals(ScheduleState.KEY_STATE)||!Thread.currentThread().getName().equals(name)||!once.compareAndSet(false,true))return;read.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("gate timeout");}catch(InterruptedException e){throw new AssertionError(e);}}
    }
    static final class Task<T>{final FutureTask<T> future;final Thread thread;Task(String name,java.util.concurrent.Callable<T> action){future=new FutureTask<>(action);thread=new Thread(future,name);thread.start();}T get()throws Exception{return future.get(10,TimeUnit.SECONDS);}}
    static void await(CountDownLatch latch)throws Exception{yes(latch.await(5,TimeUnit.SECONDS),"latch timeout");}
    static void blocked(Thread thread)throws Exception{
        ThreadMXBean bean=ManagementFactory.getThreadMXBean();long deadline=System.currentTimeMillis()+5000;
        while(System.currentTimeMillis()<deadline){ThreadInfo info=bean.getThreadInfo(thread.getId());if(info!=null&&info.getThreadState()==Thread.State.BLOCKED){yes(info.getLockName()!=null,"monitor identified");return;}Thread.sleep(5);}
        throw new AssertionError("second writer did not block on Schedule monitor: "+thread.getState());
    }
    static void testInitializationAndPersistence(){
        reset();JSONObject missing=ScheduleState.collect(ctx);yes(!missing.optBoolean("available"),"missing unavailable");yes(!prefs.contains(ScheduleState.KEY_STATE),"read did not initialize");
        yes(!query().optBoolean("ok"),"query missing unavailable");ScheduleState.initialize(ctx);yes(ScheduleState.collect(ctx).optBoolean("available"),"explicit initialization");
        yes(query().getJSONArray("blocks").length()==0,"initialized genuinely empty");
        long a=now()-7200000,b=a+3600000;JSONObject r=ok(create("plan","A",a,b));String id=r.getJSONObject("block").getString("id");
        String raw=prefs.getString(ScheduleState.KEY_STATE,"");ScheduleState.initialize(ctx);yes(raw.equals(prefs.getString(ScheduleState.KEY_STATE,"")),"initialization does not reset");
        ScheduleCore restored=ScheduleCore.fromJson(raw);yes(restored.get(id,null)!=null,"persistent roundtrip");
        JSONObject update=command("update");ScheduleCore.put(update,"block_id",id);ScheduleCore.put(update,"title","B");ok(update);
        yes(state().getJSONArray("blocks").getJSONObject(0).getString("title").equals("B"),"block_id update");
        JSONObject delete=command("delete");ScheduleCore.put(delete,"block_id",id);ScheduleCore.put(delete,"scope","this");ok(delete);
        yes(query().getJSONArray("blocks").length()==0,"delete persisted");
        prefs.failNext=true;JSONObject failed=call(create("plan","failed",a,b));yes(!failed.optBoolean("ok"),"commit failure reported");yes(state().getJSONArray("blocks").length()==0,"failed commit no partial state");
        prefs.edit().putString(ScheduleState.KEY_STATE,"{broken").commit();yes(!ScheduleState.collect(ctx).optBoolean("available"),"corrupt unavailable");
        yes(!call(create("plan","cannot reset",a,b)).optBoolean("ok"),"no silent corrupt overwrite");
    }
    static void testTodoBindingAndActual(){
        reset();ScheduleState.initialize(ctx);long a=now()-7200000,b=a+3600000;
        JSONObject todo=TodoState.handleCommand(ctx,o("action","todo_action","operation","create","title","数据结构","category","考研"));yes(todo.optBoolean("ok"),todo.toString());String tid=todo.getJSONObject("todo").getString("id");
        JSONObject bad=create("plan","bad",a,b);ScheduleCore.put(bad,"todo_id","missing");yes(!call(bad).optBoolean("ok"),"missing Todo rejected");
        JSONObject p=create("plan","数据结构",a,b);ScheduleCore.put(p,"todo_id",tid);
        ok(p);ok(p);yes(TodoState.findByIdForFocus(ctx,tid)!=null,"Todo still exists");
        yes("open".equals(TodoState.findByIdForFocus(ctx,tid).optString("status")),"Schedule does not complete Todo");
        yes(ScheduleState.queryLocal(ctx,ScheduleCore.date(a,java.util.TimeZone.getDefault()),false,"plan",0,100).getInt("total_count")>=2,"same Todo has multiple plans");
        JSONObject actual=create("actual","吃饭",a,b);ScheduleCore.put(actual,"source","user");ok(actual);
        JSONObject ai=create("actual","补录",a,b);ScheduleCore.put(ai,"actor","ai");ScheduleCore.put(ai,"source","ai_confirmed");
        yes(!call(ai).optBoolean("ok"),"AI unconfirmed actual rejected");ScheduleCore.put(ai,"confirmed_by_user",true);ScheduleCore.put(ai,"idempotency_key","actual-confirmed-001");ok(ai);
        JSONObject forged=create("actual","伪造",a,b);ScheduleCore.put(forged,"actor","ai");ScheduleCore.put(forged,"source","focus_session");ScheduleCore.put(forged,"confirmed_by_user",true);
        yes(!call(forged).optBoolean("ok"),"no forged Focus source");
        JSONObject future=create("actual","未来",now()+60000,now()+120000);yes(!call(future).optBoolean("ok"),"no future completed actual");
    }
    static void testFocusSourceAndOverrides()throws Exception{
        reset();ScheduleState.initialize(ctx);
        JSONObject started=FocusMode.handleCommand(ctx,o("action","start_focus_mode","duration_minutes",30,"goal","数据结构"));yes(started.optBoolean("ok"),started.toString());
        String sid=started.getString("session_id");Thread.sleep(5);
        JSONObject ended=FocusMode.handleCommand(ctx,o("action","end_focus_mode","reason","manual"));yes(ended.optBoolean("ok"),ended.toString());
        JSONObject session=FocusSessionCore.findSession(FocusMode.completedSessionsSnapshot(ctx),sid);yes(session!=null,"completed source");
        long start=session.getLong("started_at_ms"),end=session.getLong("ended_at_ms");
        yes(end>start,"real completed Focus has positive elapsed time");
        String id=ScheduleCore.focusId(sid);JSONObject h=FocusMode.completedSessionsSnapshot(ctx);
        JSONObject projection=ScheduleCore.focusProjection(session);yes(projection!=null,"valid production projection");
        long from=start-1000,to=end+1000;
        ScheduleCore core=ScheduleCore.fromJson(state().toString());yes(core.query(from,to,"actual","",0,100,h).getInt("total_count")==1,"source projection");
        int writes=prefs.commitCount;
        JSONObject q=ScheduleState.handleCommand(ctx,o("action","get_schedule","from_ms",from,"to_ms",to));yes(q.optBoolean("ok"),q.toString());
        yes(q.getJSONArray("blocks").length()==1,"one source actual");
        yes(prefs.commitCount==writes,"projection is read-only");
        JSONObject edit=command("update");ScheduleCore.put(edit,"block_id",id);ScheduleCore.put(edit,"title","修正后的学习");ScheduleCore.put(edit,"start_at_ms",start+100);ScheduleCore.put(edit,"end_at_ms",Math.max(start+200,end));ok(edit);
        JSONObject after=ScheduleState.handleCommand(ctx,o("action","get_schedule","from_ms",from,"to_ms",to)).getJSONArray("blocks").getJSONObject(0);
        yes(after.optBoolean("user_overridden"),"user override stored");yes(after.getLong("start_at_ms")==start+100,"source cannot overwrite edit");
        yes(state().getJSONArray("overrides").length()==1,"one persisted override");
        JSONObject again=ScheduleState.handleCommand(ctx,o("action","get_schedule","from_ms",from,"to_ms",to));yes(again.getJSONArray("blocks").length()==1,"repeated source sync idempotent");
        JSONObject del=command("delete");ScheduleCore.put(del,"block_id",id);ScheduleCore.put(del,"scope","this");ok(del);
        yes(ScheduleState.handleCommand(ctx,o("action","get_schedule","from_ms",from,"to_ms",to)).getJSONArray("blocks").length()==0,"source tombstone");
        yes(FocusSessionCore.findSession(FocusMode.completedSessionsSnapshot(ctx),sid)!=null,"Focus history not deleted");
        JSONObject restore=command("restore");ScheduleCore.put(restore,"block_id",id);ok(restore);
        yes(ScheduleState.handleCommand(ctx,o("action","get_schedule","from_ms",from,"to_ms",to)).getJSONArray("blocks").length()==1,"explicit restore");
    }
    static void testIdempotentCreateAfterLostResponse()throws Exception{
        reset();ScheduleState.initialize(ctx);long a=now()+3600000,b=a+3600000;
        JSONObject noKey=aiCreate("缺少幂等键",a,b,null);
        yes(!call(noKey).optBoolean("ok"),"remote create requires stable identity");

        JSONObject create=aiCreate("可靠创建",a,b,"schedule-create-attempt-001");
        JSONObject first=ok(create);String id=first.getJSONObject("block").getString("id");
        yes(!first.optBoolean("idempotency_replayed"),"first create is not replayed");
        // Simulate: Android committed, but the transport response was lost and the same MCP call retries.
        JSONObject retry=ok(create);
        yes(retry.optBoolean("idempotency_replayed"),"lost-response retry is replayed");
        yes(id.equals(retry.getJSONObject("block").getString("id")),"retry returns original block id");
        yes(state().getJSONArray("blocks").length()==1,"retry does not create a second block");
        yes(state().getJSONArray("idempotency").length()==1,"idempotency ledger persisted");
        JSONObject conflict=aiCreate("不同意图",a,b,"schedule-create-attempt-001");
        yes(!call(conflict).optBoolean("ok"),"same key cannot merge a different intent");
        JSONObject stable=aiCreate("稳定领域 ID",a+7200000,b+7200000,null);ScheduleCore.put(stable,"block_id","schedule_client_stable");
        JSONObject stableFirst=ok(stable),stableRetry=ok(stable);
        yes(stableRetry.optBoolean("idempotency_replayed"),"stable domain id is an idempotency fallback");
        yes(stableFirst.getJSONObject("block").getString("id").equals(stableRetry.getJSONObject("block").getString("id")),"stable domain retry keeps id");

        reset();ScheduleState.initialize(ctx);
        JSONObject concurrent=aiCreate("并发创建",a,b,"schedule-create-concurrent-001");
        Gate gate=new Gate("create-A");prefs.hook=gate;
        Task<JSONObject> one=new Task<>("create-A",()->call(concurrent));await(gate.read);
        Task<JSONObject> two=new Task<>("create-B",()->call(concurrent));blocked(two.thread);
        gate.release.countDown();JSONObject oneResult=one.get(),twoResult=two.get();prefs.hook=null;
        yes(oneResult.optBoolean("ok")&&twoResult.optBoolean("ok"),"concurrent retries succeed");
        yes(oneResult.getJSONObject("block").getString("id").equals(twoResult.getJSONObject("block").getString("id")),"concurrent retries share block id");
        yes(state().getJSONArray("blocks").length()==1,"concurrent retry is atomic");
    }
    static void testConcurrentWriters()throws Exception{
        reset();ScheduleState.initialize(ctx);long a=now()-7200000,b=a+3600000;
        Gate gate=new Gate("writer-A");prefs.hook=gate;
        Task<JSONObject> first=new Task<>("writer-A",()->call(create("plan","A",a,b)));await(gate.read);
        Task<JSONObject> second=new Task<>("writer-B",()->call(create("plan","B",a,b)));blocked(second.thread);
        gate.release.countDown();yes(first.get().optBoolean("ok"),"first commit");yes(second.get().optBoolean("ok"),"second commit");prefs.hook=null;
        yes(state().getJSONArray("blocks").length()==2,"no lost create");
        String actual=ok(create("actual","原始",a,b)).getJSONObject("block").getString("id");
        Gate editGate=new Gate("editor");prefs.hook=editGate;
        Task<JSONObject> edit=new Task<>("editor",()->{JSONObject c=command("update");ScheduleCore.put(c,"block_id",actual);ScheduleCore.put(c,"title","用户修正");return call(c);});await(editGate.read);
        Task<JSONObject> other=new Task<>("other-writer",()->call(create("plan","C",a,b)));blocked(other.thread);
        editGate.release.countDown();yes(edit.get().optBoolean("ok"),"edit commit");yes(other.get().optBoolean("ok"),"other commit");prefs.hook=null;
        yes(state().getJSONArray("blocks").length()==4,"no lost edit or create");
        JSONObject saved=ScheduleCore.fromJson(state().toString()).get(actual,null);yes(saved.optBoolean("user_overridden")&&saved.optString("title").equals("用户修正"),"actual override preserved");
        yes(ScheduleReminderTestAdapter.calls>0,"reschedule invoked outside state lock");
    }
    static void testExactIdAndRecurrenceScopes()throws Exception{
        reset();ScheduleState.initialize(ctx);long a=now()-7200000,b=a+3600000;
        JSONObject series=create("plan","重复计划",a,b);
        ScheduleCore.put(series,"repeat",o("frequency","daily","timezone","UTC"));
        JSONObject created=ok(series).getJSONObject("block");String sid=created.getString("id");
        String day=ScheduleCore.date(a,java.util.TimeZone.getTimeZone("UTC"));
        String oid=ScheduleCore.occurrenceId(sid,day);
        JSONObject root=ScheduleState.handleCommand(ctx,o("action","get_schedule","block_id",sid));
        yes(root.optBoolean("ok")&&root.getJSONObject("block").has("repeat"),"exact series lookup");
        JSONObject occurrence=ScheduleState.handleCommand(ctx,o("action","get_schedule","block_id",oid));
        yes(occurrence.optBoolean("ok")&&oid.equals(occurrence.getJSONObject("block").getString("id")),"exact occurrence lookup");
        JSONObject edit=command("update");ScheduleCore.put(edit,"block_id",oid);ScheduleCore.put(edit,"scope","this");ScheduleCore.put(edit,"title","只改本次");ok(edit);
        JSONObject rootAfter=ScheduleState.handleCommand(ctx,o("action","get_schedule","block_id",sid));
        yes(rootAfter.optBoolean("ok"),rootAfter.toString());
        yes(rootAfter.getJSONObject("block").getString("title").equals("重复计划"),"series remains unchanged");
        JSONObject deleted=command("delete");ScheduleCore.put(deleted,"block_id",sid);ScheduleCore.put(deleted,"scope","all");ok(deleted);
        yes(ScheduleState.handleCommand(ctx,o("action","get_schedule","block_id",oid)).getJSONObject("block").getString("title").equals("只改本次"),"explicit occurrence survives series deletion");
        yes(ScheduleState.handleCommand(ctx,o("action","get_schedule","block_id",sid)).isNull("block"),"deleted series absent");
        JSONObject restoreSeries=command("restore");ScheduleCore.put(restoreSeries,"block_id",sid);
        yes(call(restoreSeries).optString("result").contains("restore_not_supported"),"public restore does not promise deleted series recovery");
        yes(!ScheduleState.handleCommand(ctx,o("action","get_schedule","view","not-a-view")).optBoolean("ok"),"invalid view rejected");
    }
    static void testLockGuard()throws Exception{
        reset();ScheduleState.initialize(ctx);Method load=ScheduleState.class.getDeclaredMethod("load",Context.class);load.setAccessible(true);
        try{load.invoke(null,ctx);throw new AssertionError("unlocked load accepted");}catch(InvocationTargetException e){yes(e.getCause() instanceof IllegalStateException,"state lock guard");}
    }
    public static void main(String[] args)throws Exception{
        testInitializationAndPersistence();testTodoBindingAndActual();testFocusSourceAndOverrides();testIdempotentCreateAfterLostResponse();testConcurrentWriters();testExactIdAndRecurrenceScopes();testLockGuard();
        System.out.println("ScheduleStatePersistenceTest: PASS (production Android state RMW, real Todo/Focus, controlled threads, in-memory SharedPreferences)");
    }
}
