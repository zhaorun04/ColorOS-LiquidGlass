#!/system/bin/sh
# V1.9.205.288.138 – rebound-window A/B/A capture (Termux)
# sh lg_test.sh a single a1 | b single b1 | a single a2 | pack | restore
# Safety: backs up the TWO keys it modifies; never rewrites unrelated Kyant settings.
set -u
BASE=/sdcard/Download/LG138_TestResults
BACKUP="$BASE/original_settings.tsv"
MODE="${1:-help}"
case "$MODE" in
  pack)
    su -c "cd /sdcard/Download && tar -czf LG138_TestResults.tar.gz LG138_TestResults" || exit 8
    echo 'Output: /sdcard/Download/LG138_TestResults.tar.gz'; exit 0;;
  restore)
    if [ ! -f "$BACKUP" ]; then
      echo 'No original-settings backup found. Not changing any settings.'; exit 9
    fi
    while IFS='|' read -r key value; do
      case "$key" in
        liquidglass_kyant_rebound_host_ab_288138|liquidglass_kyant_release_trace_28821) ;;
        *) echo "Unexpected backup key: $key"; exit 9;;
      esac
      case "$value" in
        null) su -c "settings delete global '$key'" >/dev/null || exit 9;;
        0|1) su -c "settings put global '$key' '$value'" || exit 9;;
        *) echo "Invalid backup value: $value"; exit 9;;
      esac
    done < "$BACKUP"
    su -c 'am force-stop com.android.launcher; input keyevent KEYCODE_HOME'
    mv "$BACKUP" "$BASE/original_settings.restored.$(date +%Y%m%d_%H%M%S).tsv"
    echo 'Restored original settings from saved backup (no global preset overrides).'; exit 0;;
  a|b) ;;
  *) echo 'Usage: sh lg_test.sh a|b single|multi [label] | pack | restore'; exit 2;;
esac
SCENE="${2:-single}"
case "$SCENE" in single|multi) ;; *) echo 'Scene must be single or multi'; exit 2;; esac
LABEL="${3:-run}"
case "$LABEL" in *[!a-zA-Z0-9_-]*|'') echo 'Invalid label'; exit 2;; esac
mkdir -p "$BASE" || exit 3
# Never overwrite backup. A missing/unreadable setting must fail safely.
if [ ! -e "$BACKUP" ]; then
  TEMP="$BACKUP.tmp.$$"
  : > "$TEMP" || exit 3
  for key in liquidglass_kyant_rebound_host_ab_288138 liquidglass_kyant_release_trace_28821; do
    val="$(su -c "settings get global '$key'" 2>&1)"; rc=$?
    case "$val" in null|0|1) ;; *) echo "Cannot safely backup $key: $val"; rm -f "$TEMP"; exit 4;; esac
    [ "$rc" -eq 0 ] || { echo "Setting read failed $key: $val"; rm -f "$TEMP"; exit 4; }
    printf '%s|%s\n' "$key" "$val" >> "$TEMP"
  done
  mv "$TEMP" "$BACKUP" || exit 4
fi
if [ "$MODE" = a ]; then EXPECT=0; else EXPECT=1; fi
su -c "settings put global liquidglass_kyant_rebound_host_ab_288138 '$EXPECT' && settings put global liquidglass_kyant_release_trace_28821 1" || exit 5
for pair in "liquidglass_kyant_rebound_host_ab_288138:$EXPECT" 'liquidglass_kyant_release_trace_28821:1'; do
  key="${pair%%:*}"; expected="${pair#*:}"
  actual="$(su -c "settings get global '$key'" 2>&1)" || exit 5
  [ "$actual" = "$expected" ] || { echo "Setting mismatch $key=$actual, expected $expected"; exit 5; }
done
su -c 'am force-stop com.android.launcher; input keyevent KEYCODE_HOME' || exit 5
sleep 3
VERSION="$(su -c 'dumpsys package top.liquidglass.vector | grep versionName' 2>/dev/null)"
case "$VERSION" in *versionName=1.9.205.288.138*) ;; *) echo "Wrong installed module version: $VERSION"; exit 6;; esac
OUT="$BASE/${SCENE}_${MODE}_${LABEL}_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUT" || exit 6
{
  echo "module=$VERSION mode=$MODE expectedHostDisabled=$EXPECT scene=$SCENE label=$LABEL"
  echo "date=$(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "launcher_pid=$(su -c 'pidof com.android.launcher' 2>&1)"
  echo "device=$(getprop ro.product.model) sdk=$(getprop ro.build.version.sdk)"
  echo 'MODIFIED SETTINGS:'
  for key in liquidglass_kyant_rebound_host_ab_288138 liquidglass_kyant_release_trace_28821; do
    v="$(su -c "settings get global '$key'" 2>&1)"; rc=$?
    echo "SETTING $key exit=$rc value=$v"
  done
} > "$OUT/device_info.txt"
snapshot() {
  name="$1"
  {
    date
    su -c 'dumpsys thermalservice | head -70' 2>&1
    su -c 'for p in /sys/devices/system/cpu/cpufreq/policy*; do echo "POLICY=$p"; for k in scaling_cur_freq scaling_min_freq scaling_max_freq scaling_governor; do if [ -r "$p/$k" ]; then echo "$k=$(cat "$p/$k")"; fi; done; done' 2>&1
    su -c 'cat /proc/meminfo | head -12' 2>&1
  } > "$OUT/${name}_environment.txt"
}
snapshot before
su -c 'logcat -c' >/dev/null 2>&1 || true
printf 'Mode %s, scene %s. Keep Launcher on the chosen page. Start short non-page-turning swipe/rebound gestures after 5 seconds and repeat for 15 seconds.\n' "$MODE" "$SCENE"
sleep 5
su -c 'dumpsys gfxinfo com.android.launcher reset' >/dev/null 2>&1
su -c "date +%s%N > '$OUT/start_epoch_ns.txt'"
su -c "perfetto -a com.android.launcher -o '$OUT/perfetto-trace' -t 15s -b 128mb gfx view sched freq" > "$OUT/perfetto_status.txt" 2>&1
STATUS=$?
su -c "date +%s%N > '$OUT/end_epoch_ns.txt'"
su -c "dumpsys gfxinfo com.android.launcher framestats > '$OUT/frames.txt'" 2> "$OUT/frames_error.txt"
su -c "logcat -d -b main -b system -b crash -v threadtime | grep -E 'KYANT288138|KYANT28821_TOUCH_RELEASE|FATAL EXCEPTION|VectorLegacyBridge|KYANT288137' > '$OUT/logcat.txt'" 2> "$OUT/log_error.txt"
snapshot after
printf 'perfetto_exit=%s\n' "$STATUS" >> "$OUT/device_info.txt"
if [ "$STATUS" -ne 0 ]; then echo "WARN: Perfetto failed. Review $OUT/perfetto_status.txt"; fi
if ! grep -q 'KYANT288138_RELEASE' "$OUT/logcat.txt"; then echo 'WARN: no release markers found, check Vector scope and touch hook'; fi
if [ "$MODE" = b ] && ! grep -q 'KYANT288138_HOST_DISABLED' "$OUT/logcat.txt"; then echo 'WARN: no host-disabled marker; verify experiment activated'; fi
echo "Saved $OUT"
