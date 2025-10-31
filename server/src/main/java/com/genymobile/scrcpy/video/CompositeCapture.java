package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.device.ConfigurationException;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.device.DisplayInfo;
import com.genymobile.scrcpy.device.Orientation;
import com.genymobile.scrcpy.device.Size;
import com.genymobile.scrcpy.opengl.DualImageReaderGLRunner;
import com.genymobile.scrcpy.opengl.OpenGLException;
import com.genymobile.scrcpy.util.HandlerExecutor;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.LogUtils;
import com.genymobile.scrcpy.wrappers.ServiceManager;
import com.genymobile.scrcpy.wrappers.SurfaceControl;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.os.Build;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.view.Surface;
import android.graphics.Rect;
import com.genymobile.scrcpy.FakeContext; // not directly used, but keep parity

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Composite capture that simultaneously captures display and camera,
 * compositing them into a picture-in-picture layout.
 */
@TargetApi(AndroidVersions.API_31_ANDROID_12)
public class CompositeCapture extends SurfaceCapture {

    private final Options options;
    private final int displayId;
    private final int maxSize;
    private final String explicitCameraId;
    private final CameraFacing cameraFacing;
    private final Size explicitCameraSize;
    private final CameraAspectRatio cameraAspectRatio;
    private final int cameraFps;
    private final boolean cameraHighSpeed;

    private DisplayInfo displayInfo;
    private Size videoSize;
    private Size displaySize;
    private Size cameraSize;

    private VirtualDisplay virtualDisplay;
    private IBinder display;
    private DualImageReaderGLRunner glRunner;
    private ScreenCapture mirrorCapture;

    // Camera resources
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private Executor cameraExecutor;
    private String cameraId;
    private final AtomicBoolean disconnected = new AtomicBoolean();

    private HandlerThread glThread;
    private Handler glHandler;

    public CompositeCapture(Options options) {
        this.options = options;
        this.displayId = options.getDisplayId();
        assert displayId != Device.DISPLAY_ID_NONE;
        this.maxSize = options.getMaxSize();
        
        // Camera options
        this.explicitCameraId = options.getCameraId();
        this.cameraFacing = options.getCameraFacing();
        this.explicitCameraSize = options.getCameraSize();
        this.cameraAspectRatio = options.getCameraAspectRatio();
        this.cameraFps = options.getCameraFps();
        this.cameraHighSpeed = options.getCameraHighSpeed();
    }

    @Override
    protected void init() throws ConfigurationException, IOException {
        // Initialize camera
        cameraThread = new HandlerThread("camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraExecutor = new HandlerExecutor(cameraHandler);

        // Initialize GL thread
        glThread = new HandlerThread("OpenGLComposite");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());

        try {
            cameraId = selectCamera(explicitCameraId, cameraFacing);
            if (cameraId == null) {
                throw new ConfigurationException("No matching camera found");
            }

            Ln.i("Using camera '" + cameraId + "' for composite capture");
            cameraDevice = openCamera(cameraId);
        } catch (CameraAccessException | InterruptedException e) {
            throw new IOException(e);
        }

        // Prepare a ScreenCapture helper to mirror the main display into our provided surface
        mirrorCapture = new ScreenCapture(null, options);
        mirrorCapture.init();
    }

    @Override
    public void prepare() throws ConfigurationException, IOException {
        // Get display info
        displayInfo = ServiceManager.getDisplayManager().getDisplayInfo(displayId);
        if (displayInfo == null) {
            Ln.e("Display " + displayId + " not found\n" + LogUtils.buildDisplayListMessage());
            throw new ConfigurationException("Unknown display id: " + displayId);
        }

        if ((displayInfo.getFlags() & DisplayInfo.FLAG_SUPPORTS_PROTECTED_BUFFERS) == 0) {
            Ln.w("Display doesn't have FLAG_SUPPORTS_PROTECTED_BUFFERS flag, mirroring can be restricted");
        }

        Size rawDisplaySize = displayInfo.getSize();

        // Calculate display and camera sizes
        // Force a safer upper bound for encoding on embedded devices
        int requestedMax = maxSize > 0 ? maxSize : 0;
        int safeCap = 1920; // prefer <= 1080p for stability on Rokid
        int targetCap = requestedMax > 0 ? Math.min(requestedMax, safeCap) : safeCap;
        displaySize = rawDisplaySize.limit(targetCap).round8();
        
        // Select camera size
        try {
            Size selectedCameraSize = selectCameraSize(cameraId, explicitCameraSize, maxSize, cameraAspectRatio, cameraHighSpeed);
            if (selectedCameraSize == null) {
                throw new IOException("Could not select camera size");
            }
            // Limit camera size to reasonable pip dimensions (e.g., 25% of display size)
            int maxCameraWidth = displaySize.getWidth() / 2;
            int maxCameraHeight = displaySize.getHeight() / 2;
            cameraSize = selectedCameraSize.limit(Math.min(maxCameraWidth, maxCameraHeight)).round8();
        } catch (CameraAccessException e) {
            throw new IOException(e);
        }

        // Video size is same as display size (camera will be composited as pip)
        videoSize = displaySize;
        Ln.i("Composite sizes -> display:" + displaySize + " camera:" + cameraSize + " output:" + videoSize);

        Ln.i("Composite capture - Display: " + displaySize + ", Camera: " + cameraSize + ", Output: " + videoSize);

        // Prepare mirror helper with same options sizing logic
        try {
            mirrorCapture.prepare();
        } catch (ConfigurationException e) {
            throw e;
        }
    }

