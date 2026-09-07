package dev.linjian.peek;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Minimal standalone Phase 5 editor; full application navigation and visual polish remain Phase 7. */
public final class ScheduleActivity extends Activity {
    private String date;private boolean week=false;private String mode="both";private String selectedId="";
    private JSONArray blocks=new JSONArray();private ScheduleGridView grid;private Spinner picker;private TextView selected,count;private Button dateButton,viewButton,modeButton;
    private boolean bindingPicker=false;
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);setContentView(R.layout.activity_schedule);
        try { ScheduleState.initialize(this); } catch(Exception e) { Toast.makeText(this,"时间轴初始化失败："+e.getMessage(),Toast.LENGTH_LONG).show(); }
        date=ScheduleCore.date(System.currentTimeMillis(),TimeZone.getDefault());
        if(saved!=null){date=saved.getString("date",date);week=saved.getBoolean("week",false);mode=saved.getString("mode","both");}
        grid=findViewById(R.id.scheduleGrid);picker=findViewById(R.id.schedulePicker);selected=findViewById(R.id.scheduleSelected);count=findViewById(R.id.scheduleCount);
        dateButton=findViewById(R.id.scheduleDate);viewButton=findViewById(R.id.scheduleView);modeButton=findViewById(R.id.scheduleMode);
        findViewById(R.id.scheduleBack).setOnClickListener(v->finish());
        findViewById(R.id.scheduleAddPlan).setOnClickListener(v->editor(null,"plan"));findViewById(R.id.scheduleAddActual).setOnClickListener(v->editor(null,"actual"));
        findViewById(R.id.schedulePrev).setOnClickListener(v->{date=ScheduleCore.addDays(date,week?-7:-1);refresh();});
        findViewById(R.id.scheduleNext).setOnClickListener(v->{date=ScheduleCore.addDays(date,week?7:1);refresh();});
        findViewById(R.id.scheduleToday).setOnClickListener(v->{date=ScheduleCore.date(System.currentTimeMillis(),TimeZone.getDefault());refresh();});
        dateButton.setOnClickListener(v->chooseDate());viewButton.setOnClickListener(v->{week=!week;refresh();});
        modeButton.setOnClickListener(v->{mode=mode.equals("both")?"plan":mode.equals("plan")?"actual":"both";refresh();});
        picker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){if(bindingPicker||position<=0)return;JSONObject b=blocks.optJSONObject(position-1);if(b!=null&&!b.optString("id").equals(selectedId))select(b.optString("id"),true);}
        });
        selected.setOnClickListener(v->{JSONObject b=find(selectedId);if(b!=null)showDetails(b);});
        refresh();
    }
    @Override protected void onResume(){super.onResume();if(date!=null&&grid!=null)refresh();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("date",date);out.putBoolean("week",week);out.putString("mode",mode);}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private void chooseDate(){Calendar c=Calendar.getInstance();c.setTimeInMillis(ScheduleCore.localMidnight(date,TimeZone.getDefault()));new DatePickerDialog(this,(v,y,m,d)->{date=String.format(Locale.US,"%04d-%02d-%02d",y,m+1,d);refresh();},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show();}
    private JSONObject find(String id){for(int i=0;i<blocks.length();i++){JSONObject b=blocks.optJSONObject(i);if(b!=null&&id.equals(b.optString("id")))return b;}return null;}
    private JSONObject command(String op){return ScheduleCore.obj("action","schedule_action","operation",op,"actor","user","user_confirmed",true);}
    private JSONObject run(JSONObject cmd){JSONObject r=ScheduleState.handleCommand(this,cmd);if(!r.optBoolean("ok",false))toast(r.optString("result","日程操作失败"));return r;}
    private void refresh(){
        dateButton.setText(date);viewButton.setText(week?"周视图":"日视图");modeButton.setText(mode.equals("both")?"计划＋实际":mode.equals("plan")?"仅计划":"仅实际");
        List<JSONObject> all=new ArrayList<>();int offset=0;JSONObject response;
        do {response=ScheduleState.queryLocal(this,date,week,mode,offset,500);if(!response.optBoolean("ok",false)){toast(response.optString("result"));return;}
            JSONArray page=response.optJSONArray("blocks");if(page==null)break;for(int i=0;i<page.length();i++)if(page.optJSONObject(i)!=null)all.add(page.optJSONObject(i));offset+=page.length();
        }while(response.optBoolean("has_more",false)&&offset<response.optInt("total_count",0));
        JSONArray displayed=new JSONArray();List<String> labels=new ArrayList<>();labels.add("选择时间块查看或编辑");
        for(JSONObject b:all){displayed.put(b);labels.add(("plan".equals(b.optString("kind"))?"计划 · ":"实际 · ")+ScheduleCore.format(b.optLong("start_at_ms"),"MM-dd HH:mm",TimeZone.getDefault())+" "+b.optString("title"));}
        blocks=displayed;count.setText(all.size()+" 个时间块");
        bindingPicker=true;ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);picker.setAdapter(adapter);bindingPicker=false;
        grid.setData(blocks,date,week,mode,id->select(id,true));
        JSONObject active=find(selectedId);if(active==null){selectedId="";selected.setText("选择时间块查看详情");}else select(selectedId,false);
    }
    private void select(String id,boolean show){JSONObject b=find(id);if(b==null)return;selectedId=id;grid.select(id);
        String kind=b.optString("kind").equals("plan")?"计划":"实际";
        String span=ScheduleCore.format(b.optLong("start_at_ms"),"MM-dd HH:mm",TimeZone.getDefault())+" → "+ScheduleCore.format(b.optLong("end_at_ms"),"MM-dd HH:mm",TimeZone.getDefault());
        selected.setText(kind+" · "+b.optString("title")+"\n"+span+"\n点击查看详情 / 编辑 / 删除");
        bindingPicker=true;
        for(int i=0;i<blocks.length();i++)if(id.equals(blocks.optJSONObject(i).optString("id"))){picker.setSelection(i+1);break;}
        bindingPicker=false;
        if(show)showDetails(b);
    }
    private JSONObject readBlock(String id) {
        JSONObject q=ScheduleState.handleCommand(this,ScheduleCore.obj("action","get_schedule","block_id",id));
        if(!q.optBoolean("ok",false)) {toast(q.optString("result","读取失败"));return null;}
        return q.optJSONObject("block");
    }
    private void editSeries(String id) {
        JSONObject series=readBlock(id);
        if(series==null) {toast("重复规则已不存在");return;}
        editor(series,"plan");
    }
    private void showDetails(JSONObject b){String kind=b.optString("kind").equals("plan")?"计划":"实际";
        StringBuilder s=new StringBuilder();s.append(kind).append(" · ").append(b.optString("title"));
        s.append("\n").append(ScheduleCore.format(b.optLong("start_at_ms"),"yyyy-MM-dd HH:mm",TimeZone.getDefault())).append(" → ").append(ScheduleCore.format(b.optLong("end_at_ms"),"yyyy-MM-dd HH:mm",TimeZone.getDefault()));
        if(!b.optString("category","").isEmpty())s.append("\n分类：").append(b.optString("category"));
        if(!b.optString("note","").isEmpty())s.append("\n备注：").append(b.optString("note"));
        if(!b.optString("todo_id","").isEmpty())s.append("\nTodo ID：").append(b.optString("todo_id"));
        if(!b.optString("focus_session_id","").isEmpty())s.append("\nFocus session：").append(b.optString("focus_session_id")).append("\n此处修正不会改变正式 Focus 时长。");
        if(b.optBoolean("user_overridden",false))s.append("\n已由用户修正，自动同步不会覆盖。");
        if(!b.optString("series_id","").isEmpty()) {
            String seriesId=b.optString("series_id");
            new AlertDialog.Builder(this).setTitle("重复计划 · 选择范围").setMessage(s.toString())
                    .setItems(new String[]{"编辑本次","编辑整个规则","删除本次","删除整个规则"},(d,which)->{
                        if(which==0)editor(b,"plan");
                        else if(which==1)editSeries(seriesId);
                        else if(which==2)confirmDelete(b);
                        else {JSONObject series=readBlock(seriesId);if(series!=null)confirmDelete(series);else toast("重复规则已不存在");}
                    }).setNegativeButton("关闭",null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("时间块详情").setMessage(s.toString())
                .setPositiveButton("编辑",(d,w)->editor(b,b.optString("kind")))
                .setNeutralButton("删除",(d,w)->confirmDelete(b)).setNegativeButton("关闭",null).show();
    }
    private void confirmDelete(JSONObject b){boolean series=b.has("repeat");String scope=series?"all":"this";
        String warning=series?"删除整个重复规则。已经单独修改的 occurrence 会保留，避免丢失用户记录。":"删除此时间块。若来自 Focus，删除只隐藏时间轴投影，不删除原始 Focus session。";
        new AlertDialog.Builder(this).setTitle("确认删除").setMessage(warning).setPositiveButton("删除",(d,w)->{
            JSONObject cmd=command("delete");ScheduleCore.put(cmd,"id",b.optString("id"));ScheduleCore.put(cmd,"scope",scope);
            if(run(cmd).optBoolean("ok",false)){selectedId="";refresh();}
        }).setNegativeButton("取消",null).show();
    }
    private static final class EditorFields {
        EditText title,note,category,color,until,reminder;
        Button start,end;Spinner todo,repeat;CheckBox[] weekdays=new CheckBox[7];
        long startMs,endMs;List<String> todoIds=new ArrayList<>();List<JSONObject> todos=new ArrayList<>();
    }
    private EditText field(LinearLayout root,String label,String value,boolean multiline){
        TextView caption=new TextView(this);caption.setText(label);caption.setTextSize(12);caption.setTextColor(0xff514651);root.addView(caption);
        EditText input=new EditText(this);input.setText(value==null?"":value);input.setSingleLine(!multiline);input.setTextSize(14);input.setMinimumHeight(dp(48));root.addView(input,new LinearLayout.LayoutParams(-1,-2));return input;
    }
    private TextView caption(LinearLayout root,String value){TextView t=new TextView(this);t.setText(value);t.setTextSize(12);t.setTextColor(0xff514651);t.setPadding(0,dp(8),0,dp(4));root.addView(t);return t;}
    private Button button(LinearLayout root,String value){Button b=new Button(this);b.setText(value);b.setMinHeight(dp(48));root.addView(b,new LinearLayout.LayoutParams(-1,dp(48)));return b;}
    private void selectDateTime(EditorFields f,boolean start){
        long value=start?f.startMs:f.endMs;Calendar c=Calendar.getInstance();c.setTimeInMillis(value);
        new DatePickerDialog(this,(v,y,m,d)->new TimePickerDialog(this,(v2,h,min)->{
            String day=String.format(Locale.US,"%04d-%02d-%02d",y,m+1,d),time=String.format(Locale.US,"%02d:%02d",h,min);
            try{long ms=ScheduleCore.localTime(day,time,TimeZone.getDefault());if(start)f.startMs=ms;else f.endMs=ms;updateTimeButtons(f);}catch(Exception e){toast(e.getMessage());}
        },c.get(Calendar.HOUR_OF_DAY),c.get(Calendar.MINUTE),true).show(),c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show();
    }
    private void updateTimeButtons(EditorFields f){f.start.setText("开始 · "+ScheduleCore.format(f.startMs,"yyyy-MM-dd HH:mm",TimeZone.getDefault()));f.end.setText("结束 · "+ScheduleCore.format(f.endMs,"yyyy-MM-dd HH:mm",TimeZone.getDefault()));}
    private void editor(JSONObject prior,String kind){
        boolean edit=prior!=null,actual=kind.equals("actual"),series=edit&&prior.has("repeat"),occurrence=edit&&prior.has("series_id");
        EditorFields f=new EditorFields();long now=System.currentTimeMillis();TimeZone localZone=TimeZone.getDefault();
        String today=ScheduleCore.date(now,localZone);
        long defaultStart;
        if(actual) {
            // A new actual is a past fact, never a proposed future activity.
            long defaultEnd=date.equals(today)?now:Math.min(now,ScheduleCore.localTime(date,"10:00",localZone));
            defaultStart=defaultEnd-3600000L;
        } else if(date.equals(today)) defaultStart=now+3600000L-now%60000L;
        else defaultStart=ScheduleCore.localTime(date,"09:00",localZone);
        f.startMs=edit?prior.optLong("start_at_ms"):defaultStart;
        f.endMs=edit?prior.optLong("end_at_ms"):actual?Math.min(now,defaultStart+3600000L):defaultStart+3600000L;
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(8),dp(18),dp(12));
        ScrollView scroll=new ScrollView(this);scroll.addView(root);
        if(actual)caption(root,"实际记录由你确认。修改 Focus 来源只改变时间轴显示，不修改原始专注时长。");
        f.title=field(root,"标题",edit?prior.optString("title"):"",false);
        f.note=field(root,"备注（可选）",edit?prior.optString("note"):"",true);
        f.category=field(root,"分类（可选）",edit?prior.optString("category"):"",false);
        f.color=field(root,"颜色（可选，#RRGGBB）",edit?prior.optString("color"):"",false);
        f.start=button(root,"");f.end=button(root,"");updateTimeButtons(f);f.start.setOnClickListener(v->selectDateTime(f,true));f.end.setOnClickListener(v->selectDateTime(f,false));
        caption(root,"关联 Todo（可选，不改变 deadline 或完成状态）");
        f.todo=new Spinner(this);List<String> options=new ArrayList<>();options.add("不关联 Todo");f.todoIds.add("");
        JSONArray todos=TodoState.collect(this).optJSONArray("todos");if(todos!=null)for(int i=0;i<todos.length();i++){
            JSONObject todo=todos.optJSONObject(i);if(todo==null)continue;String id=todo.optString("id");if(id.isEmpty())continue;f.todos.add(todo);f.todoIds.add(id);options.add(todo.optString("title")+" · "+id.substring(0,Math.min(8,id.length()))+("completed".equals(todo.optString("status"))?"（已完成）":""));
        }
        if(edit&&!prior.optString("todo_id","").isEmpty()&&!f.todoIds.contains(prior.optString("todo_id"))){f.todoIds.add(prior.optString("todo_id"));options.add("原关联 Todo 已删除 · "+prior.optString("todo_id"));}
        ArrayAdapter<String> todoAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,options);todoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);f.todo.setAdapter(todoAdapter);root.addView(f.todo,new LinearLayout.LayoutParams(-1,dp(48)));
        if(edit)f.todo.setSelection(Math.max(0,f.todoIds.indexOf(prior.optString("todo_id",""))));
        f.todo.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
            if(pos<=0||pos>f.todos.size()||edit)return;JSONObject todo=f.todos.get(pos-1);
            if(f.title.getText().toString().trim().isEmpty())f.title.setText(todo.optString("title"));
            if(f.category.getText().toString().trim().isEmpty())f.category.setText(todo.optString("category"));
        }});
        if(!actual){
            caption(root,"重复计划（只对计划有效）");f.repeat=new Spinner(this);String[] repeatLabels={"不重复","每天","每周 / 指定星期"};ArrayAdapter<String> ra=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,repeatLabels);ra.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);f.repeat.setAdapter(ra);root.addView(f.repeat,new LinearLayout.LayoutParams(-1,dp(48)));
            JSONObject r=edit?prior.optJSONObject("repeat"):null;if(r!=null)f.repeat.setSelection("daily".equals(r.optString("frequency"))?1:2);
            LinearLayout days=new LinearLayout(this);days.setOrientation(LinearLayout.HORIZONTAL);root.addView(days);
            for(int i=0;i<7;i++){final int w=i+1;CheckBox c=new CheckBox(this);c.setText(new String[]{"一","二","三","四","五","六","日"}[i]);c.setTextSize(11);c.setMinHeight(dp(48));days.addView(c,new LinearLayout.LayoutParams(0,dp(48),1));f.weekdays[i]=c;
                JSONArray set=r==null?null:r.optJSONArray("weekdays");if(set!=null)for(int j=0;j<set.length();j++)if(set.optInt(j)==w)c.setChecked(true);
            }
            f.until=field(root,"重复结束日期（可选，yyyy-MM-dd）",r==null?"":r.optString("until_date",""),false);
            f.reminder=field(root,"提前提醒分钟（空白为不提醒，0 为开始时）",edit&&prior.has("reminder_minutes_before")&&prior.optInt("reminder_minutes_before")>=0?String.valueOf(prior.optInt("reminder_minutes_before")):"",false);
            f.reminder.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            if(occurrence){f.repeat.setEnabled(false);f.until.setEnabled(false);for(CheckBox c:f.weekdays)c.setEnabled(false);}
        }
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(edit?"编辑"+(actual?"实际":"计划"):"新建"+(actual?"实际":"计划")).setView(scroll).setPositiveButton("保存",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                if(f.endMs<=f.startMs)throw ScheduleCore.bad("结束时间必须晚于开始时间");
                JSONObject cmd=command(edit?"update":"create");if(edit)ScheduleCore.put(cmd,"id",prior.optString("id"));else ScheduleCore.put(cmd,"kind",kind);
                if(edit)ScheduleCore.put(cmd,"scope",series?"all":"this");
                ScheduleCore.put(cmd,"title",f.title.getText().toString().trim());ScheduleCore.put(cmd,"note",f.note.getText().toString());ScheduleCore.put(cmd,"category",f.category.getText().toString().trim());ScheduleCore.put(cmd,"color",f.color.getText().toString().trim());
                ScheduleCore.put(cmd,"start_at_ms",f.startMs);ScheduleCore.put(cmd,"end_at_ms",f.endMs);
                String todoId=f.todoIds.get(f.todo.getSelectedItemPosition());
                if(!todoId.isEmpty()&&TodoState.findByIdForFocus(this,todoId)==null)throw ScheduleCore.bad("关联 Todo 已不存在，请重新选择或取消关联。");
                ScheduleCore.put(cmd,"todo_id",todoId);
                if(actual){if(!edit){ScheduleCore.put(cmd,"source","user");ScheduleCore.put(cmd,"user_confirmed",true);}}
                else if(!occurrence){
                    int repeat=f.repeat.getSelectedItemPosition();
                    if(repeat>0){String anchor=ScheduleCore.date(f.startMs,TimeZone.getDefault());String endDate=ScheduleCore.date(f.endMs,TimeZone.getDefault());
                        JSONArray weekdays=new JSONArray();for(int i=0;i<7;i++)if(f.weekdays[i].isChecked())weekdays.put(i+1);
                        JSONObject r=ScheduleCore.obj("frequency",repeat==1?"daily":"weekly","timezone",TimeZone.getDefault().getID(),"anchor_date",anchor,"start_time",ScheduleCore.format(f.startMs,"HH:mm",TimeZone.getDefault()),"end_time",ScheduleCore.format(f.endMs,"HH:mm",TimeZone.getDefault()),"end_day_offset",daysBetween(anchor,endDate),"weekdays",weekdays,"until_date",f.until.getText().toString().trim());
                        ScheduleCore.put(cmd,"repeat",r);
                    }else ScheduleCore.put(cmd,"repeat",ScheduleCore.obj("frequency","none"));
                }
                if(!actual){String reminder=f.reminder.getText().toString().trim();ScheduleCore.put(cmd,"reminder_minutes_before",reminder.isEmpty()?-1:Integer.parseInt(reminder));}
                if(edit&&actual)ScheduleCore.put(cmd,"user_confirmed",true);
                JSONObject r=run(cmd);if(r.optBoolean("ok",false)){dialog.dismiss();selectedId=edit?prior.optString("id"):r.optJSONObject("block").optString("id");refresh();}
            }catch(Exception e){toast(e.getMessage()==null?"输入不合法":e.getMessage());}
        }));dialog.show();
    }
    private int daysBetween(String a,String b){long start=ScheduleCore.localMidnight(a,TimeZone.getTimeZone("UTC")),end=ScheduleCore.localMidnight(b,TimeZone.getTimeZone("UTC"));return(int)((end-start)/86400000L);}
}
