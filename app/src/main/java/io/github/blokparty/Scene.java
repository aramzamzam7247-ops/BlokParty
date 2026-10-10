package io.github.blokparty;

import java.util.ArrayList;

/** То, что нужно нарисовать: персонажи, монетки, падающие блоки. Одинаково для хоста и клиента. */
final class Scene {
    static final class Avatar {
        int id;
        String nick = "";
        int color;
        boolean bot, self, alive = true, moving, seen;
        float x, y, z, yaw, phase;
        // цель для плавного движения (у клиента)
        float tx, ty, tz, tyaw;
        boolean fresh = true;
    }

    final ArrayList<Avatar> avatars = new ArrayList<Avatar>();
    float[] coins = new float[3 * 16];
    int nCoins;
    float[] rocks = new float[4 * 64];
    int nRocks;

    void clearItems() {
        nCoins = 0;
        nRocks = 0;
    }

    void addCoin(float x, float y, float z) {
        if (nCoins * 3 + 3 > coins.length) return;
        coins[nCoins * 3] = x;
        coins[nCoins * 3 + 1] = y;
        coins[nCoins * 3 + 2] = z;
        nCoins++;
    }

    void addRock(float x, float y, float z, float size) {
        if (nRocks * 4 + 4 > rocks.length) return;
        rocks[nRocks * 4] = x;
        rocks[nRocks * 4 + 1] = y;
        rocks[nRocks * 4 + 2] = z;
        rocks[nRocks * 4 + 3] = size;
        nRocks++;
    }

    Avatar byId(int id, boolean create) {
        for (int i = 0; i < avatars.size(); i++) if (avatars.get(i).id == id) return avatars.get(i);
        if (!create) return null;
        Avatar a = new Avatar();
        a.id = id;
        avatars.add(a);
        return a;
    }
}
