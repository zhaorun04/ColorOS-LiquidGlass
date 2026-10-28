package top.liquidglass.vector;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.graphics.Color;

public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setTextSize(17f);
        text.setTextColor(Color.BLACK);
        text.setPadding(36, 50, 36, 36);
        text.setText("ColorOS Liquid Glass V1.0\n\nVector compatibility test only.\n\nEnable this module in Vector, select SystemUI and OplusLauncher, reboot, then check Vector logs for ColorOSLiquidGlass / HOOK_LOADED.\n\nNo visual changes are applied by this build.");
        setContentView(text);
    }
}
