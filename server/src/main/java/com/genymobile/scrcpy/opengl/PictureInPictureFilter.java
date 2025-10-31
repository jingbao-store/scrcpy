package com.genymobile.scrcpy.opengl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * OpenGL filter that composites two video streams into picture-in-picture layout.
 * The display stream is rendered as the background, and the camera stream is rendered
 * as a smaller overlay in one of the corners.
 */
public class PictureInPictureFilter implements OpenGLFilter {

    /**
     * Position of the picture-in-picture overlay
     */
    public enum Position {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    private int program;
    private int colorProgram;
    private FloatBuffer bgVertexBuffer;
    private FloatBuffer bgTexCoordsBuffer;
    private FloatBuffer pipVertexBuffer;
    private FloatBuffer pipTexCoordsBuffer;
    private FloatBuffer pipBorderBuffer;

    private int vertexPosLoc;
    private int texCoordsInLoc;
    private int texLoc;
    private int texMatrixLoc;
    private int colorVertexPosLoc;
    private int colorUniformLoc;

    private final Position pipPosition;
    private final float pipWidthRatio;
    private final float pipHeightRatio;
    private final float pipMarginRatio;

    // Second texture for camera stream
    private int cameraTextureId = -1;
    private float[] cameraTexMatrix = new float[16];

    /**
     * Create a picture-in-picture filter.
     *
     * @param position       Position of the pip overlay
     * @param widthRatio     Width of pip as ratio of total width (0.0-1.0)
     * @param heightRatio    Height of pip as ratio of total height (0.0-1.0)
     * @param marginRatio    Margin from edges as ratio of total size (0.0-1.0)
     */
    public PictureInPictureFilter(Position position, float widthRatio, float heightRatio, float marginRatio) {
        this.pipPosition = position;
        this.pipWidthRatio = widthRatio;
        this.pipHeightRatio = heightRatio;
        this.pipMarginRatio = marginRatio;
    }

    /**
     * Create a picture-in-picture filter with default settings (bottom-right, 25% size, 2% margin).
     */
    public PictureInPictureFilter() {
        this(Position.BOTTOM_RIGHT, 0.40f, 0.40f, 0.02f);
    }

