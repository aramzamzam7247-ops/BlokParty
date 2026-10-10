package io.github.blokparty;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

final class GameRenderer implements GLSurfaceView.Renderer {
    private final Game game;
    private final Input input;
    private long last;

    GameRenderer(Game game, Input input) {
        this.game = game;
        this.input = input;
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        game.surfaceCreated();
        last = System.nanoTime();
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        input.setSize(w, h);
        game.resize(w, h);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        long now = System.nanoTime();
        float dt = (now - last) / 1e9f;
        last = now;
        game.frame(dt);
    }
}
