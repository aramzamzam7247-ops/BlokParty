package io.github.blokparty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Random;

/** Паркур (обби): гонка по полосе препятствий с чекпоинтами и лавой. */
final class ObbyGame extends MiniGame {
    private static final int[] PALETTE = {0x3F80F0, 0xF2CC40, 0x9966E6, 0x4DCCD9, 0xE8E8E8, 0xF28CC0};
    private static final int SEGMENTS = 34;
    static final float AFTER_FIRST = 30f, MAX_TIME = 300f;

    /** Платформы по порядку прохождения (без лавы) — по ним идут боты. */
    final ArrayList<Box> path = new ArrayList<Box>();
    private final Random rnd = new Random();
    private float minTop, finishZ, firstFinish;
    private int finishers;

    ObbyGame(Match m) { super(m); }

    @Override String name() { return "Паркур"; }
    @Override String hint() { return "Добеги до золотого финиша"; }
    @Override String key() { return "obby"; }
    @Override boolean lowerIsBetter() { return true; }
    @Override String formatScore(float v) { return time(v); }
    @Override boolean hasScore(Player p) { return p.done; }

    @Override
    void build(ArrayList<Box> b) {
        path.clear();
        Random r = new Random(2026);
        float cx = 0, top = 0, cz;
        add(b, Box.plat(0, 0, -3, 6, 6, 1, 0x9AA3AD, Box.SOLID));
        cz = 3;
        minTop = 0;
        int ci = 0;
        for (int i = 0; i < SEGMENTS; i++) {
            if (i > 0 && i % 7 == 0) {
                float z0 = cz + 1.6f;
                add(b, Box.plat(cx, top, z0, 4, 4, 1, 0x4CC94C, Box.CHECKPOINT));
                cz = z0 + 4;
                continue;
            }
            int type = i < 3 ? 0 : r.nextInt(5);
            if (type == 3 && top >= 5) type = 4;
            int col = PALETTE[ci++ % PALETTE.length];
            switch (type) {
                case 0: {
                    float size = 2 + r.nextInt(2);
                    int dy = r.nextInt(3) - 1;
                    if (top >= 6) dy = Math.min(dy, 0);
                    if (top <= -4) dy = Math.max(dy, 0);
                    float gap = 1.3f + r.nextFloat() * (dy > 0 ? 0.5f : 0.9f);
                    cx += (r.nextFloat() - 0.5f) * 2f;
                    top += dy;
                    float z0 = cz + gap;
                    add(b, Box.plat(cx, top, z0, size, size, 1, col, Box.SOLID));
                    cz = z0 + size;
                    break;
                }
                case 1: {
                    float gap = 1.2f + r.nextFloat() * 0.6f;
                    float len = 5 + r.nextInt(3);
                    float z0 = cz + gap;
                    add(b, Box.plat(cx, top, z0, 1f, len, 0.6f, col, Box.SOLID));
                    cz = z0 + len;
                    break;
                }
                case 2: {
                    float z0 = cz + 1.4f;
                    add(b, Box.plat(cx, top, z0, 3, 2.5f, 1, col, Box.SOLID));
                    b.add(Box.plat(cx, top, z0 + 2.5f, 3, 2f, 1, 0xFF3A1A, Box.KILL));
                    add(b, Box.plat(cx, top, z0 + 4.5f, 3, 2.5f, 1, col, Box.SOLID));
                    cz = z0 + 7;
                    break;
                }
                case 3: {
                    for (int k = 0; k < 3; k++) {
                        top += 1;
                        float z0 = cz + 1.0f;
                        add(b, Box.plat(cx, top, z0, 1.6f, 1.6f, 1, col, Box.SOLID));
                        cz = z0 + 1.6f;
                    }
                    break;
                }
                default: {
                    for (int k = 0; k < 3; k++) {
                        float gap = 1.4f + r.nextFloat() * 0.4f;
                        float z0 = cz + gap;
                        add(b, Box.plat(cx, top, z0, 1f, 1f, 1, col, Box.SOLID));
                        cz = z0 + 1;
                    }
                    break;
                }
            }
            minTop = Math.min(minTop, top);
        }
        float z0 = cz + 1.6f;
        add(b, Box.plat(cx, top, z0, 6, 6, 1, 0xFFC92E, Box.FINISH));
        finishZ = z0 + 3;
        b.add(new Box(-80, minTop - 26, -30, 80, minTop - 25, cz + 50, 0x2E6FC9, Box.DECOR));
    }

    private void add(ArrayList<Box> b, Box box) {
        b.add(box);
        path.add(box);
    }

