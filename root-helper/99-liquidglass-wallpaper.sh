#!/system/bin/sh
# V1.9.80: inotify event-driven sync, with 5-minute metadata-only fallback.
# Android toybox inotifyd is optional; fallback works without it.
PKG=top.liquidglass.vector
SRC=/data/system/users/0/wallpaper_screenshot.png
DSTDIR=/data/user/0/$PKG/files
DST=$DSTDIR/wallpaper_reference.jpg
SIGFILE=$DSTDIR/.wallpaper_reference_source_sig
# Separate copy owned by Launcher: bypass third-party provider cold-start restrictions.
LAUNCHERDIR=/data/user/0/com.android.launcher/files
LAUNCHERDST=$LAUNCHERDIR/liquidglass_wallpaper_reference.jpg
LAUNCHERSIG=$LAUNCHERDIR/.liquidglass_wallpaper_reference_sig
TRIGGER=/data/local/tmp/.liquidglass-wallpaper-trigger
LOG=/data/local/tmp/liquidglass-wallpaper-sync.log
WATCHER_PID=
cleanup() { [ -n "$WATCHER_PID" ] && kill "$WATCHER_PID" 2>/dev/null; rm -f "$TRIGGER"; }
trap cleanup EXIT INT TERM
# Only one service loop; avoid duplicates after manual testing/restarts.
LOCK=/data/local/tmp/.liquidglass-wallpaper-sync.lock
# Reject stale locks even when the old PID was reused by an unrelated service.
# Do not kill any process: only check the owner identity.
lock_owner_alive() {
  old=$(cat "$LOCK/pid" 2>/dev/null)
  case "$old" in ''|*[!0-9]*) return 1;; esac
  [ -r "/proc/$old/cmdline" ] || return 1
  cmd=$(tr '\000' ' ' < "/proc/$old/cmdline" 2>/dev/null)
  case "$cmd" in *"/data/adb/service.d/99-liquidglass-wallpaper.sh"*) ;; *) return 1;; esac
  old_start=$(cat "$LOCK/starttime" 2>/dev/null)
  [ -n "$old_start" ] || return 1
  now_start=$(sed 's/.*) //' "/proc/$old/stat" 2>/dev/null | awk '{print $20}')
  [ -n "$now_start" ] && [ "$old_start" = "$now_start" ]
}
if ! mkdir "$LOCK" 2>/dev/null; then
  if lock_owner_alive; then exit 0; fi
  rm -rf "$LOCK"
  mkdir "$LOCK" 2>/dev/null || exit 0
fi
printf '%s' "$$" > "$LOCK/pid"
sed 's/.*) //' "/proc/$$/stat" 2>/dev/null | awk '{print $20}' > "$LOCK/starttime"
cleanup_all() { cleanup; rm -rf "$LOCK"; }
trap cleanup_all EXIT INT TERM
# inotifyd launches this script for filesystem events in the watched directory.
 # Watch source directory for close-write / moved-to / create events.
# If inotifyd is absent or fails, the 5-minute fallback remains active.
if command -v inotifyd >/dev/null 2>&1; then
  inotifyd /data/adb/service.d/99-liquidglass-wallpaper-notify.sh /data/system/users/0:cmw >/dev/null 2>&1 &
  WATCHER_PID=$!
fi
# Persist a minimal non-secret configuration snapshot independent of the app provider.
PREF=/data/user/0/$PKG/shared_prefs/glass.xml
SNAPKEY=liquidglass_boot_snapshot_v1
LASTSNAP=""
xml_value() {
  line=$(grep -F "name=\"$1\"" "$PREF" 2>/dev/null | head -n 1)
  case "$line" in *'value="true"'*) echo 1;; *'value="false"'*) echo 0;;
    *) echo "$line" | sed -n 's/.*value="\([-0-9]*\)".*/\1/p';; esac
}
config_value() { v=$(xml_value "$1"); case "$v" in ''|*[!0-9-]*) echo "$2";; *) echo "$v";; esac; }
sync_config() {
  [ -s "$PREF" ] || return
  grep -q 'name="enabled"' "$PREF" 2>/dev/null || return
  # The first item is the schema marker; the remaining 15 match provider columns.
  SNAP="LG1"
  for entry in 'enabled:0' 'alpha:210' 'tint:0' 'tint_strength:30' 'highlight:0' 'diagnostics:0' 'highlight_strength:25' 'probe_mode:0' 'highlight_width:2' 'directional_light:1' 'native_blur_experiment:0' 'blur_radius_percent:100' 'native_alpha_probe:1' 'wallpaper_compare:0' 'real_refraction:0'; do
    key=${entry%%:*}; fallback=${entry#*:}
    SNAP="$SNAP|$(config_value "$key" "$fallback")"
  done
  [ "$SNAP" = "$LASTSNAP" ] && return
  if settings put global "$SNAPKEY" "$SNAP" 2>>"$LOG"; then
    LASTSNAP="$SNAP"
    echo "$(date) config snapshot published" >> "$LOG"
  else
    echo "$(date) config snapshot publish failed" >> "$LOG"
  fi
}
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
sync_launcher_reference() {
  [ -f "$SRC" ] && [ -d "$LAUNCHERDIR" ] || return
  LUID=$(stat -c %u /data/user/0/com.android.launcher 2>/dev/null)
  case "$LUID" in ''|*[!0-9]*) return;; esac
  BEFORE=$(stat -c '%s:%Y' "$SRC" 2>/dev/null)
  [ -n "$BEFORE" ] || return
  PREV=$(cat "$LAUNCHERSIG" 2>/dev/null)
  [ "$BEFORE" != "$PREV" ] || return
  sleep 2
  AFTER=$(stat -c '%s:%Y' "$SRC" 2>/dev/null)
  [ "$BEFORE" = "$AFTER" ] || return
  TMP="$LAUNCHERDIR/.liquidglass_wallpaper.new.$$"
  if cp "$SRC" "$TMP" 2>/dev/null && [ "$(stat -c %s "$TMP" 2>/dev/null)" -gt 1024 ]; then
    FINAL=$(stat -c '%s:%Y' "$SRC" 2>/dev/null)
    if [ "$FINAL" = "$AFTER" ]; then
      chown "$LUID:$LUID" "$TMP" && chmod 600 "$TMP"
      restorecon "$TMP" 2>/dev/null
      if mv -f "$TMP" "$LAUNCHERDST"; then
        printf '%s' "$FINAL" > "$LAUNCHERSIG"
        chown "$LUID:$LUID" "$LAUNCHERSIG"
        chmod 600 "$LAUNCHERSIG"
        restorecon "$LAUNCHERDST" "$LAUNCHERSIG" 2>/dev/null
        echo "$(date) launcher reference synced $FINAL" >> "$LOG"
      fi
    fi
  fi
  rm -f "$TMP"
}
sync_config
sync_launcher_reference
sync_once
# Check the event marker every 5s (cheap stat); full source metadata every 300s.
# No wallpaper image reads unless metadata changed.
TICKS=0
while true; do
  sleep 5
  sync_config
  TICKS=$((TICKS + 1))
  if [ -e "$TRIGGER" ]; then
    rm -f "$TRIGGER"
    sync_once
    sync_launcher_reference
  elif [ "$TICKS" -ge 60 ]; then
    sync_once
    sync_launcher_reference
  fi
  [ "$TICKS" -ge 60 ] && TICKS=0
done
