#!/system/bin/sh
# LG141 trace-verified rebound capture v7; existing V288.141 APK, no rebuild.
# Fix: perfetto --background-wait ready handshake (do not monitor su process PID).
# Usage: sh lg141_rebound_trace_verified_v7.sh single a1
# Requires Termux python3 for actual Perfetto protobuf time verification.
set -u
SCENE="${1:-single}"
LABEL="${2:-a1}"
case "$SCENE" in single|multi) ;; *) echo 'Usage: sh lg141_rebound_trace_verified_v7.sh single|multi [label]';exit 2;; esac
case "$LABEL" in *[!a-zA-Z0-9_-]*|'') echo 'Invalid label';exit 2;; esac
command -v python3 >/dev/null 2>&1 || { echo 'ERROR: python3 not available in Termux. Install with: pkg install python'; exit 2; }
command -v su >/dev/null 2>&1 || { echo 'ERROR: su is unavailable'; exit 2; }
BASE=/sdcard/Download/LG141_TraceVerified
OUT="$BASE/${SCENE}_${LABEL}_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUT" || exit 3
KEYS='liquidglass_kyant_same_frame_probe_288139 liquidglass_kyant_ui_frame_probe_288140 liquidglass_kyant_visibility_gate_probe_288141'
HOST='liquidglass_kyant_rebound_host_ab_288138'
root_get() { su -c "settings get global '$1'" 2>/dev/null; }
HOSTVAL="$(root_get "$HOST")" || exit 4
[ "$HOSTVAL" = 0 ] || { echo "ERROR: full-glass Host is not enabled ($HOST=$HOSTVAL). Do not run until corrected.";exit 4; }
VERSION="$(su -c 'dumpsys package top.liquidglass.vector | grep versionName' 2>/dev/null)"
case "$VERSION" in *versionName=1.9.205.288.141*) ;; *) echo "ERROR: Expected V288.141, found: $VERSION";exit 4;; esac
BACKUP="$OUT/original_settings.tsv"
: > "$BACKUP"
for k in $KEYS; do
  v="$(root_get "$k")" || { echo "ERROR: cannot read $k";exit 4; }
  case "$v" in null|0|1) ;; *) echo "ERROR: unexpected setting $k=$v";exit 4;; esac
  printf '%s|%s\n' "$k" "$v" >> "$BACKUP"
done
CHANGED=0
LOG_PID=''
stop_live_log() {
  case "$LOG_PID" in *[!0-9]*|'') return 0;; esac
  su -c "kill '$LOG_PID'" >/dev/null 2>&1 || true
  LOG_PID=''
}
restore() {
  stop_live_log
  [ "$CHANGED" = 1 ] || return 0
  while IFS='|' read -r k v; do
    [ -n "$k" ] || continue
    if [ "$v" = null ]; then su -c "settings delete global '$k'" >/dev/null 2>&1 || true
    else su -c "settings put global '$k' '$v'" >/dev/null 2>&1 || true; fi
  done < "$BACKUP"
  su -c 'am force-stop com.android.launcher; input keyevent KEYCODE_HOME' >/dev/null 2>&1 || true
}
trap 'restore' EXIT
trap 'echo "Interrupted. Partial files retained: $OUT"; exit 130' HUP INT TERM
CHANGED=1
for k in $KEYS; do su -c "settings put global '$k' 1" >/dev/null || exit 5;done
su -c 'am force-stop com.android.launcher; input keyevent KEYCODE_HOME' >/dev/null || exit 5
sleep 4
[ -n "$(su -c 'pidof com.android.launcher' 2>/dev/null)" ] || { echo 'ERROR: Launcher not running';exit 5; }
for k in $KEYS; do [ "$(root_get "$k")" = 1 ] || { echo "ERROR: activation failed $k"; exit 5; };done
cat > "$OUT/trace_config.pbtx" <<'CONF'
buffers { size_kb: 262144 fill_policy: RING_BUFFER }
duration_ms: 120000
data_sources { config { name: "linux.ftrace" ftrace_config {
  ftrace_events: "sched/sched_switch"
  ftrace_events: "sched/sched_wakeup"
  ftrace_events: "power/cpu_frequency"
  atrace_categories: "gfx"
  atrace_categories: "view"
  atrace_categories: "input"
  atrace_categories: "wm"
  atrace_apps: "com.android.launcher"
} } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
data_sources { config { name: "linux.process_stats" } }
CONF
{ echo "module=$VERSION"; echo "scene=$SCENE"; echo "label=$LABEL"; echo "host=$HOSTVAL"; date; } > "$OUT/manifest.txt"
{
  echo "before_launcher_pid=$(su -c 'pidof com.android.launcher' 2>/dev/null)"
  echo "before_epoch=$(date +%s)"
  for k in $KEYS; do echo "before_$k=$(root_get "$k")"; done
} > "$OUT/probe_runtime.txt"
# 不清空系统日志缓冲区，避免删除已有排障证据。
# 用独立滚动日志保存采集全过程，解决“120 秒结束才 dump 导致日志丢失”的问题。
# -f/-r/-n 由 Android logcat 直接负责写入与轮转，避免 Termux 后台调度影响。
LOG_PID="$(su -c "sh -c 'logcat -b main -b system -b crash -v threadtime -T 1 -f \"$OUT/live_logcat.txt\" -r 16384 -n 8 </dev/null >/dev/null 2>&1 & echo \$!'" 2>/dev/null | tr -d '\r' | tail -1)"
case "$LOG_PID" in
  *[!0-9]*|'') echo '警告：持续日志采集启动结果不确定，将保留结束时的 logcat 快照。'; LOG_PID='';;
  *) echo "持续日志采集进程 PID=$LOG_PID";;
