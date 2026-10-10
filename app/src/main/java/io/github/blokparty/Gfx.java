package io.github.blokparty;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * Вся графика. Рассчитано на OpenGL ES 2.0 и слабые чипы вроде Mali-400:
 * в фрагментных шейдерах только mediump, один куб на всё, один батч для интерфейса.
 */
final class Gfx {

    // ---------- 3D ----------
    private static final String VS3 =
            "uniform mat4 uVP;\n" +
            "uniform mat4 uModel;\n" +
            "uniform vec3 uCam;\n" +
            "uniform vec2 uFog;\n" +
            "attribute vec3 aPos;\n" +
            "attribute vec3 aNorm;\n" +
            "varying float vLight;\n" +
            "varying float vFog;\n" +
            "void main() {\n" +
            "  vec4 wp = uModel * vec4(aPos, 1.0);\n" +
            "  vec3 n = normalize((uModel * vec4(aNorm, 0.0)).xyz);\n" +
            "  vLight = 0.5 + 0.5 * max(dot(n, vec3(0.37, 0.86, 0.35)), 0.0) - 0.12 * max(-n.y, 0.0);\n" +
            "  float d = length(wp.xyz - uCam);\n" +
            "  vFog = clamp((d - uFog.x) / (uFog.y - uFog.x), 0.0, 1.0);\n" +
            "  gl_Position = uVP * wp;\n" +
            "}\n";

    private static final String FS3 =
            "precision mediump float;\n" +
            "uniform vec4 uColor;\n" +
            "uniform vec3 uFogColor;\n" +
            "varying float vLight;\n" +
            "varying float vFog;\n" +
            "void main() {\n" +
            "  vec3 c = uColor.rgb * vLight;\n" +
            "  gl_FragColor = vec4(mix(c, uFogColor, vFog), uColor.a);\n" +
            "}\n";

    // ---------- UI ----------
    private static final String VSUI =
            "uniform vec2 uScreen;\n" +
            "attribute vec2 aPos;\n" +
            "attribute vec2 aUV;\n" +
            "attribute vec4 aCol;\n" +
            "varying vec2 vUV;\n" +
            "varying vec4 vCol;\n" +
            "void main() {\n" +
            "  vUV = aUV; vCol = aCol;\n" +
            "  gl_Position = vec4(aPos.x / uScreen.x * 2.0 - 1.0, 1.0 - aPos.y / uScreen.y * 2.0, 0.0, 1.0);\n" +
            "}\n";

    private static final String FSUI =
            "precision mediump float;\n" +
            "uniform sampler2D uTex;\n" +
            "varying vec2 vUV;\n" +
            "varying vec4 vCol;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(uTex, vUV) * vCol;\n" +
            "}\n";

    static final String CHARS =
            " 0123456789.,:;!?-+=%/()*'\"#<>_↑←→№—" +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" +
            "АБВГДЕЁЖЗИЙКЛМНОП" +
            "РСТУФХЦЧШЩЪЫЬЭЮЯ" +
            "абвгдеёжзийклмноп" +
            "рстуфхцчшщъыьэюя";

    static final int ATLAS = 512, CELL_W = 32, CELL_H = 40, COLS = 16;
    static final float FONT_SIZE = 28f, BASELINE = 30f;
    private static final int SPECIAL_Y = 410; // ниже сетки символов
    private static final float CIRCLE_X = 0, CIRCLE_SIZE = 64, WHITE_X = 80;

    private int p3, a3Pos, a3Norm, u3VP, u3Model, u3Color, u3Cam, u3Fog, u3FogColor;
    private int cubeVbo;

    private int pUI, aUIPos, aUIUV, aUICol, uUIScreen, uUITex;
    private int atlasTex;

    private final short[] charMap = new short[65536];
    private final float[] adv = new float[CHARS.length()];

    private static final int MAX_QUADS = 700;
    private static final int STRIDE = 8; // x y u v r g b a
    private final float[] ui = new float[MAX_QUADS * 6 * STRIDE];
    private FloatBuffer uiBuf;
    private int uiVerts;
    private int screenW = 1, screenH = 1;

    Gfx() {
        Arrays.fill(charMap, (short) -1);
        for (int i = 0; i < CHARS.length(); i++) charMap[CHARS.charAt(i)] = (short) i;
        // Ширины до создания атласа (на случай, если текст измеряют раньше)
        Arrays.fill(adv, FONT_SIZE * 0.6f);
    }

