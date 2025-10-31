#!/bin/bash

# 分离式双路录制脚本 - 分别录制display和camera供后期编辑使用
# 作者: AI Assistant
# 日期: 2025-10-31

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 配置
DEVICE_SERIAL=""
OUTPUT_DIR="pip_recordings"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
DISPLAY_VIDEO="${OUTPUT_DIR}/display_${TIMESTAMP}.mp4"
CAMERA_VIDEO="${OUTPUT_DIR}/camera_${TIMESTAMP}.mp4"

# Camera竖屏模式 (1=竖屏, 0=横屏, 可通过环境变量 CAMERA_PORTRAIT 覆盖)
CAMERA_PORTRAIT="${CAMERA_PORTRAIT:-0}"
# Camera画面朝向 (支持 0/90/180/270 或 flip variants, 可通过环境变量 CAMERA_ORIENTATION 直接覆盖)
CAMERA_ORIENTATION_OVERRIDE="${CAMERA_ORIENTATION:-}"
# Camera分辨率(如 1080x1920), 可通过环境变量 CAMERA_SIZE 指定
CAMERA_SIZE_OVERRIDE="${CAMERA_SIZE:-}"
# Camera宽高比(如 9:16), 与 CAMERA_SIZE 互斥, 可通过环境变量 CAMERA_AR 指定
CAMERA_AR_OVERRIDE="${CAMERA_AR:-}"

if [ "${CAMERA_PORTRAIT}" = "1" ]; then
    DEFAULT_CAMERA_ORIENTATION="90"
    DEFAULT_CAMERA_SIZE=""
    DEFAULT_CAMERA_AR="9:16"
else
    DEFAULT_CAMERA_ORIENTATION=""
    DEFAULT_CAMERA_SIZE=""
    DEFAULT_CAMERA_AR=""
fi

if [ -n "${CAMERA_ORIENTATION_OVERRIDE}" ]; then
    RESOLVED_CAMERA_ORIENTATION="${CAMERA_ORIENTATION_OVERRIDE}"
else
    RESOLVED_CAMERA_ORIENTATION="${DEFAULT_CAMERA_ORIENTATION}"
fi

if [ -n "${CAMERA_SIZE_OVERRIDE}" ]; then
    RESOLVED_CAMERA_SIZE="${CAMERA_SIZE_OVERRIDE}"
    RESOLVED_CAMERA_AR=""
else
    RESOLVED_CAMERA_SIZE="${DEFAULT_CAMERA_SIZE}"
    if [ -n "${CAMERA_AR_OVERRIDE}" ]; then
        RESOLVED_CAMERA_AR="${CAMERA_AR_OVERRIDE}"
    else
        RESOLVED_CAMERA_AR="${DEFAULT_CAMERA_AR}"
    fi
fi

# 函数:打印彩色信息
info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
    exit 1
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

# 函数:清理临时文件
cleanup() {
    info "清理中..."
    # 停止所有scrcpy进程
    pkill -f "scrcpy.*--video-source" 2>/dev/null || true
    # 等待进程完全退出
    sleep 1
}

# 注册清理函数
trap cleanup EXIT INT TERM

# 创建输出目录
mkdir -p "${OUTPUT_DIR}"

# 检查设备
info "检查ADB设备..."
if ! adb devices | grep -q "device$"; then
    error "未找到ADB设备,请确保设备已连接"
fi

DEVICE_SERIAL=$(adb devices | grep "device$" | head -1 | awk '{print $1}')
info "使用设备: ${DEVICE_SERIAL}"

# 检查scrcpy
if [ ! -x "./run" ]; then
    error "未找到scrcpy可执行文件 ./run"
fi

info "========================================="
info "  display & camera 分离录制方案"
info "========================================="
info "输出目录: ${OUTPUT_DIR}"
info "时间戳: ${TIMESTAMP}"
info ""
warn "请注意:"
warn "1. 将同时启动两个scrcpy窗口(display和camera)"
warn "2. 请保持两个窗口都在运行"
warn "3. 按 Ctrl+C 停止录制"
warn "4. 停止后会在输出目录保留原始视频, 需自行后期合成"
info ""
info "3秒后自动开始录制..."
sleep 3 

