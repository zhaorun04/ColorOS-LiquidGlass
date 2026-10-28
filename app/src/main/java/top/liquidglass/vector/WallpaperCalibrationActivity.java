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
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        getWindow().setDimAmount(0f);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        android.util.DisplayMetrics dm=new android.util.DisplayMetrics();getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        screenW=dm.widthPixels;screenH=dm.heightPixels;
        WallpaperManager wm=WallpaperManager.getInstance(this);
        desiredW=wm.getDesiredMinimumWidth();
        android.os.ParcelFileDescriptor fd=null;
        try {
            fd=wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM);
            if(fd!=null){
                BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
                BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(),null,o);
                imageW=o.outWidth;imageH=o.outHeight;
                // A bounded preview decode avoids full-resolution allocations.
                o=new BitmapFactory.Options();o.inSampleSize=8;
                source=BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(),null,o);
            }
        }catch(Throwable ignored){}finally{if(fd!=null)try{fd.close();}catch(Throwable ignored){}}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18,28,18,18);
        root.setBackgroundColor(0x11000000);
        TextView heading=new TextView(this);heading.setText("V1.9.52 · 壁纸映射目视校准（实验）");
        heading.setTextColor(Color.WHITE);heading.setTextSize(18);heading.setShadowLayer(3,1,1,Color.BLACK);root.addView(heading);
        info=new TextView(this);info.setTextColor(Color.WHITE);info.setTextSize(12);info.setShadowLayer(3,1,1,Color.BLACK);root.addView(info);
        preview=new CalibrationView(this);root.addView(preview,new LinearLayout.LayoutParams(-1,0,1f));
        addSlider(root,"屏幕采样 X",50,v->pointX=v/100f);
        addSlider(root,"屏幕采样 Y",45,v->pointY=v/100f);
        addSlider(root,"假设的壁纸水平偏移",50,v->offsetX=v/100f);
        TextView warning=new TextView(this);warning.setText("注意：背景由 Android 壁纸窗口提供，可能与桌面实际裁切/偏移不同。两种模型均未验证；截图仅供人工对照。没有读取桌面图标或录屏。");warning.setTextSize(12);warning.setTextColor(Color.WHITE);warning.setBackgroundColor(0x99000000);warning.setPadding(10,8,10,8);root.addView(warning);
        setContentView(root);update();
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
    private void update(){if(preview!=null)preview.invalidate();if(info!=null)info.setText("原图 "+imageW+"×"+imageH+" · 屏幕 "+screenW+"×"+screenH+" · 期望宽度 "+desiredW+"\n采样屏幕坐标 ("+(int)(pointX*screenW)+","+(int)(pointY*screenH)+")  偏移假设 "+String.format(java.util.Locale.US,"%.2f",offsetX));}
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
                    float scale=scales[i];float px=(pointX*screenW+Math.max(0f,imageW*scale-screenW)*offsetX)/scale;
                    float py=(pointY*screenH+Math.max(0f,imageH*scale-screenH)*.5f)/scale;
                    float radius=Math.min(imageW,imageH)*.065f;
                    float l=Math.max(0,px-radius),t=Math.max(0,py-radius),r=Math.min(imageW,px+radius),b=Math.min(imageH,py+radius);
                    Rect src=new Rect((int)(l*source.getWidth()/imageW),(int)(t*source.getHeight()/imageH),
                        Math.max(1,(int)(r*source.getWidth()/imageW)),Math.max(1,(int)(b*source.getHeight()/imageH)));
                    p.setColor(Color.WHITE);p.setFilterBitmap(true);c.drawBitmap(source,src,dst,p);
                }
                p.setColor(Color.WHITE);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);c.drawRect(dst,p);p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFE4A0);c.drawLine(dst.centerX()-8,dst.centerY(),dst.centerX()+8,dst.centerY(),p);
                c.drawLine(dst.centerX(),dst.centerY()-8,dst.centerX(),dst.centerY()+8,p);
            }
            float markerX=pointX*w,markerY=pointY*Math.max(0,h-side-55);
            p.setColor(0xFFFFD85E);p.setStrokeWidth(2);c.drawCircle(markerX,markerY,10,p);c.drawLine(markerX-18,markerY,markerX+18,markerY,p);c.drawLine(markerX,markerY-18,markerX,markerY+18,p);
        }
    }
}
