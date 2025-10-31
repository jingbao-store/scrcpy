package com.genymobile.scrcpy.opengl;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * OpenGL filter that composites:
 * - RGBA background (display from ImageReader)
 * - YUV420 PIP overlay (camera from ImageReader)
 */
public class RgbaYuvPipFilter {

    private int rgbaProgram;
    private int yuvProgram;

    private int rgbaVertexPosLoc;
    private int rgbaTexCoordsInLoc;
    private int rgbaTexLoc;
    private int rgbaGlobalAlphaLoc;

    private int yuvVertexPosLoc;
    private int yuvTexCoordsInLoc;
    private int texYLoc;
    private int texULoc;
    private int texVLoc;

    private FloatBuffer defaultCameraVertexBuffer;
    private FloatBuffer defaultCameraTexCoordsBuffer;
    private FloatBuffer cameraVertexBuffer;
    private FloatBuffer cameraTexCoordsBuffer;
    private FloatBuffer displayVertexBuffer;
    private FloatBuffer displayTexCoordsBuffer;

    private int displayTexId = -1;
    private int yTexId = -1, uTexId = -1, vTexId = -1;
    private int displayWidth, displayHeight;
    private int cameraWidth, cameraHeight;
    private int outputWidth, outputHeight;

    // Display PIP settings (display overlay on camera background)
    private static final float DISPLAY_WIDTH_RATIO = 0.60f;  // Display takes 60% width
    private static final float DISPLAY_HEIGHT_RATIO = 0.80f; // Display takes 80% height
    private static final float DISPLAY_MARGIN_RATIO = 0.05f;
    private static final float DEFAULT_DISPLAY_ALPHA = 0.6f;
    private float displayAlpha = DEFAULT_DISPLAY_ALPHA;

    
    // Position: centered or top-center for AR overlay
    private enum OverlayPosition { CENTER, TOP_CENTER, BOTTOM_CENTER }
    private static final OverlayPosition OVERLAY_POSITION = OverlayPosition.CENTER;

    public void setDisplayTexture(int texId, int width, int height) {
        this.displayTexId = texId;
        this.displayWidth = width;
        this.displayHeight = height;
    }

    public void setYuvTextures(int yTexId, int uTexId, int vTexId, int width, int height) {
        this.yTexId = yTexId;
        this.uTexId = uTexId;
        this.vTexId = vTexId;
        this.cameraWidth = width;
        this.cameraHeight = height;
    }

    public void setDisplayAlpha(float alpha) {
        this.displayAlpha = clamp(alpha, 0f, 1f);
    }

    public enum Rotation {
        ROT_0,
        ROT_90,
        ROT_180,
        ROT_270
    }

    public void configureCamera(int cameraWidth, int cameraHeight, int outputWidth, int outputHeight, Rotation rotation) {
        this.cameraWidth = cameraWidth;
        this.cameraHeight = cameraHeight;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;

        boolean swap = rotation == Rotation.ROT_90 || rotation == Rotation.ROT_270;
        float rotatedWidth = swap ? cameraHeight : cameraWidth;
        float rotatedHeight = swap ? cameraWidth : cameraHeight;

        float cameraAspect = rotatedWidth / rotatedHeight;
        float outputAspect = (float) outputWidth / outputHeight;

        float visibleWidth;
        float visibleHeight;
        if (cameraAspect >= outputAspect) {
            visibleHeight = 1f;
            visibleWidth = outputAspect / cameraAspect;
        } else {
            visibleWidth = 1f;
            visibleHeight = cameraAspect / outputAspect;
        }

        float uCenter = 0.5f;
        float uMin = clamp(uCenter - visibleWidth / 2f, 0f, 1f);
        float uMax = clamp(uCenter + visibleWidth / 2f, 0f, 1f);

        float vBottom = 1f;
        float vTop = clamp(vBottom - visibleHeight, 0f, 1f);

        cameraVertexBuffer = defaultCameraVertexBuffer;
        float[] texCoords = getTexCoordsForRotation(uMin, uMax, vTop, vBottom, rotation);
        cameraTexCoordsBuffer = createFloatBuffer(texCoords);
    }

