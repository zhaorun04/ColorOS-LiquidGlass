#!/system/bin/sh
# toybox inotifyd callback: $1 event, $2 watched directory, $3 filename.
[ "$3" = 'wallpaper_screenshot.png' ] && touch /data/local/tmp/.liquidglass-wallpaper-trigger
