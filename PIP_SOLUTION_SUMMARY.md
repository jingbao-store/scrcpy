# 画中画录制方案总结

## 📋 项目目标

实现屏幕录制+摄像头的画中画(PIP)合成视频,类似 `recorder` 项目的需求。

## 🔄 方案演进历程

### 方案1: 设备端实时OpenGL合成 ❌

**实现**: 修改scrcpy添加 `--video-source=composite`
- 创建 `CompositeCapture.java` 同时捕获display和camera
- 使用 `DualSourceOpenGLRunner` 进行OpenGL实时合成
- 通过 `PictureInPictureFilter` 实现画中画渲染

**遇到的问题**:
1. ✗ 显示流帧率极低 (60帧 vs 相机2000+帧)
2. ✗ OES纹理采样兼容性问题
3. ✗ 相机画面显示异常(灰色方块)
4. ✗ 即使切换到YUV路径,PIP仍然无法正确显示

**结论**: 设备端实时合成在Rokid眼镜上存在严重的兼容性和性能问题

### 方案2: PC端FFmpeg合成 ✅ (最终方案)

**实现**: 分别录制两路视频,在PC端使用FFmpeg合成

**工作流程**:
```
┌─────────────┐     ┌─────────────┐
│   Display   │     │   Camera    │
│  Recording  │     │  Recording  │
└──────┬──────┘     └──────┬──────┘
       │                   │
       └───────┬───────────┘
               │
        ┌──────▼───────┐
        │   FFmpeg     │
        │  Compositing │
        └──────┬───────┘
               │
        ┌──────▼───────┐
        │  PIP Video   │
        └──────────────┘
```

**优势**:
- ✅ 稳定可靠,不依赖设备端复杂实现
- ✅ 两路流独立录制,各自最佳帧率
- ✅ 录制后可灵活调整PIP参数
- ✅ 无兼容性问题
- ✅ PC端处理质量更高

## 🎯 最终实现

### 文件清单

1. **`record_pip.sh`** - 自动化录制和合成脚本
   - 同时启动display和camera录制
   - 自动计算PIP尺寸和位置
   - 使用FFmpeg合成最终视频

2. **`PC_PIP_SOLUTION.md`** - 详细使用文档
   - 使用方法
   - FFmpeg参数说明
   - 自定义配置选项

3. **设备端composite实现** (保留但不推荐)
   - `VideoSource.COMPOSITE` 枚举
   - `CompositeCapture.java`
   - `DualSourceOpenGLRunner.java`
   - `PictureInPictureFilter.java`
   - `YuvPipOpenGLFilter.java`

### 使用方法

#### 方式1: 自动化脚本 (推荐)

```bash
cd /Users/nicholasmac/Documents/code/scrcpy
./record_pip.sh
```

脚本会:
1. 启动两个scrcpy窗口分别录制
2. 等待用户按Ctrl+C停止
3. 自动使用FFmpeg合成画中画视频

#### 方式2: 手动执行

**步骤1**: 分别录制
```bash
# 终端1 - Display
./run x --video-source=display --no-audio -m1280 --record=display.mp4

# 终端2 - Camera  
./run x --video-source=camera --no-audio --record=camera.mp4
```

**步骤2**: FFmpeg合成
```bash
/Users/nicholasmac/Downloads/FFmpeg/ffmpeg -y \
    -i display.mp4 -i camera.mp4 \
    -filter_complex "[1:v]scale=120:-1[pip];[0:v][pip]overlay=main_w-overlay_w-10:main_h-overlay_h-10" \
    pip_output.mp4
```

### 测试结果

已成功测试:
- ✅ Display录制: 480x640, ~12秒, 30KB
- ✅ Camera录制: 2560x1440, ~5秒, 6.8MB
- ✅ PIP合成: 480x640, 12秒, 780KB
- ✅ PIP正确显示在右下角

## 📊 方案对比

| 特性 | 设备端合成 | PC端合成 ✅ |
|------|-----------|-----------|
| 实现复杂度 | 高 (OpenGL) | 低 (FFmpeg) |
| 稳定性 | ❌ 低 | ✅ 高 |
| 兼容性 | ❌ 中 | ✅ 高 |
| 灵活性 | 固定 | ✅ 任意调整 |
| 帧率问题 | ❌ 不匹配 | ✅ 独立 |
| 实时预览 | 支持 | 录制后处理 |
| 推荐度 | ⭐⭐ | ⭐⭐⭐⭐⭐ |

## 🎨 FFmpeg高级用法

### 调整PIP大小
```bash
# 更大的PIP (40%)
[1:v]scale=192:-1[pip]

# 固定尺寸
[1:v]scale=320:180[pip]
```

### 调整PIP位置
```bash
# 左上角
overlay=10:10

# 右上角
overlay=main_w-overlay_w-10:10

# 左下角
overlay=10:main_h-overlay_h-10

# 居中
overlay=(main_w-overlay_w)/2:(main_h-overlay_h)/2
```

### 添加边框
```bash
-filter_complex "[1:v]scale=120:-1,drawbox=0:0:iw:ih:yellow:3[pip];[0:v][pip]overlay=..."
```

### 添加阴影效果
```bash
-filter_complex "[1:v]scale=120:-1,split[pip][tmp];[tmp]drawbox=0:0:iw:ih:black@0.5:fill[shadow];[0:v][shadow]overlay=...[bg];[bg][pip]overlay=..."
```

## 📝 后续改进建议

1. **添加音频支持**: 从display或camera录制音频
2. **实时预览**: 使用两个scrcpy窗口实时预览效果
3. **批量处理**: 支持批量合成多个录制
4. **GUI工具**: 创建图形界面配置PIP参数
5. **硬件加速**: 使用FFmpeg硬件编码(如VideoToolbox)加速

## 🏆 总结

**最终方案**: PC端FFmpeg合成是最佳选择

优点:
- 实现简单,代码量少
- 稳定可靠,无兼容性问题
- 灵活强大,支持各种特效
- 质量更高,处理能力更强

适用场景:
- ✅ 需要高质量输出
- ✅ 需要灵活调整PIP参数
- ✅ 设备性能有限
- ✅ 需要后期编辑能力

设备端实时合成虽然可以实时预览,但在Rokid眼镜上存在严重的技术障碍,不建议使用。

## 📚 相关文件

- `record_pip.sh` - 自动化录制脚本
- `PC_PIP_SOLUTION.md` - 详细使用文档
- `test_pip_final.mp4` - 测试合成视频示例
- `CHANGES_COMPOSITE.md` - 设备端composite实现的变更记录
- `PICTURE_IN_PICTURE.md` - 画中画功能文档

---

**作者**: AI Assistant  
**日期**: 2025-10-31  
**项目**: scrcpy 画中画录制功能

