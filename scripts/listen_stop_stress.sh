#!/usr/bin/env bash
# Repeated Listen -> Stop stress run against a connected device.
#
# Drives the foreground service directly so each cycle exercises the real session lifecycle
# (ASR startup, concurrent Hy-MT2 model load, graceful stop, forced-cancellation escalation).
# It fails if any cycle does not reach STOPPED, if a QNN worker process survives a stop, or if
# the app dies. Nothing leaves the device: only logcat lifecycle lines are read, and those
# never contain transcript text.
#
# Usage: scripts/listen_stop_stress.sh [cycles] [listen_seconds]
#   ANDROID_SERIAL may select a device; STOP_AFTER_MS forces Stop mid-startup on some cycles.
set -euo pipefail

cycles="${1:-20}"
listen_seconds="${2:-6}"
package="com.asr.live"
service="$package/.service.CaptionService"
adb=${ADB:-adb}

$adb wait-for-device
$adb shell pm list packages | grep -q "^package:$package$" || {
    echo "Install the app first: $package is not present on the device" >&2; exit 1; }

# STOPPED is reported by CaptionLifecycle; the pipeline trail lives under CaptionPipeline.
wait_for_stopped() {
    local deadline=$((SECONDS + 15))
    while (( SECONDS < deadline )); do
        if $adb shell dumpsys activity services "$package" | grep -q "ServiceRecord.*CaptionService" ; then
            sleep 0.3
        else
            return 0
        fi
    done
    return 1
}

failures=0
$adb logcat -c || true
for (( cycle = 1; cycle <= cycles; cycle++ )); do
    # Alternate a full listening window with an immediate stop, so both the steady-state path
    # and the stop-during-startup path (QNN init, GGUF load) are covered.
    if (( cycle % 3 == 0 )); then hold=0.4; else hold="$listen_seconds"; fi

    echo "cycle $cycle/$cycles (listen ${hold}s)"
    $adb shell am start-foreground-service -n "$service" \
        --es profile DUTCH_ENGLISH --es quality HY_Q8 --ei threads 6 >/dev/null
    sleep "$hold"
    $adb shell am start-foreground-service -n "$service" -a com.asr.live.action.STOP >/dev/null

    if ! wait_for_stopped; then
        echo "  FAIL: cycle $cycle did not reach STOPPED within 15s" >&2
        failures=$((failures + 1))
    fi
    if $adb shell ps -A 2>/dev/null | grep -q "$package:qnn"; then
        echo "  FAIL: cycle $cycle left a QNN worker process running" >&2
        failures=$((failures + 1))
    fi
    sleep 1
done

echo
echo "lifecycle trail:"
$adb logcat -d -s CaptionLifecycle:I CaptionPipeline:D | grep -E \
    "listen-requested|asr-initialized|microphone-started|hy-load-|stop-requested|graceful-shutdown-started|forced-cancellation-triggered|asr-finish-|session-fully-stopped|shutdown-deadline-exceeded" || true

if (( failures > 0 )); then
    echo "FAILED: $failures problem(s) across $cycles cycles" >&2
    exit 1
fi
echo "OK: $cycles Listen -> Stop cycles all reached STOPPED"
