#!/system/bin/sh
# V1.9.80: inotify event-driven sync, with 5-minute metadata-only fallback.
# Android toybox inotifyd is optional; fallback works without it.
PKG=top.liquidglass.vector
SRC=/data/system/users/0/wallpaper_screenshot.png
DSTDIR=/data/user/0/$PKG/files
DST=$DSTDIR/wallpaper_reference.jpg
SIGFILE=$DSTDIR/.wallpaper_reference_source_sig
TRIGGER=/data/local/tmp/.liquidglass-wallpaper-trigger
LOG=/data/local/tmp/liquidglass-wallpaper-sync.log
WATCHER_PID=
cleanup() { [ -n "$WATCHER_PID" ] && kill "$WATCHER_PID" 2>/dev/null; rm -f "$TRIGGER"; }
trap cleanup EXIT INT TERM
# Only one service loop; avoid duplicates after manual testing/restarts.
LOCK=/data/local/tmp/.liquidglass-wallpaper-sync.lock
if ! mkdir "$LOCK" 2>/dev/null; then
  OLD=$(cat "$LOCK/pid" 2>/dev/null)
  if [ -n "$OLD" ] && kill -0 "$OLD" 2>/dev/null; then exit 0; fi
  rm -rf "$LOCK"; mkdir "$LOCK" 2>/dev/null || exit 0
fi
printf '%s' "$$" > "$LOCK/pid"
cleanup_all() { cleanup; rm -rf "$LOCK"; }
trap cleanup_all EXIT INT TERM
# inotifyd launches this script for filesystem events in the watched directory.
sleep 20
# Watch source directory for close-write / moved-to / create events.
# If inotifyd is absent or fails, the 5-minute fallback remains active.
if command -v inotifyd >/dev/null 2>&1; then
  inotifyd /data/adb/service.d/99-liquidglass-wallpaper-notify.sh /data/system/users/0:cmw >/dev/null 2>&1 &
  WATCHER_PID=$!
fi
sync_once() {
  [ -f "$SRC" ] && [ -d "$DSTDIR" ] || return
  UIDNUM=$(stat -c %u "/data/user/0/$PKG" 2>/dev/null)
  case "$UIDNUM" in ''|*[!0-9]*) return;; esac
  BEFORE=$(stat -c '%s:%Y' "$SRC" 2>/dev/null)
  [ -n "$BEFORE" ] || return
  PREV=$(cat "$SIGFILE" 2>/dev/null)
  [ "$BEFORE" != "$PREV" ] || return
  # Debounce writes and verify that the source has stabilized.
  sleep 2
  AFTER=$(stat -c '%s:%Y' "$SRC" 2>/dev/null)
  [ "$BEFORE" = "$AFTER" ] || return
  TMP="$DSTDIR/.wallpaper_reference.new.$$"
  if cp "$SRC" "$TMP" 2>/dev/null && [ "$(stat -c %s "$TMP" 2>/dev/null)" -gt 1024 ]; then
    FINAL=$(stat -c '%s:%Y' "$SRC" 2>/dev/null)
    if [ "$FINAL" = "$AFTER" ]; then
      chown "$UIDNUM:$UIDNUM" "$TMP" && chmod 600 "$TMP"
      restorecon "$TMP" 2>/dev/null
      if mv -f "$TMP" "$DST"; then
        printf '%s' "$FINAL" > "$SIGFILE"
        chown "$UIDNUM:$UIDNUM" "$SIGFILE"
        chmod 600 "$SIGFILE"
        restorecon "$DST" "$SIGFILE" 2>/dev/null
        echo "$(date) synced $FINAL" >> "$LOG"
      fi
    fi
  fi
  rm -f "$TMP"
}
sync_once
# Check the event marker every 5s (cheap stat); full source metadata every 300s.
# No wallpaper image reads unless metadata changed.
TICKS=0
while true; do
  sleep 5
  TICKS=$((TICKS + 1))
  if [ -e "$TRIGGER" ]; then
    rm -f "$TRIGGER"
    sync_once
  elif [ "$TICKS" -ge 60 ]; then
    sync_once
  fi
  [ "$TICKS" -ge 60 ] && TICKS=0
done
