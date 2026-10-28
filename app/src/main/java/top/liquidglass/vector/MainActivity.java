package top.liquidglass.vector;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.content.SharedPreferences;
import android.widget.*;

public final class MainActivity extends Activity {
    private SharedPreferences prefs;
    private LinearLayout root;
    private TextView label(String s, int size) {
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.rgb(35,45,55));
        t.setPadding(0,16,0,12);root.addView(t);return t;
    }
    private Switch toggle(String title,String key,boolean def) {
        Switch s=new Switch(this);s.setText(title);s.setTextSize(16);s.setPadding(0,12,0,12);
        s.setChecked(prefs.getBoolean(key,def));root.addView(s);
        s.setOnCheckedChangeListener((v,on)->prefs.edit().putBoolean(key,on).apply());return s;
    }
    private SeekBar slider(String title,String key,int def,int min,int max) {
        TextView t=label(title+": "+prefs.getInt(key,def)+" / "+max,15);
        SeekBar bar=new SeekBar(this);bar.setMax(max-min);bar.setProgress(Math.max(0,Math.min(max-min,prefs.getInt(key,def)-min)));
        root.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean from){int value=p+min;t.setText(title+": "+value+" / "+max);if(from)prefs.edit().putInt(key,value).apply();}
            public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){}
        });return bar;
    }
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);prefs=getSharedPreferences("glass",0);
        ScrollView scroll=new ScrollView(this);root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36,32,36,36);root.setBackgroundColor(Color.rgb(247,249,252));scroll.addView(root);
        label("ColorOS Liquid Glass V1.9.15",23);
        label("保留 V1.9.10 外观；新增 ColorOS 原生模糊被动探测，不会强制开启隐藏模糊或模糊应用图标。",14);
        Switch enabled=toggle("启用文件夹材质实验","enabled",false);
        label("透明度（数值越小越透明）",18);
        SeekBar normal=slider("统一文件夹不透明度","alpha",210,40,255);
        label("玻璃色调（实验功能）",18);
        String[] options={"原生（推荐）","冷白","浅灰","深色"};
        Spinner tint=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options);
        tint.setAdapter(adapter);tint.setSelection(Math.max(0,Math.min(3,prefs.getInt("tint",0))));root.addView(tint);
        SeekBar tintStrength=slider("色调强度","tint_strength",30,0,90);
        tintStrength.setEnabled(prefs.getInt("tint",0)!=0);
        tint.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p,android.view.View v,int pos,long id){prefs.edit().putInt("tint",pos).apply();tintStrength.setEnabled(pos!=0);}
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        Switch highlight=toggle("柔和高光描边（默认关闭）","highlight",false);
        SeekBar highlightStrength=slider("柔和高光强度","highlight_strength",35,0,100);
        SeekBar highlightWidth=slider("高光内扩宽度（像素）","highlight_width",2,1,5);
        Switch directional=toggle("方向性反射（左上亮、右下柔）","directional_light",true);
        label("背景图层诊断模式（切换后返回桌面等待约 2 秒）",18);
        String[] probeOptions={"0 · 正常材质","1 · 仅原生背景（关闭高光与叠色）","2 · 红色轮廓对照（仅用于短时测试）","3 · 仅色调（关闭高光）"};
        Spinner probe=new Spinner(this);
        probe.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,probeOptions));
        probe.setSelection(Math.max(0,Math.min(3,prefs.getInt("probe_mode",0))));root.addView(probe);
        probe.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> parent,android.view.View view,int pos,long id){prefs.edit().putInt("probe_mode",pos).apply();}
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        label("建议在模式 0 测试高光宽度 1～5 与方向性反射。模式 2 的红线是几何对照，不是正式外观。",13);
        Switch diag=toggle("保留诊断日志","diagnostics",true);
        Button reset=new Button(this);reset.setText("恢复原生外观并重置设置");root.addView(reset);
        reset.setOnClickListener(v->{prefs.edit().putBoolean("enabled",false).putInt("alpha",210)
            .putInt("tint",0).putInt("tint_strength",30).putBoolean("highlight",false).putInt("highlight_strength",35).putInt("highlight_width",2).putBoolean("directional_light",true).putInt("probe_mode",0).apply();
            enabled.setChecked(false);normal.setProgress(170);tint.setSelection(0);tintStrength.setProgress(30);highlight.setChecked(false);highlightStrength.setProgress(35);highlightWidth.setProgress(1);directional.setChecked(true);probe.setSelection(0);
            Toast.makeText(this,"已关闭实验；若旧背景未恢复，请重启桌面",Toast.LENGTH_LONG).show();});
        label("提示：色调现在使用原生背景 Alpha 遮罩；原生模式下色调强度不可调。关闭实验后若外观未立即恢复，可重启桌面。开启诊断日志后，返回桌面观察文件夹并导出 Vector 日志，搜索 BLUR_PROBE 与 NATIVE_BLUR_CALL。探测不会主动启用模糊。",13);
        setContentView(scroll);
    }
}
