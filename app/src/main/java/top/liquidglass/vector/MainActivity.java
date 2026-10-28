package top.liquidglass.vector;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.content.SharedPreferences;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Switch;
import android.widget.SeekBar;

public final class MainActivity extends Activity {
    private SharedPreferences prefs;
    private TextView opacity;
    private TextView line(LinearLayout root, String s, int size) {
        TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(Color.rgb(35,45,55));
        t.setPadding(0,16,0,16); root.addView(t); return t;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences("glass",0);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(42,55,42,30); root.setBackgroundColor(Color.rgb(247,249,252));
        line(root,"ColorOS Liquid Glass  V1.7",23);
        line(root,"文件夹原生背景透明度实验（不包含实时模糊）。修改后返回桌面，重新打开文件夹或重启桌面以观察变化。",14);
        Switch enabled=new Switch(this); enabled.setText("启用原生背景透明度实验"); enabled.setTextSize(16);
        enabled.setChecked(prefs.getBoolean("enabled",false)); root.addView(enabled);
        enabled.setOnCheckedChangeListener((b,on)->prefs.edit().putBoolean("enabled",on).apply());
        opacity=line(root,"背景不透明度："+prefs.getInt("alpha",210)+" / 255",16);
        SeekBar seek=new SeekBar(this); seek.setMax(255); seek.setProgress(prefs.getInt("alpha",210)); root.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int v,boolean fromUser){ int a=Math.max(40,v); opacity.setText("背景不透明度："+a+" / 255"); if(fromUser) prefs.edit().putInt("alpha",a).apply(); }
            public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){}
        });
        Switch diag=new Switch(this); diag.setText("保留诊断日志"); diag.setChecked(prefs.getBoolean("diagnostics",true)); root.addView(diag);
        diag.setOnCheckedChangeListener((b,on)->prefs.edit().putBoolean("diagnostics",on).apply());
        TextView reset=line(root,"恢复默认：关闭实验，背景不透明度 210",15);
        reset.setOnClickListener(v->{prefs.edit().putBoolean("enabled",false).putInt("alpha",210).apply(); enabled.setChecked(false); seek.setProgress(210);});
        line(root,"说明：不会启用旧版白色描边。当前仅针对可获取到的背景 Drawable 生效；若原生背景采用 Canvas 自绘，可能仍无视觉变化。",13);
        setContentView(root);
    }
}
