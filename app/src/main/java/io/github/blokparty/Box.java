package io.github.blokparty;

/** Неподвижный блок мира (параллелепипед). */
final class Box {
    static final int SOLID = 0, KILL = 1, CHECKPOINT = 2, FINISH = 3, DECOR = 4;

    final float x0, y0, z0, x1, y1, z1;
    final float r, g, b;
    final int kind;

    Box(float x0, float y0, float z0, float x1, float y1, float z1, int rgb, int kind) {
        this.x0 = x0; this.y0 = y0; this.z0 = z0;
        this.x1 = x1; this.y1 = y1; this.z1 = z1;
        this.r = ((rgb >> 16) & 255) / 255f;
        this.g = ((rgb >> 8) & 255) / 255f;
        this.b = (rgb & 255) / 255f;
        this.kind = kind;
    }

    /** Платформа: центр по X, верх по Y, начало по Z, ширина, длина по Z, толщина. */
    static Box plat(float cx, float top, float z0, float sx, float sz, float thick, int rgb, int kind) {
        return new Box(cx - sx / 2f, top - thick, z0, cx + sx / 2f, top, z0 + sz, rgb, kind);
    }

    /** Блок по центру основания. */
    static Box block(float cx, float cz, float sx, float sz, float bottom, float top, int rgb) {
        return new Box(cx - sx / 2f, bottom, cz - sz / 2f, cx + sx / 2f, top, cz + sz / 2f, rgb, SOLID);
    }

    boolean solid() { return kind != DECOR; }

    float cx() { return (x0 + x1) / 2f; }
    float cz() { return (z0 + z1) / 2f; }
}
