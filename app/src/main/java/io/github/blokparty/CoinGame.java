package io.github.blokparty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Random;

/** Монетки: кто больше соберёт за минуту. */
final class CoinGame extends MiniGame {
    static final float LIMIT = 60f;

    // cx, cz, ширина, длина, высота
    private static final float[][] OBSTACLES = {
            {4, 4, 3, 3, 1}, {7, 4, 3, 3, 2}, {7, 7, 2, 2, 3},
            {-5, -5, 3, 3, 1}, {-5, -8, 2, 2, 2},
            {-7, 6, 4, 2, 1},
            {5, -6, 2, 2, 1}, {8, -6, 2, 2, 2}, {9.5f, -9, 2, 2, 3},
    };
    private static final int[] COLORS = {0x3F80F0, 0x9966E6, 0xF28CC0, 0x4DCCD9, 0xE8E8E8};

    private final Random r = new Random();
    private final ArrayList<float[]> spots = new ArrayList<float[]>();
    private final float[][] coins = new float[6][];
    private int nCoins = 3;

    CoinGame(Match m) { super(m); }

    @Override String name() { return "Монетки"; }
    @Override String hint() { return "Собери больше всех за минуту"; }
    @Override String key() { return "coins"; }
    @Override boolean lowerIsBetter() { return false; }
    @Override String formatScore(float v) { return "Монет: " + (int) v; }

    @Override
    void build(ArrayList<Box> b) {
        b.add(new Box(-13, -1, -13, 13, 0, 13, 0x5DBB4F, Box.SOLID));
        int wall = 0x8C7A66;
        b.add(new Box(-14, -1, -14, -13, 2, 14, wall, Box.SOLID));
        b.add(new Box(13, -1, -14, 14, 2, 14, wall, Box.SOLID));
        b.add(new Box(-13, -1, -14, 13, 2, -13, wall, Box.SOLID));
        b.add(new Box(-13, -1, 13, 13, 2, 14, wall, Box.SOLID));
        for (int i = 0; i < OBSTACLES.length; i++) {
            float[] o = OBSTACLES[i];
            b.add(Box.block(o[0], o[1], o[2], o[3], 0, o[4], COLORS[i % COLORS.length]));
        }
        spots.clear();
        for (int x = -11; x <= 11; x += 2) {
            for (int z = -11; z <= 11; z += 2) {
                if (Math.abs(x) < 3 && Math.abs(z) < 3) continue;
                if (covered(x, z)) continue;
                spots.add(new float[]{x, 0, z});
            }
        }
        for (int i = 0; i < OBSTACLES.length; i++) {
            float[] o = OBSTACLES[i];
            spots.add(new float[]{o[0], o[4], o[1]});
        }
    }

    private static boolean covered(float x, float z) {
        for (int i = 0; i < OBSTACLES.length; i++) {
            float[] o = OBSTACLES[i];
            if (x + 0.7f > o[0] - o[2] / 2 && x - 0.7f < o[0] + o[2] / 2
                    && z + 0.7f > o[1] - o[3] / 2 && z - 0.7f < o[1] + o[3] / 2) return true;
        }
        return false;
    }

    @Override
    void reset() {
        nCoins = Math.min(coins.length, m.players.size() + 2);
        for (int i = 0; i < coins.length; i++) coins[i] = null;
    }

    @Override
    void spawn(Player p, int idx) {
        float x = ((idx % 4) - 1.5f) * 1.4f;
        float z = (idx / 4) * 1.4f - 0.7f;
        m.teleport(p, x, 0.02f, z);
    }

    private void spawnCoin(int i) {
        for (int tries = 0; tries < 40; tries++) {
            float[] s = spots.get(r.nextInt(spots.size()));
            boolean ok = true;
            for (int j = 0; j < nCoins && ok; j++) {
                if (j == i || coins[j] == null) continue;
                float dx = coins[j][0] - s[0], dz = coins[j][2] - s[2];
                if (dx * dx + dz * dz < 9f) ok = false;
            }
            for (int j = 0; j < m.players.size() && ok; j++) {
                Player p = m.players.get(j);
                float dx = p.x - s[0], dz = p.z - s[2];
                if (dx * dx + dz * dz < 6f) ok = false;
            }
            if (ok || tries == 39) {
                coins[i] = new float[]{s[0], s[1] + 0.9f, s[2]};
                return;
            }
        }
    }

