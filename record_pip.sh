#!/bin/bash

# 画中画录制脚本 - 分别录制display和camera,然后在PC端合成
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
FINAL_VIDEO="${OUTPUT_DIR}/pip_${TIMESTAMP}.mp4"

# FFmpeg路径
FFMPEG="/Users/nicholasmac/Downloads/FFmpeg/ffmpeg"

# PIP设置
PIP_POSITION="BR"  # TL=左上, TR=右上, BL=左下, BR=右下
PIP_WIDTH_RATIO=0.25  # PIP宽度占主画面的比例
PIP_MARGIN=20  # PIP距离边缘的像素

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
info "  画中画录制 - 分离式录制方案"
info "========================================="
info "输出目录: ${OUTPUT_DIR}"
info "时间戳: ${TIMESTAMP}"
info ""
warn "请注意:"
warn "1. 将同时启动两个scrcpy窗口(display和camera)"
warn "2. 请保持两个窗口都在运行"
warn "3. 按 Ctrl+C 停止录制"
warn "4. 停止后将自动合成画中画视频"
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
info "启动camera录制..."
./run x --video-source=camera --no-audio --record="${CAMERA_VIDEO}" \
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
info "Camera窗口: 将作为PIP叠加"
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

# 步骤4: 使用FFmpeg合成画中画
info ""
info "开始合成画中画视频..."

# 获取主视频分辨率
MAIN_WIDTH=$(ffprobe -v error -select_streams v:0 -show_entries stream=width -of csv=p=0 "${DISPLAY_VIDEO}")
MAIN_HEIGHT=$(ffprobe -v error -select_streams v:0 -show_entries stream=height -of csv=p=0 "${DISPLAY_VIDEO}")

info "主视频分辨率: ${MAIN_WIDTH}x${MAIN_HEIGHT}"

# 计算PIP尺寸和位置
PIP_WIDTH=$(echo "${MAIN_WIDTH} * ${PIP_WIDTH_RATIO}" | bc | cut -d. -f1)
PIP_HEIGHT=$(echo "${PIP_WIDTH} * 9 / 16" | bc)  # 假设16:9比例

case ${PIP_POSITION} in
    TL) # 左上
        OVERLAY_X=${PIP_MARGIN}
        OVERLAY_Y=${PIP_MARGIN}
        ;;
    TR) # 右上
        OVERLAY_X=$((MAIN_WIDTH - PIP_WIDTH - PIP_MARGIN))
        OVERLAY_Y=${PIP_MARGIN}
        ;;
    BL) # 左下
        OVERLAY_X=${PIP_MARGIN}
        OVERLAY_Y=$((MAIN_HEIGHT - PIP_HEIGHT - PIP_MARGIN))
        ;;
    BR) # 右下
        OVERLAY_X=$((MAIN_WIDTH - PIP_WIDTH - PIP_MARGIN))
        OVERLAY_Y=$((MAIN_HEIGHT - PIP_HEIGHT - PIP_MARGIN))
        ;;
esac

info "PIP尺寸: ${PIP_WIDTH}x${PIP_HEIGHT}"
info "PIP位置: (${OVERLAY_X}, ${OVERLAY_Y})"

# FFmpeg合成命令
${FFMPEG} -y -i "${DISPLAY_VIDEO}" -i "${CAMERA_VIDEO}" \
    -filter_complex "[1:v]scale=${PIP_WIDTH}:${PIP_HEIGHT}[pip]; \
                     [0:v][pip]overlay=${OVERLAY_X}:${OVERLAY_Y}" \
    "${FINAL_VIDEO}" \
    > "${OUTPUT_DIR}/ffmpeg_${TIMESTAMP}.log" 2>&1

if [ ! -f "${FINAL_VIDEO}" ]; then
    error "画中画视频合成失败"
fi

FINAL_SIZE=$(ls -lh "${FINAL_VIDEO}" | awk '{print $5}')
success "画中画视频已生成: ${FINAL_VIDEO} (${FINAL_SIZE})"

# 步骤5: 保留原始文件
info ""
info "原始文件保留在: ${OUTPUT_DIR}"
info "  - Display: ${DISPLAY_VIDEO}"
info "  - Camera: ${CAMERA_VIDEO}"

info ""
success "========================================="
success "  录制完成!"
success "========================================="
success "最终视频: ${FINAL_VIDEO}"
info ""
info "可以使用以下命令播放:"
info "  open \"${FINAL_VIDEO}\""
info ""

