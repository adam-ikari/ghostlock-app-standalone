#!/usr/bin/env bash
# Collect every device fact the GhostLock port needs, from a stock (non-root) Titan 2.
#
#   ./scripts/titan2-props.sh > artifacts/props-<date>.txt
#
# Everything here is read-only. Nothing is pushed, nothing is modified.
set -u

ADB=${ADB:-adb}
SERIAL=${SERIAL:-}
if [ -n "$SERIAL" ]; then
  ADB="$ADB -s $SERIAL"
fi

section() { printf '\n===== %s =====\n' "$1"; }
run() { printf '\n$ %s\n' "$*"; "$@" 2>&1 | sed 's/^/  /'; }

section "device / adb state"
run "$ADB" get-state
run "$ADB" shell id
run "$ADB" shell getprop ro.product.manufacturer
run "$ADB" shell getprop ro.product.model
run "$ADB" shell getprop ro.product.device
run "$ADB" shell getprop ro.product.name
run "$ADB" shell getprop ro.hardware
run "$ADB" shell getprop ro.board.platform
run "$ADB" shell getprop ro.soc.manufacturer
run "$ADB" shell getprop ro.soc.model
run "$ADB" shell getprop ro.build.version.release
run "$ADB" shell getprop ro.build.version.sdk
run "$ADB" shell getprop ro.build.fingerprint
run "$ADB" shell getprop ro.build.display.id
run "$ADB" shell getprop ro.build.date
run "$ADB" shell getprop ro.bootimage.build.date

section "boot state (locked bootloader?)"
run "$ADB" shell getprop ro.boot.flash.locked
run "$ADB" shell getprop ro.boot.verifiedbootstate
run "$ADB" shell getprop ro.boot.vbmeta.device_state
run "$ADB" shell getprop ro.boot.dynamic_partitions
run "$ADB" shell getprop ro.boot.hardware
run "$ADB" shell getprop ro.boot.baseband
run "$ADB" shell getprop ro.boot.slot_suffix

section "kernel identity (the exact profile key)"
run "$ADB" shell uname -a
run "$ADB" shell uname -r
run "$ADB" shell uname -v
run "$ADB" shell cat /proc/version
run "$ADB" shell cat /proc/sys/kernel/osrelease
run "$ADB" shell ls /proc/sys/kernel/ostype
run "$ADB" shell cat /proc/cmdline

section "kallsyms / iomem visibility (no root: addresses are usually zeroed)"
run "$ADB" shell head -5 /proc/kallsyms
run "$ADB" shell cat /proc/sys/kernel/kptr_restrict
run "$ADB" shell cat /proc/sys/kernel/perf_event_paranoid
run "$ADB" shell ls /proc/iomem
run "$ADB" shell head -40 /proc/iomem

section "kernel config visibility (BTF? KALLSYMS_ALL?)"
run "$ADB" shell ls /proc/config.gz
run "$ADB" shell ls /sys/kernel/btf/vmlinux
run "$ADB" shell ls /sys/kernel/debug
run "$ADB" shell ls /sys/kernel/tracing/events/sched
run "$ADB" shell ls /sys/kernel/tracing

section "selinux"
run "$ADB" shell getenforce
run "$ADB" shell ls /sys/fs/selinux/enforce
run "$ADB" shell cat /sys/fs/selinux/enforce
run "$ADB" shell id -Z

section "existing root? (must be none before we test the exploit)"
run "$ADB" shell which su
run "$ADB" shell ls /system/bin/su
run "$ADB" shell ls /system/xbin/su
run "$ADB" shell pm list packages
run "$ADB" shell ls /data/adb

section "partitions (boot / vendor_boot are what the extractor needs)"
run "$ADB" shell ls -l /dev/block/by-name/boot
run "$ADB" shell ls -l /dev/block/by-name/vendor_boot
run "$ADB" shell ls /dev/block/by-name
run "$ADB" shell "cat /dev/block/by-name/boot >/dev/null 2>&1 && echo BOOT-READABLE || echo BOOT-NOT-READABLE"
run "$ADB" shell "cat /dev/block/by-name/vendor_boot >/dev/null 2>&1 && echo VENDOR_BOOT-READABLE || echo VENDOR_BOOT-NOT-READABLE"

section "modules / kernel build info"
run "$ADB" shell cat /proc/modules
run "$ADB" shell ls /lib/modules
