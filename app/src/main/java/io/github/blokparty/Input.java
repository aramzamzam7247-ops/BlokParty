package io.github.blokparty;

import android.view.MotionEvent;

import java.util.ArrayList;

/**
 * Сенсорное управление. Вызывается из UI-потока, читается из GL-потока,
 * поэтому все методы synchronized.
 * Левая половина экрана — джойстик, кнопка справа внизу — прыжок,
 * остальная правая половина — поворот камеры.
 */
final class Input {
    private static final int MAXP = 16;

    private int w = 1, h = 1;
    private boolean playMode;

    private int joyId = -1;
    private float jsx, jsy, jx, jy;

    private int lookId = -1;
    private float lookLast, lookAccum;

    private int jumpId = -1;
    private boolean jumpPressed;

    private boolean back;

    private final float[] downX = new float[MAXP], downY = new float[MAXP];
    private final boolean[] moved = new boolean[MAXP];
    private final ArrayList<float[]> taps = new ArrayList<float[]>();

    static float jumpR(int w, int h) { return Math.min(w, h) * 0.16f; }
    static float jumpX(int w, int h) { return w - jumpR(w, h) * 1.35f; }
    static float jumpY(int w, int h) { return h - jumpR(w, h) * 1.35f; }
    static float joyR(int w, int h) { return Math.min(w, h) * 0.15f; }

    synchronized void setSize(int w, int h) { this.w = w; this.h = h; }

    synchronized void setPlayMode(boolean on) {
        if (on != playMode) { playMode = on; releaseAll(); }
    }

    synchronized void reset() { releaseAll(); taps.clear(); jumpPressed = false; lookAccum = 0; }

    private void releaseAll() { joyId = -1; lookId = -1; jumpId = -1; }

    synchronized void onTouch(MotionEvent e) {
        int act = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (act) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                down(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) move(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                up(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
            default:
                break;
        }
    }

    private void down(int id, float x, float y) {
        if (id >= 0 && id < MAXP) { downX[id] = x; downY[id] = y; moved[id] = false; }
        if (!playMode) return;
        float dx = x - jumpX(w, h), dy = y - jumpY(w, h), r = jumpR(w, h) * 1.25f;
        if (dx * dx + dy * dy < r * r) {
            jumpId = id;
            jumpPressed = true;
        } else if (x < w * 0.5f && joyId < 0) {
            joyId = id; jsx = jx = x; jsy = jy = y;
        } else if (x >= w * 0.5f && lookId < 0) {
            lookId = id; lookLast = x;
        }
    }

    private void move(int id, float x, float y) {
        if (id >= 0 && id < MAXP && !moved[id]) {
            float dx = x - downX[id], dy = y - downY[id], t = Math.min(w, h) * 0.04f;
            if (dx * dx + dy * dy > t * t) moved[id] = true;
        }
        if (id == joyId) { jx = x; jy = y; }
        if (id == lookId) { lookAccum += x - lookLast; lookLast = x; }
    }

    private void up(int id, float x, float y) {
        boolean wasMoved = id >= 0 && id < MAXP && moved[id];
        if (!wasMoved && id != jumpId) taps.add(new float[]{x, y});
        if (id == joyId) joyId = -1;
        if (id == lookId) lookId = -1;
        if (id == jumpId) jumpId = -1;
    }

    synchronized void pressBack() { back = true; }

    synchronized boolean takeBack() { boolean b = back; back = false; return b; }

    synchronized void pollTaps(ArrayList<float[]> out) { out.addAll(taps); taps.clear(); }

    synchronized float takeLook() { float v = lookAccum; lookAccum = 0; return v; }

    synchronized boolean takeJump() { boolean b = jumpPressed; jumpPressed = false; return b; }

    synchronized boolean jumpHeld() { return jumpId >= 0; }

    /** out[0..1] — направление джойстика (-1..1), out[2..5] — база и ручка для отрисовки, out[6] — активен ли. */
    synchronized void getJoy(float[] out) {
        float r = joyR(w, h);
        if (joyId < 0) {
            out[0] = out[1] = 0;
            out[2] = out[4] = w * 0.17f; out[3] = out[5] = h * 0.72f; out[6] = 0;
            return;
        }
        float dx = jx - jsx, dy = jy - jsy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > r) { dx = dx / len * r; dy = dy / len * r; }
        out[0] = dx / r; out[1] = dy / r;
        out[2] = jsx; out[3] = jsy; out[4] = jsx + dx; out[5] = jsy + dy; out[6] = 1;
    }
}
