#!/system/bin/sh
# V288.152 opt-in consumption-side sync experiment; restore original state.
KEY=liquidglass_kyant_rebound_consume_ab_288152
SAVE="$HOME/.lg152_rebound_consume_original"
read_value() { su -c "settings get global $KEY"; }
restart() { su -c 'am force-stop com.android.launcher; input keyevent KEYCODE_HOME'; }
case "${1:-}" in
  status)
    echo "consume_ab=$(read_value)"
    echo "pipeline=$(su -c 'settings get global liquidglass_kyant_pipeline_ab_28819')"
    echo "persistent=$(su -c 'settings get global liquidglass_kyant_persistent_probe_2884')"
    ;;
  baseline|candidate)
    if [ ! -f "$SAVE" ]; then read_value > "$SAVE" || exit 1; fi
    if [ "$1" = candidate ]; then val=1; else val=0; fi
    su -c "settings put global $KEY $val" || exit 1
    restart
    echo "mode=$1 consume_ab=$val. Stop and restore if optical glass is abnormal."
    ;;
  restore)
    if [ ! -f "$SAVE" ]; then echo "No backup: refusing to guess" >&2; exit 1; fi
    old=$(cat "$SAVE")
    case "$old" in
      null|'') su -c "settings delete global $KEY" || exit 1 ;;
      0|1) su -c "settings put global $KEY $old" || exit 1 ;;
      *) echo "Unrecognized backup $old" >&2; exit 1 ;;
    esac
    restart
    rm -f "$SAVE"
    echo "restored=$old"
    ;;
  *) echo "Usage: sh lg152_consume_ab.sh baseline|candidate|restore|status" >&2; exit 2 ;;
esac
