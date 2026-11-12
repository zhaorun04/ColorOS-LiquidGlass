#!/system/bin/sh
# V288.151: controlled test of PRE-EXISTING nativeFrameCommit28863 path.
# No optical source changes. Run from Termux: sh lg151_publish_ab.sh <baseline|candidate|restore|status>
KEY=liquidglass_kyant_native_frame_commit_ab_28863
SAVE="$HOME/.lg151_native_frame_commit_before_ab"
readvalue() { su -c "settings get global $KEY"; }
restart_launcher() { su -c 'am force-stop com.android.launcher; input keyevent KEYCODE_HOME'; }
case "${1:-}" in
  status)
    echo "commit_switch=$(readvalue)"
    echo "pipeline=$(su -c 'settings get global liquidglass_kyant_pipeline_ab_28819')"
    echo "persistent=$(su -c 'settings get global liquidglass_kyant_persistent_probe_2884')"
    su -c 'logcat -d -v threadtime | grep -E "KYANT28863_HOST|KYANT2884_RUN_ERROR|VerifyError" | tail -15'
    ;;
  baseline|candidate)
    if [ ! -f "$SAVE" ]; then
      readvalue > "$SAVE" || exit 1
      echo "Original setting saved to $SAVE: $(cat "$SAVE")"
    fi
    if [ "$1" = baseline ]; then value=0; else value=1; fi
    su -c "settings put global $KEY $value" || exit 1
    restart_launcher
    echo "mode=$1 commit_switch=$value; first check Kyant0 folder and Dock, then swipe without page change"
    ;;
  restore)
    if [ ! -f "$SAVE" ]; then
      echo "No saved value; refusing to guess original setting" >&2; exit 1
    fi
    old=$(cat "$SAVE")
    case "$old" in
      null|'') su -c "settings delete global $KEY" || exit 1 ;;
      0|1) su -c "settings put global $KEY $old" || exit 1 ;;
      *) echo "Unexpected saved value $old; refusing to change" >&2; exit 1 ;;
    esac
    restart_launcher
    rm -f "$SAVE"
    echo "Restored original native frame commit setting: $old"
    ;;
  *)
    echo "Usage: sh lg151_publish_ab.sh baseline|candidate|restore|status" >&2
    exit 2 ;;
esac