    @Override
    public void init() throws OpenGLException {
        String vertexShaderCode = "#version 100\n"
                + "attribute vec4 vertex_pos;\n"
                + "attribute vec4 tex_coords_in;\n"
                + "varying vec2 tex_coords;\n"
                + "uniform mat4 tex_matrix;\n"
                + "void main() {\n"
                + "    gl_Position = vertex_pos;\n"
                + "    tex_coords = (tex_matrix * tex_coords_in).xy;\n"
                + "}";

        String fragmentShaderCode = "#version 100\n"
                + "#extension GL_OES_EGL_image_external : require\n"
                + "precision highp float;\n"
                + "uniform samplerExternalOES tex;\n"
                + "varying vec2 tex_coords;\n"
                + "void main() {\n"
                + "    vec4 c = texture2D(tex, tex_coords);\n"
                + "    gl_FragColor = vec4(c.rgb, 1.0);\n" // force opaque
                + "}";

        program = GLUtils.createProgram(vertexShaderCode, fragmentShaderCode);
        if (program == 0) {
            throw new OpenGLException("Failed to create program");
        }

        vertexPosLoc = GLES20.glGetAttribLocation(program, "vertex_pos");
        GLUtils.checkGlError();
        texCoordsInLoc = GLES20.glGetAttribLocation(program, "tex_coords_in");
        GLUtils.checkGlError();
        texLoc = GLES20.glGetUniformLocation(program, "tex");
        GLUtils.checkGlError();
        texMatrixLoc = GLES20.glGetUniformLocation(program, "tex_matrix");
        GLUtils.checkGlError();

        // Simple color shader for debug background/border
        String colorVs = "#version 100\n"
                + "attribute vec4 vertex_pos;\n"
                + "void main() {\n"
                + "  gl_Position = vertex_pos;\n"
                + "}";
        String colorFs = "#version 100\n"
                + "precision mediump float;\n"
                + "uniform vec4 uColor;\n"
                + "void main() {\n"
                + "  gl_FragColor = uColor;\n"
                + "}";
        colorProgram = GLUtils.createProgram(colorVs, colorFs);
        if (colorProgram == 0) {
            throw new OpenGLException("Failed to create color program");
        }
        colorVertexPosLoc = GLES20.glGetAttribLocation(colorProgram, "vertex_pos");
        GLUtils.checkGlError();
        colorUniformLoc = GLES20.glGetUniformLocation(colorProgram, "uColor");
        GLUtils.checkGlError();

        // Initialize background (full screen)
        float[] bgVertices = {
                -1.0f, -1.0f,  // bottom-left
                1.0f, -1.0f,  // bottom-right
                -1.0f,  1.0f,  // top-left
                1.0f,  1.0f   // top-right
        };
        bgVertexBuffer = createFloatBuffer(bgVertices);

        float[] bgTexCoords = {
                0.0f, 0.0f,  // bottom-left
                1.0f, 0.0f,  // bottom-right
                0.0f, 1.0f,  // top-left
                1.0f, 1.0f   // top-right
        };
        bgTexCoordsBuffer = createFloatBuffer(bgTexCoords);

        // Calculate pip position
        float pipWidth = pipWidthRatio * 2.0f;  // Convert to OpenGL coordinates (-1 to 1)
        float pipHeight = pipHeightRatio * 2.0f;
        float margin = pipMarginRatio * 2.0f;

        float pipLeft, pipBottom;
        switch (pipPosition) {
            case TOP_LEFT:
                pipLeft = -1.0f + margin;
                pipBottom = 1.0f - margin - pipHeight;
                break;
            case TOP_RIGHT:
                pipLeft = 1.0f - margin - pipWidth;
                pipBottom = 1.0f - margin - pipHeight;
                break;
            case BOTTOM_LEFT:
                pipLeft = -1.0f + margin;
                pipBottom = -1.0f + margin;
                break;
            case BOTTOM_RIGHT:
            default:
                pipLeft = 1.0f - margin - pipWidth;
                pipBottom = -1.0f + margin;
                break;
        }

        float[] pipVertices = {
                pipLeft, pipBottom,                    // bottom-left
                pipLeft + pipWidth, pipBottom,         // bottom-right
                pipLeft, pipBottom + pipHeight,        // top-left
                pipLeft + pipWidth, pipBottom + pipHeight  // top-right
        };
        pipVertexBuffer = createFloatBuffer(pipVertices);

        // Border uses a rectangle order to avoid hourglass shape
        float[] pipBorder = {
                pipLeft, pipBottom,                    // BL
                pipLeft + pipWidth, pipBottom,         // BR
                pipLeft + pipWidth, pipBottom + pipHeight, // TR
                pipLeft, pipBottom + pipHeight         // TL
        };
        pipBorderBuffer = createFloatBuffer(pipBorder);

        float[] pipTexCoords = {
                0.0f, 0.0f,  // bottom-left
                1.0f, 0.0f,  // bottom-right
                0.0f, 1.0f,  // top-left
                1.0f, 1.0f   // top-right
        };
        pipTexCoordsBuffer = createFloatBuffer(pipTexCoords);
    }

    private FloatBuffer createFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer buffer = bb.asFloatBuffer();
        buffer.put(data);
        buffer.position(0);
        return buffer;
    }

    /**
     * Set the camera texture and transform matrix.
     * This should be called before draw() to update the camera stream.
     *
     * @param textureId  Camera texture ID
     * @param texMatrix  Camera texture transform matrix
     */
    public void setCameraTexture(int textureId, float[] texMatrix) {
        this.cameraTextureId = textureId;
        System.arraycopy(texMatrix, 0, this.cameraTexMatrix, 0, 16);
    }

