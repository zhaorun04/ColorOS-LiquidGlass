#!/system/bin/sh
# ColorOS-LiquidGlass V1.9.205.288.135: Termux A/B/C capture.
# Usage: sh lg_test.sh baseline|conservative|optimized [single|multi]
set -u
PROFILE="${1:-}"
SCENE="${2:-single}"
case "$PROFILE" in
  baseline) MODE=0; LABEL=A_baseline ;;
  conservative) MODE=1; LABEL=B_conservative ;;
  optimized) MODE=2; LABEL=C_optimized ;;
  *) echo "Usage: sh lg_test.sh baseline|conservative|optimized [single|multi]"; exit 2 ;;
esac
case "$SCENE" in single|multi) ;; *) echo "Scene must be single or multi"; exit 2 ;; esac
if ! command -v su >/dev/null 2>&1; then
  echo "Root (su) is required"; exit 3
fi
OUT="/sdcard/Download/LG135_TestResults/${LABEL}_${SCENE}_$(date +%Y%m%d_%H%M%S)"
su -c "mkdir -p '$OUT'" || exit 4
# A/B/C all share the same baseline switches. Legacy bridge switch is disabled
# and only the new 3-mode key determines the experiment.
su -c "settings put global liquidglass_kyant_bridge_notify_ab_288134 0;
 settings put global liquidglass_kyant_bridge_notify_mode_288135 $MODE;
 settings put global liquidglass_kyant_gpu_reference_ab_288110 1;
 settings put global liquidglass_kyant_source_record_reuse_288125 0;
 settings put global liquidglass_kyant_static_layer_cache_288116 0;
 settings put global liquidglass_kyant_effect_isolation_ab_288129 0;
 settings put global liquidglass_kyant_visibility_ab_288132 -1;
 settings put global liquidglass_kyant_offscreen_cull_288113 0;
 settings put global liquidglass_kyant_bridge_origin_probe_288133 1;
 settings put global liquidglass_kyant_perfetto_sections_288120 0;
 am force-stop com.android.launcher;
 input keyevent KEYCODE_HOME" || exit 5
sleep 3
su -c "{
  echo profile=$PROFILE scene=$SCENE mode=$MODE;
  date;
  settings get global liquidglass_kyant_bridge_notify_mode_288135;
  settings get global liquidglass_kyant_gpu_reference_ab_288110;
  dumpsys package top.liquidglass.vector | grep versionName;
  pidof com.android.launcher;
  getprop ro.build.version.release;
  getprop ro.product.model;
 } > '$OUT/device_info.txt'" || exit 6
ACTUAL="$(su -c 'settings get global liquidglass_kyant_bridge_notify_mode_288135' | tr -d '\r')"
if [ "$ACTUAL" != "$MODE" ]; then
 echo "Experiment setting mismatch: got $ACTUAL expected $MODE"; exit 7
fi
su -c 'dumpsys gfxinfo com.android.launcher reset' >/dev/null 2>&1
printf '\nMode %s (%s), scene %s.\n' "$MODE" "$PROFILE" "$SCENE"
printf 'Place the Launcher on the requested page NOW. During the next 15 seconds, swipe repeatedly in the same way.\n'
printf 'Perfetto starts in 3 seconds...\n'
sleep 3
su -c "perfetto -a com.android.launcher -o '$OUT/perfetto-trace' -t 15s -b 128mb gfx view sched freq" || echo "WARNING: Perfetto failed"
su -c "dumpsys gfxinfo com.android.launcher framestats > '$OUT/frames.txt'"
su -c "logcat -d -b all -v threadtime | grep -E 'KYANT288135|KYANT288134|KYANT288133|KYANT288112_NATIVE|FATAL EXCEPTION' > '$OUT/logcat.txt'"
su -c "ls -lh '$OUT'" 
echo "Finished: $OUT"
if command -v zip >/dev/null 2>&1; then
   # Accessible to Termux when external storage permission is enabled.
   (cd /sdcard/Download/LG135_TestResults && zip -q -r "$(basename "$OUT").zip" "$(basename "$OUT")") 2>/dev/null || true
fi
printf 'After testing ALL profiles, restore with:\n'
printf "su -c 'settings put global liquidglass_kyant_bridge_notify_mode_288135 0; am force-stop com.android.launcher'\n"
