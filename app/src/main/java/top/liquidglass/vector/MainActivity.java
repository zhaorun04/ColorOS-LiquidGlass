package top.liquidglass.vector;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.*;

/** V1.9.101: clean settings surface; legacy preferences remain intact. */
public final class MainActivity extends Activity {
    private SharedPreferences prefs;
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
        s.setOnCheckedChangeListener((v,checked)->prefs.edit().putBoolean(key,checked).commit());current.addView(s);
    }
    private void slider(String label,String key,int def,int min,int max){
        TextView caption=text("",13,false);current.addView(caption);
        SeekBar bar=new SeekBar(this);bar.setMax(max-min);bar.setProgress(Math.max(0,Math.min(max-min,prefs.getInt(key,def)-min)));current.addView(bar);
        Runnable update=()->caption.setText(label+"  ·  "+(bar.getProgress()+min));update.run();
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int progress,boolean fromUser){update.run();if(fromUser)prefs.edit().putInt(key,progress+min).apply();}
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){prefs.edit().putInt(key,b.getProgress()+min).commit();}
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
        TextView version=text("V1.9.118  ·  ColorOS 桌面玻璃材质",13,false);LinearLayout.LayoutParams v=new LinearLayout.LayoutParams(-1,-2);v.setMargins(0,dp(4),0,dp(20));body.addView(version,v);
        section("玻璃效果","独立壁纸模糊、折射、底色和高光。");
        toggle("启用桌面文件夹玻璃", "enabled",false);
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
        // Legacy native_alpha_probe is deliberately disabled: blur and material opacity are separate.
        prefs.edit().putBoolean("native_alpha_probe",false).apply();
        TextView blurInfo=text("自定义模糊使用壁纸采样，不模糊应用图标。系统模糊开关不再强制开启；ColorOS 自身仍可能启用原生模糊。",12,false);current.addView(blurInfo);
        section("柔和高光","静态圆角高光与光学轮廓；不改变已验证的描边几何。");
        toggle("启用柔和高光描边","highlight",false);
        slider("高光强度 (%)","highlight_strength",30,0,100);
        slider("描边宽度（像素）","highlight_width",2,1,5);
        toggle("方向性反射","directional_light",true);
        section("视觉风格","保留必要的色调控制；移除旧版诊断和无效实验入口。");
        TextView tintLabel=text("色调",13,false);current.addView(tintLabel);
        Spinner tint=new Spinner(this);String[] names={"原生 · 推荐","冷白","浅灰","深色"};
        tint.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,names));
        tint.setSelection(Math.max(0,Math.min(3,prefs.getInt("tint",0))));current.addView(tint);
        tint.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int pos,long id){prefs.edit().putInt("tint",pos).apply();}
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        slider("色调强度","tint_strength",30,0,90);
        action("应用清透玻璃预设",()->{
            prefs.edit().putBoolean("enabled",true).putInt("alpha",95).putInt("tint",0).putInt("tint_strength",20)
                .putBoolean("highlight",true).putInt("highlight_strength",55).putInt("highlight_width",2)
                .putBoolean("directional_light",true).putBoolean("native_alpha_probe",false).putBoolean("real_refraction",true)
                .putInt("probe_mode",0).commit();recreate();
        });
        action("恢复液态玻璃推荐参数 (14 / 35 / 18 / 30)",()->{
            prefs.edit().putInt("custom_blur_dp",14).putInt("refraction_percent",35)
                .putInt("glass_tint_opacity",18).putInt("highlight_strength",30)
                .putInt("custom_blur_mode",1).putBoolean("real_refraction",true)
                .putBoolean("highlight",true).putBoolean("enabled",true).commit();recreate();
        });
        section("维护与状态","高级实验已从首页隐藏，保留必要的 Root 脚本更新与日志开关。");
        TextView status=text("检查 Root 辅助脚本中…",12,false);current.addView(status);
        action("检查并更新 Root 辅助脚本",()->RootHelperUpdater.runAsync(this,status::setText));
        toggle("详细诊断日志","diagnostics",false);
        TextView foot=text("提示：旧版校准、对照图和诊断模式不再显示在首页，已有数据不会自动删除。",12,false);body.addView(foot);
        setContentView(scroll);
        RootHelperUpdater.runAsync(this,status::setText);
    }
}
