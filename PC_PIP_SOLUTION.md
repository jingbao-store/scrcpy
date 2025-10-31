# PC端画中画合成方案

## 方案概述

经过多次尝试设备端实时合成后,发现存在以下问题:
- 显示流帧率极低
- OpenGL纹理采样兼容性问题
- 相机画面显示异常(灰块)

**新方案**:在PC端使用FFmpeg合成两路独立录制的视频,更简单、稳定、可靠。

## 方案优势

✅ **稳定性高**: 不依赖设备端复杂的OpenGL实时合成  
✅ **帧率独立**: 两路流各自以最佳帧率录制,互不干扰  
✅ **灵活可调**: 录制后可以随意调整PIP位置、大小、透明度等  
✅ **兼容性好**: 避免了OES纹理采样的兼容性问题  
✅ **质量更高**: PC端FFmpeg合成质量更好,支持更多特效  

## 使用方法

### 方法1: 使用自动化脚本 (推荐)

```bash
cd /Users/nicholasmac/Documents/code/scrcpy
./record_pip.sh
```

脚本会自动:
1. 同时启动两个scrcpy窗口录制
2. 等待用户按Ctrl+C停止
3. 使用FFmpeg自动合成画中画视频

### 方法2: 手动分步执行

#### 步骤1: 分别录制两路视频

终端1 - 录制display:
```bash
cd /Users/nicholasmac/Documents/code/scrcpy
./run x --video-source=display --no-audio -m1280 --record=display.mp4
```

终端2 - 录制camera:
```bash
cd /Users/nicholasmac/Documents/code/scrcpy
./run x --video-source=camera --no-audio --record=camera.mp4
```

按Ctrl+C同时停止两个录制。

#### 步骤2: 使用FFmpeg合成画中画

```bash
# 基础合成 (camera在右下角, 占主画面25%宽度)
ffmpeg -i display.mp4 -i camera.mp4 \
    -filter_complex "[1:v]scale=iw*0.25:-1[pip]; \
                     [0:v][pip]overlay=main_w-overlay_w-20:main_h-overlay_h-20" \
    -c:v libx264 -preset medium -crf 23 \
    pip_output.mp4
```

## FFmpeg合成参数说明

### 调整PIP尺寸
```bash
# 更大的PIP (主画面的40%)
[1:v]scale=iw*0.4:-1[pip]

# 固定尺寸 (320x180)
[1:v]scale=320:180[pip]
```

### 调整PIP位置

```bash
# 左上角 (边距20px)
overlay=20:20

# 右上角
overlay=main_w-overlay_w-20:20

# 左下角
overlay=20:main_h-overlay_h-20

# 右下角 (默认)
overlay=main_w-overlay_w-20:main_h-overlay_h-20

# 居中
overlay=(main_w-overlay_w)/2:(main_h-overlay_h)/2
```

### 添加边框和阴影

```bash
ffmpeg -i display.mp4 -i camera.mp4 \
    -filter_complex "[1:v]scale=iw*0.25:-1,\
                     drawbox=0:0:iw:ih:yellow:5[pip]; \
                     [0:v][pip]overlay=main_w-overlay_w-20:main_h-overlay_h-20" \
    -c:v libx264 -preset medium -crf 23 \
    pip_with_border.mp4
```

### 添加圆角效果

```bash
ffmpeg -i display.mp4 -i camera.mp4 \
    -filter_complex "[1:v]scale=iw*0.25:-1,\
                     format=yuva420p,\
                     geq=lum='p(X,Y)':a='if(lt(abs(W/2-X),W/2-10)*lt(abs(H/2-Y),H/2-10),255,0)'[pip]; \
                     [0:v][pip]overlay=main_w-overlay_w-20:main_h-overlay_h-20" \
    -c:v libx264 -preset medium -crf 23 \
    pip_rounded.mp4
```

## 配置选项

编辑 `record_pip.sh` 可以调整以下参数:

```bash
# PIP位置: TL=左上, TR=右上, BL=左下, BR=右下
PIP_POSITION="BR"

# PIP宽度占主画面的比例
PIP_WIDTH_RATIO=0.25

# PIP距离边缘的像素
PIP_MARGIN=20
```

## 示例输出

```
pip_recordings/
├── display_20251031_133529.mp4   # 主画面录制
├── camera_20251031_133529.mp4    # 相机录制
└── pip_20251031_133529.mp4       # 最终合成的画中画视频
```

## 故障排除

### 问题: FFmpeg未找到
```bash
# macOS (Homebrew)
brew install ffmpeg

# 或使用已编译的FFmpeg
export PATH="/Users/nicholasmac/Documents/code/scrcpy/ffmpeg-source:$PATH"
```

### 问题: 两个视频时长不一致
FFmpeg会自动以较短的视频为准,也可以指定:
```bash
ffmpeg -i display.mp4 -i camera.mp4 \
    -filter_complex "..." \
    -shortest \  # 以较短视频为准
    output.mp4
```

### 问题: 画面不同步
确保两个scrcpy几乎同时启动,或在FFmpeg中手动调整:
```bash
# 延迟camera 0.5秒
ffmpeg -i display.mp4 -itsoffset 0.5 -i camera.mp4 ...
```

## 与设备端合成的对比

| 特性 | 设备端合成 | PC端合成 ✅ |
|------|-----------|-----------|
| 实现复杂度 | 高 (OpenGL) | 低 (FFmpeg) |
| 稳定性 | 中 (帧率不匹配) | 高 |
| 灵活性 | 低 (固定位置) | 高 (任意调整) |
| 兼容性 | 中 (纹理问题) | 高 |
| 性能影响 | 设备端CPU/GPU | PC端 |
| 实时预览 | 支持 | 录制后处理 |

## 总结

PC端合成方案是更实用和可靠的选择,特别适合:
- 需要高质量输出
- 需要灵活调整PIP参数
- 设备性能有限的场景
- 需要添加更多特效(边框、阴影、动画等)

设备端实时合成方案仍然保留在代码中(`--video-source=composite`),可用于需要实时预览的场景。