    @Override
    public void draw(int displayTextureId, float[] displayTexMatrix) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLUtils.checkGlError();

        GLES20.glUseProgram(program);
        GLUtils.checkGlError();

        GLES20.glEnableVertexAttribArray(vertexPosLoc);
        GLUtils.checkGlError();
        GLES20.glEnableVertexAttribArray(texCoordsInLoc);
        GLUtils.checkGlError();

        // Draw background (display stream)
        GLES20.glVertexAttribPointer(vertexPosLoc, 2, GLES20.GL_FLOAT, false, 0, bgVertexBuffer);
        GLUtils.checkGlError();
        GLES20.glVertexAttribPointer(texCoordsInLoc, 2, GLES20.GL_FLOAT, false, 0, bgTexCoordsBuffer);
        GLUtils.checkGlError();

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLUtils.checkGlError();
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, displayTextureId);
        GLUtils.checkGlError();
        GLES20.glUniform1i(texLoc, 0);
        GLUtils.checkGlError();

        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, displayTexMatrix, 0);
        GLUtils.checkGlError();

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLUtils.checkGlError();

        // Draw pip overlay (camera stream) if available
        if (cameraTextureId != -1) {
            // Draw a semi-transparent debug rectangle under the pip
            GLES20.glUseProgram(colorProgram);
            GLUtils.checkGlError();
            GLES20.glEnableVertexAttribArray(colorVertexPosLoc);
            GLUtils.checkGlError();
            GLES20.glVertexAttribPointer(colorVertexPosLoc, 2, GLES20.GL_FLOAT, false, 0, pipVertexBuffer);
            GLUtils.checkGlError();
            // background (dark with alpha)
            GLES20.glEnable(GLES20.GL_BLEND);
            GLUtils.checkGlError();
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLUtils.checkGlError();
            GLES20.glUniform4f(colorUniformLoc, 0f, 0f, 0f, 0.35f);
            GLUtils.checkGlError();
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLUtils.checkGlError();
            // border
            GLES20.glUniform4f(colorUniformLoc, 1f, 1f, 0f, 0.9f);
            GLUtils.checkGlError();
            GLES20.glLineWidth(4f);
            GLUtils.checkGlError();
            GLES20.glVertexAttribPointer(colorVertexPosLoc, 2, GLES20.GL_FLOAT, false, 0, pipBorderBuffer);
            GLUtils.checkGlError();
            GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4);
            GLUtils.checkGlError();
            GLES20.glDisableVertexAttribArray(colorVertexPosLoc);
            GLUtils.checkGlError();

            // Now draw the camera texture
            GLES20.glUseProgram(program);
            GLUtils.checkGlError();
            // Draw the camera texture opaque (avoid alpha from external texture)

            GLES20.glVertexAttribPointer(vertexPosLoc, 2, GLES20.GL_FLOAT, false, 0, pipVertexBuffer);
            GLUtils.checkGlError();
            GLES20.glVertexAttribPointer(texCoordsInLoc, 2, GLES20.GL_FLOAT, false, 0, pipTexCoordsBuffer);
            GLUtils.checkGlError();

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLUtils.checkGlError();
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
            GLUtils.checkGlError();
            GLES20.glUniform1i(texLoc, 0);
            GLUtils.checkGlError();

            GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, cameraTexMatrix, 0);
            GLUtils.checkGlError();

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLUtils.checkGlError();

            // no blending needed for opaque draw
        }

        GLES20.glDisableVertexAttribArray(vertexPosLoc);
        GLUtils.checkGlError();
        GLES20.glDisableVertexAttribArray(texCoordsInLoc);
        GLUtils.checkGlError();
    }

    @Override
    public void release() {
        GLES20.glDeleteProgram(program);
        GLUtils.checkGlError();
        if (colorProgram != 0) {
            GLES20.glDeleteProgram(colorProgram);
            GLUtils.checkGlError();
        }
    }
}

