#!/system/bin/sh
# README capture choreography (English UI, 480x480 emulator, log-only transport).
sleep 2
# direct mode: two keycaps fire immediately
input tap 240 70
sleep 1
input tap 410 240
sleep 2
# hub long-press opens the menu
input swipe 240 240 240 240 900
sleep 2.5
# key map screen
input tap 240 325
sleep 2.5
input tap 146 78
sleep 1.2
# input log (now carries the events from above)
input swipe 240 420 240 150 400
sleep 0.8
input tap 240 409
sleep 2.5
input tap 146 78
sleep 1.2
# settings
input tap 240 333
sleep 2.5
input swipe 240 380 240 90 400
sleep 1.5
input swipe 240 380 240 90 400
sleep 1.5
input tap 146 78
sleep 1.2
input tap 146 78
sleep 1.5
# switch to the sequence profile (two hub taps) and dial a code
input tap 240 240
sleep 1
input tap 240 240
sleep 1.5
input tap 240 70
sleep 0.6
input tap 410 240
sleep 0.6
input tap 240 410
sleep 0.6
input tap 410 240
sleep 0.6
input tap 348 101
sleep 3