    public void init() throws OpenGLException {
        // RGBA shader for display background
        String vsRgba = "#version 100\n" +
                "attribute vec4 vertex_pos;\n" +
                "attribute vec2 tex_coords_in;\n" +
                "varying vec2 tex_coords;\n" +
                "void main(){\n" +
                "  gl_Position = vertex_pos;\n" +
                "  tex_coords = tex_coords_in;\n" +
                "}";

        String fsRgba = "#version 100\n" +
                "precision mediump float;\n" +
                "uniform sampler2D tex;\n" +
                "uniform float global_alpha;\n" +
                "varying vec2 tex_coords;\n" +
                "void main(){\n" +
                "  vec4 color = texture2D(tex, tex_coords);\n" +
                "  gl_FragColor = vec4(color.rgb, color.a * global_alpha);\n" +
                "}";

        // YUV shader for camera PIP
        String vsYuv = "#version 100\n" +
                "attribute vec4 vertex_pos;\n" +
                "attribute vec2 tex_coords_in;\n" +
                "varying vec2 v_tc;\n" +
                "void main(){\n" +
                "  gl_Position = vertex_pos;\n" +
                "  v_tc = tex_coords_in;\n" +
                "}";

        String fsYuv = "#version 100\n" +
                "precision mediump float;\n" +
                "varying vec2 v_tc;\n" +
                "uniform sampler2D texY;\n" +
                "uniform sampler2D texU;\n" +
                "uniform sampler2D texV;\n" +
                "void main(){\n" +
                "  float y = texture2D(texY, v_tc).r;\n" +
                "  float u = texture2D(texU, v_tc).r - 0.5;\n" +
                "  float v = texture2D(texV, v_tc).r - 0.5;\n" +
                "  float r = y + 1.402 * v;\n" +
                "  float g = y - 0.344136 * u - 0.714136 * v;\n" +
                "  float b = y + 1.772 * u;\n" +
                "  gl_FragColor = vec4(r, g, b, 1.0);\n" +
                "}";

        rgbaProgram = GLUtils.createProgram(vsRgba, fsRgba);
        if (rgbaProgram == 0) throw new OpenGLException("Failed to create RGBA program");
        
        yuvProgram = GLUtils.createProgram(vsYuv, fsYuv);
        if (yuvProgram == 0) throw new OpenGLException("Failed to create YUV program");

        // Get uniform/attribute locations for RGBA program
        rgbaVertexPosLoc = GLES20.glGetAttribLocation(rgbaProgram, "vertex_pos");
        GLUtils.checkGlError();
        rgbaTexCoordsInLoc = GLES20.glGetAttribLocation(rgbaProgram, "tex_coords_in");
        GLUtils.checkGlError();
        rgbaTexLoc = GLES20.glGetUniformLocation(rgbaProgram, "tex");
        GLUtils.checkGlError();
        rgbaGlobalAlphaLoc = GLES20.glGetUniformLocation(rgbaProgram, "global_alpha");
        GLUtils.checkGlError();

        // Get uniform/attribute locations for YUV program
        yuvVertexPosLoc = GLES20.glGetAttribLocation(yuvProgram, "vertex_pos");
        GLUtils.checkGlError();
        yuvTexCoordsInLoc = GLES20.glGetAttribLocation(yuvProgram, "tex_coords_in");
        GLUtils.checkGlError();
        texYLoc = GLES20.glGetUniformLocation(yuvProgram, "texY");
        texULoc = GLES20.glGetUniformLocation(yuvProgram, "texU");
        texVLoc = GLES20.glGetUniformLocation(yuvProgram, "texV");
        GLUtils.checkGlError();

        // Full-screen quad for camera background (default)
        float[] fullVertices = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
        defaultCameraVertexBuffer = createFloatBuffer(fullVertices);
        cameraVertexBuffer = defaultCameraVertexBuffer;

        float[] defaultTexCoords = {0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f};
        defaultCameraTexCoordsBuffer = createFloatBuffer(defaultTexCoords);
        cameraTexCoordsBuffer = defaultCameraTexCoordsBuffer;

        // Display overlay quad (centered, semi-transparent for AR)
        float displayW = DISPLAY_WIDTH_RATIO * 2f;
        float displayH = DISPLAY_HEIGHT_RATIO * 2f;
        float left, bottom;
        
        switch (OVERLAY_POSITION) {
            case TOP_CENTER:
                left = -DISPLAY_WIDTH_RATIO;
                bottom = 1f - DISPLAY_MARGIN_RATIO * 2f - displayH;
                break;
            case BOTTOM_CENTER:
                left = -DISPLAY_WIDTH_RATIO;
                bottom = -1f + DISPLAY_MARGIN_RATIO * 2f;
                break;
            case CENTER:
            default:
                left = -DISPLAY_WIDTH_RATIO;
                bottom = -DISPLAY_HEIGHT_RATIO;
                break;
        }
        
        float[] displayVertices = {left, bottom, left + displayW, bottom, left, bottom + displayH, left + displayW, bottom + displayH};
        displayVertexBuffer = createFloatBuffer(displayVertices);
        
        float[] displayTexCoords = {0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f}; // Flipped vertically
        displayTexCoordsBuffer = createFloatBuffer(displayTexCoords);
    }

