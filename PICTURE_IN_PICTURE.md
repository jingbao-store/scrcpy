# Picture-in-Picture (Composite) Mode

scrcpy 现在支持同时捕获屏幕和摄像头，并将它们合成为画中画布局！

## 功能特性

- ✅ **同时捕获** - 屏幕和摄像头同时录制
- ✅ **实时合成** - 在 Android 端使用 OpenGL 实时合成
- ✅ **画中画布局** - 摄像头显示在屏幕右下角
- ✅ **可录制** - 支持录制为视频文件
- ✅ **高性能** - 使用硬件加速编码

## 系统要求

- Android 12+ (API Level 31+)
- 摄像头权限和屏幕录制权限

## 基本使用

### 启动画中画模式

```bash
scrcpy --video-source=composite
```

### 录制画中画视频

```bash
scrcpy --video-source=composite --record=output.mp4
```

### 不显示窗口，仅录制

```bash
scrcpy --video-source=composite --record=output.mp4 --no-playback
```

## 高级选项

### 指定摄像头

```bash
# 使用后置摄像头
scrcpy --video-source=composite --camera-facing=back

# 使用前置摄像头
scrcpy --video-source=composite --camera-facing=front

# 指定特定摄像头 ID
scrcpy --video-source=composite --camera-id=0
```

### 设置摄像头参数

```bash
# 设置摄像头分辨率
scrcpy --video-source=composite --camera-size=1280x720

# 设置摄像头帧率
scrcpy --video-source=composite --camera-fps=30

# 限制最大分辨率
scrcpy --video-source=composite -m1920
```

### 视频编码选项

```bash
# 使用 H.265 编码
scrcpy --video-source=composite --video-codec=h265

# 设置码率
scrcpy --video-source=composite --video-bit-rate=10M

# 限制帧率
scrcpy --video-source=composite --max-fps=30
```

### 音频选项

```bash
# 不录制音频
scrcpy --video-source=composite --no-audio

# 录制音频但不播放
scrcpy --video-source=composite --no-audio-playback --record=output.mp4
```

## 示例场景

### 1. 游戏录制（带主播画面）

```bash
scrcpy --video-source=composite \
       --camera-facing=front \
       --camera-size=640x480 \
       --video-codec=h265 \
       --video-bit-rate=12M \
       --record=gameplay_$(date +%Y%m%d_%H%M%S).mp4
```

### 2. 教学视频录制

```bash
scrcpy --video-source=composite \
       --camera-facing=front \
       -m1920 \
       --max-fps=30 \
       --record=tutorial.mp4
```

### 3. AR 体验录制

```bash
scrcpy --video-source=composite \
       --camera-facing=back \
       --camera-size=1920x1080 \
       --video-codec=h264 \
       --record=ar_demo.mp4
```

## 画中画布局

当前实现：
- **主画面**: 屏幕内容（全屏）
- **画中画**: 摄像头内容（右下角）
- **画中画大小**: 主画面的 25%
- **边距**: 主画面的 2%
- **位置**: 固定在右下角

### 默认配置

```
┌─────────────────────────────┐
│                             │
│    屏幕内容（主画面）         │
│                             │
│                             │
│                             │
│                   ┌───────┐ │
│                   │摄像头  │ │
│                   └───────┘ │
└─────────────────────────────┘
```

## 性能建议

对于 Rokid 设备或其他性能有限的设备：

```bash
scrcpy --video-source=composite \
       -m1280 \
       --camera-size=640x480 \
       --max-fps=24 \
       --video-bit-rate=6M
```

## 故障排除

### 1. 无法启动 composite 模式

**错误**: "Composite capture is not supported before Android 12"

**解决方案**: 确保设备运行 Android 12 或更高版本

```bash
adb shell getprop ro.build.version.sdk
# 应该返回 31 或更高
```

### 2. 摄像头权限被拒绝

**解决方案**: 在设备上手动授予摄像头权限

```bash
adb shell pm grant com.genymobile.scrcpy android.permission.CAMERA
```

### 3. 性能问题或卡顿

**解决方案**: 降低分辨率和帧率

```bash
scrcpy --video-source=composite -m1024 --max-fps=24 --camera-size=480x360
```

### 4. 查看可用摄像头

```bash
scrcpy --list-cameras
```

## 技术实现

### 架构

```
Android 端:
┌────────────────────────────────────────────┐
│  Display Capture    Camera Capture         │
│       ↓                  ↓                  │
│  [SurfaceTexture]   [SurfaceTexture]       │
│       ↓                  ↓                  │
│  ┌─────────────────────────────┐           │
│  │  OpenGL Compositor          │           │
│  │  (PictureInPictureFilter)   │           │
│  └─────────────────────────────┘           │
│               ↓                             │
│         [MediaCodec]                        │
│               ↓                             │
│         Encoded Stream                      │
└────────────────────────────────────────────┘
         ↓ (via ADB/TCP)
PC 端:
┌────────────────────────────────────────────┐
│         Receive & Decode                    │
│               ↓                             │
│         Display / Record                    │
└────────────────────────────────────────────┘
```

### 核心组件

- **CompositeCapture.java**: 主控制器，管理 display 和 camera 捕获
- **DualSourceOpenGLRunner.java**: 管理两个输入 SurfaceTexture
- **PictureInPictureFilter.java**: OpenGL 着色器，合成画中画效果
- **VideoSource.COMPOSITE**: 新的视频源枚举

## 未来改进

可能的功能增强：
- [ ] 可配置的画中画位置（左上、右上、左下、右下）
- [ ] 可配置的画中画大小比例
- [ ] 圆角边框效果
- [ ] 边框颜色和阴影
- [ ] 画中画的拖拽调整（通过控制命令）
- [ ] 多摄像头同时显示

## 相关文档

- [Camera](doc/camera.md) - 摄像头捕获详细说明
- [Video](doc/video.md) - 视频参数配置
- [Recording](doc/recording.md) - 录制选项

## 贡献者

这个功能是为了支持 AR 眼镜（如 Rokid）的屏幕+摄像头同步录制需求而开发的。

## 许可证

遵循 scrcpy 的 Apache License 2.0

