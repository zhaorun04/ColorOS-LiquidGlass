#!/system/bin/sh
# V1.9.205.288.136: isolated outer rounded-clip A/B data collection.
# sh lg_test.sh baseline|clip-isolated single|multi
set -u
PROFILE="${1:-}"; SCENE="${2:-single}"
if [ "$PROFILE" = "pack" ]; then
  su -c "cd /sdcard/Download && tar -czf LG136_TestResults.tar.gz LG136_TestResults" || exit 9
  echo "Upload /sdcard/Download/LG136_TestResults.tar.gz"
  exit 0
fi
case "$PROFILE" in baseline) MODE=0; LABEL=A_baseline ;; clip-isolated) MODE=1; LABEL=B_clip_isolated ;; *) echo "Usage: sh lg_test.sh baseline|clip-isolated [single|multi], or sh lg_test.sh pack"; exit 2 ;; esac
case "$SCENE" in single|multi) ;; *) echo "Scene must be single or multi"; exit 2 ;; esac
command -v su >/dev/null 2>&1 || { echo "Root required"; exit 3; }
BASE=/sdcard/Download/LG136_TestResults
OUT="$BASE/${LABEL}_${SCENE}_$(date +%Y%m%d_%H%M%S)"
su -c "mkdir -p '$OUT'" || exit 4
# Do not enable a previous notification/cache/clip experiment accidentally.
su -c "settings put global liquidglass_kyant_bridge_notify_mode_288135 0; settings put global liquidglass_kyant_bridge_notify_ab_288134 0; settings put global liquidglass_kyant_gpu_reference_ab_288110 1; settings put global liquidglass_kyant_source_record_reuse_288125 0; settings put global liquidglass_kyant_static_layer_cache_288116 0; settings put global liquidglass_kyant_effect_isolation_ab_288129 0; settings put global liquidglass_kyant_visibility_ab_288132 -1; settings put global liquidglass_kyant_offscreen_cull_288113 0; settings put global liquidglass_kyant_perfetto_sections_288120 0; settings put global liquidglass_kyant_outer_clip_ab_288136 $MODE; am force-stop com.android.launcher; input keyevent KEYCODE_HOME" || exit 5
sleep 3
# Query settings separately; preserve stderr and mark failures instead of treating
# partially failed settings calls as a trustworthy device-state snapshot.
{
 echo "profile=$PROFILE scene=$SCENE requested_mode=$MODE"
 echo "device_time=$(date '+%Y-%m-%d %H:%M:%S %z')"
 su -c 'dumpsys package top.liquidglass.vector | grep versionName' 2>&1
 su -c 'pidof com.android.launcher' 2>&1
 su -c 'getprop ro.product.model' 2>&1
 for key in liquidglass_kyant_outer_clip_ab_288136 liquidglass_kyant_gpu_reference_ab_288110 liquidglass_kyant_bridge_notify_mode_288135 liquidglass_kyant_source_record_reuse_288125 liquidglass_kyant_static_layer_cache_288116 liquidglass_kyant_effect_isolation_ab_288129 liquidglass_kyant_visibility_ab_288132 liquidglass_kyant_offscreen_cull_288113; do
   RESULT="$(su -c "settings get global $key" 2>&1)"
   STATUS=$?
   echo "SETTING $key exit=$STATUS value=$RESULT"
 done
} > "$OUT/device_info.txt" 2>&1
ACTUAL="$(su -c 'settings get global liquidglass_kyant_outer_clip_ab_288136' 2>/dev/null | tr -d '\r')"
[ "$ACTUAL" = "$MODE" ] || { echo "Mode mismatch or settings query failure: '$ACTUAL' expected '$MODE'"; exit 6; }
VERSION="$(su -c 'dumpsys package top.liquidglass.vector | grep versionName' 2>/dev/null)"
case "$VERSION" in *1.9.205.288.136*) ;; *) echo "WARNING: Expected version 1.9.205.288.136, got $VERSION"; exit 7 ;; esac
# Check actual host effective gate. A baseline should report effective=false.
su -c 'logcat -c' >/dev/null 2>&1 || true
printf '\nMode %s, scene %s. Open the intended Launcher page and prepare to swipe.\n' "$MODE" "$SCENE"
printf 'Sampling starts after 5 seconds; keep the SAME swipe pattern for 15 seconds.\n'
sleep 5
su -c 'dumpsys gfxinfo com.android.launcher reset' >/dev/null 2>&1
su -c "date +%s%N > '$OUT/capture_start_epoch_ns.txt'"
su -c "perfetto -a com.android.launcher -o '$OUT/perfetto-trace' -t 15s -b 128mb gfx view sched freq" > "$OUT/perfetto_status.txt" 2>&1
PERF_STATUS=$?
su -c "date +%s%N > '$OUT/capture_end_epoch_ns.txt'"
su -c "dumpsys gfxinfo com.android.launcher framestats > '$OUT/frames.txt'" 2> "$OUT/frames_error.txt"
su -c "logcat -d -b all -v threadtime | grep -E 'KYANT288136_HOST|KYANT288135_HOST|KYANT288112_NATIVE|FATAL EXCEPTION' > '$OUT/logcat.txt'" 2> "$OUT/log_error.txt"
echo "perfetto_exit=$PERF_STATUS" >> "$OUT/device_info.txt"
if [ "$PERF_STATUS" -ne 0 ]; then echo "ERROR: Perfetto exited $PERF_STATUS, see $OUT/perfetto_status.txt"; fi
if ! grep -q "KYANT288136_HOST.*rawMode=$MODE" "$OUT/logcat.txt"; then
 echo "WARNING: Host mode not confirmed in logcat; do not draw A/B conclusions yet."
fi
printf 'Completed: %s\n' "$OUT"
echo "Restore normal optics: su -c 'settings put global liquidglass_kyant_outer_clip_ab_288136 0; am force-stop com.android.launcher'"
