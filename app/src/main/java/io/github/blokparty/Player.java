package io.github.blokparty;

import java.util.ArrayList;

/** Игрок: физика с гравитацией и столкновениями по осям (AABB). */
final class Player {
    static final float HW = 0.3f;      // половина ширины
    static final float H = 1.8f;       // рост
    static final float G = 22f;        // гравитация
    static final float JUMP = 8.2f;    // скорость прыжка -> высота ~1.5 блока
    static final float SPEED = 5.5f;

    float x, y, z, vx, vy, vz;
    float faceYaw;
    float walkPhase;
    boolean onGround, moving, touchedKill;
    Box ground;

    // кто это
    int id;
    String nick = "";
    int color;
    boolean bot;     // управляется хостом
    boolean remote;  // позиция приходит по сети

    // состояние в текущем матче
    boolean alive = true, done;
    float score, progress, grace;
    int falls, coins;
    float cpX, cpY, cpZ;
    Box cpBox;

    // мозги бота
    int botTarget;
    float botSkill = 0.85f, botStuck, botTimer, botWx, botWz;
    int botAvoid = -1;
    float botAvoidT;

    void resetMatch() {
        alive = true;
        done = false;
        score = 0;
        progress = 0;
        grace = 0;
        falls = 0;
        coins = 0;
        cpBox = null;
        botTarget = 1;
        botStuck = 0;
        botTimer = 0;
        botAvoid = -1;
        botAvoidT = 0;
        botWx = 1e9f;
        botWz = 0;
    }

    void spawn(float x, float y, float z, float yaw) {
        this.x = x; this.y = y; this.z = z;
        vx = vy = vz = 0;
        faceYaw = yaw;
        onGround = false;
        ground = null;
        touchedKill = false;
    }

    /** mx, mz — желаемое направление движения (длина 0..1). */
    void update(float dt, ArrayList<Box> boxes, float mx, float mz, boolean jump) {
        float tvx = mx * SPEED, tvz = mz * SPEED;
        float k = Math.min(1f, dt * (onGround ? 16f : 5f));
        vx += (tvx - vx) * k;
        vz += (tvz - vz) * k;

        moving = mx * mx + mz * mz > 0.01f;
        if (moving) {
            float target = (float) Math.atan2(mx, mz);
            float d = target - faceYaw;
            while (d > Math.PI) d -= (float) (2 * Math.PI);
            while (d < -Math.PI) d += (float) (2 * Math.PI);
            faceYaw += d * Math.min(1f, dt * 14f);
            walkPhase += dt * 11f;
        }

        if (jump && onGround) {
            vy = JUMP;
            onGround = false;
        }

        touchedKill = false;
        int steps = Math.max(1, (int) Math.ceil(dt / 0.0125f));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            vy -= G * h;
            if (vy < -35f) vy = -35f;
            moveAxis(0, vx * h, boxes);
            moveAxis(2, vz * h, boxes);
            onGround = false;
            ground = null;
            moveAxis(1, vy * h, boxes);
        }
    }

    private void moveAxis(int axis, float d, ArrayList<Box> boxes) {
        if (d == 0f) return;
        if (axis == 0) x += d;
        else if (axis == 1) y += d;
        else z += d;
        for (int i = 0, n = boxes.size(); i < n; i++) {
            Box b = boxes.get(i);
            if (!b.solid()) continue;
            if (x + HW <= b.x0 || x - HW >= b.x1 || y + H <= b.y0 || y >= b.y1
                    || z + HW <= b.z0 || z - HW >= b.z1) continue;
            if (b.kind == Box.KILL) touchedKill = true;
            if (axis == 0) {
                x = d > 0 ? b.x0 - HW - 0.001f : b.x1 + HW + 0.001f;
                vx = 0;
            } else if (axis == 2) {
                z = d > 0 ? b.z0 - HW - 0.001f : b.z1 + HW + 0.001f;
                vz = 0;
            } else {
                if (d < 0) {
                    y = b.y1;
                    onGround = true;
                    ground = b;
                } else {
                    y = b.y0 - H - 0.001f;
                }
                vy = 0;
            }
        }
    }

    /** Пересекается ли игрок с коробкой (для падающих блоков, монет и т.п.). */
    boolean hits(float bx0, float by0, float bz0, float bx1, float by1, float bz1) {
        return x + HW > bx0 && x - HW < bx1 && y + H > by0 && y < by1 && z + HW > bz0 && z - HW < bz1;
    }
}
