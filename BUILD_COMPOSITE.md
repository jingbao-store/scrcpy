# 构建和使用 scrcpy 画中画模式

## 概述

本分支添加了画中画（Picture-in-Picture）功能，可以同时捕获 Android 设备的屏幕和摄像头，并实时合成为单一视频流。

## 新增功能

- ✅ 同时捕获屏幕和摄像头
- ✅ 实时 OpenGL 合成
- ✅ 画中画布局（摄像头在右下角）
- ✅ 支持录制和实时显示
- ✅ 兼容所有现有的 scrcpy 功能

## 构建步骤

### 1. 安装依赖

**macOS:**
```bash
brew install sdl2 ffmpeg libusb
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt install meson ninja-build \
    libsdl2-dev libavcodec-dev libavformat-dev libavutil-dev \
    libswresample-dev libusb-1.0-0-dev
```

**Windows:**
请参考官方文档：https://github.com/Genymobile/scrcpy/blob/master/doc/build.md#windows

### 2. 构建 Android 服务端

```bash
cd /Users/nicholasmac/Documents/code/scrcpy

# 构建服务端 JAR
cd server
./gradlew assembleRelease

# 服务端 JAR 将生成在：
# server/build/outputs/apk/release/server-release-unsigned.apk
```

### 3. 构建客户端

```bash
cd /Users/nicholasmac/Documents/code/scrcpy

# 配置构建
meson setup x --buildtype=release --strip -Db_lto=true

# 编译
ninja -Cx

# 二进制文件位于：x/app/scrcpy
```

### 4. 安装（可选）

```bash
ninja -Cx install
```

或者直接运行：
```bash
./run
```

## 快速测试

### 1. 连接设备

```bash
adb devices
```

### 2. 运行测试脚本

```bash
./test_composite.sh
```

### 3. 手动测试基本功能

```bash
# 基本画中画模式
./run --video-source=composite

# 录制 30 秒视频
./run --video-source=composite --record=test.mp4 &
PID=$!
sleep 30
kill $PID

# 播放录制的视频
ffplay test.mp4
```

## 使用示例

### 游戏录制（带主播画面）

```bash
scrcpy --video-source=composite \
       --camera-facing=front \
       --camera-size=640x480 \
       --video-codec=h265 \
       --video-bit-rate=12M \
       --record=gameplay.mp4
```

### 教学视频

```bash
scrcpy --video-source=composite \
       --camera-facing=front \
       -m1920 \
       --max-fps=30 \
       --record=tutorial.mp4
```

### AR 应用演示

```bash
scrcpy --video-source=composite \
       --camera-facing=back \
       --camera-size=1920x1080 \
       --record=ar_demo.mp4
```

## 代码修改摘要

### Android 服务端 (Java)

1. **VideoSource.java** - 添加 `COMPOSITE` 枚举
2. **PictureInPictureFilter.java** (新建) - OpenGL 画中画合成滤镜
3. **DualSourceOpenGLRunner.java** (新建) - 双输入源 OpenGL 运行器
4. **CompositeCapture.java** (新建) - 同时捕获 display 和 camera
5. **Server.java** - 添加 composite 模式支持

### PC 客户端 (C)

1. **options.h** - 添加 `SC_VIDEO_SOURCE_COMPOSITE` 枚举
2. **cli.c** - 添加 `composite` 参数解析和帮助文本

## 目录结构

```
scrcpy/
├── server/src/main/java/com/genymobile/scrcpy/
│   ├── video/
│   │   ├── VideoSource.java          (修改)
│   │   └── CompositeCapture.java     (新建)
│   ├── opengl/
│   │   ├── PictureInPictureFilter.java    (新建)
│   │   └── DualSourceOpenGLRunner.java    (新建)
│   └── Server.java                   (修改)
├── app/src/
│   ├── options.h                     (修改)
│   └── cli.c                        (修改)
├── PICTURE_IN_PICTURE.md            (新建 - 使用文档)
├── BUILD_COMPOSITE.md               (新建 - 本文件)
└── test_composite.sh                (新建 - 测试脚本)
```

## 技术细节

### 工作原理

```
┌─────────────────────────────────────────┐
│           Android 设备                   │
│  ┌─────────────┐    ┌──────────────┐    │
│  │  Display    │    │   Camera     │    │
│  │  Capture    │    │   Capture    │    │
│  └──────┬──────┘    └──────┬───────┘    │
│         │                  │             │
│         ▼                  ▼             │
│  [SurfaceTexture]   [SurfaceTexture]    │
│         │                  │             │
│         └──────────┬───────┘             │
│                    ▼                     │
│         ┌──────────────────┐             │
│         │  OpenGL Composer  │            │
│         │  (画中画合成)      │            │
│         └──────────┬─────────┘           │
│                    ▼                     │
│            [MediaCodec H.264]            │
│                    ▼                     │
│            编码后的视频流                 │
└────────────────────┼────────────────────┘
                     │ (via ADB)
                     ▼
         ┌───────────────────────┐
         │     PC 端               │
         │  解码 & 显示/录制        │
         └───────────────────────┘
```

### 性能优化

- 使用硬件编码器（MediaCodec）
- OpenGL 合成在 GPU 上执行
- 异步帧处理
- 独立的 OpenGL 线程

### 画中画布局

- 主画面：全屏显示屏幕内容
- 画中画：右下角显示摄像头，占主画面 25%
- 边距：2% 的屏幕尺寸
- 支持透明混合

## 故障排除

### 编译错误

**错误**: `VideoSource.COMPOSITE cannot be found`

**解决方案**: 确保先构建服务端，然后再构建客户端

### 运行时错误

**错误**: "Composite capture is not supported"

**解决方案**: 
1. 检查 Android 版本 >= 12
2. 确认摄像头权限已授予
3. 查看 logcat 获取详细错误

```bash
adb logcat | grep scrcpy
```

### 性能问题

**解决方案**: 降低分辨率和帧率

```bash
scrcpy --video-source=composite \
       -m1280 \
       --camera-size=640x480 \
       --max-fps=24
```

## 调试

### 查看 Android 端日志

```bash
adb logcat | grep -E "scrcpy|CompositeCapture|OpenGL|MediaCodec"
```

### 查看可用编码器

```bash
scrcpy --list-encoders
```

### 查看可用摄像头

```bash
scrcpy --list-cameras
```

## 贡献

欢迎提交 Issue 和 Pull Request！

## 相关文档

- [PICTURE_IN_PICTURE.md](PICTURE_IN_PICTURE.md) - 详细使用指南
- [doc/camera.md](doc/camera.md) - 摄像头选项说明
- [doc/video.md](doc/video.md) - 视频编码选项

## 许可证

遵循 scrcpy 的 Apache License 2.0

---

**开发者**: Nicholas Mac  
**日期**: 2025-10-30  
**目的**: 支持 AR 眼镜（Rokid）的屏幕+摄像头同步录制需求

