package com.genymobile.scrcpy.opengl;

import com.genymobile.scrcpy.device.Size;
import com.genymobile.scrcpy.util.Ln;

import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Handler;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenGL runner using two ImageReaders (display RGBA + camera YUV)
 * to avoid SurfaceTexture OES texture limitations.
 */
public final class DualImageReaderGLRunner {

    private EGLDisplay eglDisplay;
    private EGLContext eglContext;
    private EGLSurface eglSurface;

    private ImageReader displayImageReader;
    private ImageReader cameraImageReader;
    private Surface displayInputSurface;
    private Surface cameraInputSurface;

    private int displayTexId;
    private int yTexId, uTexId, vTexId;

    private final Handler handler;
    private boolean stopped;

    private final AtomicBoolean displayFrameAvailable = new AtomicBoolean(false);
    private final AtomicBoolean cameraFrameAvailable = new AtomicBoolean(false);

    private long displayFrameCount = 0;
    private long cameraFrameCount = 0;

    private RgbaYuvPipFilter filter;

    public DualImageReaderGLRunner(Handler handler) {
        this.handler = handler;
    }

    public static class InputSurfaces {
        public final Surface displaySurface;
        public final Surface cameraSurface;

        public InputSurfaces(Surface displaySurface, Surface cameraSurface) {
            this.displaySurface = displaySurface;
            this.cameraSurface = cameraSurface;
        }
    }

    public InputSurfaces start(Size displaySize, Size cameraSize, Size outputSize, Surface outputSurface) throws OpenGLException {
        final Semaphore sem = new Semaphore(0);
        Throwable[] throwableRef = new Throwable[1];
        InputSurfaces[] resultRef = new InputSurfaces[1];

        handler.post(() -> {
            try {
                resultRef[0] = run(displaySize, cameraSize, outputSize, outputSurface);
            } catch (Throwable throwable) {
                throwableRef[0] = throwable;
            } finally {
                sem.release();
            }
        });

        try {
            sem.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Throwable throwable = throwableRef[0];
        if (throwable != null) {
            if (throwable instanceof OpenGLException) {
                throw (OpenGLException) throwable;
            }
            throw new OpenGLException("Asynchronous OpenGL runner init failed", throwable);
        }

        return resultRef[0];
    }

    private InputSurfaces run(Size displaySize, Size cameraSize, Size outputSize, Surface outputSurface) throws OpenGLException {
        Ln.i("DualImageReaderGL init: display=" + displaySize + ", camera=" + cameraSize + ", output=" + outputSize);
        
        // Init EGL
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new OpenGLException("Unable to get EGL14 display");
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new OpenGLException("Unable to initialize EGL14");
        }

        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0);
        if (numConfigs[0] <= 0) {
            EGL14.eglTerminate(eglDisplay);
            throw new OpenGLException("Unable to find ES2 EGL config");
        }
        EGLConfig eglConfig = configs[0];