    private static FloatBuffer createFloatBuffer(float[] array) {
        ByteBuffer bb = ByteBuffer.allocateDirect(array.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(array);
        fb.position(0);
        return fb;
    }

    public void draw() {
        // Clear to opaque black so letterboxed regions are neutral instead of undefined memory
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // Enable alpha blending for transparent AR overlays
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        
        // 1. Draw camera as background (YUV)
        if (yTexId != -1 && uTexId != -1 && vTexId != -1) {
            GLES20.glUseProgram(yuvProgram);
            GLES20.glEnableVertexAttribArray(yuvVertexPosLoc);
            FloatBuffer camVertices = cameraVertexBuffer != null ? cameraVertexBuffer : defaultCameraVertexBuffer;
            GLES20.glVertexAttribPointer(yuvVertexPosLoc, 2, GLES20.GL_FLOAT, false, 0, camVertices);
            GLES20.glEnableVertexAttribArray(yuvTexCoordsInLoc);
            FloatBuffer camTex = cameraTexCoordsBuffer != null ? cameraTexCoordsBuffer : defaultCameraTexCoordsBuffer;
            GLES20.glVertexAttribPointer(yuvTexCoordsInLoc, 2, GLES20.GL_FLOAT, false, 0, camTex);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yTexId);
            GLES20.glUniform1i(texYLoc, 0);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uTexId);
            GLES20.glUniform1i(texULoc, 1);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vTexId);
            GLES20.glUniform1i(texVLoc, 2);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        // 2. Draw display overlay (RGBA with alpha blending for transparency)
        if (displayTexId != -1) {
            GLES20.glUseProgram(rgbaProgram);
            GLES20.glEnableVertexAttribArray(rgbaVertexPosLoc);
            GLES20.glVertexAttribPointer(rgbaVertexPosLoc, 2, GLES20.GL_FLOAT, false, 0, displayVertexBuffer);
            GLES20.glEnableVertexAttribArray(rgbaTexCoordsInLoc);
            GLES20.glVertexAttribPointer(rgbaTexCoordsInLoc, 2, GLES20.GL_FLOAT, false, 0, displayTexCoordsBuffer);
            
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, displayTexId);
            GLES20.glUniform1i(rgbaTexLoc, 0);
            GLES20.glUniform1f(rgbaGlobalAlphaLoc, displayAlpha);
            
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
        
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    public void release() {
        if (rgbaProgram != 0) {
            GLES20.glDeleteProgram(rgbaProgram);
        }
        if (yuvProgram != 0) {
            GLES20.glDeleteProgram(yuvProgram);
        }
    }

    private static float[] getTexCoordsForRotation(float uMin, float uMax, float vTop, float vBottom, Rotation rotation) {
        float[] coords = {
                uMin, vBottom,
                uMax, vBottom,
                uMin, vTop,
                uMax, vTop
        };

        for (int i = 0; i < 4; ++i) {
            float u = coords[i * 2];
            float v = coords[i * 2 + 1];
            float[] rotated = rotateTexCoord(u, v, rotation);
            coords[i * 2] = rotated[0];
            coords[i * 2 + 1] = rotated[1];
        }
        return coords;
    }

    private static float[] getTexCoordsForRotation(Rotation rotation) {
        return getTexCoordsForRotation(0f, 1f, 0f, 1f, rotation);
    }

    private static float[] rotateTexCoord(float u, float v, Rotation rotation) {
        switch (rotation) {
            case ROT_90:
                return new float[]{v, 1f - u};
            case ROT_180:
                return new float[]{1f - u, 1f - v};
            case ROT_270:
                return new float[]{1f - v, u};
            case ROT_0:
            default:
                return new float[]{u, v};
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

