package com.genymobile.scrcpy.opengl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * OpenGL filter that draws a display (external OES) as background and a YUV420 pip overlay.
 */
public class YuvPipOpenGLFilter implements OpenGLFilter {

    private int bgProgram;
    private int yuvProgram;

    private int bgVertexPosLoc;
    private int bgTexCoordsInLoc;
    private int bgTexLoc;
    private int bgTexMatrixLoc;

    private int pipVertexPosLoc;
    private int pipTexCoordsInLoc;
    private int texYLoc;
    private int texULoc;
    private int texVLoc;

    private FloatBuffer bgVertexBuffer;
    private FloatBuffer bgTexCoordsBuffer;
    private FloatBuffer pipVertexBuffer;
    private FloatBuffer pipTexCoordsBuffer;

    private float[] displayTexMatrix = new float[16];

    public enum Position { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private final Position pipPosition;
    private final float pipWidthRatio;
    private final float pipHeightRatio;
    private final float pipMarginRatio;

    private int bgTextureId = -1; // external oes
    private int yTexId = -1, uTexId = -1, vTexId = -1; // 2D textures

    public YuvPipOpenGLFilter(Position position, float widthRatio, float heightRatio, float marginRatio) {
        this.pipPosition = position;
        this.pipWidthRatio = widthRatio;
        this.pipHeightRatio = heightRatio;
        this.pipMarginRatio = marginRatio;
    }

    public YuvPipOpenGLFilter() {
        this(Position.BOTTOM_RIGHT, 0.25f, 0.25f, 0.03f);
    }

    public void setBackground(int oesTexId, float[] texMatrix) {
        this.bgTextureId = oesTexId;
        System.arraycopy(texMatrix, 0, this.displayTexMatrix, 0, 16);
    }

    public void setYuvTextures(int yTexId, int uTexId, int vTexId) {
        this.yTexId = yTexId;
        this.uTexId = uTexId;
        this.vTexId = vTexId;
    }

    @Override
    public void init() throws OpenGLException {
        String vs = "#version 100\n" +
                "attribute vec4 vertex_pos;\n" +
                "attribute vec4 tex_coords_in;\n" +
                "varying vec2 tex_coords;\n" +
                "uniform mat4 tex_matrix;\n" +
                "void main(){\n" +
                "  gl_Position = vertex_pos;\n" +
                "  tex_coords = (tex_matrix * tex_coords_in).xy;\n" +
                "}";

        String fsBg = "#version 100\n" +
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "uniform samplerExternalOES tex;\n" +
                "varying vec2 tex_coords;\n" +
                "void main(){ vec4 c = texture2D(tex, tex_coords); gl_FragColor = vec4(c.rgb,1.0); }";

        String vsPip = "#version 100\n" +
                "attribute vec4 vertex_pos;\n" +
                "attribute vec2 tex_coords_in;\n" +
                "varying vec2 v_tc;\n" +
                "void main(){ gl_Position = vertex_pos; v_tc = tex_coords_in; }";

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
                "  gl_FragColor = vec4(r,g,b,1.0);\n" +
                "}";

        bgProgram = GLUtils.createProgram(vs, fsBg);
        if (bgProgram == 0) throw new OpenGLException("Failed to create bg program");
        yuvProgram = GLUtils.createProgram(vsPip, fsYuv);
        if (yuvProgram == 0) throw new OpenGLException("Failed to create yuv program");

        bgVertexPosLoc = GLES20.glGetAttribLocation(bgProgram, "vertex_pos");
        GLUtils.checkGlError();
        bgTexCoordsInLoc = GLES20.glGetAttribLocation(bgProgram, "tex_coords_in");
        GLUtils.checkGlError();
        bgTexLoc = GLES20.glGetUniformLocation(bgProgram, "tex");
        GLUtils.checkGlError();
        bgTexMatrixLoc = GLES20.glGetUniformLocation(bgProgram, "tex_matrix");
        GLUtils.checkGlError();

        pipVertexPosLoc = GLES20.glGetAttribLocation(yuvProgram, "vertex_pos");
        GLUtils.checkGlError();
        pipTexCoordsInLoc = GLES20.glGetAttribLocation(yuvProgram, "tex_coords_in");
        GLUtils.checkGlError();
        texYLoc = GLES20.glGetUniformLocation(yuvProgram, "texY");
        texULoc = GLES20.glGetUniformLocation(yuvProgram, "texU");
        texVLoc = GLES20.glGetUniformLocation(yuvProgram, "texV");
        GLUtils.checkGlError();

        float[] bgVertices = { -1f,-1f, 1f,-1f, -1f,1f, 1f,1f };
        bgVertexBuffer = create(bgVertices);
        float[] bgTex = { 0f,0f, 1f,0f, 0f,1f, 1f,1f };
        bgTexCoordsBuffer = create(bgTex);

        // pip rect
        float pipW = pipWidthRatio*2f;
        float pipH = pipHeightRatio*2f;
        float m = pipMarginRatio*2f;
        float left, bottom;
        switch (pipPosition){
            case TOP_LEFT: left = -1f + m; bottom = 1f - m - pipH; break;
            case TOP_RIGHT: left = 1f - m - pipW; bottom = 1f - m - pipH; break;
            case BOTTOM_LEFT: left = -1f + m; bottom = -1f + m; break;
            case BOTTOM_RIGHT: default: left = 1f - m - pipW; bottom = -1f + m; break;
        }
        float[] pipVertices = { left,bottom, left+pipW,bottom, left,bottom+pipH, left+pipW,bottom+pipH };
        pipVertexBuffer = create(pipVertices);
        float[] pipTex = { 0f,0f, 1f,0f, 0f,1f, 1f,1f };
        pipTexCoordsBuffer = create(pipTex);
    }

