package top.liquidglass.vector;

import android.app.Activity;
import android.app.WallpaperManager;
import android.os.Bundle;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import android.content.Context;

/** Experimental manual calibration: no screenshots, no compositor measurement. */
public final class WallpaperCalibrationActivity extends Activity {
    private Bitmap source;
    private CalibrationView preview;
    private TextView info;
    private int imageW, imageH, screenW, screenH, desiredW;
    private float pointX=.5f, pointY=.45f, offsetX=.5f;
    private float verticalShiftPx=0f, scaleCorrection=1f;
    private int insetTop=0, insetBottom=0, viewScreenTop=0;
    private final float[] savedX={Float.NaN,Float.NaN},savedY={Float.NaN,Float.NaN};
    private final float[] savedShift={0f,0f},savedScale={1f,1f};
    private int activePoint=0;
    private TextView validation;
    private static final float MIN_ANCHOR_DISTANCE_PX=100f;
    private float referenceX=Float.NaN,referenceY=Float.NaN;
    private String referenceStatus="UNAVAILABLE: 没有独立屏幕背景像素；禁止自动判定模型正确";
    private TextView anchors;
    private LinearLayout resultsRoot;
    private FrameLayout selectionRoot;
    private boolean selecting=false;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        getWindow().setDimAmount(0f);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        android.util.DisplayMetrics dm=new android.util.DisplayMetrics();getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        screenW=dm.widthPixels;screenH=dm.heightPixels;
        WallpaperManager wm=WallpaperManager.getInstance(this);
        desiredW=wm.getDesiredMinimumWidth();
        // Read only from our app's private provider. The Launcher publishes this
        // preview when detailed diagnostics run; no wallpaper permission is needed here.
        String previewStatus="MISSING";
        try {
            android.os.Bundle bundle=getContentResolver().call(
                android.net.Uri.parse("content://top.liquidglass.vector.config/settings"),
                "readCalibrationPreview",null,null);
            if(bundle!=null){
                previewStatus=bundle.getString("status","UNKNOWN");
                if("OK".equals(previewStatus)){
                    imageW=bundle.getInt("sourceW",0);imageH=bundle.getInt("sourceH",0);
                    byte[] jpeg=bundle.getByteArray("jpeg");
                    if(jpeg!=null)source=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);
                    if(source==null||imageW<=0||imageH<=0)previewStatus="INVALID_IMAGE";
                    int cachedId=bundle.getInt("wallpaperId",-1);
                    try {
                        int currentId=wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
                        if(cachedId>=0 && currentId>=0 && cachedId!=currentId)previewStatus="WALLPAPER_CHANGED";
                    }catch(Throwable ignored){}
                    long age=System.currentTimeMillis()-bundle.getLong("time",0);
                    if(age<0 || age>86400000L)previewStatus="STALE_PREVIEW";
                }
            }
        }catch(Throwable t){previewStatus="READ_"+t.getClass().getSimpleName();}
        if(!"OK".equals(previewStatus)){
            if(source!=null){source.recycle();source=null;}
            imageW=0;imageH=0;
        }
        final String statusText=previewStatus;
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18,28,18,18);
        root.setBackgroundColor(0x11000000);
        TextView heading=new TextView(this);heading.setText("V1.9.57 · 参照有效性与双点校验（实验）");
        heading.setTextColor(Color.WHITE);heading.setTextSize(18);heading.setShadowLayer(3,1,1,Color.BLACK);root.addView(heading);
        info=new TextView(this);info.setTextColor(Color.WHITE);info.setTextSize(12);info.setShadowLayer(3,1,1,Color.BLACK);root.addView(info);
        TextView state=new TextView(this);state.setTextColor(0xFFFFD78C);state.setTextSize(13);
        state.setText("校准样本："+statusText+("OK".equals(statusText)?"（来自 Launcher 私有缓存）":"\n请开启详细诊断、重启 Launcher、打开文件夹，再返回本页。"));
        root.addView(state);
        Button choosePoint=new Button(this);choosePoint.setText("第一步：无遮挡选点（点击壁纸位置）");
        choosePoint.setOnClickListener(v->showSelection());root.addView(choosePoint);
        preview=new CalibrationView(this);root.addView(preview,new LinearLayout.LayoutParams(-1,0,1f));
        LinearLayout buttons=new LinearLayout(this);buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button saveA=new Button(this);saveA.setText("记录点 A");saveA.setOnClickListener(v->savePoint(0));buttons.addView(saveA,new LinearLayout.LayoutParams(0,-2,1));
        Button saveB=new Button(this);saveB.setText("记录点 B");saveB.setOnClickListener(v->savePoint(1));buttons.addView(saveB,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(buttons);
        anchors=new TextView(this);anchors.setTextColor(Color.WHITE);anchors.setTextSize(11);root.addView(anchors);
        validation=new TextView(this);validation.setTextColor(0xFFFFD78C);validation.setTextSize(12);root.addView(validation);
        // Geometry comes from the actual activity window, not assumed status-bar dimensions.
        root.setOnApplyWindowInsetsListener((v,insets)->{
            insetTop=insets.getSystemWindowInsetTop();insetBottom=insets.getSystemWindowInsetBottom();
            update();return v.onApplyWindowInsets(insets);
        });
        addSlider(root,"屏幕采样 X",50,v->pointX=v/100f);
        addSlider(root,"屏幕采样 Y",45,v->pointY=v/100f);
        addSlider(root,"假设的壁纸水平偏移",50,v->offsetX=v/100f);
        addSlider(root,"原图垂直平移（-400 至 +400 像素）",50,v->verticalShiftPx=(v-50)*8f);
        addSlider(root,"模型缩放修正（0.75 至 1.25 倍）",50,v->scaleCorrection=.75f+v*.005f);
        TextView warning=new TextView(this);warning.setText("注意：本版不会把壁纸原图自身当成独立参照，也不会虚构自动匹配置信度。候选图中央十字严格对应标出的原图采样坐标（包括靠近边缘时）；黄色屏幕十字只是页面定位提示。点 A/B 保存的是当前手动校准参数，不能自动证明映射正确。独立 Activity 与 Launcher 裁切可能不同。未启用折射或录屏。");warning.setTextSize(12);warning.setTextColor(Color.WHITE);warning.setBackgroundColor(0x99000000);warning.setPadding(10,8,10,8);root.addView(warning);
        resultsRoot=root;
        setContentView(root);root.post(this::update);update();
    }
    /** Fullscreen wallpaper selection. The sample panels and sliders are not attached. */
    private void showSelection(){
        selecting=true;
        FrameLayout frame=new FrameLayout(this);
        frame.setBackgroundColor(0x00000000);
        View hit=new View(this){
            private final Paint marker=new Paint(3);
            @Override public boolean onTouchEvent(android.view.MotionEvent event){
                if(event.getAction()==android.view.MotionEvent.ACTION_DOWN || event.getAction()==android.view.MotionEvent.ACTION_MOVE){
                    int[] loc=new int[2];getLocationOnScreen(loc);
                    float absX=loc[0]+event.getX(),absY=loc[1]+event.getY();
                    pointX=Math.max(0f,Math.min(1f,absX/screenW));
                    pointY=Math.max(0f,Math.min(1f,absY/screenH));
                    invalidate();return true;
                }
                return event.getAction()==android.view.MotionEvent.ACTION_UP;
            }
            @Override protected void onDraw(Canvas canvas){
                super.onDraw(canvas);
                int[] loc=new int[2];getLocationOnScreen(loc);
                float x=pointX*screenW-loc[0],y=pointY*screenH-loc[1];
                marker.setColor(0xFFFFD85E);marker.setStrokeWidth(2.5f);
                canvas.drawCircle(x,y,10,marker);
                canvas.drawLine(x-20,y,x+20,y,marker);canvas.drawLine(x,y-20,x,y+20,marker);
            }
        };
        frame.addView(hit,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(12,8,12,8);controls.setBackgroundColor(0xB8000000);
        TextView help=new TextView(this);help.setText("V1.9.57 · 选点模式：直接点击壁纸特征，黄色十字为屏幕绝对坐标。此模式不显示采样图。");
        help.setTextColor(Color.WHITE);help.setTextSize(13);controls.addView(help);
        Button done=new Button(this);done.setText("第二步：锁定位置并查看对照结果");
        done.setOnClickListener(v->showResults());controls.addView(done);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        frame.addView(controls,lp);
        selectionRoot=frame;
        setContentView(frame);
    }
    private void showResults(){
        selecting=false;
        if(resultsRoot!=null){
            setContentView(resultsRoot);
            resultsRoot.post(this::update);
        }
    }
    interface Change {void apply(int v);}
    private void addSlider(LinearLayout root,String name,int initial,Change change){
        TextView t=new TextView(this);t.setText(name);t.setTextColor(Color.WHITE);t.setShadowLayer(3,1,1,Color.BLACK);root.addView(t);
        SeekBar bar=new SeekBar(this);bar.setMax(100);bar.setProgress(initial);root.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean user){change.apply(p);update();}
            public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}
        });
    }
    private void savePoint(int which){
        float sx=pointX*screenW,sy=pointY*screenH;
        int other=1-which;
        if(!Float.isNaN(savedX[other]) && Math.hypot(sx-savedX[other],sy-savedY[other])<MIN_ANCHOR_DISTANCE_PX){
            android.widget.Toast.makeText(this,"点 A/B 距离不足 100px，请选择不同高度的特征点",android.widget.Toast.LENGTH_LONG).show();
            referenceStatus="INVALID_ANCHORS: A/B 位置过近，不能用于缩放分析";
            update();return;
        }
        savedX[which]=sx;savedY[which]=sy;
        savedShift[which]=verticalShiftPx;savedScale[which]=scaleCorrection;
        activePoint=which;update();
    }
    private String pointSummary(int which){
        if(Float.isNaN(savedX[which]))return (which==0?"A":"B")+": 未记录";
        return String.format(java.util.Locale.US,"%s: 屏幕(%.0f,%.0f) Y平移%+.0f 缩放%.3f",which==0?"A":"B",savedX[which],savedY[which],savedShift[which],savedScale[which]);
    }
    private void update(){
        if(preview!=null){int[] loc=new int[2];preview.getLocationOnScreen(loc);viewScreenTop=loc[1];preview.invalidate();}
        if(anchors!=null){
            String diff="";
            if(!Float.isNaN(savedX[0])&&!Float.isNaN(savedX[1])){
                float dy=savedY[1]-savedY[0];float ds=savedShift[1]-savedShift[0];
                float distance=(float)Math.hypot(savedX[1]-savedX[0],dy);
                if(distance<MIN_ANCHOR_DISTANCE_PX)diff="\n双点无效：两点距离不足 100px";
                else diff=String.format(java.util.Locale.US,"\n双点距离 %.0fpx，屏幕ΔY=%+.0f，手动平移ΔY=%+.0f。仅描述手动参数，不是自动匹配误差。",distance,dy,ds);
            }
            anchors.setText(pointSummary(0)+"\n"+pointSummary(1)+diff);
        }
        if(validation!=null){
            String model="";
            if(source!=null && imageW>0 && imageH>0){
                float cover=Math.max((float)screenW/imageW,(float)screenH/imageH)*scaleCorrection;
                float desired=Math.max(Math.max((float)screenW/imageW,(float)screenH/imageH),(float)desiredW/imageW)*scaleCorrection;
                float px=pointX*screenW,py=pointY*screenH;
                float coverY=(py+verticalShiftPx+Math.max(0,imageH*cover-screenH)*.5f)/cover;
                float desiredY=(py+verticalShiftPx+Math.max(0,imageH*desired-screenH)*.5f)/desired;
                model=String.format(java.util.Locale.US,"\n候选原图 Y：COVER=%.1f / DESIRED=%.1f（差 %.1fpx）",coverY,desiredY,Math.abs(coverY-desiredY));
            }
            validation.setText("参照来源：WALLPAPER_ORIGINAL_ONLY\n自动匹配：不可用（缺少独立的实际屏幕壁纸像素）\n"+referenceStatus+model);
        }
        if(info!=null)info.setText("原图 "+imageW+"×"+imageH+" · 屏幕 "+screenW+"×"+screenH+" · 期望宽度 "+desiredW
            +"\n采样屏幕坐标 ("+(int)(pointX*screenW)+","+(int)(pointY*screenH)+") 偏移 "+String.format(java.util.Locale.US,"%.2f",offsetX)
            +"\nInsets 上/下 "+insetTop+"/"+insetBottom+" · 预览屏幕Y="+viewScreenTop
            +"\n模型Y平移 "+(int)verticalShiftPx+"px · 缩放修正 "+String.format(java.util.Locale.US,"%.3f",scaleCorrection));
    }
    @Override protected void onDestroy(){super.onDestroy();if(source!=null&&!source.isRecycled())source.recycle();}
    private final class CalibrationView extends View {
        private final Paint p=new Paint(3);
        CalibrationView(Context c){super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);
            float w=getWidth(),h=getHeight(),side=Math.min((w-24f)/2f,180f*getResources().getDisplayMetrics().density);
            float y=Math.max(48f,h-side-38f),x=(w-(2*side+12f))/2f;
            p.setColor(0xAA101820);c.drawRoundRect(new RectF(x-8,y-32,x+2*side+20,y+side+12),10,10,p);
            p.setTextSize(13f*getResources().getDisplayMetrics().scaledDensity);p.setColor(Color.WHITE);
            c.drawText("CENTER_COVER",x,y-10,p);c.drawText("DESIRED_WIDTH",x+side+12,y-10,p);
            float[] scales={imageW>0&&imageH>0?Math.max((float)screenW/imageW,(float)screenH/imageH):0f,
                imageW>0&&imageH>0?Math.max(Math.max((float)screenW/imageW,(float)screenH/imageH),(float)desiredW/imageW):0f};
            for(int i=0;i<2;i++){
                float xx=x+i*(side+12f);RectF dst=new RectF(xx,y,xx+side,y+side);
                p.setColor(0xFF26323B);c.drawRect(dst,p);
                if(source!=null&&!source.isRecycled()&&scales[i]>0f){
                    float scale=scales[i]*scaleCorrection;
                    float px=(pointX*screenW+Math.max(0f,imageW*scale-screenW)*offsetX)/scale;
                    float py=(pointY*screenH+verticalShiftPx+Math.max(0f,imageH*scale-screenH)*.5f)/scale;
                    // Convert the actual source center once, then construct a fixed-size
                    // source rectangle around it. Never silently shift the center at edges.
                    float cacheX=px*source.getWidth()/imageW;
                    float cacheY=py*source.getHeight()/imageH;
                    float radius=Math.min(source.getWidth(),source.getHeight())*.065f;
                    float l=cacheX-radius,t=cacheY-radius,r=cacheX+radius,b=cacheY+radius;
                    RectF srcF=new RectF(l,t,r,b);
                    RectF visible=new RectF(Math.max(0,l),Math.max(0,t),Math.min(source.getWidth(),r),Math.min(source.getHeight(),b));
                    if(visible.width()>0&&visible.height()>0){
                        Rect src=new Rect((int)Math.floor(visible.left),(int)Math.floor(visible.top),
                            Math.min(source.getWidth(),(int)Math.ceil(visible.right)),Math.min(source.getHeight(),(int)Math.ceil(visible.bottom)));
                        RectF actualDst=new RectF(dst.left+(src.left-srcF.left)/srcF.width()*dst.width(),
                            dst.top+(src.top-srcF.top)/srcF.height()*dst.height(),
                            dst.left+(src.right-srcF.left)/srcF.width()*dst.width(),
                            dst.top+(src.bottom-srcF.top)/srcF.height()*dst.height());
                        p.setColor(Color.WHITE);p.setFilterBitmap(true);c.save();c.clipRect(dst);c.drawBitmap(source,src,actualDst,p);c.restore();
                    }
                    p.setColor(Color.WHITE);p.setTextSize(10f*getResources().getDisplayMetrics().scaledDensity);
                    c.drawText(String.format(java.util.Locale.US,"原图(%.1f,%.1f)",px,py),xx+4,y+side-8,p);
                    p.setColor(0xFFFFE4A0);
                    if(px>=0&&px<imageW&&py>=0&&py<imageH){
                        c.drawLine(dst.centerX()-8,dst.centerY(),dst.centerX()+8,dst.centerY(),p);
                        c.drawLine(dst.centerX(),dst.centerY()-8,dst.centerX(),dst.centerY()+8,p);
                    }else{
                        c.drawText("越界",dst.centerX()-18,dst.centerY(),p);
                    }
                }else{
                    p.setColor(Color.WHITE);c.drawText("无可用壁纸预览",xx+10,y+side*.5f,p);
                }
                p.setColor(Color.WHITE);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);c.drawRect(dst,p);p.setStyle(Paint.Style.FILL);
            }
            // Map absolute screen position into this View's local coordinates.
            // Do not compress the Y axis to fit the available drawing area.
            int[] location=new int[2];getLocationOnScreen(location);
            float markerX=pointX*screenW-location[0],markerY=pointY*screenH-location[1];
            boolean markerVisible=markerX>=0&&markerX<=w&&markerY>=0&&markerY<=h;
            if(!markerVisible)return;
            p.setColor(0xFFFFD85E);p.setStrokeWidth(2);c.drawCircle(markerX,markerY,10,p);c.drawLine(markerX-18,markerY,markerX+18,markerY,p);c.drawLine(markerX,markerY-18,markerX,markerY+18,p);
        }
    }
}
