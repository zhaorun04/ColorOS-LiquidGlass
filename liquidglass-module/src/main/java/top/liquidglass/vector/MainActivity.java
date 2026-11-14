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

/** V138: material-first settings, with legacy preference keys retained for renderer compatibility. */
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

    // V203: UI commit -> immediate direct snapshot broadcast; Root is asynchronous fallback.
    private final Handler publishHandler194=new Handler(Looper.getMainLooper());
    private final Runnable rootSignal194=()->new Thread(()->{
        String expected=ConfigPublisher202.snapshot(getApplicationContext());
        boolean ok=ConfigPublisher202.publishVerified(expected);
        boolean materialOk=ConfigPublisher202.publishMaterialVerified(
            ConfigPublisher202.materialSnapshot(getApplicationContext()));
        android.util.Log.i("LiquidGlass203","CONFIG_203_BACKGROUND_PUBLISH verified="+ok+
            " material230="+materialOk);
    },"LiquidGlass-BackgroundPublish203").start();
    private void notifyConfigChanged128(){
        getContentResolver().notifyChange(GlassConfigProvider.URI,null);
        String snapshot=ConfigPublisher202.snapshot(getApplicationContext());
        try{
            android.content.Intent i=new android.content.Intent("top.liquidglass.vector.CONFIG_CHANGED_128");
            i.setPackage("com.android.launcher");
            i.putExtra("lg_snapshot_203",snapshot);
            i.putExtra(MaterialConfig230.EXTRA,ConfigPublisher202.materialSnapshot(getApplicationContext()));
            sendBroadcast(i);
            android.util.Log.i("LiquidGlass203","CONFIG_203_DIRECT_SENT fields="+snapshot.split("\\|",-1).length);
        }catch(Throwable t){android.util.Log.w("LiquidGlass203","CONFIG_203_DIRECT_FAILED",t);}
        publishHandler194.removeCallbacks(rootSignal194);
        publishHandler194.postDelayed(rootSignal194,900L);
    }
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
    // Presentation-only category headers: never read/write a preference.
    private void category229(String title,String subtitle){
        TextView heading=text(title,21,true);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);
        hp.setMargins(dp(2),dp(18),dp(2),dp(5));body.addView(heading,hp);
        TextView note=text(subtitle,12,false);
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);
        np.setMargins(dp(2),0,dp(2),dp(14));body.addView(note,np);
    }

    private void toggle(String label,String key,boolean def){
        Switch s=new Switch(this);s.setText(label);s.setTextColor(ink);s.setTextSize(14);s.setPadding(0,dp(8),0,dp(8));s.setChecked(prefs.getBoolean(key,def));
        s.setOnCheckedChangeListener((v,checked)->{
            putBooleanIfChanged193(key,checked);
        });current.addView(s);
    }
    // V288.27: apply the complete, device-verified Kyant preset atomically.
    // No UI success is reported until Root has completed and values are read back.
    private static final String KYANT_FOLDER_KEY="liquidglass_kyant_persistent_probe_2884";
    private static final String KYANT_DOCK_KEY="liquidglass_kyant_dock_28827";
    private static final String[][] KYANT_SAFE_PRESET_28827={
        {"liquidglass_kyant_pipeline_ab_28819","-1"},
        {"liquidglass_kyant_display_isolation_28811","0"},
        {"liquidglass_kyant_native_layer_28812","0"},
        {"liquidglass_kyant_native_bridge_2888","0"},
        {"liquidglass_kyant_motion_ab_2882","0"},
        {"liquidglass_kyant_motion_ab_2884","0"},
        {"liquidglass_kyant_native_compare_2887","0"},
        {"liquidglass_kyant_legacy_sync_ab_2886","0"},
        {"liquidglass_kyant_render_ab_2883","0"},
        {"liquidglass_kyant_bridge_sync_2889","0"},
        {"liquidglass_kyant_layer_trace_28817","0"},
        {"liquidglass_kyant_right_start_trace_28813","0"},
        {"liquidglass_kyant_frame_sync_ab_28822","0"},
        {"liquidglass_kyant_native_coordinate_ab_28826","0"},
        {"liquidglass_kyant_rebound_layer_ab_28818","0"},
        {"liquidglass_kyant_matrix_trace_28814","0"},
        {"liquidglass_kyant_frame_trace_28815","0"},
        {"liquidglass_kyant_release_trace_28821","0"},
        {"liquidglass_kyant_right_redraw_28816","0"}
    };
    private static String rootCommand28827(String command) throws Exception {
        Process process=new ProcessBuilder("su","-c",command).redirectErrorStream(true).start();
        java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
        Thread reader=new Thread(()->{try(java.io.InputStream in=process.getInputStream()){
            byte[] bytes=new byte[1024];int count;
            while((count=in.read(bytes))!=-1 && output.size()<32768)output.write(bytes,0,count);
        }catch(Exception ignored){}});
        reader.start();
        boolean finished=process.waitFor(12,java.util.concurrent.TimeUnit.SECONDS);
        if(!finished){process.destroyForcibly();throw new Exception("Root command timeout");}
        reader.join(1000);
        if(process.exitValue()!=0)throw new Exception("Root command failed: "+output.toString());
        return output.toString().trim();
    }
    // Both toggles share the SAME validated render preset, but persist separately.
    private void kyantGlobalSwitch28827(String caption,String key){
        final Switch control=new Switch(this);
        control.setText(caption);control.setTextColor(ink);control.setTextSize(14);
        control.setPadding(0,dp(8),0,dp(8));control.setEnabled(false);
        current.addView(control);
        final TextView state=text("正在读取实际开关…",12,false);current.addView(state);
        new Thread(()->{
            boolean enabled=false,success=false;
            try {enabled="1".equals(rootCommand28827("settings get global "+key));success=true;}
            catch(Exception ignored){}
            final boolean initial=enabled,readOk=success;
            runOnUiThread(()->{
                if(!readOk){state.setText("Root 读取失败，请检查 KernelSU 授权");return;}
                control.setChecked(initial);control.setEnabled(true);
                state.setText(initial?"已开启 · 当前状态已读取":"已关闭 · 保留原有容器玻璃效果");
                control.setOnCheckedChangeListener((button,wanted)->{
                    control.setEnabled(false);state.setText("正在应用并核对光学配置…");
                    new Thread(()->{
                        boolean verified=false;
                        try {
                            StringBuilder script=new StringBuilder("set -e\n");
                            if(wanted)for(String[] entry:KYANT_SAFE_PRESET_28827)
                                script.append("settings put global ").append(entry[0]).append(' ').append(entry[1]).append('\n');
                            script.append("settings put global ").append(key).append(wanted?" 1\n":" 0\n");
                            rootCommand28827(script.toString());
                            // V288.36: verify the complete preset in ONE root
                            // process instead of launching su separately for each key.
                            StringBuilder verifyScript=new StringBuilder("set -e\n");
                            verifyScript.append("[ \"$(settings get global ").append(key)
                                .append(")\" = \"").append(wanted?"1":"0").append("\" ]\n");
                            if(wanted)for(String[] entry:KYANT_SAFE_PRESET_28827){
                                verifyScript.append("[ \"$(settings get global ").append(entry[0])
                                    .append(")\" = \"").append(entry[1]).append("\" ]\n");
                            }
                            rootCommand28827(verifyScript.toString());
                            verified=true;
                        }catch(Exception ignored){}
                        final boolean ok=verified;
                        runOnUiThread(()->{
                            if(!ok){control.setOnCheckedChangeListener(null);control.setChecked(!wanted);
                                state.setText("写入或核对失败，请重新打开页面检查");control.setEnabled(false);return;}
                            state.setText(wanted?"已应用稳定光学配置 · 重启 Launcher 后生效":"已关闭 · 重启 Launcher 后恢复原有玻璃");
                            control.setEnabled(true);
                            Toast.makeText(this,"配置已核对，建议通过 Vector 重启 Launcher",Toast.LENGTH_LONG).show();
                        });
                    },"KyantSwitch28827").start();
                });
            });
        },"KyantRead28827").start();
    }
    // Direct Settings.Global writes, verified after each change. Launcher observes
    // these keys and both Compose optical hosts update without a process restart.
    private void kyantGlobalSlider28827(String caption,String key,int fallback,int low,int high){
        final TextView label=text(caption+" · 正在读取",13,false);current.addView(label);
        final SeekBar seek=new SeekBar(this);
        seek.setMax(high-low);seek.setEnabled(false);current.addView(seek);
        new Thread(()->{
            int n=fallback; boolean ready=false;
            try {n=Integer.parseInt(rootCommand28827("settings get global "+key));ready=true;}
            catch(Exception e){
                // A new global has not yet been created: use renderer's actual default.
                ready=true;
            }
            final int initial=Math.max(low,Math.min(high,n)); final boolean ok=ready;
            runOnUiThread(()->{
                if(!ok){label.setText(caption+" · Root 不可用");return;}
                seek.setProgress(initial-low);seek.setEnabled(true);
                label.setText(caption+" · "+initial);
                seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                    public void onProgressChanged(SeekBar bar,int progress,boolean user){
                        if(user)label.setText(caption+" · "+(progress+low)+"（松手应用）");
                    }
                    public void onStartTrackingTouch(SeekBar bar){}
                    public void onStopTrackingTouch(SeekBar bar){
                        final int value=bar.getProgress()+low;
                        bar.setEnabled(false);
                        new Thread(()->{
                            boolean applied=false;
                            try {
                                rootCommand28827("settings put global "+key+" "+value);
                                applied=String.valueOf(value).equals(rootCommand28827("settings get global "+key));
                            }catch(Exception ignored){}
                            final boolean verified=applied;
                            runOnUiThread(()->{
                                bar.setEnabled(true);
                                label.setText(caption+" · "+value+(verified?"（已同步）":"（写入失败）"));
                            });
                        },"KyantMaterial28827").start();
                    }
                });
            });
        },"KyantMaterialRead28827").start();
    }
    // V288.30: new large-square sampling A/B belongs with the real Kyant
    // optical controls, but does NOT overwrite the known-good optical preset.
    // The renderer reads this setting at host creation; restart Launcher to test.
    private void kyantLargeSquareSwitch28830(){
        final String key="liquidglass_kyant_large_square_source_28830";
        final Switch control=new Switch(this);
        control.setText("大方形文件夹 · 连续壁纸采样（实验）");
        control.setTextColor(ink);control.setTextSize(14);
        control.setPadding(0,dp(8),0,dp(8));control.setEnabled(false);
        current.addView(control);
        final TextView status=text("读取大文件夹采样模式…",12,false);
        current.addView(status);
        new Thread(()->{
            boolean enabled=true,success=false;
            try {
                String value=rootCommand28827("settings get global "+key);
                // Missing setting means renderer default: enabled.
                enabled=!"0".equals(value);
                success=true;
            }catch(Exception ignored){}
            final boolean initial=enabled,ok=success;
            runOnUiThread(()->{
                if(!ok){status.setText("Root 状态读取失败");return;}
                control.setChecked(initial);control.setEnabled(true);
                status.setText(initial?"连续浮点采样 · 重启 Launcher 后生效":"V288.29 原裁剪采样 · 对照模式");
                control.setOnCheckedChangeListener((v,wanted)->{
                    control.setEnabled(false);
                    status.setText("正在写入并校验…");
                    new Thread(()->{
                        boolean verified=false;
                        try {
                            rootCommand28827("settings put global "+key+(wanted?" 1":" 0"));
                            verified=(wanted?"1":"0").equals(rootCommand28827("settings get global "+key));
                        }catch(Exception ignored){}
                        final boolean saved=verified;
                        runOnUiThread(()->{
                            if(!saved){
                                control.setOnCheckedChangeListener(null);
                                control.setChecked(!wanted);
                                control.setEnabled(false);
                                status.setText("写入失败，请重新进入页面核对");
                            }else{
                                control.setEnabled(true);
                                status.setText(wanted?"已设连续采样 · 重启 Launcher 生效":"已设原裁剪对照 · 重启 Launcher 生效");
                            }
                        });
                    },"KyantLargeSquare28830").start();
                });
            });
        },"KyantLargeSquareRead28830").start();
    }
    private void kyantSettingsPanel28827(){
        kyantGlobalSwitch28827("桌面文件夹 · Kyant0 光学引擎",KYANT_FOLDER_KEY);
        kyantGlobalSwitch28827("Dock 胶囊 · Kyant0 光学引擎",KYANT_DOCK_KEY);
        current.addView(text("两个 Kyant 开关独立。Dock Kyant 需要上方「启用 Dock 玻璃」主开关处于开启状态；不会擅自打开已手动关闭的 Dock。任一 Kyant 开启都会自动应用稳定光学路径并关闭冲突实验。",12,false));
        current.addView(text("Kyant0 原版五项光学控制（保留原有高光附加项）：圆角默认跟随系统容器，100% 为原始圆角；色差 0=关闭、1=开启。",12,false));
        kyantGlobalSlider28827("圆角半径 · 系统圆角比例 (%)", "liquidglass_kyant_corner_scale_288161",100,0,150);
        kyantGlobalSlider28827("模糊半径 (dp)", "liquidglass_kyant_optical_blur_28827",0,0,20);
        kyantGlobalSlider28827("折射高度 · 容器短边比例 (%)", "liquidglass_kyant_refraction_height_288161",10,0,50);
        kyantGlobalSlider28827("折射强度 (%)", "liquidglass_kyant_optical_strength_28827",100,0,200);
        kyantGlobalSlider28827("色差效果 (0 关闭 / 1 开启)", "liquidglass_kyant_chromatic_288161",0,0,1);
        kyantGlobalSlider28827("附加 · 高光透明度 (%)", "liquidglass_kyant_optical_highlight_28827",100,0,100);
        current.addView(text("默认值与 V288.160 一致。修改后由 Launcher 监听全局配置并更新；圆角调整仅改变 Kyant 光学遮罩，不改变 OEM 文件夹和 Dock 的实际布局几何。",12,false));
        kyantLargeSquareSwitch28830();
        current.addView(text("此项仅作用于大方形文件夹；小文件夹、竖条、横条及 Dock 不受影响。连续采样与旧版裁剪可对照测试，切换后请重启 Launcher。",12,false));
    }
    private void slider(String label,String key,int def,int min,int max){
        TextView caption=text("",13,false);current.addView(caption);
        SeekBar bar=new SeekBar(this);bar.setMax(max-min);bar.setProgress(Math.max(0,Math.min(max-min,prefs.getInt(key,def)-min)));current.addView(bar);
        Runnable update=()->caption.setText(label+"  ·  "+(bar.getProgress()+min));update.run();
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int progress,boolean fromUser){update.run();}
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){putIntIfChanged193(key,b.getProgress()+min);}
        });
    }
    // V230 opt-in material: throttle direct Launcher updates during dragging to
    // ~20 fps; final finger release is always committed. The existing 33 LG1
    // settings remain unchanged and the Root publication is still debounced.
    private void materialSlider230(String label,String key,int def,int min,int max){
        TextView caption=text("",13,false);current.addView(caption);
        SeekBar bar=new SeekBar(this);bar.setMax(max-min);
        bar.setProgress(Math.max(0,Math.min(max-min,prefs.getInt(key,def)-min)));
        current.addView(bar);
        Runnable update=()->caption.setText(label+"  ·  "+(bar.getProgress()+min));
        update.run();
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            long lastSent=0;
            public void onProgressChanged(SeekBar b,int progress,boolean fromUser){
                update.run();
                if(!fromUser)return;
                long now=android.os.SystemClock.uptimeMillis();
                if(now-lastSent>=50L){
                    lastSent=now;
                    putIntIfChanged193(key,progress+min);
                }
            }
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){
                putIntIfChanged193(key,b.getProgress()+min);
            }
        });
    }
    // V95: decimal dp is persisted as tenths to avoid SharedPreferences float/int migration issues.
    private void microBlurSlider95(){
        final String key="custom_blur_tenths_95";
        int legacy=prefs.getInt("custom_blur_dp",3);
        int initial=Math.max(0,Math.min(400,prefs.getInt(key,legacy*10)));
        TextView caption=text("",13,false);current.addView(caption);
        SeekBar bar=new SeekBar(this);bar.setMax(400);bar.setProgress(initial);current.addView(bar);
        Runnable update=()->caption.setText(String.format(java.util.Locale.US,"桌面文件夹背景模糊半径  ·  %.1f dp",bar.getProgress()/10f));update.run();
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int progress,boolean fromUser){update.run();}
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){updateMicroBlur193(b.getProgress());}
        });
    }
    private void updateMicroBlur193(int progress){
        int dp=Math.round(progress/10f);
        if(prefs.getInt("custom_blur_tenths_95",-1)==progress && prefs.getInt("custom_blur_dp",-1)==dp)return;
        prefs.edit().putInt("custom_blur_tenths_95",progress).putInt("custom_blur_dp",dp).commit();
        audit193("USER_CHANGED_BLUR");
        notifyConfigChanged128();
    }
    private void action(String label,Runnable run){
        Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextColor(blue);b.setOnClickListener(v->run.run());current.addView(b,new LinearLayout.LayoutParams(-1,dp(52)));
    }
    private String styleLabel186(){
        int style=prefs.getInt("optical_style_186",0);
        return style==2?"泡泡液态玻璃":style==1?"iOS 27 风格":"自定义参数（统一曲面）";
    }
    private void applyOpticalStyle186(int style){
        SharedPreferences.Editor e=prefs.edit();
        e.putInt("optical_style_186",style)
         .putInt("nonlinear_107",style==2?86:68)
         .putInt("thickness_107",style==2?82:60)
         .putInt("glass_tint_opacity",style==2?7:9)
         .putInt("surface_mode_195",style==2?3:2)
         .putInt("surface_strength_195",style==2?95:58)
         .putInt("surface_softness_195",style==2?92:145)
         .putInt("custom_blur_tenths_95",style==2?5:12)
         .putInt("custom_blur_dp",style==2?1:1)
         .putInt("custom_blur_mode",1).commit();
        notifyConfigChanged128();
        Toast.makeText(this,"已应用二维曲面透镜风格，Launcher 将实时刷新玻璃参数",Toast.LENGTH_LONG).show();
        recreate();
    }
    // V193: opening, backgrounding and closing the settings UI are read-only.
    @Override protected void onPause(){
        audit193("UI_ON_PAUSE_READ_ONLY");
        super.onPause();
    }
    private void audit193(String event){
        if(prefs==null)return;
        android.util.Log.i("LiquidGlass193",event+" uptime="+android.os.SystemClock.uptimeMillis()
            +" style="+prefs.getInt("optical_style_186",0)
            +" lens="+prefs.getInt("nonlinear_107",65)
            +" thickness="+prefs.getInt("thickness_107",65)
            +" surface="+prefs.getInt("surface_mode_195",3)
            +" strength="+prefs.getInt("surface_strength_195",100)
            +" softness="+prefs.getInt("surface_softness_195",100)
            +" nativeAlpha="+prefs.getBoolean("native_alpha_probe",false));
    }
    private void putIntIfChanged193(String key,int value){
        if(prefs.contains(key) && prefs.getInt(key,value)==value)return;
        boolean saved=prefs.edit().putInt(key,value).commit();
        android.util.Log.i("LiquidGlass209","CONFIG_209_INT_SAVE key="+key+" value="+value+" committed="+saved);
        if(!saved)return;
        audit193("USER_CHANGED_"+key);
        notifyConfigChanged128();
    }
    private void putBooleanIfChanged193(String key,boolean value){
        if(prefs.contains(key) && prefs.getBoolean(key,value)==value)return;
        boolean saved=prefs.edit().putBoolean(key,value).commit();
        android.util.Log.i("LiquidGlass204","CONFIG_204_SWITCH_SAVE key="+key+" value="+value+" committed="+saved+" readback="+prefs.getBoolean(key,!value));
        if(!saved)return;
        audit193("USER_CHANGED_"+key);
        notifyConfigChanged128();
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);prefs=getSharedPreferences("glass",0);
        ConfigMigration194.reconcileOnce(this,prefs);
        // V219: disable retired visual overlays and legacy 2D displacement on
        // first upgrade. Keep normal lens, folder, Dock, icon and blur settings.
        if(!prefs.getBoolean("legacy_probe_cleanup_219",false)){
            prefs.edit().putBoolean("diagnostics",false)
                .putBoolean("wallpaper_compare",false).putInt("probe_mode",0)
                .putBoolean("real_refraction",false).putInt("refraction_percent",0)
                .putBoolean("clear_refraction",false)
                .putBoolean("native_blur_experiment",false)
                .putBoolean("native_alpha_probe",false)
                .putInt("dynamic_sheen_mode",0).commit();
            notifyConfigChanged128();
            LegacyDiagnostics219.cleanupAsync(this,(success,reason)->{
                if(success)notifyConfigChanged128();
                else android.util.Log.w("LiquidGlass219","ROOT_DIAGNOSTICS_CLEANUP_FAILED "+reason);
            });
        }
        // V217: persist first-run defaults before publishing. A saved explicit OFF
        // remains authoritative across app restarts, Launcher restarts and reboot.
        SharedPreferences.Editor firstDefaults217=prefs.edit();
        boolean needsDefaults217=false;
        if(!prefs.contains("enabled")){firstDefaults217.putBoolean("enabled",true);needsDefaults217=true;}
        if(!prefs.contains("dock_glass_204")){firstDefaults217.putBoolean("dock_glass_204",true);needsDefaults217=true;}
        if(!prefs.contains("icon_optics_204")){firstDefaults217.putBoolean("icon_optics_204",true);needsDefaults217=true;}
        if(needsDefaults217){
            boolean savedDefaults217=firstDefaults217.commit();
            android.util.Log.i("LiquidGlass217","BOOT_DEFAULTS_217 committed="+savedDefaults217+
                " folder="+prefs.getBoolean("enabled",true)+" dock="+prefs.getBoolean("dock_glass_204",true)+
                " icon="+prefs.getBoolean("icon_optics_204",true));
            if(savedDefaults217)notifyConfigChanged128();
        }
        audit193("UI_ON_CREATE_V194");
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xFFF3F6FA);
        body=column();body.setPadding(dp(18),dp(28),dp(18),dp(32));scroll.addView(body);
        TextView heading=text("Liquid Glass  ·  光学工作台",27,true);body.addView(heading);
        TextView version=text("V1.9.205.232  ·  新统一玻璃材质实验 · 稳定引擎可回退",13,false);LinearLayout.LayoutParams v=new LinearLayout.LayoutParams(-1,-2);v.setMargins(0,dp(4),0,dp(20));body.addView(version,v);
        category229("01  总览与预设","常用操作集中管理；页面打开不会覆盖已有配置。");
        section("稳定引擎风格预设（兼容）","用于 V229 正式引擎；V230 实验材质的统一参数位于下方第三板块。预设不会覆盖已保存的 V230 光学参数，也不会强制切换容器开关。");
        TextView activeStyle186=text("当前风格："+styleLabel186(),15,true);current.addView(activeStyle186);
        LinearLayout styleButtons186=new LinearLayout(this);styleButtons186.setOrientation(LinearLayout.HORIZONTAL);current.addView(styleButtons186);
        Button bubble186=new Button(this);bubble186.setAllCaps(false);bubble186.setText("泡泡液态玻璃");
        Button ios186=new Button(this);ios186.setAllCaps(false);ios186.setText("iOS 27 风格");
        styleButtons186.addView(bubble186,new LinearLayout.LayoutParams(0,dp(58),1));
        styleButtons186.addView(ios186,new LinearLayout.LayoutParams(0,dp(58),1));
        bubble186.setOnClickListener(clickedView186 -> applyOpticalStyle186(2));
        ios186.setOnClickListener(clickedView186 -> applyOpticalStyle186(1));
        TextView styleNote186=text("仅作用于稳定引擎（V229）兼容参数。V230 实验材质另行保存，不与旧预设绑定。",12,false);
        current.addView(styleNote186);
        category229("02  玻璃容器","文件夹、Dock 和图标独立启用；尊重手动关闭。");
        section("玻璃材质总览","文件夹与 Dock 共用光学参数；两者可分别启用。预设不会强制打开旧版实验模式。");
        toggle("启用桌面文件夹玻璃（开机恢复，手动关闭后保持关闭）", "enabled",true);
        TextView presetInfo=text("首次配置默认启用文件夹与 Dock 玻璃；重启时恢复上次保存的开关状态，手动关闭不会被强制打开。",12,false);current.addView(presetInfo);
        action("应用清透液态玻璃推荐参数",()->{
            prefs.edit().putBoolean("enabled",true)
                .putInt("custom_blur_dp",2).putInt("custom_blur_tenths_95",20).putInt("custom_blur_mode",1)
                .putInt("glass_tint_opacity",6).putInt("tint",0).putInt("tint_strength",0)
                .putInt("nonlinear_107",72).putInt("thickness_107",65)
                .putInt("interior_107",48).putInt("focus_107",45).putInt("reflection_107",22)
                .putInt("refraction_percent",0).putBoolean("real_refraction",false)
                .putInt("surface_mode_195",3).putInt("surface_strength_195",65)
                .putInt("surface_softness_195",100).commit();
            notifyConfigChanged128();
            Toast.makeText(this,"已应用统一透镜推荐参数；Dock 开关保持原状态",Toast.LENGTH_LONG).show();
            recreate();
        });
        TextView presetHint=text("推荐参数是测试起点，不代表已达到 iOS 目标。旧二维折射默认关闭；正式路径始终由统一曲面透镜负责。",12,false);
        current.addView(presetHint);
        
        toggle("启用 Dock 玻璃（默认开启，手动关闭后保持关闭）","dock_glass_204",true);
        TextView dockInfo=text("Dock 保持原有尺寸和位置；V132 与文件夹共用直接背景采样及透镜位移 Shader，并按容器几何自适应。",12,false);current.addView(dockInfo);
        section("Kyant0 液态玻璃 · 文件夹与 Dock", "统一光学入口；分别控制桌面文件夹和 Dock，参数共用且真实作用于 Kyant 渲染。");
        kyantSettingsPanel28827();

        category229("03  统一玻璃材质","同一曲面驱动折射、环境响应和边缘厚度；默认关闭，新版可随时回退稳定引擎。");
        section("Unified Glass Surface · 单一光学模型","开启后由单次 Shader 合成整片玻璃；旧引擎仍完整保留，关闭即可恢复 V229 的渲染路径。");
        toggle("使用 V230 统一玻璃曲面（实验，默认关闭)",MaterialConfig230.PREF_ON,false);
        materialSlider230("整体光学强度 (%)",MaterialConfig230.PREF_STRENGTH,70,0,100);
        materialSlider230("曲面厚度与弯曲过渡 (%)",MaterialConfig230.PREF_THICKNESS,55,0,100);
        materialSlider230("环境融合与反射响应 (%)",MaterialConfig230.PREF_ENV,60,0,100);
        final TextView qualityLabel230=text("采样质量（0=性能优先、1=局部五点、2=局部九点）",13,false);
        current.addView(qualityLabel230);
        Spinner qualitySpinner230=new Spinner(this);
        String[] qualityNames230={"0 · 性能优先（单点）","1 · 均衡（边缘五点）","2 · 画质优先（边缘九点）"};
        qualitySpinner230.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,qualityNames230));
        qualitySpinner230.setSelection(Math.max(0,Math.min(2,prefs.getInt(MaterialConfig230.PREF_QUALITY,0))));
        current.addView(qualitySpinner230);
        qualitySpinner230.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> parent,View item,int pos,long id){
                putIntIfChanged193(MaterialConfig230.PREF_QUALITY,pos);
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        current.addView(text("实验引擎默认关闭。每个新参数持久保存，修改后通知 Launcher 即时应用；后台 Root 发布独立 LG230 快照，重启恢复。折射、反射共用同一高度场和法线，不再叠加第二道白边。",12,false));
        final LinearLayout compatLens230=column();
        final Button compatButton230=new Button(this);
        compatButton230.setText("展开稳定引擎兼容参数（V229）");compatButton230.setAllCaps(false);
        current.addView(compatButton230);current.addView(compatLens230);
        compatLens230.setVisibility(View.GONE);
        LinearLayout materialRoot230=current;
        current=compatLens230;
        slider("原引擎二维曲面折射强度 (%)","nonlinear_107",65,0,100);
        slider("原引擎边缘曲面厚度 (%)","thickness_107",55,0,100);
        slider("原引擎内区曲率过渡 (%)","interior_107",20,0,100);
        slider("原引擎中心聚焦强度 (%)","focus_107",45,0,100);
        slider("原引擎曲面反射比例 (%)","reflection_107",30,0,100);
        TextView surfaceLabel230=text("稳定引擎环境反射模式",13,false);current.addView(surfaceLabel230);
        Spinner surfaceSpinner230=new Spinner(this);
        String[] surfaceModes230={"0 · 关闭","1 · 柔和环境反射","2 · 反射与高光","3 · 增强环境响应"};
        surfaceSpinner230.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,surfaceModes230));
        surfaceSpinner230.setSelection(Math.max(0,Math.min(3,prefs.getInt("surface_mode_195",3))));current.addView(surfaceSpinner230);
        surfaceSpinner230.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> parent,View item,int pos,long id){
                putIntIfChanged193("surface_mode_195",pos);
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        slider("原引擎环境反射强度 (%)","surface_strength_195",100,0,200);
        slider("原引擎反射柔和度 (%)","surface_softness_195",100,50,200);
        current.addView(text("这些原字段仍沿用 LG1 原有 33 项持久化配置；关闭新引擎时直接恢复原值，不执行隐式迁移。",12,false));
        current=materialRoot230;
        compatButton230.setOnClickListener(clickedCompat230->{
            boolean open=compatLens230.getVisibility()!=View.VISIBLE;
            compatLens230.setVisibility(open?View.VISIBLE:View.GONE);
            compatButton230.setText(open?"收起稳定引擎兼容参数":"展开稳定引擎兼容参数（V229）");
        });
        category229("04  材质与背景","模糊、透明度、色调仍使用原来的持久化键值。");
        section("玻璃外观","背景模糊和底色独立于折射；应用图标不会被自定义壁纸模糊处理。");
        slider("玻璃底色不透明度 (%)","glass_tint_opacity",18,0,100);
        microBlurSlider95();
        TextView modeLabel=text("背景模糊模式",13,false);current.addView(modeLabel);
        Spinner blurMode=new Spinner(this);
        String[] blurModes={"关闭模糊 · 纯折射","自定义壁纸模糊 · 推荐","ColorOS 原生兼容"};
        blurMode.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,blurModes));
        blurMode.setSelection(Math.max(0,Math.min(2,prefs.getInt("custom_blur_mode",1))));current.addView(blurMode);
        blurMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int pos,long id){putIntIfChanged193("custom_blur_mode",pos);}
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        // Legacy native_alpha_probe is deliberately disabled: blur and material opacity are separate.
        // V193: do not mutate native_alpha_probe while constructing the settings page.
        TextView blurInfo=text("自定义模糊使用壁纸采样，不模糊应用图标。系统模糊开关不再强制开启；ColorOS 自身仍可能启用原生模糊。",12,false);current.addView(blurInfo);
        section("玻璃底色色调","选择玻璃底色的冷暖色调与叠加浓度；与模糊、折射、表面反射独立。");
        TextView tintLabel=text("色调",13,false);current.addView(tintLabel);
        Spinner tint=new Spinner(this);String[] names={"原生 · 推荐","冷白","浅灰","深色"};
        tint.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,names));
        tint.setSelection(Math.max(0,Math.min(3,prefs.getInt("tint",0))));current.addView(tint);
        tint.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int pos,long id){putIntIfChanged193("tint",pos);}
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        slider("底色色调强度 (%)","tint_strength",30,0,90);
        TextView tintInfo=text("底色色调强度为 0% 时不添加颜色；原生模式不添加色调。颜色变化需在玻璃底色不透明度和当前渲染路径允许的情况下观察。",12,false);current.addView(tintInfo);
        category229("05  应用图标","图标光学与文件夹二维折射相互独立。");
        section("应用图标内缘立体光影","图标轮廓内侧的细薄光学描边，不位移图标像素、不绘制外圈厚白边；亮暗随对角方向缓和变化。");
        toggle("应用图标薄内缘明暗光学效果","icon_optics_204",true);
        category229("06  实验与维护","诊断功能保持隔离，避免污染正常渲染。");
        section("旧版兼容与实验","旧二维壁纸折射仅用于对照测试。默认折叠，避免误以为它是第二套必需的光学系统。");
        final LinearLayout legacyContainer=column();
        final Button legacyButton=new Button(this);
        legacyButton.setAllCaps(false);legacyButton.setText("展开旧版二维折射实验");
        current.addView(legacyButton);
        current.addView(legacyContainer);
        legacyContainer.setVisibility(View.GONE);
        final LinearLayout savedCurrent=current;
        current=legacyContainer;
        toggle("旧二维壁纸折射（实验对照）","real_refraction",false);
        slider("旧二维折射强度 (%)","refraction_percent",0,0,100);
        toggle("旧版玻璃细节增强","clear_refraction",false);

        current=savedCurrent;
        legacyButton.setOnClickListener(clickedView->{
            boolean show=legacyContainer.getVisibility()!=View.VISIBLE;
            legacyContainer.setVisibility(show?View.VISIBLE:View.GONE);
            legacyButton.setText(show?"收起旧版实验参数":"展开旧版二维折射实验");
        });
        section("维护与状态","V221 保留 V219 已验证的二维折射实时传递；正式 Shader 对底部中心和圆角过渡采取按需 5/9 点采样，失败自动回退 V219；不重置折射、模糊、Dock 与图标开关。");
        action("一键关闭所有旧版绘制和可视诊断模式",()->{
            LegacyDiagnostics219.cleanupAsync(this,(success,reason)->{
                if(success)notifyConfigChanged128();
                Toast.makeText(this,success?"历史可视诊断已关闭，备份位于 /data/local/tmp/liquidglass_legacy_globals_before_219.txt":
                    "关闭失败："+reason,Toast.LENGTH_LONG).show();
            });
        });
        action("退出折射诊断模式（恢复正常透镜）",()->{
            // Settings.Global requires privileged write access. Use the existing
            // KernelSU root path instead of calling putInt as an ordinary app.
            new Thread(()->{
                boolean ok=false;
                Process process=null;
                try{
                    process=new ProcessBuilder("su","-c","settings put global liquidglass_aa_probe_208 0")
                        .redirectErrorStream(true).start();
                    ok=process.waitFor(7,java.util.concurrent.TimeUnit.SECONDS) && process.exitValue()==0;
                    if(!ok && process.isAlive())process.destroyForcibly();
                }catch(Throwable ignored){}
                finally{if(process!=null)process.destroy();}
                final boolean succeeded=ok;
                if(succeeded){
                    try{
                        android.content.Intent refresh=new android.content.Intent("top.liquidglass.vector.CONFIG_CHANGED_128");
                        refresh.setPackage("com.android.launcher");
                        refresh.putExtra("lg_snapshot_203",ConfigPublisher202.snapshot(getApplicationContext()));
                        sendBroadcast(refresh);
                    }catch(Throwable ignored){}
                }
                runOnUiThread(()->Toast.makeText(this,succeeded?
                    "已恢复模式 0；请返回桌面检查":"切换失败：请用 Termux 的 su 命令恢复模式 0",Toast.LENGTH_LONG).show());
            },"LiquidGlass-DiagnosticReset217").start();
        });
        rootStatus=text("正在检查 Root 脚本与运行进程…",12,false);current.addView(rootStatus);
        action("检查并更新 Root 辅助脚本",()->RootHelperUpdater.runAsync(this,result->{
            rootStatus.setText(result);
            rootStatusHandler.removeCallbacks(rootStatusPoll);
            rootStatusHandler.postDelayed(rootStatusPoll,1000);
        }));
        TextView serviceHint=text("V221：保留已验证的 UI→Launcher 实时参数和独立二维透镜路由。新 Shader 尝试平滑距离场、局部 5/9 点采样及角部位移修正，若创建失败自动回退 V219。图标取消外圈月晕，改为薄内缘光影。",12,false);current.addView(serviceHint);
        toggle("详细诊断日志","diagnostics",false);
        TextView foot=text("提示：旧版校准、对照图和诊断模式不再显示在首页，已有数据不会自动删除。",12,false);body.addView(foot);
        section("Kyant0 实验与调试入口", "主光学控制已统一放在玻璃容器设置区；以下页面仅供独立实验。");
        action("V265 真实壁纸参考帧 Kyant 折射测试", () -> startActivity(new android.content.Intent(this, KyantReferenceFrame265Activity.class)));
        action("V256 原生 View 背景快照桥接测试", () -> startActivity(new android.content.Intent(this, KyantNativeBridge256Activity.class)));
        action("V255 Kyant 多背景与拖动透镜测试", () -> startActivity(new android.content.Intent(this, KyantEngine255Activity.class)));
        action("V251 Kyant 原版 Shader 桥接测试（独立界面）", () -> startActivity(new android.content.Intent(this, KyantBridgeTestActivity251.class)));
        setContentView(scroll);
        audit193("UI_RENDER_COMPLETE_NO_IMPLICIT_WRITE");
        // V193: only explicit user actions can persist optical settings.
    }
}