    @Override
    public void start(Surface surface) throws IOException {
        try {
            // Create dual ImageReader OpenGL runner (bypasses SurfaceTexture OES limitations)
            glRunner = new DualImageReaderGLRunner(glHandler);
            Ln.i("Composite: starting ImageReader GL runner");
            DualImageReaderGLRunner.InputSurfaces inputSurfaces = glRunner.start(displaySize, cameraSize, videoSize, surface);
            Ln.i("Composite: ImageReader GL runner started");

            // Start display capture via ScreenCapture helper into RGBA ImageReader surface
            try {
                mirrorCapture.start(inputSurfaces.displaySurface);
                Ln.i("Composite: ScreenCapture mirror started to RGBA ImageReader");
            } catch (Exception e2) {
                stop();
                throw new IOException(e2);
            }

            // Start camera capture into YUV ImageReader surface
            CameraCaptureSession session = createCaptureSession(cameraDevice, inputSurfaces.cameraSurface);
            CaptureRequest request = createCaptureRequest(inputSurfaces.cameraSurface);
            setRepeatingRequest(session, request);
            Ln.d("Composite: Camera capture started to YUV ImageReader");

        } catch (CameraAccessException | InterruptedException | OpenGLException e) {
            stop();
            throw new IOException(e);
        }
    }

    @Override
    public void stop() {
        if (glRunner != null) {
            glRunner.stopAndRelease();
            glRunner = null;
        }
        if (mirrorCapture != null) {
            mirrorCapture.stop();
        }
    }

    @Override
    public void release() {
        if (mirrorCapture != null) {
            mirrorCapture.release();
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (display != null) {
            SurfaceControl.destroyDisplay(display);
            display = null;
        }

        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }

        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
        }