# 步骤1: 启动display录制
info "启动display录制..."
./run x --video-source=display --no-audio -m1280 --record="${DISPLAY_VIDEO}" \
    --window-title="Display Recording" \
    > "${OUTPUT_DIR}/display_${TIMESTAMP}.log" 2>&1 &
DISPLAY_PID=$!
sleep 2

if ! ps -p ${DISPLAY_PID} > /dev/null; then
    error "Display录制启动失败"
fi
success "Display录制已启动 (PID: ${DISPLAY_PID})"

# 步骤2: 启动camera录制
CAMERA_ARGS=()

if [ -n "${RESOLVED_CAMERA_ORIENTATION}" ]; then
    ORIENTATION_DISPLAY="${RESOLVED_CAMERA_ORIENTATION#@}"
    CAPTURE_ORIENTATION="${RESOLVED_CAMERA_ORIENTATION}"
    if [[ "${CAPTURE_ORIENTATION}" != @* ]]; then
        CAPTURE_ORIENTATION="@${CAPTURE_ORIENTATION}"
    fi

    if [[ "${ORIENTATION_DISPLAY}" =~ ^[0-9]+$ ]]; then
        info "Camera画面顺时针旋转: ${ORIENTATION_DISPLAY}° (锁定 ${CAPTURE_ORIENTATION})"
    else
        info "Camera画面方向参数: ${ORIENTATION_DISPLAY} (锁定 ${CAPTURE_ORIENTATION})"
    fi

    CAMERA_ARGS+=("--capture-orientation=${CAPTURE_ORIENTATION}")
    CAMERA_ARGS+=("--orientation=${ORIENTATION_DISPLAY}")
    if [[ "${ORIENTATION_DISPLAY}" =~ ^[0-9]+$ ]]; then
        CAMERA_ARGS+=("--record-orientation=${ORIENTATION_DISPLAY}")
    fi
fi

if [ -n "${RESOLVED_CAMERA_SIZE}" ]; then
    info "Camera分辨率: ${RESOLVED_CAMERA_SIZE}"
    CAMERA_ARGS+=("--camera-size=${RESOLVED_CAMERA_SIZE}")
elif [ -n "${RESOLVED_CAMERA_AR}" ]; then
    info "Camera宽高比: ${RESOLVED_CAMERA_AR}"
    CAMERA_ARGS+=("--camera-ar=${RESOLVED_CAMERA_AR}")
fi

info "启动camera录制..."
./run x --video-source=camera --no-audio "${CAMERA_ARGS[@]}" --record="${CAMERA_VIDEO}" \
    --window-title="Camera Recording" \
    > "${OUTPUT_DIR}/camera_${TIMESTAMP}.log" 2>&1 &
CAMERA_PID=$!
sleep 2

if ! ps -p ${CAMERA_PID} > /dev/null; then
    kill ${DISPLAY_PID} 2>/dev/null || true
    error "Camera录制启动失败"
fi
success "Camera录制已启动 (PID: ${CAMERA_PID})"

info ""
success "✓ 两路录制都已启动!"
info "Display窗口: 主画面"
info "Camera窗口: 备用画面"
info ""
warn "按 Ctrl+C 停止录制..."

# 等待用户中断
wait ${DISPLAY_PID} ${CAMERA_PID} 2>/dev/null || true

# 步骤3: 检查录制文件
info ""
info "检查录制文件..."

if [ ! -f "${DISPLAY_VIDEO}" ]; then
    error "Display视频文件未生成"
fi

if [ ! -f "${CAMERA_VIDEO}" ]; then
    error "Camera视频文件未生成"
fi

DISPLAY_SIZE=$(ls -lh "${DISPLAY_VIDEO}" | awk '{print $5}')
CAMERA_SIZE=$(ls -lh "${CAMERA_VIDEO}" | awk '{print $5}')

info "Display视频: ${DISPLAY_SIZE}"
info "Camera视频: ${CAMERA_SIZE}"

info ""
info "原始文件保留在: ${OUTPUT_DIR}"
info "  - Display: ${DISPLAY_VIDEO}"
info "  - Camera: ${CAMERA_VIDEO}"

info ""
success "========================================="
success "  录制完成!"
success "========================================="
info "如需画中画合成, 请使用你熟悉的剪辑工具"
info ""

