#!/system/bin/sh
# ColorOS-LiquidGlass visual rebound capture; no settings modified.
set -eu
OUTDIR=/sdcard/Download
STAMP=$(date +%Y%m%d_%H%M%S)
OUT="$OUTDIR/LG_rebound_visual_$STAMP.mp4"
DURATION=18
BITRATE=16000000
mkdir -p "$OUTDIR"
echo 'ColorOS-LiquidGlass 视觉录制（仅录屏，不更改任何模块设置）'
echo "输出：$OUT"
echo '开始后：先静止约2秒；做3～4次同页短右滑并松手回弹；最后静止约2秒。'
echo '确保文件夹与其外侧清晰壁纸纹理同屏可见，尽量避免通知、触摸指示遮挡。'
echo "录制时长：${DURATION}s；码率：${BITRATE}bps。"
# A direct invocation works in Android adb shell; Termux may need su.
if command -v su >/dev/null 2>&1; then
    if su -c 'command -v screenrecord >/dev/null 2>&1' >/dev/null 2>&1; then
        su -c "screenrecord --time-limit $DURATION --bit-rate $BITRATE '$OUT'"
    else
        screenrecord --time-limit "$DURATION" --bit-rate "$BITRATE" "$OUT"
    fi
else
    screenrecord --time-limit "$DURATION" --bit-rate "$BITRATE" "$OUT"
fi
if [ -s "$OUT" ]; then
  echo "录制完成：$OUT"
  ls -lh "$OUT"
else
  echo '未生成有效视频，请检查 screenrecord 输出信息及存储空间。' >&2
  exit 1
fi
