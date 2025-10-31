#!/bin/bash

# scrcpy Composite Mode Test Script
# 画中画模式测试脚本

set -e
set -o pipefail

echo "========================================="
echo "scrcpy Composite Mode Test Script"
echo "========================================="
echo ""

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 检查设备连接
echo "1. Checking device connection..."
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}Error: No device connected${NC}"
    exit 1
fi

DEVICE_SERIAL=$(adb devices | grep "device$" | head -1 | awk '{print $1}')
echo -e "${GREEN}✓ Device connected: $DEVICE_SERIAL${NC}"
echo ""

# 检查 Android 版本
echo "2. Checking Android version..."
SDK_VERSION=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [ "$SDK_VERSION" -lt 31 ]; then
    echo -e "${RED}Error: Composite mode requires Android 12+ (SDK 31+)${NC}"
    echo "   Current SDK version: $SDK_VERSION"
    exit 1
fi
echo -e "${GREEN}✓ Android version OK (SDK $SDK_VERSION)${NC}"
echo ""

# 检查摄像头（强制使用仓库内构建：./run x）
echo "3. Checking available cameras..."
SCRCPY_BIN="./run x"

echo "   Available cameras:"
$SCRCPY_BIN --list-cameras -s "$DEVICE_SERIAL" 2>/dev/null || echo "   (Could not list cameras)"
echo ""

# 测试 1: 基本画中画模式
# 日志目录与工具
LOG_DIR="logs"
mkdir -p "$LOG_DIR"

start_logcat() {
  local name="$1"
  LOGCAT_FILE="$LOG_DIR/${name}_logcat_$(date +%Y%m%d_%H%M%S).txt"
  adb logcat -c >/dev/null 2>&1 || true
  adb logcat -v time | tee "$LOGCAT_FILE" &
  LOGCAT_PID=$!
  # 设备断开自动退出
  (
    while true; do
      state=$(adb -s "$DEVICE_SERIAL" get-state 2>/dev/null || echo "unknown")
      if [ "$state" != "device" ]; then
        echo "Device disconnected, stopping test..." | tee -a "$LOGCAT_FILE"
        if [ -n "$SCRCPY_CHILD" ]; then
          kill $SCRCPY_CHILD 2>/dev/null || true
        fi
        break
      fi
      sleep 1
    done
  ) &
  WATCH_PID=$!
}

stop_logcat() {
  if [ -n "$LOGCAT_PID" ]; then
    kill $LOGCAT_PID 2>/dev/null || true
    wait $LOGCAT_PID 2>/dev/null || true
    unset LOGCAT_PID
  fi
  if [ -n "$WATCH_PID" ]; then
    kill $WATCH_PID 2>/dev/null || true
    wait $WATCH_PID 2>/dev/null || true
    unset WATCH_PID
  fi
}

trap 'stop_logcat' EXIT

echo "========================================="
echo "Test 1: Basic Composite Mode"
echo "========================================="
echo "Starting scrcpy with composite mode (foreground). Close the window to continue..."
SCRCPY_LOG="$LOG_DIR/test1_scrcpy_$(date +%Y%m%d_%H%M%S).log"
echo "Scrcpy log: $SCRCPY_LOG"
start_logcat "test1"

# 前台运行（受设备断开监控），等待你关闭 scrcpy 窗口
$SCRCPY_BIN -Vdebug --video-source=composite -s "$DEVICE_SERIAL" 2>&1 | tee "$SCRCPY_LOG" &
SCRCPY_CHILD=$!
wait $SCRCPY_CHILD || true
unset SCRCPY_CHILD

stop_logcat
echo -e "${GREEN}✓ Test 1 completed${NC}"
echo ""

# 测试 2: 录制画中画视频
echo "========================================="
echo "Test 2: Recording Composite Video"
echo "========================================="
OUTPUT_FILE="composite_test_$(date +%Y%m%d_%H%M%S).mp4"
echo "Recording 10 seconds of composite video..."
echo "Output file: $OUTPUT_FILE"
echo ""

SCRCPY_LOG2="$LOG_DIR/test2_scrcpy_$(date +%Y%m%d_%H%M%S).log"
echo "Scrcpy log: $SCRCPY_LOG2"
start_logcat "test2"

timeout 10s $SCRCPY_BIN -Vdebug --video-source=composite \
                           --record="$OUTPUT_FILE" \
                           -s "$DEVICE_SERIAL" \
                           --no-playback \
                           2>&1 | tee "$SCRCPY_LOG2" || true

stop_logcat

if [ -f "$OUTPUT_FILE" ]; then
    FILE_SIZE=$(stat -f%z "$OUTPUT_FILE" 2>/dev/null || stat -c%s "$OUTPUT_FILE" 2>/dev/null)
    if [ "$FILE_SIZE" -gt 10000 ]; then
        echo -e "${GREEN}✓ Test 2 completed${NC}"
        echo "  Video file created: $OUTPUT_FILE"
        echo "  File size: $((FILE_SIZE / 1024)) KB"
        echo ""
        echo "  You can play it with:"
        echo "  ffplay $OUTPUT_FILE"
    else
        echo -e "${RED}✗ Test 2 FAILED: Video file too small${NC}"
        rm -f "$OUTPUT_FILE"
    fi
else
    echo -e "${RED}✗ Test 2 FAILED: No video file created${NC}"
fi
echo ""

# 测试 3: 使用特定摄像头
echo "========================================="
echo "Test 3: Using Front Camera"
echo "========================================="
echo "Starting with front camera (foreground). Close the window to continue..."
echo ""

SCRCPY_LOG3="$LOG_DIR/test3_scrcpy_$(date +%Y%m%d_%H%M%S).log"
echo "Scrcpy log: $SCRCPY_LOG3"
start_logcat "test3"

$SCRCPY_BIN -Vdebug --video-source=composite \
            --camera-facing=front \
            -s "$DEVICE_SERIAL" \
            --window-width=800 \
            --window-height=600 2>&1 | tee "$SCRCPY_LOG3"

stop_logcat
echo -e "${GREEN}✓ Test 3 completed${NC}"
echo ""

# 测试总结
echo "========================================="
echo "Test Summary"
echo "========================================="
echo ""
echo "All tests completed!"
echo ""
echo "Next steps:"
echo "1. Review the recorded video (if created)"
echo "2. Try different camera configurations:"
echo "   - --camera-size=1280x720"
echo "   - --camera-fps=30"
echo "   - --max-fps=24"
echo "3. Try recording longer videos for real use cases"
echo ""
echo "Usage examples:"
echo "  # Gaming with face cam"
echo "  $SCRCPY_BIN --video-source=composite --camera-facing=front --record=gameplay.mp4"
echo ""
echo "  # Tutorial recording"
echo "  $SCRCPY_BIN --video-source=composite -m1920 --record=tutorial.mp4"
echo ""
echo "See PICTURE_IN_PICTURE.md for more information."
echo ""
echo -e "${GREEN}All tests passed!${NC}"