    @Override
    void reset() {
        firstFinish = -1;
        finishers = 0;
    }

    @Override
    void spawn(Player p, int idx) {
        float x = ((idx % 4) - 1.5f) * 1.2f;
        float z = -1.5f + (idx / 4) * 1.3f;
        p.cpX = x; p.cpY = 0; p.cpZ = z;
        p.cpBox = null;
        p.botTarget = 1;
        m.teleport(p, x, 0.02f, z);
    }

    @Override
    void update(float dt) {
        boolean allDone = true;
        for (int i = 0; i < m.players.size(); i++) {
            Player p = m.players.get(i);
            if (p.done) continue;
            allDone = false;
            p.progress = clamp(p.z / finishZ, 0f, 1f);
            Box g = m.groundOf(p);
            if (g != null && p.grace <= 0) {
                if (g.kind == Box.CHECKPOINT && g != p.cpBox) {
                    p.cpBox = g;
                    p.cpX = g.cx(); p.cpY = g.y1; p.cpZ = g.cz();
                    m.msg(p, "Чекпоинт!");
                }
                if (g.kind == Box.FINISH) {
                    p.done = true;
                    p.score = m.time;
                    p.progress = 1;
                    finishers++;
                    if (firstFinish < 0) firstFinish = m.time;
                    m.msg(null, p.nick + " финишировал! Место " + finishers);
                    continue;
                }
            }
            if (p.grace <= 0 && (m.touchingKill(p) || p.y < minTop - 12f)) {
                p.falls++;
                m.teleport(p, p.cpX, p.cpY + 0.02f, p.cpZ);
                p.botTarget = p.cpBox == null ? 1 : path.indexOf(p.cpBox) + 1;
                p.botAvoid = -1;
                m.msg(p, "Упс! Обратно к чекпоинту");
            }
        }
        if (allDone || (firstFinish >= 0 && m.time - firstFinish >= AFTER_FIRST) || m.time >= MAX_TIME) m.over = true;
    }

    @Override
    void bot(Player b, float dt, float[] c) {
        Box g = b.onGround ? b.ground : null;
        if (g != null) {
            int gi = path.indexOf(g);
            if (gi >= 0 && gi + 1 > b.botTarget) b.botTarget = gi + 1;
        }
        if (b.botTarget >= path.size()) b.botTarget = path.size() - 1;
        if (b.botTarget != b.botAvoid) {
            // новый прыжок: решаем заранее, ошибётся ли бот (слабые ошибаются чаще)
            b.botAvoid = b.botTarget;
            b.botStuck = rnd.nextFloat() < (1f - b.botSkill) * 0.35f ? 1f : 0f;
            b.botWx = 0.45f + (rnd.nextFloat() - 0.5f) * (1f - b.botSkill) * 2f;
        }
        Box t = path.get(b.botTarget);
        float tx = t.cx();
        float tz = Math.min(t.cz(), t.z0 + 1.2f);
        float dx = tx - b.x, dz = tz - b.z;
        float d = (float) Math.sqrt(dx * dx + dz * dz);
        if (d < 0.05f) return;
        float sp = b.botSkill;
        if (!b.onGround) sp *= Math.min(1f, d / 1.4f);
        c[0] = dx / d * sp;
        c[1] = dz / d * sp;
        if (g != null && g != t && b.botStuck == 0f) {
            float look = b.botWx;
            float ax = b.x + dx / d * look, az = b.z + dz / d * look;
            boolean out = ax < g.x0 || ax > g.x1 || az < g.z0 || az > g.z1;
            if (out) c[2] = 1;
        }
    }

    @Override
    String hud(Player p) {
        StringBuffer s = new StringBuffer();
        s.append(time(p.done ? p.score : m.time));
        s.append("  Падений: ").append(p.falls);
        s.append("  ").append((int) (p.progress * 100)).append('%');
        if (firstFinish >= 0) {
            int left = (int) Math.ceil(AFTER_FIRST - (m.time - firstFinish));
            if (left < 0) left = 0;
            s.append("  Конец через ").append(left);
        }
        return s.toString();
    }

    @Override
    void rank(ArrayList<Player> list) {
        Collections.sort(list, new Comparator<Player>() {
            public int compare(Player a, Player b) {
                if (a.done != b.done) return a.done ? -1 : 1;
                if (a.done) return Float.compare(a.score, b.score);
                return Float.compare(b.progress, a.progress);
            }
        });
    }

    @Override
    String scoreText(Player p) {
        return p.done ? time(p.score) : "не дошёл, " + (int) (p.progress * 100) + "%";
    }

    float finishZ() { return finishZ; }
}
