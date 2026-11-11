#!/system/bin/sh
# V1.9.205.288.137 — source/clip provenance + frame-state repeatability capture.
# Usage: sh lg_test.sh single|multi [run_label]; sh lg_test.sh pack; sh lg_test.sh restore
set -u
PROFILE="${1:-}"
BASE=/sdcard/Download/LG137_TestResults
if [ "$PROFILE" = pack ]; then
  su -c "cd /sdcard/Download && tar -czf LG137_TestResults.tar.gz LG137_TestResults" || exit 9
  echo "Upload /sdcard/Download/LG137_TestResults.tar.gz"; exit 0
fi
if [ "$PROFILE" = restore ]; then
  su -c 'settings put global liquidglass_kyant_clip_provenance_probe_288137 0; settings put global liquidglass_kyant_outer_clip_ab_288136 0; settings put global liquidglass_kyant_perfetto_sections_288120 0; am force-stop com.android.launcher'
  echo 'Diagnostic disabled; original glass effects restored'; exit 0
fi
case "$PROFILE" in single|multi) ;; *) echo 'Usage: sh lg_test.sh single|multi [run_label] | pack | restore'; exit 2 ;; esac
LABEL="${2:-run}"
case "$LABEL" in *[!a-zA-Z0-9_-]*|'') echo 'run_label must contain only letters, digits, _ or -'; exit 2;; esac
OUT="$BASE/${PROFILE}_${LABEL}_$(date +%Y%m%d_%H%M%S)"
su -c "mkdir -p '$OUT'" || exit 4
su -c 'settings put global liquidglass_kyant_gpu_reference_ab_288110 1; settings put global liquidglass_kyant_outer_clip_ab_288136 0; settings put global liquidglass_kyant_bridge_notify_mode_288135 0; settings put global liquidglass_kyant_bridge_notify_ab_288134 0; settings put global liquidglass_kyant_source_record_reuse_288125 0; settings put global liquidglass_kyant_static_layer_cache_288116 0; settings put global liquidglass_kyant_effect_isolation_ab_288129 0; settings put global liquidglass_kyant_visibility_ab_288132 -1; settings put global liquidglass_kyant_offscreen_cull_288113 0; settings put global liquidglass_kyant_clip_provenance_probe_288137 1; settings put global liquidglass_kyant_perfetto_sections_288120 0; am force-stop com.android.launcher; input keyevent KEYCODE_HOME' || exit 5
sleep 3
VERSION="$(su -c 'dumpsys package top.liquidglass.vector | grep versionName' 2>/dev/null)"
case "$VERSION" in *versionName=1.9.205.288.137*) ;; *) echo "Wrong module version: $VERSION"; exit 6;; esac
ACTUAL="$(su -c 'settings get global liquidglass_kyant_clip_provenance_probe_288137' 2>/dev/null | tr -d '\r')"
[ "$ACTUAL" = 1 ] || { echo "Probe not enabled: $ACTUAL"; exit 6; }
{
  echo "profile=$PROFILE run=$LABEL module=$VERSION"
  echo "date=$(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "launcher_pid=$(su -c 'pidof com.android.launcher' 2>&1)"
  echo "model=$(getprop ro.product.model)"
  echo "sdk=$(getprop ro.build.version.sdk)"
  for key in liquidglass_kyant_clip_provenance_probe_288137 liquidglass_kyant_outer_clip_ab_288136 liquidglass_kyant_gpu_reference_ab_288110 liquidglass_kyant_bridge_notify_mode_288135 liquidglass_kyant_effect_isolation_ab_288129; do
    RESULT="$(su -c "settings get global $key" 2>&1)"; STATUS=$?
    echo "SETTING $key exit=$STATUS value=$RESULT"
  done
} > "$OUT/device_info.txt" 2>&1
snapshot() {
  name="$1"
  {
    echo "SNAPSHOT_$name"
    date
    su -c 'dumpsys thermalservice | head -80' 2>&1
    su -c 'for p in /sys/devices/system/cpu/cpufreq/policy*; do echo "POLICY:$p"; for k in scaling_cur_freq scaling_min_freq scaling_max_freq scaling_governor; do if [ -r "$p/$k" ]; then echo "$k=$(cat "$p/$k")"; fi; done; done' 2>&1
    su -c 'for p in /sys/class/thermal/thermal_zone*; do if [ -r "$p/type" ] && [ -r "$p/temp" ]; then echo "$(cat "$p/type")=$(cat "$p/temp")"; fi; done' 2>&1
    su -c 'cat /proc/meminfo | head -12' 2>&1
  } > "$OUT/${name}_environment.txt"
}

snapshot before
su -c 'logcat -c' >/dev/null 2>&1 || true
printf 'Scene: %s. Go to the requested page; in five seconds swipe in your usual pattern for 15 seconds.\n' "$PROFILE"
sleep 5
su -c 'dumpsys gfxinfo com.android.launcher reset' >/dev/null 2>&1
su -c "date +%s%N > '$OUT/start_epoch_ns.txt'"
su -c "perfetto -a com.android.launcher -o '$OUT/perfetto-trace' -t 15s -b 128mb gfx view sched freq" > "$OUT/perfetto_status.txt" 2>&1
PERF=$?
su -c "date +%s%N > '$OUT/end_epoch_ns.txt'"
su -c "dumpsys gfxinfo com.android.launcher framestats > '$OUT/frames.txt'" 2> "$OUT/frames_error.txt"
su -c "logcat -d -b all -v threadtime | grep -E 'KYANT288137|KYANT288112_NATIVE|FATAL EXCEPTION' > '$OUT/logcat.txt'" 2> "$OUT/log_error.txt"
snapshot after
echo "perfetto_exit=$PERF" >> "$OUT/device_info.txt"
if [ "$PERF" -ne 0 ]; then echo 'WARNING: Perfetto command failed, see perfetto_status.txt'; fi
if ! su -c "grep -q 'KYANT288137_CLIP' '$OUT/logcat.txt'"; then echo 'WARNING: no internal clip logs; inspect launcher configuration'; fi
echo "Saved $OUT"
