package com.genymobile.scrcpy.opengl;

import com.genymobile.scrcpy.device.Size;
import com.genymobile.scrcpy.util.Ln;

import android.graphics.SurfaceTexture;
import android.media.Image;
import android.media.ImageReader;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.view.Surface;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenGL runner that manages two input sources (display and camera)
 * and composites them using a PictureInPictureFilter.
 */
public final class DualSourceOpenGLRunner {

    private EGLDisplay eglDisplay;
    private EGLContext eglContext;
    private EGLSurface eglSurface;

    private final PictureInPictureFilter filter;

    // Display (background) input
    private SurfaceTexture displaySurfaceTexture;
    private Surface displayInputSurface;
    private int displayTextureId;

    // Camera (pip) input - YUV path
    private boolean useYuvCamera = true;
    private ImageReader cameraImageReader;
    private Surface cameraInputSurface;
    private int yTexId = -1, uTexId = -1, vTexId = -1;
    private YuvPipOpenGLFilter yuvFilter;

    private final Handler handler;
    private boolean stopped;

    // Track which sources have new frames ready
    private final AtomicBoolean displayFrameAvailable = new AtomicBoolean(false);
    private final AtomicBoolean cameraFrameAvailable = new AtomicBoolean(false);

    private static final String TAG = "CompositeGL";
    private long displayFrameCount = 0;
    private long cameraFrameCount = 0;
    // Debug: invert background/pip roles to validate camera rendering path
    private static final boolean INVERT_FOR_DEBUG = false;

    public DualSourceOpenGLRunner(PictureInPictureFilter filter, Handler handler) {
        this.filter = filter;
        this.handler = handler;
    }

    /**
     * Class to hold the two input surfaces
     */
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
        Ln.i("CompositeGL init: display=" + displaySize + ", camera=" + cameraSize + ", output=" + outputSize);
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

        // Create texture for display
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        GLUtils.checkGlError();
        displayTextureId = textures[0];

