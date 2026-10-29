#!/system/bin/sh
# V1.9.100: fast event-driven sync and 4-second metadata fallback.
# Source is checked twice before atomic replacement; never expose partial copies.
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
  # The first item is the schema marker; 21 fields match HookEntry.restoreBootSnapshot().
  SNAP="LG1"
  for entry in 'enabled:0' 'alpha:210' 'tint:0' 'tint_strength:30' 'highlight:0' 'diagnostics:0' 'highlight_strength:25' 'probe_mode:0' 'highlight_width:2' 'directional_light:1' 'native_blur_experiment:0' 'blur_radius_percent:100' 'native_alpha_probe:1' 'wallpaper_compare:0' 'real_refraction:0' 'custom_blur_dp:14' 'refraction_percent:35' 'glass_tint_opacity:18' 'custom_blur_mode:1' 'clear_refraction:0' 'dynamic_sheen_mode:0'; do
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
  sleep 0.2
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
  [ -f "$SRC" ] || { echo "$(date) launcher reference failed: source missing" >> "$LOG"; return; }
  LAUNCHERBASE=/data/user/0/com.android.launcher
  [ -d "$LAUNCHERBASE" ] || { echo "$(date) launcher reference waiting: launcher data missing" >> "$LOG"; return; }
  LUID=$(stat -c %u "$LAUNCHERBASE" 2>/dev/null)
  LGID=$(stat -c %g "$LAUNCHERBASE" 2>/dev/null)
  case "$LUID:$LGID" in *[!0-9:]*|:*) echo "$(date) launcher reference failed: invalid owner" >> "$LOG"; return;; esac
  if [ ! -d "$LAUNCHERDIR" ]; then
    mkdir -p "$LAUNCHERDIR" || { echo "$(date) launcher reference failed: mkdir" >> "$LOG"; return; }
    chown "$LUID:$LGID" "$LAUNCHERDIR" && chmod 700 "$LAUNCHERDIR"
    restorecon -R "$LAUNCHERDIR" 2>/dev/null
  fi
  BEFORE=$(stat -c '%s:%Y' "$SRC" 2>/dev/null)
  [ -n "$BEFORE" ] || return
  PREV=$(cat "$LAUNCHERSIG" 2>/dev/null)
  if [ "$BEFORE" = "$PREV" ] && [ -s "$LAUNCHERDST" ]; then return; fi
  sleep 0.2
  AFTER=$(stat -c '%s:%Y' "$SRC" 2>/dev/null)
  [ "$BEFORE" = "$AFTER" ] || return
  TMP="$LAUNCHERDIR/.liquidglass_wallpaper.new.$$"
  if cp "$SRC" "$TMP" 2>/dev/null && [ "$(stat -c %s "$TMP" 2>/dev/null)" -gt 1024 ]; then
    FINAL=$(stat -c '%s:%Y' "$SRC" 2>/dev/null)
    if [ "$FINAL" = "$AFTER" ]; then
      chown "$LUID:$LGID" "$TMP" && chmod 600 "$TMP"
      restorecon "$TMP" 2>/dev/null
      if mv -f "$TMP" "$LAUNCHERDST"; then
        printf '%s' "$FINAL" > "$LAUNCHERSIG"
        chown "$LUID:$LGID" "$LAUNCHERSIG"
        chmod 600 "$LAUNCHERSIG"
        restorecon "$LAUNCHERDST" "$LAUNCHERSIG" 2>/dev/null
        if [ -s "$LAUNCHERDST" ]; then
          echo "$(date) launcher reference synced $FINAL" >> "$LOG"
        else
          echo "$(date) launcher reference failed: missing after move" >> "$LOG"
        fi
      fi
    fi
  fi
  rm -f "$TMP"
}
# V1.9.94: do not wait for the five-minute fallback when Android CE
# storage / Launcher private files appear after Magisk service startup.
# Run the first checks immediately; repeat quickly until both copies exist.
sync_config
sync_launcher_reference
sync_once
TICKS=0
BOOT_TICKS=0
while true; do
  if [ "$BOOT_TICKS" -lt 180 ]; then sleep 1; else sleep 2; fi
  sync_config
  TICKS=$((TICKS + 1))
  if [ -e "$TRIGGER" ]; then
    rm -f "$TRIGGER"
    sync_launcher_reference
    sync_once
  elif [ "$BOOT_TICKS" -lt 180 ]; then
    # During the first three minutes, missing paths must not defer
    # synchronization to the normal five-minute maintenance cycle.
    [ -s "$LAUNCHERDST" ] || sync_launcher_reference
    [ -s "$DST" ] || sync_once
    # If the source changed across reboot, signatures will be refreshed.
    if [ $((BOOT_TICKS % 2)) -eq 0 ]; then
      sync_launcher_reference
      sync_once
    fi
  elif [ "$TICKS" -ge 2 ]; then
    sync_launcher_reference
    sync_once
  fi
  [ "$TICKS" -ge 2 ] && TICKS=0
  [ "$BOOT_TICKS" -lt 180 ] && BOOT_TICKS=$((BOOT_TICKS + 1))
done