        if (glThread != null) {
            glThread.quitSafely();
            glThread = null;
        }
    }

    @Override
    public Size getSize() {
        return videoSize;
    }

    @Override
    public boolean setMaxSize(int maxSize) {
        // Cannot dynamically change max size for composite capture
        return false;
    }

    @Override
    public boolean isClosed() {
        return disconnected.get();
    }

    @Override
    public void requestInvalidate() {
        // Not supported for composite capture
    }

    // Camera helper methods

    private static String selectCamera(String explicitCameraId, CameraFacing cameraFacing) 
            throws CameraAccessException, ConfigurationException {
        CameraManager cameraManager = ServiceManager.getCameraManager();

        String[] cameraIds = cameraManager.getCameraIdList();
        if (explicitCameraId != null) {
            if (!Arrays.asList(cameraIds).contains(explicitCameraId)) {
                Ln.e("Camera with id " + explicitCameraId + " not found\n" + LogUtils.buildCameraListMessage(false));
                throw new ConfigurationException("Camera id not found");
            }
            return explicitCameraId;
        }

        if (cameraFacing == null) {
            // Use the first one
            return cameraIds.length > 0 ? cameraIds[0] : null;
        }

        for (String cameraId : cameraIds) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            int facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (cameraFacing.value() == facing) {
                return cameraId;
            }
        }

        return null;
    }

    @TargetApi(AndroidVersions.API_24_ANDROID_7_0)
    private static Size selectCameraSize(String cameraId, Size explicitSize, int maxSize, 
                                       CameraAspectRatio aspectRatio, boolean highSpeed)
            throws CameraAccessException {
        if (explicitSize != null) {
            return explicitSize;
        }

        CameraManager cameraManager = ServiceManager.getCameraManager();
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

        StreamConfigurationMap configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        android.util.Size[] sizes = highSpeed ? configs.getHighSpeedVideoSizes() : configs.getOutputSizes(MediaCodec.class);
        if (sizes == null) {
            return null;
        }

        Stream<android.util.Size> stream = Arrays.stream(sizes);
        if (maxSize > 0) {
            stream = stream.filter(it -> it.getWidth() <= maxSize && it.getHeight() <= maxSize);
        }

        Optional<android.util.Size> selected = stream.max((s1, s2) -> {
            int cmp = Integer.compare(s1.getWidth(), s2.getWidth());
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(s1.getHeight(), s2.getHeight());
        });

        if (selected.isPresent()) {
            android.util.Size size = selected.get();
            return new Size(size.getWidth(), size.getHeight());
        }

        return null;
    }

    @SuppressLint("MissingPermission")
    private CameraDevice openCamera(String id) throws CameraAccessException, InterruptedException {
        CompletableFuture<CameraDevice> future = new CompletableFuture<>();
        ServiceManager.getCameraManager().openCamera(id, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice camera) {
                Ln.d("Camera opened successfully");
                future.complete(camera);
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                Ln.w("Camera disconnected");
                disconnected.set(true);
                invalidate();
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                int cameraAccessExceptionErrorCode;
                switch (error) {
                    case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                        cameraAccessExceptionErrorCode = CameraAccessException.CAMERA_IN_USE;
                        break;
                    case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                        cameraAccessExceptionErrorCode = CameraAccessException.MAX_CAMERAS_IN_USE;
                        break;
                    case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                        cameraAccessExceptionErrorCode = CameraAccessException.CAMERA_DISABLED;
                        break;
                    case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                    case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                    default:
                        cameraAccessExceptionErrorCode = CameraAccessException.CAMERA_ERROR;
                        break;
                }
                future.completeExceptionally(new CameraAccessException(cameraAccessExceptionErrorCode));
            }
        }, cameraHandler);

        try {
            return future.get();
        } catch (ExecutionException e) {
            throw (CameraAccessException) e.getCause();
        }
    }

    private CameraCaptureSession createCaptureSession(CameraDevice camera, Surface surface) 
            throws CameraAccessException, InterruptedException {
        CompletableFuture<CameraCaptureSession> future = new CompletableFuture<>();
        OutputConfiguration outputConfig = new OutputConfiguration(surface);
        List<OutputConfiguration> outputs = Arrays.asList(outputConfig);

        int sessionType = cameraHighSpeed ? SessionConfiguration.SESSION_HIGH_SPEED : SessionConfiguration.SESSION_REGULAR;
        SessionConfiguration sessionConfig = new SessionConfiguration(sessionType, outputs, cameraExecutor, 
                new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession session) {
                future.complete(session);
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession session) {
                future.completeExceptionally(new CameraAccessException(CameraAccessException.CAMERA_ERROR));
            }
        });

        camera.createCaptureSession(sessionConfig);

        try {
            return future.get();
        } catch (ExecutionException e) {
            throw (CameraAccessException) e.getCause();
        }
    }

    private CaptureRequest createCaptureRequest(Surface surface) throws CameraAccessException {
        CaptureRequest.Builder requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        requestBuilder.addTarget(surface);

        if (cameraFps > 0) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(cameraFps, cameraFps));
        }

        return requestBuilder.build();
    }

    private void setRepeatingRequest(CameraCaptureSession session, CaptureRequest request) 
            throws CameraAccessException, InterruptedException {
        CameraCaptureSession.CaptureCallback callback = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {
                // Do nothing
            }

            @Override
            public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                Ln.w("Camera capture failed: frame " + failure.getFrameNumber());
            }
        };

        if (cameraHighSpeed) {
            CameraConstrainedHighSpeedCaptureSession highSpeedSession = (CameraConstrainedHighSpeedCaptureSession) session;
            List<CaptureRequest> requests = highSpeedSession.createHighSpeedRequestList(request);
            highSpeedSession.setRepeatingBurst(requests, callback, cameraHandler);
        } else {
            session.setRepeatingRequest(request, callback, cameraHandler);
        }
    }

    // Helpers copied from ScreenCapture for SurfaceControl fallback
    private static IBinder createDisplay() throws Exception {
        boolean secure = Build.VERSION.SDK_INT < AndroidVersions.API_30_ANDROID_11
                || (Build.VERSION.SDK_INT == AndroidVersions.API_30_ANDROID_11 && !"S".equals(Build.VERSION.CODENAME));
        return SurfaceControl.createDisplay("scrcpy-composite", secure);
    }

    private static void setDisplaySurface(IBinder display, Surface surface, Rect deviceRect, Rect displayRect, int layerStack) {
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(display, surface);
            SurfaceControl.setDisplayProjection(display, 0, deviceRect, displayRect);
            SurfaceControl.setDisplayLayerStack(display, layerStack);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }
}