        // Configure display texture
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, displayTextureId);
        GLUtils.checkGlError();
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLUtils.checkGlError();
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLUtils.checkGlError();
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.checkGlError();
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.checkGlError();

        // Create SurfaceTextures for display
        displaySurfaceTexture = new SurfaceTexture(displayTextureId);
        displaySurfaceTexture.setDefaultBufferSize(displaySize.getWidth(), displaySize.getHeight());
        displayInputSurface = new Surface(displaySurfaceTexture);

        // Create YUV camera path (ImageReader + YUV textures)
        if (useYuvCamera) {
            // Create YUV textures
            int[] yuv = new int[3];
            GLES20.glGenTextures(3, yuv, 0);
            GLUtils.checkGlError();
            yTexId = yuv[0]; 
            uTexId = yuv[1]; 
            vTexId = yuv[2];
            
            for (int i = 0; i < 3; i++) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuv[i]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            }
            GLUtils.checkGlError();
            
            cameraImageReader = ImageReader.newInstance(cameraSize.getWidth(), cameraSize.getHeight(), 
                    android.graphics.ImageFormat.YUV_420_888, 3);
            cameraInputSurface = cameraImageReader.getSurface();
            
            yuvFilter = new YuvPipOpenGLFilter();
            yuvFilter.init();
            Ln.i("CompositeGL: Using YUV camera path");
        }

        if (filter != null) {
            filter.init();
        }

        // Set up frame listeners
        displaySurfaceTexture.setOnFrameAvailableListener(surfaceTexture -> {
            if (stopped) {
                return;
            }
            displayFrameAvailable.set(true);
            displayFrameCount++;
            if ((displayFrameCount % 30) == 0) {
                Ln.i("CompositeGL display frames=" + displayFrameCount);
            }
            tryRender(outputSize);
        }, handler);

        if (useYuvCamera) {
            cameraImageReader.setOnImageAvailableListener(reader -> {
                if (stopped) {
                    return;
                }
                Image image = reader.acquireLatestImage();
                if (image == null) {
                    return;
                }
                
                Image.Plane[] planes = image.getPlanes();
                int width = image.getWidth();
                int height = image.getHeight();
                
                // Upload Y, U, V planes to GL textures
                uploadPlane(planes[0], yTexId, width, height);
                uploadPlane(planes[1], uTexId, width / 2, height / 2);
                uploadPlane(planes[2], vTexId, width / 2, height / 2);
                
                if (cameraFrameCount == 0) {
                    Ln.i("CompositeGL: First YUV camera frame uploaded " + width + "x" + height);
                }
                
                image.close();
                
                cameraFrameAvailable.set(true);
                cameraFrameCount++;
                if ((cameraFrameCount % 30) == 0) {
                    Ln.i("CompositeGL camera frames=" + cameraFrameCount);
                }
                tryRender(outputSize);
            }, handler);
        }

        return new InputSurfaces(displayInputSurface, cameraInputSurface);
    }

    private void tryRender(Size outputSize) {
        // Render whenever any source has a frame; if display not ready yet,
        // we will use camera as background (fallback) so that users see content.
        if (!displayFrameAvailable.get() && !cameraFrameAvailable.get()) {
            return;
        }

        if ((displayFrameCount % 60) == 0) {
            Ln.i("CompositeGL tick d=" + displayFrameCount + " c=" + cameraFrameCount);
        }

        render(outputSize);
    }

    private void render(Size outputSize) {
        GLES20.glViewport(0, 0, outputSize.getWidth(), outputSize.getHeight());
        GLUtils.checkGlError();

        // Update display texture
        boolean hasDisplay = false;
        if (displayFrameAvailable.getAndSet(false)) {
            displaySurfaceTexture.updateTexImage();
            hasDisplay = true;
        }

        float[] displayMatrix = new float[16];
        displaySurfaceTexture.getTransformMatrix(displayMatrix);

        // Camera is ready if flag is set (YUV path doesn't need updateTexImage)
        boolean hasCamera = cameraFrameAvailable.getAndSet(false);

        if (useYuvCamera) {
            // YUV camera path: display as background (OES), camera as PIP (YUV)
            yuvFilter.setBackground(displayTextureId, displayMatrix);
            yuvFilter.setYuvTextures(yTexId, uTexId, vTexId);
            yuvFilter.draw(0, null);
        } else {
            // Legacy OES path (not used currently, kept for reference)
            float[] cameraMatrix = new float[16];
            filter.setCameraTexture(0, cameraMatrix);
            filter.draw(displayTextureId, displayMatrix);
        }

        // Use the display timestamp as the primary timestamp
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, displaySurfaceTexture.getTimestamp());
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }

    public void stopAndRelease() {
        final Semaphore sem = new Semaphore(0);

        handler.post(() -> {
            stopped = true;

            if (displaySurfaceTexture != null) {
                displaySurfaceTexture.setOnFrameAvailableListener(null, handler);
            }
            if (cameraImageReader != null) {
                cameraImageReader.setOnImageAvailableListener(null, null);
                cameraImageReader.close();
            }

            if (filter != null) {
                filter.release();
            }
            if (yuvFilter != null) {
                yuvFilter.release();
            }

            if (yTexId != -1) {
                int[] textures = {displayTextureId, yTexId, uTexId, vTexId};
                GLES20.glDeleteTextures(4, textures, 0);
            } else {
                int[] textures = {displayTextureId};
                GLES20.glDeleteTextures(1, textures, 0);
            }
            GLUtils.checkGlError();

            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            eglContext = EGL14.EGL_NO_CONTEXT;
            eglSurface = EGL14.EGL_NO_SURFACE;

            if (displaySurfaceTexture != null) {
                displaySurfaceTexture.release();
            }
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

    private static void uploadPlane(Image.Plane plane, int texId, int width, int height) {
        ByteBuffer buf = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        
        // Pack data if needed (remove padding/subsampling)
        ByteBuffer packed;
        if (rowStride == width * pixelStride) {
            // Already packed
            packed = buf;
        } else {
            // Need to remove padding
            packed = ByteBuffer.allocateDirect(width * height);
            for (int y = 0; y < height; y++) {
                int rowStart = y * rowStride;
                for (int x = 0; x < width; x++) {
                    int index = rowStart + x * pixelStride;
                    packed.put(buf.get(index));
                }
            }
            packed.position(0);
        }
        
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 
                width, height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, packed);
    }
}

