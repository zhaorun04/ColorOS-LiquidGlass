#!/system/bin/sh
# V1.9.205.288.144: full-glass rebound diagnostics (no optical-host isolation).
# sh lg_test.sh single a1 | single a2 | multi m1 | pack | restore
set -u
BASE=/sdcard/Download/LG141_TestResults
BACKUP="$BASE/original_settings.tsv"
MODE="${1:-help}"
KEY_GATE=liquidglass_kyant_visibility_gate_probe_288141
KEY_PROBE=liquidglass_kyant_ui_frame_probe_288140
KEY_COORD=liquidglass_kyant_same_frame_probe_288139
KEY_HOST=liquidglass_kyant_rebound_host_ab_288138
case "$MODE" in
  pack)
    [ -d "$BASE" ] || { echo 'No LG141 results found'; exit 8; }
    su -c "cd /sdcard/Download && tar -czf LG141_TestResults.tar.gz LG141_TestResults" || exit 8
    echo 'Output: /sdcard/Download/LG141_TestResults.tar.gz'
    exit 0 ;;
  restore)
    [ -f "$BACKUP" ] || { echo 'No setting backup found; no settings changed'; exit 9; }
    while IFS='|' read -r key value; do
      case "$key" in
        liquidglass_kyant_visibility_gate_probe_288141|liquidglass_kyant_ui_frame_probe_288140|liquidglass_kyant_same_frame_probe_288139|liquidglass_kyant_rebound_host_ab_288138) ;;
        *) echo "Unexpected backup key: $key"; exit 9 ;;
      esac
      case "$value" in
        null) su -c "settings delete global '$key'" >/dev/null || exit 9 ;;
        0|1) su -c "settings put global '$key' '$value'" || exit 9 ;;
        *) echo "Invalid backup value: $value"; exit 9 ;;
      esac
    done < "$BACKUP"
    su -c 'am force-stop com.android.launcher; input keyevent KEYCODE_HOME' || exit 9
    mv "$BACKUP" "$BASE/original_settings.restored.$(date +%Y%m%d_%H%M%S).tsv"
    echo 'Restored backed-up settings.'
    exit 0 ;;
  single|multi) ;;
  *) echo 'Usage: sh lg_test.sh single|multi [a1|a2|m1] | pack | restore'; exit 2 ;;
esac
LABEL="${2:-run}"
case "$LABEL" in *[!a-zA-Z0-9_-]*|'') echo 'Invalid label'; exit 2 ;; esac
mkdir -p "$BASE" || exit 3
# Back up both keys before writing either; never silently override an existing backup.
if [ ! -e "$BACKUP" ]; then
  TEMP="$BACKUP.tmp.$$"
  : > "$TEMP" || exit 3
  for key in "$KEY_GATE" "$KEY_PROBE" "$KEY_COORD" "$KEY_HOST"; do
    v="$(su -c "settings get global '$key'" 2>&1)"; rc=$?
    if [ "$rc" -ne 0 ]; then echo "Settings read failed for $key: $v"; rm -f "$TEMP"; exit 4; fi
    case "$v" in null|0|1) ;; *) echo "Invalid initial setting $key=$v"; rm -f "$TEMP"; exit 4 ;; esac
    printf '%s|%s\n' "$key" "$v" >> "$TEMP"
  done
  mv "$TEMP" "$BACKUP" || exit 4
fi
# This test requires a visible, full-glass host. It does not disable any optical effect.
su -c "settings put global '$KEY_HOST' 0 && settings put global '$KEY_GATE' 1 && settings put global '$KEY_PROBE' 1 && settings put global '$KEY_COORD' 1" || exit 5
for pair in "$KEY_HOST:0" "$KEY_GATE:1" "$KEY_PROBE:1" "$KEY_COORD:1"; do
  key="${pair%%:*}"; expected="${pair#*:}"
  v="$(su -c "settings get global '$key'" 2>&1)"; rc=$?
  [ "$rc" -eq 0 ] && [ "$v" = "$expected" ] || { echo "Settings check failed $key=$v expected=$expected"; exit 5; }
