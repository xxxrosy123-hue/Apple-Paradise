package dev.linjian.peek;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Actual time-proportional day/week grid. Coordinates never mutate factual times. */
public final class ScheduleGridView extends View {
    public interface SelectionListener { void selected(String id); }
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Hit> hits=new ArrayList<>();
    private JSONArray blocks=new JSONArray();
    private String date=""; private boolean week=false; private String mode="both";
    private SelectionListener listener; private String selectedId="";
    private final float density;
    private static final int[] PALETTE={0xff7b9ec9,0xff9a83ba,0xff83a892,0xffc39a65,0xffb98092,0xff8d9ca7};
    private static final class Hit { RectF rect; String id; Hit(RectF r,String i){rect=r;id=i;} }
    public ScheduleGridView(Context ctx) { this(ctx,null); }
    public ScheduleGridView(Context ctx,AttributeSet attrs) { this(ctx,attrs,0); }
    public ScheduleGridView(Context ctx,AttributeSet attrs,int defStyleAttr) {
        super(ctx,attrs,defStyleAttr);
        density=getResources().getDisplayMetrics().density;
        setClickable(true);
        setFocusable(true);
    }
    private float dp(float n){return n*density;}
    public void setData(JSONArray source,String date,boolean week,String mode,SelectionListener listener) {
        this.blocks=source==null?new JSONArray():source;this.date=date;this.week=week;this.mode=mode;this.listener=listener;
        setContentDescription((week?"周":"日")+"时间轴。点击时间块查看；也可使用上方的时间块选择器。计划与实际分列显示。");
        requestLayout();invalidate();
    }
    public void select(String id){selectedId=id==null?"":id;invalidate();}
    @Override protected void onMeasure(int w,int h) {
        int width=MeasureSpec.getSize(w);if(width<=0)width=(int)dp(360);
        setMeasuredDimension(width,(int)dp(36+24*64));
    }
    private void fill(Canvas c,int color,float left,float top,float right,float bottom,float radius) {
        paint.setColor(color);paint.setStyle(Paint.Style.FILL);c.drawRoundRect(left,top,right,bottom,radius,radius,paint);
    }
    private void line(Canvas c,int color,float x1,float y1,float x2,float y2,float width) {
        paint.setColor(color);paint.setStrokeWidth(width);paint.setStyle(Paint.Style.STROKE);c.drawLine(x1,y1,x2,y2,paint);paint.setStyle(Paint.Style.FILL);
    }
    private void text(Canvas c,String value,float x,float y,float size,int color,boolean bold) {
        paint.setColor(color);paint.setTextSize(dp(size));paint.setTypeface(bold?android.graphics.Typeface.create("sans-serif-medium",0):android.graphics.Typeface.create("sans-serif",0));
        c.drawText(value,x,y,paint);
    }
    private String clipped(String value,float width,float size) {
        paint.setTextSize(dp(size));return android.text.TextUtils.ellipsize(value, new android.text.TextPaint(paint),width,android.text.TextUtils.TruncateAt.END).toString();
    }
    private static int minute(long ms,TimeZone zone) {
        Calendar c=Calendar.getInstance(zone);c.setTimeInMillis(ms);return c.get(Calendar.HOUR_OF_DAY)*60+c.get(Calendar.MINUTE);
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);hits.clear();float gutter=dp(40),header=dp(36),hour=dp(64),width=getWidth();
        fill(c,0xfffaf8f7,0,0,width,getHeight(),0);
        int days=week?7:1;float dayWidth=(width-gutter)/days;
        TimeZone zone=TimeZone.getDefault();String first=week?ScheduleCore.addDays(date,1-ScheduleCore.weekday(date)):date;
        for(int d=0;d<days;d++) {
            String day=ScheduleCore.addDays(first,d);
            float left=gutter+d*dayWidth;
            text(c,week?day.substring(5):day+"  "+weekdayName(day),left+dp(3),dp(23),week?10:13,0xff514651,true);
            line(c,0xffe2dfe3,left,header,left,getHeight(),dp(0.7f));
        }
        for(int h=0;h<=24;h++) {
            float y=header+h*hour;
            line(c,0xffe5e1e4,0,y,width,y,dp(0.7f));
            if(h<24)text(c,String.format(Locale.US,"%02d:00",h),dp(3),y+dp(12),9,0xff857b85,false);
        }
        for(int d=0;d<days;d++) {
            String localDate=ScheduleCore.addDays(first,d);long[] range=ScheduleCore.dayRange(localDate,zone);
            float x=gutter+d*dayWidth;
            int lanes="both".equals(mode)?2:1;
            if(lanes==2)line(c,0xffe9e5e9,x+dayWidth/2,header,x+dayWidth/2,getHeight(),dp(.5f));
            for(int i=0;i<blocks.length();i++) {
                JSONObject b=blocks.optJSONObject(i);if(b==null)continue;
                String kind=b.optString("kind");if(!"both".equals(mode)&&!mode.equals(kind))continue;
                long start=b.optLong("start_at_ms"),end=b.optLong("end_at_ms");
                if(!ScheduleCore.overlap(start,end,range[0],range[1]))continue;
                int begin=start<=range[0]?0:minute(start,zone),finish=end>=range[1]?1440:minute(end,zone);
                if(finish<=begin)continue;
                float top=header+hour*begin/60f,bottom=header+hour*finish/60f;
                int lane=lanes==2&&"actual".equals(kind)?1:0;
                float laneWidth=dayWidth/lanes;
                float left=x+lane*laneWidth+dp(1.5f),right=left+laneWidth-dp(3);
                int color=colorOf(b);
                paint.setColor(color);paint.setAlpha("plan".equals(kind)?140:230);paint.setStyle(Paint.Style.FILL);
                RectF rect=new RectF(left,top+dp(1),right,Math.max(top+dp(5),bottom-dp(1)));
                c.drawRoundRect(rect,dp(3),dp(3),paint);paint.setAlpha(255);
                paint.setColor("plan".equals(kind)?color:0xff4c4650);paint.setStrokeWidth(dp(selectedId.equals(b.optString("id"))?2.5f:1));paint.setStyle(Paint.Style.STROKE);
                c.drawRoundRect(rect,dp(3),dp(3),paint);paint.setStyle(Paint.Style.FILL);
                float available=rect.width()-dp(5);
                if(rect.height()>=dp(15)) {
                    String label=("plan".equals(kind)?"计划 ":"实际 ")+b.optString("title");
                    text(c,clipped(label,available,week?8:11),rect.left+dp(2.5f),rect.top+dp(12),week?8:11,0xff28252b,true);
                    if(rect.height()>=dp(30)) {
                        String times=ScheduleCore.format(start,"HH:mm",zone)+"–"+ScheduleCore.format(end,"HH:mm",zone);
                        text(c,clipped(times,available,week?7:9),rect.left+dp(2.5f),rect.top+dp(24),week?7:9,0xff3e3942,false);
                    }
                }
                hits.add(new Hit(rect,b.optString("id")));
            }
        }
        long now=System.currentTimeMillis();String today=ScheduleCore.date(now,zone);
        if(today.compareTo(first)>=0&&today.compareTo(ScheduleCore.addDays(first,days))<0) {
            int d=week?(ScheduleCore.weekday(today)-1):0;
            float y=header+hour*minute(now,zone)/60f;
            line(c,0xffb84056,gutter+d*dayWidth,y,gutter+(d+1)*dayWidth,y,dp(1.5f));
        }
    }
    private String weekdayName(String date){return new String[]{"一","二","三","四","五","六","日"}[ScheduleCore.weekday(date)-1];}
    private int colorOf(JSONObject b) {
        String category=b.optString("category","");if(category.equals("生活")&&(b.optString("title").contains("睡")||b.optString("title").contains("觉")))return 0xffb7bbc2;
        String raw=b.optString("color","");if(raw.matches("#[0-9a-fA-F]{6}"))try{return Color.parseColor(raw);}catch(Exception ignored){}
        return PALETTE[Math.floorMod(category.hashCode(),PALETTE.length)];
    }
    @Override public boolean performClick() {
        super.performClick();return true;
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent e) {
        if(e.getAction()==android.view.MotionEvent.ACTION_UP) {
            performClick();for(int i=hits.size()-1;i>=0;i--)if(hits.get(i).rect.contains(e.getX(),e.getY())) {if(listener!=null)listener.selected(hits.get(i).id);return true;}return true;
        }
        return true;
    }
}
