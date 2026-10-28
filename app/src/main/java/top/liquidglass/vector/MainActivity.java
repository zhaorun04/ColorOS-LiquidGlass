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
        text.setText("ColorOS Liquid Glass V1.5\n\nFolder preview background inspection and opt-in highlight border.\n\nDefault OFF. Enable via root shell: settings put global coloros_liquidglass_folder_glass 1\nDisable: settings put global coloros_liquidglass_folder_glass 0\n\nThis is a border experiment, not true background blur.");
        setContentView(text);
    }
}
