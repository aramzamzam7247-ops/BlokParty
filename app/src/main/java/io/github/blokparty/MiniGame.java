package io.github.blokparty;

import java.util.ArrayList;

/** Правила мини-игры для любого числа участников. Работает только на хосте. */
abstract class MiniGame {
    final Match m;

    MiniGame(Match m) { this.m = m; }

    abstract String name();

    abstract String hint();

    abstract String key();

    abstract boolean lowerIsBetter();

    abstract String formatScore(float v);

    abstract void build(ArrayList<Box> boxes);

    void reset() {}

    /** Поставить участника номер idx на старт. */
    abstract void spawn(Player p, int idx);

    /** Правила: очки, чекпоинты, выбывание, конец матча (m.over). */
    abstract void update(float dt);

    /** Управление ботом: ctl[0..1] — направление, ctl[2] > 0 — прыжок. */
    abstract void bot(Player b, float dt, float[] ctl);

    /** Строка вверху экрана для этого игрока. */
    abstract String hud(Player p);

    abstract void rank(ArrayList<Player> list);

    abstract String scoreText(Player p);

    /** Можно ли засчитать результат как рекорд. */
    boolean hasScore(Player p) { return true; }

    /** Монетки и падающие блоки. */
    void fillScene(Scene s) {}

    static String time(float sec) {
        if (sec < 0) sec = 0;
        int tenths = (int) (sec * 10f);
        int mm = tenths / 600;
        int ss = (tenths / 10) % 60;
        int d = tenths % 10;
        return mm + ":" + (ss < 10 ? "0" : "") + ss + "." + d;
    }

    static String sec1(float sec) {
        int tenths = (int) (sec * 10f);
        return (tenths / 10) + "." + (tenths % 10);
    }

    static float clamp(float v, float a, float b) {
        return v < a ? a : (v > b ? b : v);
    }
}
