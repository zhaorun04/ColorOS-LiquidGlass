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
        text.setText("ColorOS Liquid Glass V1.2\n\nRead-only view discovery for ColorOS 15.\n\nEnable in Vector for SystemUI and Launcher, reboot, and export Vector logs containing ColorOSLiquidGlass.\n\nNo UI modifications are applied.");
        setContentView(text);
    }
}
