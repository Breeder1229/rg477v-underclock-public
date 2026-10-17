package com.anbernic.control.underclock.shared;

import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.app.Activity;
import android.view.WindowManager;
import android.provider.Settings;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import java.util.Arrays;

/** Shared editor; dragging never writes settings or changes the active request. */
public final class CustomProfileEditor {
    public interface Completion { void complete(String error); }
    public interface Apply { void saveAndApply(CustomProfileValues values, Completion result); }
    private static final String[] LABELS={"저전력 코어 ×4","성능 코어 ×3","최고성능 코어 ×1","GPU"};

    public static AlertDialog show(Context supplied,int slot,CustomProfileValues initial,Apply apply,Runnable dismissed) {
        final Context context=editorContext(supplied);
        if(slot<0||slot>3||initial==null||apply==null)throw new IllegalArgumentException("Custom 슬롯 확인 실패");
        int textColor=color(context,android.R.attr.textColorPrimary,Color.DKGRAY);
        int accent=color(context,android.R.attr.colorAccent,Color.rgb(25,155,120));
        LinearLayout content=new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(context,20),dp(context,4),dp(context,20),dp(context,4));
        content.addView(text(context,"CPU·GPU 최대 클럭을 선택하세요. 저장 및 적용을 누르면 활성화됩니다.",12,textColor));
        int[] selected=initial.toArray();
        int columns=context.getResources().getDisplayMetrics().widthPixels>=dp(context,600)?2:1;
        LinearLayout controls=null;
        for(int component=0;component<4;component++) {
            if(component%columns==0) {
                controls=new LinearLayout(context);controls.setBaselineAligned(false);
                content.addView(controls,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            LinearLayout control=new LinearLayout(context);control.setOrientation(LinearLayout.VERTICAL);
            if(columns==2)control.setPadding(component%2==0?0:dp(context,8),0,component%2==0?dp(context,8):0,0);
            controls.addView(control,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
            final int index=component,unit=component==3?1000000:1000;
            final int[] options=CustomProfileValues.options(component);
            TextView label=text(context,LABELS[index]+"  ·  "+selected[index]/unit+" MHz",14,textColor);
            label.setTypeface(null,Typeface.BOLD);label.setPadding(0,dp(context,4),0,0);control.addView(label);
            SeekBar slider=new SeekBar(context);
            slider.setMax(options.length-1);slider.setKeyProgressIncrement(1);
            slider.setProgress(Arrays.binarySearch(options,selected[index]));
            slider.setProgressTintList(ColorStateList.valueOf(accent));slider.setThumbTintList(ColorStateList.valueOf(accent));
            slider.setContentDescription(LABELS[index]+" 상한 "+selected[index]/unit+" MHz");
            control.addView(slider,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(context,48)));
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar view,int progress,boolean fromUser) {
                    selected[index]=options[progress];label.setText(LABELS[index]+"  ·  "+selected[index]/unit+" MHz");
                    view.setContentDescription(LABELS[index]+" 상한 "+selected[index]/unit+" MHz");
                }
                @Override public void onStartTrackingTouch(SeekBar view) { }
                @Override public void onStopTrackingTouch(SeekBar view) { }
            });
        }
        TextView reference=text(context,"기존 프로필 참고 · MHz",14,textColor);
        reference.setTypeface(null,Typeface.BOLD);reference.setPadding(0,dp(context,6),0,dp(context,4));content.addView(reference);
        TableLayout table=new TableLayout(context);table.setStretchAllColumns(true);table.setShrinkAllColumns(true);
        String[][] rows={{"프로필","저전력","성능","최고성능","GPU"},
            {"Default","2200","3200","3350","1400"},{"Small","2000","3000","3100","1190"},
            {"Medium","1800","2700","2790","1029"},{"Large","900","1200","1400","601"}};
        for(int r=0;r<rows.length;r++) {
            TableRow row=new TableRow(context);
            for(String value:rows[r]) {
                TextView cell=text(context,value,13,textColor);cell.setSingleLine(true);
                cell.setPadding(dp(context,3),dp(context,1),dp(context,3),dp(context,1));
                if(r==0)cell.setTypeface(null,Typeface.BOLD);row.addView(cell);
            }table.addView(row);
        }content.addView(table);
        content.addView(text(context,"Default는 자체 제한을 해제하며 표에는 순정 최대값을 표시했습니다. Medium의 CPU 요청 1800 / 2700 / 2790은 실기 지원 단계 1800 / 2660 / 2700MHz와 구분합니다. 실제 클럭은 부하에 따라 선택 상한보다 낮아질 수 있습니다.",12,textColor));
        TextView error=text(context,"",14,Color.rgb(210,65,65));error.setVisibility(View.GONE);error.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(error);
        ScrollView scroll=new ScrollView(context);scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(context).setTitle("Custom "+(slot+1)).setView(scroll)
            .setNegativeButton("취소",null).setPositiveButton("저장 및 적용",null).create();
        if(!hasActivity(context)) {
            // The right-edge panel is a system-service overlay, not the settings
            // Activity. Reuse its existing overlay permission; add no manifest rights.
            if(android.os.Process.myUid()!=1000||!Settings.canDrawOverlays(context)||dialog.getWindow()==null)
                throw new IllegalStateException("Control Center 편집창 권한을 확인할 수 없습니다.");
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
        if(dismissed!=null)dialog.setOnDismissListener(ignored->dismissed.run());
        Handler main=new Handler(Looper.getMainLooper());boolean[] busy={false};
        dialog.setOnShowListener(ignored->{
            if(dialog.getWindow()!=null)dialog.getWindow().setLayout(Math.min(context.getResources().getDisplayMetrics().widthPixels-dp(context,24),dp(context,720)),ViewGroup.LayoutParams.WRAP_CONTENT);
            Button save=dialog.getButton(AlertDialog.BUTTON_POSITIVE),cancel=dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            save.setTextColor(accent);cancel.setTextColor(textColor);
            save.setOnClickListener(view->{
                if(busy[0])return;
                error.setVisibility(View.VISIBLE);
                final CustomProfileValues candidate;
                try {candidate=CustomProfileValues.of(selected);}catch(IllegalArgumentException failure){error.setText(failure.getMessage());return;}
                busy[0]=true;save.setEnabled(false);cancel.setEnabled(false);dialog.setCancelable(false);error.setText("적용 응답을 확인합니다…");
                Completion done=message->main.post(()->{
                    if(!dialog.isShowing())return;
                    if(message==null||message.isEmpty()){dialog.dismiss();return;}
                    busy[0]=false;save.setEnabled(true);cancel.setEnabled(true);dialog.setCancelable(true);error.setText(message);
                });
                try {apply.saveAndApply(candidate,done);}catch(RuntimeException failure){done.complete(failure.getMessage()==null?"설정을 적용하지 못했습니다.":failure.getMessage());}
            });
        });dialog.show();return dialog;
    }
    private static Context editorContext(Context supplied) {
        if(hasActivity(supplied))return supplied;
        Configuration config=new Configuration(supplied.getResources().getConfiguration());
        config.densityDpi=Math.round(supplied.getResources().getDisplayMetrics().densityDpi*1.28f);
        ContextThemeWrapper enlarged=new ContextThemeWrapper(supplied.createConfigurationContext(config),0);
        enlarged.getTheme().setTo(supplied.getTheme());
        return enlarged;
    }
    private static int color(Context context,int attribute,int fallback) {
        TypedValue value=new TypedValue();
        if(!context.getTheme().resolveAttribute(attribute,value,true))return fallback;
        try {return value.resourceId!=0?context.getColor(value.resourceId):value.data;}catch(RuntimeException failure){return fallback;}
    }
    private static boolean hasActivity(Context context) {
        for(int i=0;i<16;i++) {
            if(context instanceof Activity)return true;
            if(!(context instanceof ContextWrapper))return false;
            Context next=((ContextWrapper)context).getBaseContext();if(next==null||next==context)return false;
            context=next;
        }return false;
    }
    private static int dp(Context context,int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    private static TextView text(Context context,String value,int size,int color){
        TextView text=new TextView(context);text.setText(value);text.setTextSize(size);text.setTextColor(color);return text;
    }
    private CustomProfileEditor() { }
}