        int[] contextAttribList = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribList, 0);
        if (eglContext == null) {
            EGL14.eglTerminate(eglDisplay);
            throw new OpenGLException("Failed to create EGL context");
        }

        int[] surfaceAttribList = {
                EGL14.EGL_NONE
        };
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outputSurface, surfaceAttribList, 0);
        if (eglSurface == null) {
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
            throw new OpenGLException("Failed to create EGL window surface");
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
            throw new OpenGLException("Failed to make EGL context current");
        }

        // Create textures: 1 RGBA for display, 3 luminance for YUV camera
        int[] textures = new int[4];
        GLES20.glGenTextures(4, textures, 0);
        GLUtils.checkGlError();
        displayTexId = textures[0];
        yTexId = textures[1];
        uTexId = textures[2];
        vTexId = textures[3];

        // Configure RGBA texture for display
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, displayTexId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.checkGlError();

        // Configure YUV textures for camera
        for (int i = 1; i < 4; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }
        GLUtils.checkGlError();

        // Create ImageReaders
        displayImageReader = ImageReader.newInstance(
            displaySize.getWidth(), 
            displaySize.getHeight(), 
            PixelFormat.RGBA_8888,  // Direct pixel access
            3
        );
        displayInputSurface = displayImageReader.getSurface();

        cameraImageReader = ImageReader.newInstance(
            cameraSize.getWidth(), 
            cameraSize.getHeight(), 
            ImageFormat.YUV_420_888, 
            3
        );
        cameraInputSurface = cameraImageReader.getSurface();

        // Init filter
        filter = new RgbaYuvPipFilter();
        filter.init();
        filter.configureCamera(
                cameraSize.getWidth(),
                cameraSize.getHeight(),
                outputSize.getWidth(),
                outputSize.getHeight(),
                RgbaYuvPipFilter.Rotation.ROT_270);

        // Set up frame listeners
        displayImageReader.setOnImageAvailableListener(reader -> {
            if (stopped) return;
            Image image = reader.acquireLatestImage();
            if (image == null) return;
            
            Image.Plane[] planes = image.getPlanes();
            handler.post(() -> {
                uploadRgbaPlane(planes[0], displayTexId, displaySize.getWidth(), displaySize.getHeight());
                displayFrameAvailable.set(true);
                displayFrameCount++;
                if ((displayFrameCount % 30) == 0) {
                    Ln.i("DualImageReaderGL display frames=" + displayFrameCount);
                }
                image.close();
                tryRender(outputSize, displaySize, cameraSize);
            });
        }, handler);

        cameraImageReader.setOnImageAvailableListener(reader -> {
            if (stopped) return;
            Image image = reader.acquireLatestImage();
            if (image == null) return;

            Image.Plane[] planes = image.getPlanes();
            handler.post(() -> {
                uploadPlane(planes[0], yTexId, cameraSize.getWidth(), cameraSize.getHeight());
                uploadPlane(planes[1], uTexId, cameraSize.getWidth() / 2, cameraSize.getHeight() / 2);
                uploadPlane(planes[2], vTexId, cameraSize.getWidth() / 2, cameraSize.getHeight() / 2);
                
                cameraFrameAvailable.set(true);
                cameraFrameCount++;
                if ((cameraFrameCount % 30) == 0) {
                    Ln.i("DualImageReaderGL camera frames=" + cameraFrameCount);
                }
                image.close();
                tryRender(outputSize, displaySize, cameraSize);
            });
        }, handler);

        Ln.i("DualImageReaderGL: ImageReaders created, waiting for frames...");
        return new InputSurfaces(displayInputSurface, cameraInputSurface);
    }

    private static void uploadRgbaPlane(Image.Plane plane, int texId, int width, int height) {
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        
        // Pack RGBA data if stride doesn't match
        if (rowStride == width * pixelStride) {
            // Direct upload
            buf.position(0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                               GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        } else {
            // Pack with stride
            ByteBuffer packed = ByteBuffer.allocateDirect(width * height * 4);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int index = y * rowStride + x * pixelStride;
                    packed.put(buf.get(index));
                    packed.put(buf.get(index + 1));
                    packed.put(buf.get(index + 2));
                    packed.put(buf.get(index + 3));
                }
            }
            packed.position(0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                               GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, packed);
        }
    }

    private static void uploadPlane(Image.Plane plane, int texId, int width, int height) {
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        
        // Pack the plane data if needed
        ByteBuffer packed = ByteBuffer.allocateDirect(width * height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * rowStride + x * pixelStride;
                packed.put(buf.get(index));
            }
        }
        packed.position(0);
        
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, width, height, 0, 
                           GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, packed);
    }

    private void tryRender(Size outputSize, Size displaySize, Size cameraSize) {
        // Wait for at least camera frame (display might be slow to start)
        if (!cameraFrameAvailable.get()) {
            return;
        }

        if ((displayFrameCount + cameraFrameCount) % 60 == 0) {
            Ln.i("DualImageReaderGL tick d=" + displayFrameCount + " c=" + cameraFrameCount);
        }

        render(outputSize, displaySize, cameraSize);
    }

    private void render(Size outputSize, Size displaySize, Size cameraSize) {
        GLES20.glViewport(0, 0, outputSize.getWidth(), outputSize.getHeight());
        GLUtils.checkGlError();

        boolean hasDisplay = displayFrameAvailable.getAndSet(false);
        boolean hasCamera = cameraFrameAvailable.getAndSet(false);

        // Set textures for filter
        filter.setDisplayTexture(displayTexId, displaySize.getWidth(), displaySize.getHeight());
        filter.setYuvTextures(yTexId, uTexId, vTexId, cameraSize.getWidth(), cameraSize.getHeight());
        filter.draw();

        long timestamp = System.nanoTime();
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestamp);
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }

    public void stopAndRelease() {
        final Semaphore sem = new Semaphore(0);

        handler.post(() -> {
            stopped = true;

            if (displayImageReader != null) {
                displayImageReader.close();
            }
            if (cameraImageReader != null) {
                cameraImageReader.close();
            }

            if (filter != null) {
                filter.release();
            }

            int[] textures = {displayTexId, yTexId, uTexId, vTexId};
            GLES20.glDeleteTextures(4, textures, 0);
            GLUtils.checkGlError();

            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            eglContext = EGL14.EGL_NO_CONTEXT;
            eglSurface = EGL14.EGL_NO_SURFACE;

            if (displayInputSurface != null) {
                displayInputSurface.release();
            }
            if (cameraInputSurface != null) {
                cameraInputSurface.release();
            }

            sem.release();
        });

        try {
            sem.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