    /** Вызывается при каждом создании GL-контекста. */
    void init() {
        p3 = program(VS3, FS3);
        a3Pos = GLES20.glGetAttribLocation(p3, "aPos");
        a3Norm = GLES20.glGetAttribLocation(p3, "aNorm");
        u3VP = GLES20.glGetUniformLocation(p3, "uVP");
        u3Model = GLES20.glGetUniformLocation(p3, "uModel");
        u3Color = GLES20.glGetUniformLocation(p3, "uColor");
        u3Cam = GLES20.glGetUniformLocation(p3, "uCam");
        u3Fog = GLES20.glGetUniformLocation(p3, "uFog");
        u3FogColor = GLES20.glGetUniformLocation(p3, "uFogColor");

        pUI = program(VSUI, FSUI);
        aUIPos = GLES20.glGetAttribLocation(pUI, "aPos");
        aUIUV = GLES20.glGetAttribLocation(pUI, "aUV");
        aUICol = GLES20.glGetAttribLocation(pUI, "aCol");
        uUIScreen = GLES20.glGetUniformLocation(pUI, "uScreen");
        uUITex = GLES20.glGetUniformLocation(pUI, "uTex");

        cubeVbo = makeCube();
        atlasTex = makeAtlas();
        uiBuf = ByteBuffer.allocateDirect(ui.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glCullFace(GLES20.GL_BACK);
        GLES20.glFrontFace(GLES20.GL_CCW);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
    }

    void resize(int w, int h) {
        screenW = w;
        screenH = h;
    }

    // ================= 3D =================

    void begin3D(float[] vp, float cx, float cy, float cz,
                 float fogStart, float fogEnd, float fr, float fg, float fb) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glUseProgram(p3);
        GLES20.glUniformMatrix4fv(u3VP, 1, false, vp, 0);
        GLES20.glUniform3f(u3Cam, cx, cy, cz);
        GLES20.glUniform2f(u3Fog, fogStart, fogEnd);
        GLES20.glUniform3f(u3FogColor, fr, fg, fb);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, cubeVbo);
        GLES20.glEnableVertexAttribArray(a3Pos);
        GLES20.glEnableVertexAttribArray(a3Norm);
        GLES20.glVertexAttribPointer(a3Pos, 3, GLES20.GL_FLOAT, false, 24, 0);
        GLES20.glVertexAttribPointer(a3Norm, 3, GLES20.GL_FLOAT, false, 24, 12);
    }

    void drawBox(float[] model, float r, float g, float b, float a) {
        GLES20.glUniformMatrix4fv(u3Model, 1, false, model, 0);
        GLES20.glUniform4f(u3Color, r, g, b, a);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36);
    }

    /** Полупрозрачные объекты (тени, метки) рисуются после непрозрачных. */
    void blend3D(boolean on) {
        if (on) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glDepthMask(false);
        } else {
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glDepthMask(true);
        }
    }

    void end3D() {
        GLES20.glDisableVertexAttribArray(a3Pos);
        GLES20.glDisableVertexAttribArray(a3Norm);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glDepthMask(true);
    }

    private static int makeCube() {
        float[] v = cubeData();
        FloatBuffer fb = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(v).position(0);
        int[] ids = new int[1];
        GLES20.glGenBuffers(1, ids, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, v.length * 4, fb, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        return ids[0];
    }

    /** Единичный куб: 36 вершин (позиция + нормаль). */
    static float[] cubeData() {
        float[] v = new float[36 * 6];
        int k = 0;
        float[][] normals = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (float[] n : normals) {
            // u перпендикулярен n, w = n x u, тогда u x w = n и обход против часовой стрелки снаружи
            float[] u = Math.abs(n[1]) > 0.5f ? new float[]{1, 0, 0} : new float[]{0, 1, 0};
            float[] w = {n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0]};
            float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, -1}, {1, 1}, {-1, 1}};
            for (float[] c : corners) {
                for (int i = 0; i < 3; i++) v[k + i] = n[i] * 0.5f + u[i] * 0.5f * c[0] + w[i] * 0.5f * c[1];
                v[k + 3] = n[0]; v[k + 4] = n[1]; v[k + 5] = n[2];
                k += 6;
            }
        }
        return v;
    }

    // ================= Шрифт и интерфейс =================

    private int makeAtlas() {
        Bitmap bmp = Bitmap.createBitmap(ATLAS, ATLAS, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint();
        p.setAntiAlias(true);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(FONT_SIZE);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        for (int i = 0; i < CHARS.length(); i++) {
            String ch = String.valueOf(CHARS.charAt(i));
            int x = (i % COLS) * CELL_W;
            int y = (i / COLS) * CELL_H;
            cv.drawText(ch, x + 2, y + BASELINE, p);
            adv[i] = p.measureText(ch);
        }
        cv.drawCircle(CIRCLE_X + CIRCLE_SIZE / 2f, SPECIAL_Y + CIRCLE_SIZE / 2f, CIRCLE_SIZE / 2f - 1f, p);
        cv.drawRect(WHITE_X, SPECIAL_Y, WHITE_X + 8, SPECIAL_Y + 8, p);

        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();
        return ids[0];
    }

    void beginUI() {
        uiVerts = 0;
    }

    private void vert(float x, float y, float u, float v, float r, float g, float b, float a) {
        int i = uiVerts * STRIDE;
        ui[i] = x; ui[i + 1] = y; ui[i + 2] = u; ui[i + 3] = v;
        // предумноженная альфа
        ui[i + 4] = r * a; ui[i + 5] = g * a; ui[i + 6] = b * a; ui[i + 7] = a;
        uiVerts++;
    }

    void quad(float x, float y, float w, float h, float u0, float v0, float u1, float v1, int argb) {
        if (uiVerts + 6 > MAX_QUADS * 6) flushUI();
        float a = ((argb >>> 24) & 255) / 255f;
        float r = ((argb >> 16) & 255) / 255f;
        float g = ((argb >> 8) & 255) / 255f;
        float b = (argb & 255) / 255f;
        vert(x, y, u0, v0, r, g, b, a);
        vert(x + w, y, u1, v0, r, g, b, a);
        vert(x + w, y + h, u1, v1, r, g, b, a);
        vert(x, y, u0, v0, r, g, b, a);
        vert(x + w, y + h, u1, v1, r, g, b, a);
        vert(x, y + h, u0, v1, r, g, b, a);
    }

    void rect(float x, float y, float w, float h, int argb) {
        float u = (WHITE_X + 4) / ATLAS, v = (SPECIAL_Y + 4) / ATLAS;
        quad(x, y, w, h, u, v, u, v, argb);
    }

    void circle(float cx, float cy, float r, int argb) {
        float u0 = CIRCLE_X / ATLAS, v0 = SPECIAL_Y / (float) ATLAS;
        float u1 = (CIRCLE_X + CIRCLE_SIZE) / ATLAS, v1 = (SPECIAL_Y + CIRCLE_SIZE) / ATLAS;
        quad(cx - r, cy - r, r * 2, r * 2, u0, v0, u1, v1, argb);
    }

    /** Высота строки текста при масштабе sc. */
    static float lineH(float sc) {
        return CELL_H * sc;
    }

    float textW(String s, float sc) {
        float w = 0;
        for (int i = 0; i < s.length(); i++) {
            int idx = charMap[s.charAt(i)];
            w += (idx >= 0 ? adv[idx] : adv[0]) * sc;
        }
        return w;
    }

    /** Текст: (x, y) — левый верхний угол. */
    void text(String s, float x, float y, float sc, int argb) {
        float pen = x;
        for (int i = 0; i < s.length(); i++) {
            int idx = charMap[s.charAt(i)];
            if (idx < 0) { pen += adv[0] * sc; continue; }
            if (idx > 0) {
                float u0 = (idx % COLS) * CELL_W / (float) ATLAS;
                float v0 = (idx / COLS) * CELL_H / (float) ATLAS;
                quad(pen - 2 * sc, y, CELL_W * sc, CELL_H * sc,
                        u0, v0, u0 + CELL_W / (float) ATLAS, v0 + CELL_H / (float) ATLAS, argb);
            }
            pen += adv[idx] * sc;
        }
    }

    /** Текст с тенью для читаемости поверх 3D. */
    void textS(String s, float x, float y, float sc, int argb) {
        int sh = ((argb >>> 24) * 160 / 255) << 24;
        text(s, x + 2 * sc, y + 2 * sc, sc, sh);
        text(s, x, y, sc, argb);
    }

    void textC(String s, float cx, float y, float sc, int argb) {
        textS(s, cx - textW(s, sc) / 2f, y, sc, argb);
    }

    void flushUI() {
        if (uiVerts == 0) return;
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(pUI);
        GLES20.glUniform2f(uUIScreen, screenW, screenH);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTex);
        GLES20.glUniform1i(uUITex, 0);

        uiBuf.position(0);
        uiBuf.put(ui, 0, uiVerts * STRIDE);
        GLES20.glEnableVertexAttribArray(aUIPos);
        GLES20.glEnableVertexAttribArray(aUIUV);
        GLES20.glEnableVertexAttribArray(aUICol);
        uiBuf.position(0);
        GLES20.glVertexAttribPointer(aUIPos, 2, GLES20.GL_FLOAT, false, STRIDE * 4, uiBuf);
        uiBuf.position(2);
        GLES20.glVertexAttribPointer(aUIUV, 2, GLES20.GL_FLOAT, false, STRIDE * 4, uiBuf);
        uiBuf.position(4);
        GLES20.glVertexAttribPointer(aUICol, 4, GLES20.GL_FLOAT, false, STRIDE * 4, uiBuf);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, uiVerts);
        GLES20.glDisableVertexAttribArray(aUIPos);
        GLES20.glDisableVertexAttribArray(aUIUV);
        GLES20.glDisableVertexAttribArray(aUICol);
        uiVerts = 0;
    }

    // ================= Шейдеры =================

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new RuntimeException("Shader error: " + log);
        }
        return s;
    }

    private static int program(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("Link error: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }
}
