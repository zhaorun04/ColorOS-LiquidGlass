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
        label("ColorOS Liquid Glass V1.9.62",23);
        label("预设与稳定性增强版：保留已验证的光学轮廓，新增预设备份恢复与原生模糊联动。",14);
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
        label("V1.9.28 的圆角校正已保留；本版不改变圆角几何。",14);
        label("原生模糊强度暂不可调：旧滑块误修改圆角半径，已停用。",14);
        label("V1.9.62：保留V1.9.45光学外观；保留动态映射诊断；详细诊断开启时仅一次尝试读取壁纸原图3个像素，验证读取能力；不绘制、不保存图像、不启用真实折射。",13);
        label("模糊可见度通过 Drawable Alpha 调节，不等于真正改变模糊半径。",13);
        label("原生模糊背景透明度",18);
        final Switch nativeAlpha=toggle("透明度滑块同时控制原生模糊背景","native_alpha_probe",true);
        label("已在 V1.9.29 真机验证有效。控制模糊层可见度，不是模糊半径。",13);
        label("一键视觉预设（首次应用时保存当前设置）",18);
        final String[] presetKeys={"enabled","alpha","tint","tint_strength","highlight","highlight_strength","highlight_width","directional_light","probe_mode","native_alpha_probe","native_blur_experiment_enabled","native_blur_experiment"};
        final boolean[] presetBool={true,false,false,false,true,false,false,true,false,true,true,false};
        final Runnable savePresetBackup=()->{
            if(prefs.getBoolean("preset_backup_valid",false))return;
            SharedPreferences.Editor e=prefs.edit();
            for(int i=0;i<presetKeys.length;i++){
                String k=presetKeys[i];
                if(presetBool[i])e.putBoolean("preset_backup_"+k,prefs.getBoolean(k,false));
                else e.putInt("preset_backup_"+k,prefs.getInt(k,0));
            }
            e.putBoolean("preset_backup_valid",true).commit();
        };
        final Switch[] nativeExpUi=new Switch[1];
        final Runnable syncPresetUi=()->{
            enabled.setChecked(prefs.getBoolean("enabled",false));
            normal.setProgress(Math.max(0,prefs.getInt("alpha",210)-40));
            tint.setSelection(prefs.getInt("tint",0));
            tintStrength.setProgress(prefs.getInt("tint_strength",30));
            highlight.setChecked(prefs.getBoolean("highlight",false));
            highlightStrength.setProgress(prefs.getInt("highlight_strength",35));
            highlightWidth.setProgress(Math.max(0,prefs.getInt("highlight_width",2)-1));
            directional.setChecked(prefs.getBoolean("directional_light",true));
            probe.setSelection(prefs.getInt("probe_mode",0));
            nativeAlpha.setChecked(prefs.getBoolean("native_alpha_probe",true));
            if(nativeExpUi[0]!=null)nativeExpUi[0].setChecked(prefs.getBoolean("native_blur_experiment_enabled",false));
        };
        Button clearPreset=new Button(this);clearPreset.setText("应用清透液态玻璃预设");root.addView(clearPreset);
        clearPreset.setOnClickListener(v->{
            savePresetBackup.run();
            prefs.edit().putBoolean("enabled",true).putInt("alpha",95)
                .putInt("tint",0).putInt("tint_strength",20)
                .putBoolean("highlight",true).putInt("highlight_strength",55)
                .putInt("highlight_width",2).putBoolean("directional_light",true)
                .putInt("probe_mode",0).putBoolean("native_alpha_probe",true).apply();
            enabled.setChecked(true);normal.setProgress(95-40);tint.setSelection(0);
            tintStrength.setProgress(20);highlight.setChecked(true);
            highlightStrength.setProgress(55);highlightWidth.setProgress(1);
            directional.setChecked(true);probe.setSelection(0);nativeAlpha.setChecked(true);
            Toast.makeText(this,"已应用清透预设；原生模糊开关保持原设置，返回桌面查看效果",Toast.LENGTH_LONG).show();
        });
        Button restorePreset=new Button(this);restorePreset.setText("恢复应用预设前的设置");root.addView(restorePreset);
        restorePreset.setOnClickListener(v->{
            if(!prefs.getBoolean("preset_backup_valid",false)){
                Toast.makeText(this,"暂无预设前的设置备份",Toast.LENGTH_SHORT).show();return;
            }
            SharedPreferences.Editor e=prefs.edit();
            for(int i=0;i<presetKeys.length;i++){
                String k=presetKeys[i];
                if(presetBool[i])e.putBoolean(k,prefs.getBoolean("preset_backup_"+k,false));
                else e.putInt(k,prefs.getInt("preset_backup_"+k,0));
                e.remove("preset_backup_"+k);
            }
            e.remove("preset_backup_valid").commit();
            syncPresetUi.run();
            Toast.makeText(this,"已恢复预设前设置；若原生模糊状态改变请重启桌面",Toast.LENGTH_LONG).show();
        });
        label("预设：背景不透明度 95/255、原生色调、高光 55/100、宽度 2 px。原生模糊默认保留当前开关。",13);
        label("ColorOS 原生模糊隔离实验（切换后重启桌面生效）",18);
        final Switch nativeExp=toggle("实验 A：仅将材质模糊开关返回值设为 true（风险：桌面异常）","native_blur_experiment_enabled",false);
        Button presetWithBlur=new Button(this);presetWithBlur.setText("应用清透预设并开启原生模糊");root.addView(presetWithBlur);
        presetWithBlur.setOnClickListener(v->{
            clearPreset.performClick();
            nativeExp.setChecked(true);
            prefs.edit().putBoolean("native_blur_experiment_enabled",true).putInt("native_blur_experiment",1).commit();
            Toast.makeText(this,"已开启原生模糊实验；请重启桌面使其生效",Toast.LENGTH_LONG).show();
        });
        nativeExpUi[0]=nativeExp;
        // Store the integer mode consumed by the provider, not the checkbox key.
        nativeExp.setOnCheckedChangeListener((v,on)->prefs.edit().putBoolean("native_blur_experiment_enabled",on).putInt("native_blur_experiment",on?1:0).apply());
        label("实验仅修改 Launcher 内部一个已确认的布尔访问器，不修改其他模糊条件。出现闪退或显示异常时关闭此开关并重启桌面。",13);
        Switch diag=toggle("详细诊断日志（排查时开启）","diagnostics",false);
        toggle("旧版文件夹内对照图（建议关闭）","wallpaper_compare",false);
        label("V1.9.62 建议关闭旧版对照，使用独立校准页面；不会遮挡桌面文件夹图标。",13);
        Button calibrate=new Button(this);calibrate.setText("打开独立壁纸坐标校准页面（实验）");root.addView(calibrate);
        calibrate.setOnClickListener(v->startActivity(new android.content.Intent(this,WallpaperCalibrationActivity.class)));
        label("校准页面在壁纸前显示透明背景和两个原图采样块，可调整采样屏幕位置、壁纸偏移与候选模型。系统可能对独立 Activity 使用不同的壁纸偏移；因此只能目视比较，不能自动验证 Launcher 实际变换。",13);
        Button reset=new Button(this);reset.setText("恢复原生外观并重置设置");root.addView(reset);
        reset.setOnClickListener(v->{prefs.edit().putBoolean("enabled",false).putInt("alpha",210)
            .putInt("tint",0).putInt("tint_strength",30).putBoolean("highlight",false).putInt("highlight_strength",35).putInt("highlight_width",2).putBoolean("directional_light",true).putInt("probe_mode",0).putInt("native_blur_experiment",0).putInt("blur_radius_percent",100).putBoolean("native_blur_experiment_enabled",false).putBoolean("native_alpha_probe",false).putBoolean("wallpaper_compare",false).apply();
            nativeExp.setChecked(false);nativeAlpha.setChecked(false);enabled.setChecked(false);normal.setProgress(170);tint.setSelection(0);tintStrength.setProgress(30);highlight.setChecked(false);highlightStrength.setProgress(35);highlightWidth.setProgress(1);directional.setChecked(true);probe.setSelection(0);
            Toast.makeText(this,"已关闭实验；若旧背景未恢复，请重启桌面",Toast.LENGTH_LONG).show();});
        label("提示：色调现在使用原生背景 Alpha 遮罩；原生模式下色调强度不可调。关闭实验后若外观未立即恢复，可重启桌面。开启诊断日志后，返回桌面观察文件夹并导出 Vector 日志，搜索 BLUR_PROBE 与 NATIVE_BLUR_CALL。探测不会主动启用模糊。",13);
        setContentView(scroll);
    }
}