esac
sleep 2
if [ ! -f "$OUT/live_logcat.txt" ]; then
  echo '警告：尚未看到持续日志文件，请检查 root 对 Download 目录的写入权限。'
fi
printf 'live_logcat_pid=%s\n' "$LOG_PID" > "$OUT/log_capture_status.txt"
su -c 'dumpsys gfxinfo com.android.launcher reset' >/dev/null 2>&1 || true
printf '\n===== 正在准备 Perfetto 采集 =====\n'
echo '本脚本会在录制开始后自动返回桌面，无需手动切换。'
echo '请确保目标文件夹位于当前桌面页，随后进行至少 5 次短距离右滑松手回弹。'
echo '本次录制最长 120 秒，录制期间不要返回 Termux。'
# --background-wait (-D) returns after Perfetto confirms data-source startup.
# The CLI returning is EXPECTED. It must not be treated as tracing finished.
# Perfetto continues in the background for duration_ms=120000.
# Important: redirect ALL stdio inside the su -c command. Some su implementations
# wait for inherited daemon stdio pipes to close, which delays the shell until
# the 45s capture is already finished. The v3 captures demonstrated this.
# Android/Termux may restrict /proc/uptime; wall-clock timestamps from date are
# sufficient for detecting a delayed handshake. No /proc access or Python needed.
START_BEFORE="$(date +%s)"
case "$START_BEFORE" in *[!0-9]*|'') echo 'ERROR: date +%s failed'; exit 6;; esac
if ! su -c "perfetto --background-wait --txt -c '$OUT/trace_config.pbtx' -o '$OUT/perfetto-trace' > '$OUT/perfetto_status.txt' 2>&1 < /dev/null" >/dev/null 2>&1; then
  echo '错误：Perfetto 无法启动：'
  cat "$OUT/perfetto_status.txt"
  exit 6
fi
START_AFTER="$(date +%s)"
case "$START_AFTER" in *[!0-9]*|'') echo 'ERROR: date +%s failed'; exit 6;; esac
START_DELAY=$((START_AFTER - START_BEFORE))
printf 'startup_delay_seconds=%s\n' "$START_DELAY" > "$OUT/startup_check.txt"
if [ "$START_DELAY" -lt 0 ] || [ "$START_DELAY" -gt 8 ]; then
  echo "ERROR: delayed Perfetto startup acknowledgement (${START_DELAY}s). Trace may have completed before the START prompt."
  echo '已停止并保留诊断文件，请检查 su 与 Perfetto 启动行为。'
  cat "$OUT/perfetto_status.txt"
  exit 6
fi
printf 'background_start_ack=ok\n' > "$OUT/perfetto_exit.txt"
echo 'Perfetto 已确认启动，正在后台录制。'
echo '2 秒后将自动返回桌面，返回后请立即连续完成至少 5 次短距离右滑并松手回弹。'
echo '完成操作后保持桌面不动，录制约 2 分钟后返回 Termux 查看结果。'
printf 'trace_ack_wall_epoch=%s\n' "$(date +%s)" >> "$OUT/startup_check.txt"
sleep 2
su -c 'input keyevent KEYCODE_HOME' >/dev/null 2>&1 || echo '警告：自动返回桌面命令失败，请手动返回桌面。'
printf 'home_command_wall_epoch=%s\n' "$(date +%s)" >> "$OUT/startup_check.txt"
printf '\n============= 已开始录制，请在桌面操作 =============\n'
sleep 105
printf '\n============= 录制即将结束 =============\n'
echo '停止滑动，等待 Perfetto 写入追踪文件。'
sleep 20
# Trace is normally finalized after the 45s collection period; allow a short flush window.
TRACE_READY=0
TRACE_BYTES=0
N=0
while [ "$N" -lt 12 ]; do
  if [ -f "$OUT/perfetto-trace" ]; then
    TRACE_BYTES=$(wc -c < "$OUT/perfetto-trace" 2>/dev/null || echo 0)
    case "$TRACE_BYTES" in *[!0-9]*|'') TRACE_BYTES=0 ;; esac
    if [ "$TRACE_BYTES" -gt 10000 ]; then TRACE_READY=1; break; fi
  fi
  N=$((N+1))
  sleep 1
