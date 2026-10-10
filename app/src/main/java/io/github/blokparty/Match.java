package io.github.blokparty;

import java.util.ArrayList;

/**
 * Матч на хосте: уровень, все участники (сам хост, игроки по сети, боты) и правила мини-игры.
 * Не зависит от Android и графики — можно запускать хоть на ПК.
 */
final class Match {
    interface Out {
        /** to == null — всем. */
        void msg(Player to, String text);

        void teleported(Player p);
    }

    final ArrayList<Box> boxes = new ArrayList<Box>();
    final ArrayList<Player> players = new ArrayList<Player>();
    final MiniGame[] modes;
    MiniGame mode;
    int modeIndex = -1;
    float time;
    boolean running, over;
    Out out;
    boolean quiet; // во время старта телепорты не рассылаем — клиент получит точку в LEVEL

    private final float[] ctl = new float[3];

    Match() {
        modes = new MiniGame[]{new ObbyGame(this), new CoinGame(this), new DodgeGame(this)};
    }

    void start(int idx, ArrayList<Player> who) {
        modeIndex = idx;
        mode = modes[idx];
        boxes.clear();
        players.clear();
        players.addAll(who);
        time = 0;
        over = false;
        running = true;
        mode.build(boxes);
        mode.reset();
        quiet = true;
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            p.resetMatch();
            mode.spawn(p, i);
        }
        quiet = false;
    }

    /** Игрок подключился во время матча. */
    void join(Player p) {
        if (!running) return;
        p.resetMatch();
        quiet = true;
        mode.spawn(p, players.size());
        quiet = false;
        players.add(p);
    }

    void leave(Player p) {
        players.remove(p);
    }

    void stop() {
        running = false;
    }

    void update(float dt) {
        if (!running || over) return;
        time += dt;
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            if (p.grace > 0) p.grace -= dt;
            if (p.bot && p.alive && !p.done) {
                ctl[0] = ctl[1] = ctl[2] = 0;
                mode.bot(p, dt, ctl);
                p.update(dt, boxes, ctl[0], ctl[1], ctl[2] > 0);
            } else if (p.bot) {
                p.update(dt, boxes, 0, 0, false);
            }
        }
        mode.update(dt);
    }

    void teleport(Player p, float x, float y, float z) {
        p.spawn(x, y, z, 0);
        p.grace = 0.6f;
        if (!quiet && out != null) out.teleported(p);
    }

    void msg(Player to, String text) {
        if (out != null) out.msg(to, text);
    }

    /** На каком блоке стоит игрок (для сетевых игроков считаем по координатам). */
    Box groundOf(Player p) {
        if (!p.remote) return p.onGround ? p.ground : null;
        Box best = null;
        for (int i = 0, n = boxes.size(); i < n; i++) {
            Box b = boxes.get(i);
            if (!b.solid()) continue;
            if (p.x + Player.HW > b.x0 && p.x - Player.HW < b.x1 && p.z + Player.HW > b.z0 && p.z - Player.HW < b.z1
                    && Math.abs(p.y - b.y1) < 0.08f) {
                if (best == null || b.y1 > best.y1) best = b;
            }
        }
        return best;
    }

    /** Касается ли игрок лавы. */
    boolean touchingKill(Player p) {
        if (!p.remote && p.touchedKill) return true;
        for (int i = 0, n = boxes.size(); i < n; i++) {
            Box b = boxes.get(i);
            if (b.kind != Box.KILL) continue;
            if (p.x + Player.HW > b.x0 && p.x - Player.HW < b.x1 && p.z + Player.HW > b.z0 && p.z - Player.HW < b.z1
                    && p.y < b.y1 + 0.08f && p.y + Player.H > b.y0) return true;
        }
        return false;
    }

    int aliveCount(boolean humansOnly) {
        int n = 0;
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            if (p.alive && (!humansOnly || !p.bot)) n++;
        }
        return n;
    }

    ArrayList<Player> ranking() {
        ArrayList<Player> r = new ArrayList<Player>(players);
        mode.rank(r);
        return r;
    }
}
