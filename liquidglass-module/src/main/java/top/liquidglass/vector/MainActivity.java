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
    // V288.199: one UI control; update both native Kyant flags and legacy
    // container gates together. Preserve saved OFF across restarts.
    private void kyantUnifiedSwitch288199(){
        final Switch sw=new Switch(this);
        sw.setText("启用 Kyant0 液态玻璃（桌面文件夹 + Dock）");
        sw.setTextColor(ink);sw.setTextSize(14);sw.setEnabled(false);
        current.addView(sw);
        final TextView status=text("读取文件夹与 Dock 实际状态…",12,false);current.addView(status);
        new Thread(()->{
            boolean folder=false,dock=false,ok=false;
            try{
                folder="1".equals(rootCommand28827("settings get global "+KYANT_FOLDER_KEY));
                dock="1".equals(rootCommand28827("settings get global "+KYANT_DOCK_KEY));
                ok=true;
            }catch(Exception ignored){}
            final boolean f=folder,d=dock,ready=ok;
            runOnUiThread(()->{
                if(!ready){status.setText("Root 读取失败");return;}
                sw.setChecked(f&&d);sw.setEnabled(true);
                status.setText(f==d?(f?"文件夹与 Dock 均已开启":"文件夹与 Dock 均已关闭"):
                    "检测到历史开关状态不一致；切换后统一同步");
                sw.setOnCheckedChangeListener((button,wanted)->{
                    sw.setEnabled(false);status.setText("同步两个 Kyant 引擎及容器开关…");
                    new Thread(()->{
                        boolean verified=false;
                        try{
                            StringBuilder cmd=new StringBuilder("set -e\n");
                            if(wanted)for(String[] pair:KYANT_SAFE_PRESET_28827)
                                cmd.append("settings put global ").append(pair[0]).append(' ').append(pair[1]).append('\n');
                            cmd.append("settings put global ").append(KYANT_FOLDER_KEY).append(wanted?" 1\n":" 0\n");
                            cmd.append("settings put global ").append(KYANT_DOCK_KEY).append(wanted?" 1\n":" 0\n");
                            rootCommand28827(cmd.toString());
                            verified=(wanted?"1":"0").equals(rootCommand28827("settings get global "+KYANT_FOLDER_KEY))&&
                                (wanted?"1":"0").equals(rootCommand28827("settings get global "+KYANT_DOCK_KEY));
                            if(verified){
                                boolean saved=prefs.edit().putBoolean("enabled",wanted)
                                    .putBoolean("dock_glass_204",wanted).commit();
                                if(!saved)verified=false;
                                else notifyConfigChanged128();
                            }
                        }catch(Exception ignored){}
                        final boolean success=verified;
                        runOnUiThread(()->{
                            sw.setEnabled(true);
                            if(!success){sw.setOnCheckedChangeListener(null);sw.setChecked(!wanted);
                                status.setText("同步失败，请检查 Root 权限与实际状态");}
                            else status.setText(wanted?"已同步开启文件夹与 Dock · 可重启 Launcher 核验":"已同步关闭文件夹与 Dock");
                        });
                    },"KyantUnified288199").start();
                });
            });
        },"KyantUnifiedRead288199").start();
    }
    private void kyantSettingsPanel28827(){
        kyantGlobalSwitch28827("桌面文件夹 · Kyant0 光学引擎",KYANT_FOLDER_KEY);
        kyantGlobalSwitch28827("Dock 胶囊 · Kyant0 光学引擎",KYANT_DOCK_KEY);
        current.addView(text("两个 Kyant 开关独立。Dock Kyant 需要上方「启用 Dock 玻璃」主开关处于开启状态；不会擅自打开已手动关闭的 Dock。任一 Kyant 开启都会自动应用稳定光学路径并关闭冲突实验。",12,false));
        current.addView(text("Kyant0 原版五项光学控制（保留原有高光附加项）：圆角默认跟随系统容器，100% 为原始圆角；色差强度支持 0～100%，0 为关闭，建议从 5%～15% 开始。",12,false));
        kyantGlobalSlider28827("圆角半径 · 系统圆角比例 (%)", "liquidglass_kyant_corner_scale_288161",100,100,150);
        kyantGlobalSlider28827("模糊半径 (dp)", "liquidglass_kyant_optical_blur_28827",0,0,20);
        kyantGlobalSlider28827("折射高度 · 容器短边比例 (%)", "liquidglass_kyant_refraction_height_288161",10,0,10);
        kyantGlobalSlider28827("折射强度 (%)", "liquidglass_kyant_optical_strength_28827",100,0,200);
        kyantGlobalSlider28827("色差强度 (%) · 0 为关闭", "liquidglass_kyant_chromatic_intensity_288163",0,0,100);
        kyantGlobalSlider28827("附加 · 高光透明度 (%)", "liquidglass_kyant_optical_highlight_28827",100,0,100);
        current.addView(text("折射高度最大 10%，旧版超过 10% 的保存值会在渲染读取时自动限制。其他默认值与 V288.160 一致。修改后由 Launcher 监听全局配置并更新；圆角调整仅改变 Kyant 光学遮罩，不改变 OEM 文件夹和 Dock 的实际布局几何。",12,false));
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
        TextView version=text("V1.9.205.288.199 · Kyant0 与图标光学",13,false);body.addView(version);
        section("01 · Kyant0 液态玻璃", "统一控制桌面文件夹与 Dock；关闭时不影响桌面图标光学。");
        kyantUnifiedSwitch288199();
        kyantGlobalSlider28827("圆角半径 · 系统圆角比例 (%)", "liquidglass_kyant_corner_scale_288161",100,100,150);
        kyantGlobalSlider28827("模糊半径 (dp)", "liquidglass_kyant_optical_blur_28827",0,0,20);
        kyantGlobalSlider28827("折射高度 · 容器短边比例 (%)", "liquidglass_kyant_refraction_height_288161",10,0,10);
        kyantGlobalSlider28827("折射强度 (%)", "liquidglass_kyant_optical_strength_28827",100,0,200);
        kyantGlobalSlider28827("色差强度 (%) · 0 为关闭", "liquidglass_kyant_chromatic_28827",0,0,100);
        section("02 · 桌面应用图标", "保留 V288.198 的边缘轮廓、5×5 平滑及连续微透镜效果。");
        toggle("启用图标玻璃光影","icon_optics_204",true);
        slider("应用图标光学强度 (%)","icon_specular_brightness_288199",100,0,150);
        current.addView(text("统一调节贴边高光与柔和内缘光晕；0% 关闭光学叠加，调整后自动同步 Launcher。",12,false));
        section("03 · 诊断与性能分析", "保留历史诊断能力；正常使用建议关闭日志和实验模式。");
        toggle("详细日志诊断","diagnostics",false);
        action("退出旧版折射诊断（模式 0）",()->new Thread(()->{
            boolean ok=false;
            try{rootCommand28827("settings put global liquidglass_aa_probe_208 0");
                ok="0".equals(rootCommand28827("settings get global liquidglass_aa_probe_208"));}
            catch(Exception ignored){}
            final boolean verified=ok;
            runOnUiThread(()->Toast.makeText(this,verified?"诊断模式已恢复 0":"诊断模式恢复失败",Toast.LENGTH_LONG).show());
        },"DiagnosticReset288199").start());
        action("打开历史诊断模式说明",()->new android.app.AlertDialog.Builder(this)
            .setTitle("历史诊断模式")
            .setMessage("原有 Settings.Global 诊断模式与 Hook 保留，可通过历史测试脚本设置；退出时使用上方恢复按钮。为避免误触发额外绘制，默认不自动启用。")
            .setPositiveButton("知道了",null).show());
        rootStatus=text("Root 辅助服务状态检查中…",12,false);current.addView(rootStatus);
        // RootHelperUpdateReceiver continues automatic script maintenance on module upgrade.
        setContentView(scroll);
        audit193("UI_RENDER_COMPLETE_NO_IMPLICIT_WRITE");
        // V193: only explicit user actions can persist optical settings.
    }
}