done
if [ "$TRACE_READY" -ne 1 ]; then
  echo '错误：未产生有效的 Perfetto Trace 文件。'
  cat "$OUT/perfetto_status.txt"
  exit 6
fi
printf 'trace_file_bytes=%s\n' "$TRACE_BYTES" >> "$OUT/perfetto_exit.txt"
{
  echo "after_launcher_pid=$(su -c 'pidof com.android.launcher' 2>/dev/null)"
  echo "after_epoch=$(date +%s)"
  for k in $KEYS; do echo "after_$k=$(root_get "$k")"; done
} >> "$OUT/probe_runtime.txt"
# 采集结束立刻停止持续保存，确保文件写入完成。
stop_live_log
sleep 2
# 保留当前系统日志快照作为后备，并筛选持续捕获的完整原始日志。
su -c "logcat -d -b main -b system -b crash -v threadtime > '$OUT/logcat_snapshot.txt'" 2>/dev/null || true
: > "$OUT/logcat.txt"
FOUND_LIVE=0
for f in "$OUT"/live_logcat.txt*; do
  [ -f "$f" ] || continue
  FOUND_LIVE=1
  grep -E 'VectorLegacyBridge|KyantGate288141|KYANT288139|KYANT288140|KYANT288141|AndroidRuntime|FATAL EXCEPTION' "$f" >> "$OUT/logcat.txt" || true
done
if [ "$FOUND_LIVE" -ne 1 ]; then
  echo '警告：持续日志文件缺失，改用结束时快照（可能遗漏早期记录）。'
  grep -E 'VectorLegacyBridge|KyantGate288141|KYANT288139|KYANT288140|KYANT288141|AndroidRuntime|FATAL EXCEPTION' "$OUT/logcat_snapshot.txt" > "$OUT/logcat.txt" || true
fi
printf 'live_file_found=%s\n' "$FOUND_LIVE" >> "$OUT/log_capture_status.txt"
printf 'filtered_log_lines=%s\n' "$(wc -l < "$OUT/logcat.txt")" >> "$OUT/log_capture_status.txt"
for k in 288139 288140 288141; do
  printf 'tag_%s_count=%s\n' "$k" "$(grep -c "KYANT$k" "$OUT/logcat.txt" || true)" >> "$OUT/log_capture_status.txt"
done
# 单独提取可见/不可见样本。两类数据不能混作屏幕上可见偏差。
grep 'KYANT288141_GATE' "$OUT/logcat.txt" | grep 'visible=true' > "$OUT/gate_visible.txt" || true
grep 'KYANT288141_GATE' "$OUT/logcat.txt" | grep 'visible=false' > "$OUT/gate_offscreen.txt" || true
su -c "dumpsys gfxinfo com.android.launcher framestats > '$OUT/frames.txt'" || true
# 对可见/屏幕外 Gate 分开统计，明确这些只是“提交前”坐标差异。
python3 - "$OUT/gate_visible.txt" "$OUT/gate_offscreen.txt" "$OUT/gate_summary.txt" <<'PY_GATE'
import re, sys, statistics
out=[]
for label, path in [('visible',sys.argv[1]),('offscreen',sys.argv[2])]:
    vals=[]; blocked=0; total=0
    for line in open(path,encoding='utf-8',errors='replace'):
        total+=1
        blocked+=('blocked=true' in line)
        m=re.search(r'\bgapPx=([-+\d.]+)',line)
        if m: vals.append(float(m.group(1)))
    out.append('%s_samples=%s' % (label,total))
    out.append('%s_blocked=%s' % (label,blocked))
    if vals:
        out.append('%s_gap_median_px=%.3f' % (label,statistics.median(vals)))
        out.append('%s_gap_max_px=%.3f' % (label,max(vals)))
out.append('scope=pre_commit_not_presented; not evidence of presented frame offset')
open(sys.argv[3],'w').write('\n'.join(out)+'\n')
PY_GATE
# Verify against ftrace event timestamps from the generated binary Trace.
# This is more accurate than timing against the outer shell's /proc/uptime.
python3 - "$OUT/perfetto-trace" "$OUT/logcat.txt" "$OUT/validation.txt" <<'PY'
import sys, re, os
trace_path, log_path, report_path = sys.argv[1:4]