done
su -c 'am force-stop com.android.launcher; input keyevent KEYCODE_HOME' || exit 5
sleep 3
VERSION="$(su -c 'dumpsys package top.liquidglass.vector | grep versionName' 2>/dev/null)"
case "$VERSION" in *versionName=1.9.205.288.144*) ;; *) echo "Wrong module version: $VERSION"; exit 6 ;; esac
OUT="$BASE/${MODE}_${LABEL}_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUT" || exit 6
{
  echo "module=$VERSION scene=$MODE label=$LABEL optics=full probe=ui_frame_and_coordinates"
  echo "start_wall=$(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "launcher_pid=$(su -c 'pidof com.android.launcher' 2>&1)"
  echo "device=$(getprop ro.product.model) sdk=$(getprop ro.build.version.sdk)"
  for key in "$KEY_HOST" "$KEY_GATE" "$KEY_PROBE" "$KEY_COORD"; do
    value="$(su -c "settings get global '$key'" 2>&1)"; rc=$?
    echo "SETTING $key exit=$rc value=$value"
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
printf 'FULL GLASS TEST: Check Vector scope and visually confirm clear Kyant glass before the 5-second countdown ends.\n'
printf 'Keep one fixed page, perform repeated right-swipe NO PAGE TURN then release/rebound for 15 seconds.\n'
sleep 5
su -c 'dumpsys gfxinfo com.android.launcher reset' >/dev/null 2>&1
su -c "date +%s%N > '$OUT/start_epoch_ns.txt'"
# Prefer an explicit Perfetto FrameTimeline datasource. A successful command
# indicates a trace file, NOT necessarily actual FrameTimeline packets; verify
# the track/datasource from the trace before claiming final-present alignment.
cat > "$OUT/trace_config.pbtx" <<'PERFETTO_CONFIG'
buffers { size_kb: 131072 fill_policy: RING_BUFFER }
duration_ms: 15000
data_sources {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_wakeup"
      ftrace_events: "power/cpu_frequency"
      atrace_categories: "gfx"
      atrace_categories: "view"
      atrace_categories: "input"
      atrace_categories: "wm"
      atrace_apps: "com.android.launcher"
    }
  }
}
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
data_sources { config { name: "linux.process_stats" } }
PERFETTO_CONFIG
printf 'FrameTimeline text-config attempt (requires device support and access)\n' > "$OUT/perfetto_status.txt"
su -c "perfetto --txt -c '$OUT/trace_config.pbtx' -o '$OUT/perfetto-trace'" >> "$OUT/perfetto_status.txt" 2>&1
STATUS=$?
if [ "$STATUS" -eq 0 ]; then
  echo 'trace_capture_mode=frametimeline_requested_not_verified' > "$OUT/trace_mode.txt"
else
  echo 'FrameTimeline config failed; falling back to gfx/view/sched/freq. No FrameTimeline claim.' >> "$OUT/perfetto_status.txt"
  rm -f "$OUT/perfetto-trace"
  su -c "perfetto -a com.android.launcher -o '$OUT/perfetto-trace' -t 15s -b 128mb gfx view sched freq" >> "$OUT/perfetto_status.txt" 2>&1
  STATUS=$?
  echo 'trace_capture_mode=atrace_fallback_no_frametimeline_guarantee' > "$OUT/trace_mode.txt"
fi
su -c "date +%s%N > '$OUT/end_epoch_ns.txt'"
su -c "dumpsys gfxinfo com.android.launcher framestats > '$OUT/frames.txt'" 2> "$OUT/frames_error.txt"
su -c "logcat -d -b main -b system -b crash -v threadtime | grep -E 'KYANT288141|KYANT288140|KYANT288139|KYANT288138_RELEASE|KYANT28890_(PREDRAW|COMPOSE_SOURCE)|FATAL EXCEPTION|VectorLegacyBridge' > '$OUT/logcat.txt'" 2> "$OUT/log_error.txt"
snapshot after
printf 'perfetto_exit=%s\n' "$STATUS" >> "$OUT/device_info.txt"
if [ "$STATUS" -ne 0 ]; then echo "WARN: Perfetto failed. See $OUT/perfetto_status.txt"; fi
if ! grep -q 'KYANT288139_SOURCE' "$OUT/logcat.txt"; then echo 'WARN: Source markers missing; check Vector scope, selected host, gesture'; fi
if ! grep -q 'KYANT288139_PREDRAW' "$OUT/logcat.txt"; then echo 'WARN: PreDraw markers missing; check host and gesture'; fi
if ! grep -q 'KYANT288141_GATE' "$OUT/logcat.txt"; then echo 'WARN: Visibility-gate samples missing; check release, selected host and scope'; fi
if ! grep -q 'KYANT288140_UI_FRAME' "$OUT/logcat.txt"; then echo 'WARN: UI frame cadence missing; check touch hook, scope, gesture'; fi
printf 'Saved %s\n' "$OUT"