    private static FloatBuffer create(float[] a){
        ByteBuffer bb = ByteBuffer.allocateDirect(a.length*4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(a); fb.position(0); return fb;
    }

    @Override
    public void draw(int bgOesTexIdIgnored, float[] bgTexMatrix) {
        // Clear to detect rendering issues
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        
        // Background (display - OES texture)
        GLES20.glUseProgram(bgProgram);
        GLUtils.checkGlError();
        GLES20.glEnableVertexAttribArray(bgVertexPosLoc);
        GLES20.glVertexAttribPointer(bgVertexPosLoc, 2, GLES20.GL_FLOAT, false, 0, bgVertexBuffer);
        GLES20.glEnableVertexAttribArray(bgTexCoordsInLoc);
        GLES20.glVertexAttribPointer(bgTexCoordsInLoc, 2, GLES20.GL_FLOAT, false, 0, bgTexCoordsBuffer);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, bgTextureId);
        GLES20.glUniform1i(bgTexLoc, 0);
        GLES20.glUniformMatrix4fv(bgTexMatrixLoc, 1, false, displayTexMatrix, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // PIP (camera - YUV textures)
        if (yTexId != -1 && uTexId != -1 && vTexId != -1) {
            GLES20.glUseProgram(yuvProgram);
            GLES20.glEnableVertexAttribArray(pipVertexPosLoc);
            GLES20.glVertexAttribPointer(pipVertexPosLoc, 2, GLES20.GL_FLOAT, false, 0, pipVertexBuffer);
            GLES20.glEnableVertexAttribArray(pipTexCoordsInLoc);
            GLES20.glVertexAttribPointer(pipTexCoordsInLoc, 2, GLES20.GL_FLOAT, false, 0, pipTexCoordsBuffer);

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
    }

    @Override
    public void release() {
        if (bgProgram != 0) GLES20.glDeleteProgram(bgProgram);
        if (yuvProgram != 0) GLES20.glDeleteProgram(yuvProgram);
    }
}