def varint(b, p, end):
    v = 0; shift = 0
    while p < end and shift < 70:
        c=b[p];p+=1;v|=(c&127)<<shift
        if c < 128:return v,p
        shift+=7
    raise ValueError('Invalid protobuf varint')

def fields(b, start=0, end=None):
    if end is None:end=len(b)
    p=start
    while p<end:
        key,p=varint(b,p,end); no=key>>3; wt=key&7
        if wt==0:
            val,p=varint(b,p,end)
        elif wt==2:
            size,p=varint(b,p,end)
            if size > end-p:raise ValueError('Truncated protobuf bytes')
            val=(p,p+size);p+=size
        elif wt==1:
            p+=8;val=None
        elif wt==5:
            p+=4;val=None
        else:raise ValueError('Unsupported protobuf wire type')
        if p>end:raise ValueError('Truncated protobuf value')
        yield no,wt,val

def run():
    raw=open(trace_path,'rb').read()
    if len(raw)<10000:raise ValueError('Trace missing or too short')
    time_first=None;time_last=None;ev_count=0; packets=0
    for fno,wt,span in fields(raw):
        if fno!=1 or wt!=2:continue
        packets+=1
        for no,w,v in fields(raw,*span):
            if no!=1 or w!=2:continue # TracePacket.ftrace_events
            for fno2,w2,s2 in fields(raw,*v):
                if fno2!=2 or w2!=2:continue # FtraceEventBundle.event
                for event_no,ew,ts in fields(raw,*s2):
                    if event_no==1 and ew==0: # FtraceEvent.timestamp
                        ev_count+=1
                        if time_first is None or ts<time_first:time_first=ts
                        if time_last is None or ts>time_last:time_last=ts
                        break
    if time_first is None:raise ValueError('No timestamped ftrace events found')
    releases=[]
    for line in open(log_path,encoding='utf8',errors='replace'):
        if 'KYANT288139_RELEASE' not in line or 'action=UP' not in line:continue
        mt=re.search(r'\btNs=(\d+)',line)
        if mt:
            dx=re.search(r'\bdx=([-+\d.]+)',line)
            releases.append((int(mt.group(1)),dx.group(1) if dx else '?'))
    # Intentionally exclude the start/end 1.5s, to avoid boundary ambiguity.
    margin=1500000000
    inside=[(ts,dx) for ts,dx in releases if time_first+margin<=ts<=time_last-margin]
    duration=(time_last-time_first)/1e9
    # Max single drop may reflect ftrace having gaps, so require 30s of observed clock-span.
    qualified=duration>=30 and len(inside)>=5
    out=[f'trace_bytes={len(raw)}', f'packets={packets}',f'ftrace_events={ev_count}',
      f'trace_first_ftrace_ns={time_first}',f'trace_last_ftrace_ns={time_last}',
      f'trace_span_seconds={duration:.3f}',f'total_release_up={len(releases)}',
      f'inside_release_up={len(inside)}',f'releases_inside_dx={[d for _,d in inside]}',
      'possible_page_turns=NOT_AUTOMATICALLY_DETERMINED',
      'frametimeline_presence=NOT_VERIFIED_BY_THIS_VALIDATOR',
      'WARNING=This validates trace timing and UP events, not whether each swipe rebounded or was actually presented.',
      f'result={"PASS_TIMING" if qualified else "FAIL_TIMING"}']
    open(report_path,'w').write('\n'.join(out)+'\n')
    print('\n'.join(out[-7:]))
    return 0 if qualified else 7
try:sys.exit(run())
except Exception as e:
    text='result=FAIL_VALIDATOR\nerror='+str(e)+'\n'
    open(report_path,'w').write(text)
    print(text.strip())
    sys.exit(8)
PY
VALID_RC=$?
if [ "$VALID_RC" -ne 0 ];then echo '警告：时间窗校验未通过，请保留压缩包供分析。';fi
printf '===== V7 采集摘要 =====\n'
cat "$OUT/log_capture_status.txt"
cat "$OUT/validation.txt" | grep -E 'trace_span_seconds|total_release_up|inside_release_up|result=' || true
printf '可见 Gate 记录：%s 条；屏幕外 Gate 记录：%s 条\n' "$(wc -l < "$OUT/gate_visible.txt")" "$(wc -l < "$OUT/gate_offscreen.txt")"
ARCHIVE="/sdcard/Download/LG141_TraceVerified_${SCENE}_${LABEL}_$(date +%Y%m%d_%H%M%S).tar.gz"
( cd "$BASE" && tar -czf "$ARCHIVE" "$(basename "$OUT")" ) || { echo 'ERROR: packaging failed';exit 8; }
echo "压缩包位置：$ARCHIVE"
echo '正在恢复诊断开关原始状态；光学参数未修改。'
if [ "$VALID_RC" -ne 0 ]; then exit 7;fi
exit 0