    @Override
    void update(float dt) {
        for (int i = 0; i < nCoins; i++) if (coins[i] == null) spawnCoin(i);
        for (int k = 0; k < m.players.size(); k++) {
            Player p = m.players.get(k);
            if (p.botAvoidT > 0) p.botAvoidT -= dt;
            if (p.y < -5f && p.grace <= 0) m.teleport(p, 0f, 0.02f, 0f); // выпрыгнул за стену
            for (int i = 0; i < nCoins; i++) {
                float[] c = coins[i];
                float dx = p.x - c[0], dy = (p.y + 0.9f) - c[1], dz = p.z - c[2];
                if (dx * dx + dy * dy + dz * dz < 1.0f) {
                    p.coins++;
                    p.score = p.coins;
                    spawnCoin(i);
                }
            }
        }
        if (m.time >= LIMIT) m.over = true;
    }

    private float cost(Player b, int i) {
        float dx = coins[i][0] - b.x, dz = coins[i][2] - b.z;
        float h = coins[i][1] - (b.y + 0.9f);
        return (float) Math.sqrt(dx * dx + dz * dz) + Math.max(0f, h) * 3f;
    }

    @Override
    void bot(Player b, float dt, float[] c) {
        // выбор цели с «упрямством»: меняем, только если другая монетка заметно ближе
        int cur = b.botTarget;
        boolean curOk = cur >= 0 && cur < nCoins && coins[cur] != null && !(cur == b.botAvoid && b.botAvoidT > 0);
        int best = -1;
        float bestD = 1e9f;
        for (int i = 0; i < nCoins; i++) {
            if (coins[i] == null) continue;
            if (i == b.botAvoid && b.botAvoidT > 0) continue;
            float d = cost(b, i);
            if (d < bestD) { bestD = d; best = i; }
        }
        if (best < 0) return;
        if (!curOk || bestD < cost(b, cur) * 0.7f) {
            if (best != cur) { b.botTarget = best; b.botTimer = 0; b.botWx = 1e9f; }
        }
        int ti = b.botTarget;
        float[] t = coins[ti];
        float dx = t[0] - b.x, dz = t[2] - b.z;
        float d = (float) Math.sqrt(dx * dx + dz * dz);

        // следим за прогрессом: если 2.5 с не приблизились — бросаем эту монетку
        b.botTimer += dt;
        float full = d + Math.abs(t[1] - (b.y + 0.9f));
        if (full < b.botWx - 0.3f) { b.botWx = full; b.botTimer = 0; }
        if (b.botTimer > 2.5f) {
            b.botAvoid = ti;
            b.botAvoidT = 6f;
            b.botTarget = -1;
            return;
        }

        if (d > 0.05f) {
            float ux = dx / d, uz = dz / d;
            if (b.botWz > 0) { // обходим препятствие боком
                b.botWz -= dt;
                c[0] = (ux * 0.3f - uz * 0.9f) * b.botSkill;
                c[1] = (uz * 0.3f + ux * 0.9f) * b.botSkill;
            } else {
                c[0] = ux * b.botSkill;
                c[1] = uz * b.botSkill;
            }
        }
        if (!b.onGround) return;
        float sp = (float) Math.sqrt(b.vx * b.vx + b.vz * b.vz);
        if (d > 0.3f && sp < 1.0f) b.botStuck += dt; else b.botStuck = 0;
        float h = t[1] - (b.y + 0.9f);
        if ((h > 0.4f && d < 2.6f) || b.botStuck > 0.25f) {
            c[2] = 1;
            if (b.botStuck > 0.25f && b.botWz <= 0) b.botWz = 0.7f;
            b.botStuck = 0;
        }
    }

    @Override
    String hud(Player p) {
        int left = (int) Math.ceil(Math.max(0f, LIMIT - m.time));
        return "Монет: " + p.coins + "   Осталось: " + left + " с";
    }

    @Override
    void rank(ArrayList<Player> list) {
        Collections.sort(list, new Comparator<Player>() {
            public int compare(Player a, Player b) { return b.coins - a.coins; }
        });
    }

    @Override
    String scoreText(Player p) { return "Монет: " + p.coins; }

    @Override
    void fillScene(Scene s) {
        for (int i = 0; i < nCoins; i++) if (coins[i] != null) s.addCoin(coins[i][0], coins[i][1], coins[i][2]);
    }
}
