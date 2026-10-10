package io.github.blokparty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Random;

/** Уклонись: с неба падают блоки, кто продержится дольше всех. */
final class DodgeGame extends MiniGame {
    static final float HALF = 7f;
    static final float START_Y = 15f;

    static final class Rock {
        float x, y, z, vy, size;
    }

    private final Random r = new Random();
    final ArrayList<Rock> rocks = new ArrayList<Rock>();
    private float spawnT, endT;

    DodgeGame(Match m) { super(m); }

    @Override String name() { return "Уклонись"; }
    @Override String hint() { return "Не попади под падающие блоки"; }
    @Override String key() { return "dodge"; }
    @Override boolean lowerIsBetter() { return false; }
    @Override String formatScore(float v) { return sec1(v) + " сек"; }

    @Override
    void build(ArrayList<Box> b) {
        for (int ix = 0; ix < 7; ix++) {
            for (int iz = 0; iz < 7; iz++) {
                float x0 = -HALF + ix * 2, z0 = -HALF + iz * 2;
                int col = ((ix + iz) & 1) == 0 ? 0xE0E0E0 : 0xB8C2D0;
                b.add(new Box(x0, -1, z0, x0 + 2, 0, z0 + 2, col, Box.SOLID));
            }
        }
        int wall = 0x6E7F99;
        b.add(new Box(-HALF - 1, -1, -HALF - 1, -HALF, 2.4f, HALF + 1, wall, Box.SOLID));
        b.add(new Box(HALF, -1, -HALF - 1, HALF + 1, 2.4f, HALF + 1, wall, Box.SOLID));
        b.add(new Box(-HALF, -1, -HALF - 1, HALF, 2.4f, -HALF, wall, Box.SOLID));
        b.add(new Box(-HALF, -1, HALF, HALF, 2.4f, HALF + 1, wall, Box.SOLID));
    }

    @Override
    void reset() {
        rocks.clear();
        spawnT = 1.2f;
        endT = -1;
    }

    @Override
    void spawn(Player p, int idx) {
        double a = idx * 0.785398;
        float rad = idx == 0 ? 0f : 2.6f;
        m.teleport(p, (float) Math.sin(a) * rad, 0.02f, (float) Math.cos(a) * rad);
    }

    private void spawnRock() {
        Rock k = new Rock();
        k.size = 1.3f + r.nextFloat() * 0.9f;
        float lim = HALF - k.size / 2f;
        Player target = null;
        int alive = m.aliveCount(false);
        if (alive > 0 && r.nextFloat() < 0.45f) {
            int pick = r.nextInt(alive);
            for (int i = 0; i < m.players.size(); i++) {
                Player p = m.players.get(i);
                if (p.alive && pick-- == 0) { target = p; break; }
            }
        }
        if (target != null) {
            k.x = target.x + (r.nextFloat() - 0.5f) * 2f;
            k.z = target.z + (r.nextFloat() - 0.5f) * 2f;
        } else {
            k.x = (r.nextFloat() * 2f - 1f) * lim;
            k.z = (r.nextFloat() * 2f - 1f) * lim;
        }
        k.x = clamp(k.x, -lim, lim);
        k.z = clamp(k.z, -lim, lim);
        k.y = START_Y;
        rocks.add(k);
    }

    @Override
    void update(float dt) {
        int alive = m.aliveCount(false);
        spawnT -= dt;
        if (spawnT <= 0 && alive > 0) {
            spawnRock();
            float base = Math.max(0.22f, 1.0f - m.time * 0.018f);
            spawnT = base / (1f + 0.15f * (alive - 1));
        }
        for (int i = rocks.size() - 1; i >= 0; i--) {
            Rock k = rocks.get(i);
            k.vy -= 18f * dt;
            k.y += k.vy * dt;
            float hs = k.size / 2f;
            for (int j = 0; j < m.players.size(); j++) {
                Player p = m.players.get(j);
                if (!p.alive) continue;
                if (p.hits(k.x - hs, k.y, k.z - hs, k.x + hs, k.y + k.size, k.z + hs)) {
                    p.alive = false;
                    p.score = m.time;
                    m.msg(null, p.nick + " выбыл!");
                }
            }
            if (k.y <= 0f) rocks.remove(i);
        }
        for (int j = 0; j < m.players.size(); j++) {
            Player p = m.players.get(j);
            if (p.alive && p.y < -5f) { // как-то выбрался за арену
                p.alive = false;
                p.score = m.time;
                m.msg(null, p.nick + " упал с арены!");
            }
            if (p.alive) p.score = m.time;
        }
        boolean humans = false;
        for (int j = 0; j < m.players.size(); j++) if (!m.players.get(j).bot) humans = true;
        if ((humans && m.aliveCount(true) == 0) || m.aliveCount(false) == 0) {
            if (endT < 0) endT = 0;
            endT += dt;
            if (endT > 1.5f) m.over = true;
        }
    }

    @Override
    void bot(Player b, float dt, float[] c) {
        float ax = 0, az = 0;
        float notice = 3f + 6f * b.botSkill; // хорошие боты замечают блоки раньше
        for (int i = 0; i < rocks.size(); i++) {
            Rock k = rocks.get(i);
            if (k.y > notice) continue;
            float dx = b.x - k.x, dz = b.z - k.z;
            float rr = k.size / 2f + 1.3f;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d >= rr + 0.8f) continue;
            if (d < 0.01f) { dx = 1; dz = 0; d = 1; }
            float w = (rr + 0.8f - d) / (rr + 0.8f) * (1.5f - k.y / START_Y);
            ax += dx / d * w * 3f;
            az += dz / d * w * 3f;
        }
        float lim = HALF - 1.4f;
        if (b.x > lim) ax -= (b.x - lim) * 2f;
        if (b.x < -lim) ax -= (b.x + lim) * 2f;
        if (b.z > lim) az -= (b.z - lim) * 2f;
        if (b.z < -lim) az -= (b.z + lim) * 2f;
        float len = (float) Math.sqrt(ax * ax + az * az);
        if (len < 0.15f) {
            b.botTimer -= dt;
            if (b.botTimer <= 0) {
                b.botTimer = 1.5f + r.nextFloat() * 2f;
                b.botWx = (r.nextFloat() * 2f - 1f) * 4f;
                b.botWz = (r.nextFloat() * 2f - 1f) * 4f;
            }
            float dx = b.botWx - b.x, dz = b.botWz - b.z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d > 0.5f) { c[0] = dx / d * 0.35f; c[1] = dz / d * 0.35f; }
            return;
        }
        if (len > 1f) { ax /= len; az /= len; }
        c[0] = ax * b.botSkill;
        c[1] = az * b.botSkill;
    }

    @Override
    String hud(Player p) {
        String s = sec1(m.time) + "  Живых: " + m.aliveCount(false);
        if (!p.alive) s += "  Ты выбыл: " + sec1(p.score);
        return s;
    }

    @Override
    void rank(ArrayList<Player> list) {
        Collections.sort(list, new Comparator<Player>() {
            public int compare(Player a, Player b) {
                if (a.alive != b.alive) return a.alive ? -1 : 1;
                return Float.compare(b.score, a.score);
            }
        });
    }

    @Override
    String scoreText(Player p) { return sec1(p.score) + " сек"; }

    @Override
    void fillScene(Scene s) {
        for (int i = 0; i < rocks.size(); i++) {
            Rock k = rocks.get(i);
            s.addRock(k.x, k.y, k.z, k.size);
        }
    }
}
