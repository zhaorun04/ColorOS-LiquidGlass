package top.liquidglass.vector;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Upgrade attempt; foreground app provides retry and root authorization UI. */
public final class RootHelperUpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))return;
        PendingResult pending=goAsync();
        RootHelperUpdater.runAsync(context,result->{Log.i("LiquidGlassRootUpdate",result);pending.finish();});
    }
}
