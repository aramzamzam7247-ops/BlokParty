package io.github.blokparty;

/** Минимальная математика матриц 4x4 (column-major, как в OpenGL). */
final class Mat4 {
    private Mat4() {}

    static void identity(float[] m) {
        for (int i = 0; i < 16; i++) m[i] = 0f;
        m[0] = m[5] = m[10] = m[15] = 1f;
    }

    static void perspective(float[] m, float fovyDeg, float aspect, float near, float far) {
        float f = (float) (1.0 / Math.tan(Math.toRadians(fovyDeg) / 2.0));
        for (int i = 0; i < 16; i++) m[i] = 0f;
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (far + near) / (near - far);
        m[11] = -1f;
        m[14] = 2f * far * near / (near - far);
    }

    static void lookAt(float[] m, float ex, float ey, float ez,
                       float cx, float cy, float cz) {
        float fx = cx - ex, fy = cy - ey, fz = cz - ez;
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fl; fy /= fl; fz /= fl;
        // s = f x up(0,1,0)
        float sx = -fz, sy = 0f, sz = fx;
        float sl = (float) Math.sqrt(sx * sx + sz * sz);
        if (sl < 1e-6f) { sx = 1f; sz = 0f; sl = 1f; }
        sx /= sl; sz /= sl;
        // u = s x f
        float ux = sy * fz - sz * fy;
        float uy = sz * fx - sx * fz;
        float uz = sx * fy - sy * fx;
        m[0] = sx; m[4] = sy; m[8] = sz;
        m[1] = ux; m[5] = uy; m[9] = uz;
        m[2] = -fx; m[6] = -fy; m[10] = -fz;
        m[3] = 0f; m[7] = 0f; m[11] = 0f;
        m[12] = -(sx * ex + sy * ey + sz * ez);
        m[13] = -(ux * ex + uy * ey + uz * ez);
        m[14] = (fx * ex + fy * ey + fz * ez);
        m[15] = 1f;
    }

    /** out = a * b. out не должен совпадать с a или b. */
    static void mul(float[] out, float[] a, float[] b) {
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                out[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1]
                        + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
            }
        }
    }

    /** Перенос + поворот вокруг Y. Локальная ось +Z смотрит в (sin a, 0, cos a). */
    static void transRotY(float[] m, float x, float y, float z, float a) {
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        identity(m);
        m[0] = c; m[2] = -s;
        m[8] = s; m[10] = c;
        m[12] = x; m[13] = y; m[14] = z;
    }

    /** Единичный куб (-0.5..0.5) -> коробка с центром c и размером s. */
    static void box(float[] m, float cx, float cy, float cz, float sx, float sy, float sz) {
        identity(m);
        m[0] = sx; m[5] = sy; m[10] = sz;
        m[12] = cx; m[13] = cy; m[14] = cz;
    }
}
