package top.liquidglass.vector;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.*;

/** V1.9.101: clean settings surface; legacy preferences remain intact. */
public final class MainActivity extends Activity {
    private SharedPreferences prefs;
    private TextView rootStatus;
    private final Handler rootStatusHandler=new Handler(Looper.getMainLooper());
    private boolean rootStatusVisible=false;
    private boolean rootStatusBusy=false;
    private final Runnable rootStatusPoll=new Runnable(){
        @Override public void run(){
            if(!rootStatusVisible||rootStatus==null)return;
            if(!rootStatusBusy){
                rootStatusBusy=true;
                RootHelperStatus.checkAsync(MainActivity.this, result->{
                    rootStatusBusy=false;
                    if(rootStatusVisible&&rootStatus!=null)rootStatus.setText(result);
                });
            }
            rootStatusHandler.postDelayed(this,5000);
        }
    };
    @Override protected void onResume(){super.onResume();rootStatusVisible=true;rootStatusHandler.removeCallbacks(rootStatusPoll);rootStatusHandler.post(rootStatusPoll);}
    @Override protected void onStop(){rootStatusVisible=false;rootStatusHandler.removeCallbacks(rootStatusPoll);super.onStop();}

    private LinearLayout body;
    private final int ink=0xFF172635, muted=0xFF657587, blue=0xFF2979E5;
    private GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String value,int size,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(bold?ink:muted);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    private void section(String title,String desc){
        LinearLayout card=column();card.setPadding(dp(18),dp(16),dp(18),dp(16));card.setBackground(bg(Color.WHITE,22));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(14));body.addView(card,lp);
        TextView heading=text(title,18,true);card.addView(heading);
        if(desc!=null&&!desc.isEmpty()){TextView sub=text(desc,12,false);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(5),0,dp(12));card.addView(sub,p);}
        current=card;
    }
    private LinearLayout current;
    private void toggle(String label,String key,boolean def){
        Switch s=new Switch(this);s.setText(label);s.setTextColor(ink);s.setTextSize(14);s.setPadding(0,dp(8),0,dp(8));s.setChecked(prefs.getBoolean(key,def));
        s.setOnCheckedChangeListener((v,checked)->{
            prefs.edit().putBoolean(key,checked).commit();
            getContentResolver().notifyChange(GlassConfigProvider.URI,null);
        });current.addView(s);
    }
    private void slider(String label,String key,int def,int min,int max){
        TextView caption=text("",13,false);current.addView(caption);
        SeekBar bar=new SeekBar(this);bar.setMax(max-min);bar.setProgress(Math.max(0,Math.min(max-min,prefs.getInt(key,def)-min)));current.addView(bar);
        Runnable update=()->caption.setText(label+"  ·  "+(bar.getProgress()+min));update.run();
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int progress,boolean fromUser){update.run();if(fromUser){prefs.edit().putInt(key,progress+min).apply();getContentResolver().notifyChange(GlassConfigProvider.URI,null);}}
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){prefs.edit().putInt(key,b.getProgress()+min).commit();getContentResolver().notifyChange(GlassConfigProvider.URI,null);}
        });
    }
    private void action(String label,Runnable run){
        Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextColor(blue);b.setOnClickListener(v->run.run());current.addView(b,new LinearLayout.LayoutParams(-1,dp(52)));
    }
    @Override protected void onPause(){super.onPause();getContentResolver().notifyChange(GlassConfigProvider.URI,null);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);prefs=getSharedPreferences("glass",0);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xFFF3F6FA);
        body=column();body.setPadding(dp(18),dp(28),dp(18),dp(32));scroll.addView(body);
        TextView heading=text("Liquid Glass",28,true);body.addView(heading);
        TextView version=text("V1.9.205.4  ·  图标轮廓与 Dock 留白修复",13,false);LinearLayout.LayoutParams v=new LinearLayout.LayoutParams(-1,-2);v.setMargins(0,dp(4),0,dp(20));body.addView(version,v);
        section("玻璃效果","独立壁纸模糊、折射、底色和高光。");
        toggle("启用桌面文件夹玻璃", "enabled",false);
        TextView presetInfo=text("推荐预设 · V1.9.205.4。仅在点击时应用，不会在升级时覆盖自定义参数。",12,false);current.addView(presetInfo);
        action("应用清透玻璃预设",()->{
            prefs.edit().putBoolean("enabled",true).putInt("alpha",0).putInt("tint",0).putInt("tint_strength",0)
                .putInt("glass_tint_opacity",0).putInt("custom_blur_dp",4).putInt("custom_blur_mode",1).putInt("refraction_percent",45)
                .putBoolean("highlight",false).putInt("highlight_strength",45).putInt("highlight_width",2)
                .putBoolean("directional_light",true).putBoolean("native_alpha_probe",false).putBoolean("real_refraction",true)
                .putInt("probe_mode",0).commit();recreate();
        });
        action("恢复液态玻璃推荐参数 (14 / 35 / 12 / 30)",()->{
            prefs.edit().putInt("custom_blur_dp",14).putInt("refraction_percent",35)
                .putInt("glass_tint_opacity",12).putInt("alpha",0).putInt("tint",0).putInt("tint_strength",0).putInt("highlight_strength",30)
                .putInt("custom_blur_mode",1).putBoolean("real_refraction",true)
                .putBoolean("highlight",false).putBoolean("enabled",true).commit();recreate();
        });
        toggle("桌面应用图标统一表面光学（首轮适配）","icon_optics_204",false);
        toggle("自适应液态玻璃 Dock（预览）","dock_glass_204",false);
        TextView dockInfo=text("Dock 保留 V1.9.204 已验证的位置；图标光学优先适配 Launcher BubbleTextView，若未命中请提供 Vector 日志。",12,false);current.addView(dockInfo);

        slider("玻璃底色不透明度 (%)","glass_tint_opacity",18,0,100);
        slider("自定义壁纸模糊半径 (dp)","custom_blur_dp",14,0,40);
        slider("折射强度 (%)","refraction_percent",35,0,100);
        TextView modeLabel=text("背景模糊模式",13,false);current.addView(modeLabel);
        Spinner blurMode=new Spinner(this);
        String[] blurModes={"关闭模糊 · 纯折射","自定义壁纸模糊 · 推荐","ColorOS 原生兼容"};
        blurMode.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,blurModes));
        blurMode.setSelection(Math.max(0,Math.min(2,prefs.getInt("custom_blur_mode",1))));current.addView(blurMode);
        blurMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int pos,long id){prefs.edit().putInt("custom_blur_mode",pos).commit();}
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        toggle("真实壁纸折射","real_refraction",false);
        toggle("清晰折射（开启保留更多壁纸细节；0dp 不额外模糊）","clear_refraction",false);
        // Legacy native_alpha_probe is deliberately disabled: blur and material opacity are separate.
        prefs.edit().putBoolean("native_alpha_probe",false).apply();
        TextView blurInfo=text("自定义模糊使用壁纸采样，不模糊应用图标。系统模糊开关不再强制开启；ColorOS 自身仍可能启用原生模糊。",12,false);current.addView(blurInfo);
        // V1.9.199: legacy soft-highlight controls removed from UI. Existing
        // preferences remain for compatibility; the unified surface owns reflection.
        section("统一表面光学 · V1.9.199","新版圆角反射独立于背景模糊；设置保存后会同步给 Launcher。");
        TextView surfaceLabel=text("表面反射模式",13,false);current.addView(surfaceLabel);
        Spinner surfaceSpinner=new Spinner(this);
        String[] surfaceModes={"0 · 关闭表面反射","1 · 静态圆角反射","2 · 动态圆角反射","3 · 动态反射 + 柔和暗边"};
        surfaceSpinner.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,surfaceModes));
        surfaceSpinner.setSelection(Math.max(0,Math.min(3,prefs.getInt("surface_mode_195",3))));current.addView(surfaceSpinner);
        surfaceSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int pos,long id){
                prefs.edit().putInt("surface_mode_195",pos).commit();
                getContentResolver().notifyChange(GlassConfigProvider.URI,null);
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        slider("表面反射强度 (%)","surface_strength_195",100,0,200);
        slider("高光宽度 (%)","surface_softness_195",100,50,200);
        TextView surfaceHint=text("这三项是真正的 APK 设置控件；与背景模糊半径、壁纸折射强度分别控制不同效果。",12,false);
        current.addView(surfaceHint);
        section("玻璃底色色调","选择玻璃底色的冷暖色调与叠加浓度；与模糊、折射、表面反射独立。");
        TextView tintLabel=text("色调",13,false);current.addView(tintLabel);
        Spinner tint=new Spinner(this);String[] names={"原生 · 推荐","冷白","浅灰","深色"};
        tint.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,names));
        tint.setSelection(Math.max(0,Math.min(3,prefs.getInt("tint",0))));current.addView(tint);
        tint.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int pos,long id){prefs.edit().putInt("tint",pos).commit();getContentResolver().notifyChange(GlassConfigProvider.URI,null);}
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        slider("底色色调强度 (%)","tint_strength",30,0,90);
        TextView tintInfo=text("底色色调强度为 0% 时不添加颜色；原生模式不添加色调。颜色变化需在玻璃底色不透明度和当前渲染路径允许的情况下观察。",12,false);current.addView(tintInfo);
        section("维护与状态","高级实验已从首页隐藏，保留必要的 Root 脚本更新与日志开关。");
        rootStatus=text("正在检查 Root 脚本与运行进程…",12,false);current.addView(rootStatus);
        action("检查并更新 Root 辅助脚本",()->RootHelperUpdater.runAsync(this,result->{
            rootStatus.setText(result);
            rootStatusHandler.removeCallbacks(rootStatusPoll);
            rootStatusHandler.postDelayed(rootStatusPoll,1000);
        }));
        TextView serviceHint=text("安装或更新脚本后请重启手机：旧版服务进程不会因文件被覆盖而自动重启。重启后配置快照应包含 25 个字段。",12,false);current.addView(serviceHint);
        toggle("详细诊断日志","diagnostics",false);
        TextView foot=text("提示：旧版校准、对照图和诊断模式不再显示在首页，已有数据不会自动删除。",12,false);body.addView(foot);
        setContentView(scroll);
        // V1.9.203: status checks are read-only; updates require explicit button tap.
    }
}
