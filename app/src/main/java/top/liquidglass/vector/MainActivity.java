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
        label("ColorOS Liquid Glass V1.9.2",23);
        label("原生模糊兼容性诊断版。所有桌面文件夹统一材质；此版本仅观察原生模糊状态，不强制调用隐藏方法。",14);
        Switch enabled=toggle("启用文件夹材质实验","enabled",false);
        label("透明度（数值越小越透明）",18);
        SeekBar normal=slider("统一文件夹不透明度","alpha",210,40,255);
        label("玻璃色调（实验功能）",18);
        String[] options={"原生（推荐）","冷白","浅灰","深色"};
        Spinner tint=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options);
        tint.setAdapter(adapter);tint.setSelection(Math.max(0,Math.min(3,prefs.getInt("tint",0))));root.addView(tint);
        tint.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p,android.view.View v,int pos,long id){prefs.edit().putInt("tint",pos).apply();}
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        SeekBar tintStrength=slider("色调强度","tint_strength",30,0,90);
        Switch highlight=toggle("柔和高光描边（默认关闭）","highlight",false);
        Switch diag=toggle("保留诊断日志","diagnostics",true);
        Button reset=new Button(this);reset.setText("恢复原生外观并重置设置");root.addView(reset);
        reset.setOnClickListener(v->{prefs.edit().putBoolean("enabled",false).putInt("alpha",210)
            .putInt("tint",0).putInt("tint_strength",30).putBoolean("highlight",false).apply();
            enabled.setChecked(false);normal.setProgress(170);tint.setSelection(0);tintStrength.setProgress(30);highlight.setChecked(false);
            Toast.makeText(this,"已关闭实验；若旧背景未恢复，请重启桌面",Toast.LENGTH_LONG).show();});
        label("提示：色调使用 Drawable 颜色滤镜，部分主题可能不兼容。关闭实验后若外观未立即恢复，可重启桌面。新增被动诊断原生模糊初始化与调用；不会强制启用 ColorOS 隐藏模糊功能。",13);
        setContentView(scroll);
    }
}
