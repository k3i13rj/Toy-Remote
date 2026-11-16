#!/usr/bin/env bash
# Runs the TEST build of Toy Remote on the virtual phone for a set time,
# taps the app's own buttons when needed, and saves screenshots + the log.
# The bot code comes from the BOT_TOKEN secret and is never printed.
MINUTES="${1:-15}"
PKG=com.toyremote.app
OUT=test-results
mkdir -p "$OUT"

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant $PKG android.permission.BLUETOOTH_ADVERTISE 2>/dev/null || true
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb logcat -c
adb shell am start -n $PKG/.MainActivity --es test_token "$BOT_TOKEN" --ez test_autostart true
sleep 10

# Finds a button by its text in the current screen and taps its centre.
tap() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb shell cat /sdcard/ui.xml > /tmp/ui.xml 2>/dev/null
  python3 - "$1" <<'PY'
import re, sys, subprocess
want = sys.argv[1].lower()
xml = open('/tmp/ui.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if t and b and t.group(1).lower() == want:
        x1, y1, x2, y2 = map(int, b.groups())
        subprocess.run(['adb', 'shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2)])
        print('tapped', sys.argv[1])
        sys.exit(0)
sys.exit(1)
PY
}

linked=0
tested=0
end=$(( $(date +%s) + MINUTES * 60 ))
shot=0
next_shot=0
echo "Running for $MINUTES minutes. Message the bot in Telegram now."
while [ "$(date +%s)" -lt "$end" ]; do
  if [ $linked -eq 0 ]; then
    if tap "This is me"; then linked=1; sleep 3; fi
  else
    tap "Allow" > /dev/null || true
    if [ $tested -eq 0 ]; then
      if tap "Test Telegram: send me a test message"; then tested=1; fi
    fi
  fi
  now=$(date +%s)
  if [ "$now" -ge "$next_shot" ]; then
    shot=$((shot + 1))
    adb exec-out screencap -p > "$OUT/screen-$(printf %02d $shot).png"
    next_shot=$((now + 60))
  fi
  sleep 4
done

adb exec-out screencap -p > "$OUT/screen-final.png"
adb logcat -d -s ToyRemote:I > "$OUT/app-log.txt" || true
echo "Done."
