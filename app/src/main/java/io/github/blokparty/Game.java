package io.github.blokparty;

import android.content.SharedPreferences;
import android.opengl.GLES20;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Random;

/**
 * Вся игра в GL-потоке. Телефон всегда либо ХОСТ (считает матч, может принимать игроков по сети,
 * одиночная игра — это хост без гостей), либо КЛИЕНТ (подключён к чужому хосту).
 */
final class Game implements Match.Out {
    // хост
    static final int MENU = 0, PLAY = 1, RESULT = 2;
    // клиент
    static final int CWAIT = 3, CPLAY = 4, CRESULT = 5;

    static final float SKY_R = 0.55f, SKY_G = 0.76f, SKY_B = 0.96f;
    static final int C_BTN = 0xD0243A66;
    static final int C_TEXT = 0xFFFFFFFF;
    static final int C_DIM = 0xFFB8C6E0;
    static final int C_GOLD = 0xFFFFD34A;
    static final int[] MODE_COLORS = {0xFF4FC3F7, 0xFFFFD34A, 0xFFFF6B5A};
    /** Цвета футболок игроков. */
    static final float[][] SHIRTS = {
            {0.98f, 0.5f, 0.15f}, {0.9f, 0.2f, 0.25f}, {0.25f, 0.75f, 0.3f}, {0.6f, 0.35f, 0.9f},
            {0.2f, 0.75f, 0.85f}, {0.95f, 0.45f, 0.7f}, {0.95f, 0.85f, 0.2f}, {0.92f, 0.92f, 0.92f}
    };
    static final String[] BOT_NAMES = {"Бот Вася", "Бот Маша", "Бот Петя", "Бот Тоня", "Бот Гоша", "Бот Лёва"};
    static final String[] MODE_NAMES = {"Паркур", "Монетки", "Уклонись"};
    static final String[] MODE_KEYS = {"obby", "coins", "dodge"};
    static final boolean[] MODE_LOWER = {true, false, false};

    volatile int state = MENU;

    final Gfx g = new Gfx();
    final Input in;
    final SharedPreferences prefs;
    Platform platform;

    // ---------- хост ----------
    final Match match = new Match();
    final NetServer server = new NetServer();
    private final TcpHost tcp = new TcpHost(server);
    final Player me = new Player();
    private final ArrayList<Player> bots = new ArrayList<Player>();
    private int botCount;
    private boolean roomOpen;
    private String btStatus = "", wifiIp;
    private float snapTimer;
    private final Random rnd = new Random();

    // ---------- клиент ----------
    private Link link;
    private Link.Inbox inbox;
    private int myId = -1, cMode;
    private String hostNick = "", cHud = "";
    private boolean cAlive = true;
    private final ArrayList<Box> cBoxes = new ArrayList<Box>();
    private final ArrayList<Object[]> roster = new ArrayList<Object[]>(); // {id, color, bot, nick}
    private float sendTimer;
    private boolean connecting;

    // ---------- общее ----------
    final Scene scene = new Scene();
    int w = 1, h = 1;
    float s = 1f;
    float time, camYaw;
    float camX, camY, camZ;
    private final float[] proj = new float[16], view = new float[16], vp = new float[16];
    private final float[] model = new float[16], tmp = new float[16], charM = new float[16];
    private final float[] joy = new float[7], pt = new float[2], r4 = new float[4];
    private final ArrayList<float[]> taps = new ArrayList<float[]>();
    private final ArrayList<Object[]> events = new ArrayList<Object[]>();
    private final ArrayList<Net.Msg> msgs = new ArrayList<Net.Msg>();
    private final LinkedList<Runnable> posts = new LinkedList<Runnable>();

    private String resTitle = "", resRecord = "";
    private final ArrayList<String[]> resRows = new ArrayList<String[]>(); // {строка, очки, "1" если я}
    private String msg = "";
    private float msgTime;

    Game(Input in, SharedPreferences prefs) {
        this.in = in;
        this.prefs = prefs;
        match.out = this;
        me.id = 0;
        me.nick = prefs.getString("nick", "");
        me.color = prefs.getInt("color", rnd.nextInt(SHIRTS.length));
        botCount = prefs.getInt("bots", 2);
        rebuildBots();
    }

    /** Выполнить в игровом потоке (из UI-потока Android). */
    void post(Runnable r) {
        synchronized (posts) { posts.add(r); }
    }

    boolean needsNick() { return me.nick.length() == 0; }

    void surfaceCreated() { g.init(); }

    void resize(int w, int h) {
        this.w = Math.max(1, w);
        this.h = Math.max(1, h);
        s = Math.min(this.w, this.h) / 360f;
        g.resize(this.w, this.h);
        Mat4.perspective(proj, 62f, (float) this.w / this.h, 0.1f, 80f);
    }

    void flash(String text, float seconds) {
        msg = text;
        msgTime = seconds;
    }

    // ===================== Ответы платформы =====================

    void setNick(String nick) {
        me.nick = Net.cleanNick(nick);
        prefs.edit().putString("nick", me.nick).putInt("color", me.color).apply();
        if (!isClient()) server.broadcast(fRoster());
    }

    void btRoom(boolean ok, String text) {
        btStatus = text;
        if (!ok) flash(text, 3f);
    }

    void clientConnected(Link l) {
        connecting = false;
        closeRoom();
        match.stop();
        link = l;
        inbox = new Link.Inbox();
        roster.clear();
        cBoxes.clear();
        scene.avatars.clear();
        myId = -1;
        l.start(inbox);
        l.send(new Net.Writer(Net.HELLO).u8(Net.PROTO).u8(Net.KIND_ANDROID).utf(me.nick).done());
        state = CWAIT;
        in.setPlayMode(false);
    }

    void connectFailed(String text) {
        connecting = false;
        flash(text, 3.5f);
    }

    void setConnecting() { connecting = true; }

    private boolean isClient() { return state >= CWAIT; }

    // ===================== Главный цикл =====================

    void frame(float dt) {
        if (dt > 0.05f) dt = 0.05f;
        if (dt < 0f) dt = 0f;
        time += dt;
        for (;;) {
            Runnable r;
            synchronized (posts) { r = posts.isEmpty() ? null : posts.removeFirst(); }
            if (r == null) break;
            r.run();
        }
        taps.clear();
        in.pollTaps(taps);
        if (in.takeBack()) onBack();

        if (isClient()) clientNet(); else hostNet();

        switch (state) {
            case MENU: updateMenu(); break;
            case PLAY: updatePlay(dt); break;
            case RESULT: updateResult(); break;
            case CWAIT: updateCWait(); break;
            case CPLAY: updateCPlay(dt); break;
            default: updateResult(); break;
        }
        if (msgTime > 0) msgTime -= dt;
        buildScene(dt);
        render();
    }

    private void onBack() {
        if (state == PLAY || state == RESULT) toLobby();
        else if (isClient()) disconnect("Ты вышел из комнаты");
    }

    // ===================== ХОСТ =====================

    private void rebuildBots() {
        bots.clear();
        for (int i = 0; i < botCount; i++) {
            Player b = new Player();
            b.bot = true;
            b.id = 200 + i;
            b.nick = BOT_NAMES[i % BOT_NAMES.length];
            b.color = (i + 2) % SHIRTS.length;
            b.botSkill = 0.72f + rnd.nextFloat() * 0.24f;
            bots.add(b);
        }
    }

    private void toggleRoom() {
        if (roomOpen) {
            closeRoom();
            flash("Комната закрыта", 2f);
            return;
        }
        roomOpen = true;
        btStatus = "Bluetooth: запуск...";
        boolean tcpOk = tcp.start();
        wifiIp = tcpOk ? TcpHost.localIp() : null;
        if (platform != null) platform.openBtRoom(server);
        flash("Комната открыта", 2f);
    }

    private void closeRoom() {
        if (!roomOpen) return;
        roomOpen = false;
        tcp.stop();
        if (platform != null) platform.closeBtRoom();
        server.closeAll();
    }

    private ArrayList<Player> lobbyHumans() {
        ArrayList<Player> r = new ArrayList<Player>();
        r.add(me);
        Link[] ls = server.links();
        for (int i = 0; i < ls.length; i++) if (ls[i].hello && ls[i].player != null) r.add(ls[i].player);
        return r;
    }

    private void hostStart(int modeIdx) {
        if (me.nick.length() == 0) me.nick = "Игрок";
        ArrayList<Player> who = lobbyHumans();
        rebuildBots();
        who.addAll(bots);
        match.start(modeIdx, who);
        server.broadcast(fRoster());
        Link[] ls = server.links();
        for (int i = 0; i < ls.length; i++) {
            if (ls[i].hello && ls[i].player != null) ls[i].send(fLevel(ls[i].player));
        }
        camYaw = 0;
        snapTimer = 0;
        in.reset();
        state = PLAY;
        flash(match.mode.hint(), 3.5f);
    }

    private void toLobby() {
        match.stop();
        server.broadcast(new Net.Writer(Net.LOBBY).done());
        state = MENU;
        in.setPlayMode(false);
    }

    private void hostFinish() {
        ArrayList<Player> rank = match.ranking();
        MiniGame mode = match.mode;
        resRows.clear();
        Net.Writer wr = new Net.Writer(Net.RESULT).u8(match.modeIndex);
        String title = rank.size() > 1 ? "Победа: " + rank.get(0).nick : mode.name();
        wr.utf(title).u8(rank.size());
        for (int i = 0; i < rank.size(); i++) {
            Player p = rank.get(i);
            String st = mode.scoreText(p);
            resRows.add(new String[]{(i + 1) + ". " + p.nick, st, p == me ? "1" : ""});
            wr.u8(p.id).utf(p.nick).utf(st).s32(Math.round(p.score * 100f)).u8(mode.hasScore(p) ? 1 : 0);
        }
        server.broadcast(wr.done());
        resTitle = title;
        resRecord = saveRecord(match.modeIndex, mode.hasScore(me), me.score);
        state = RESULT;
        in.setPlayMode(false);
    }

    /** Личный рекорд на этом устройстве. Возвращает строку для экрана результата. */
    private String saveRecord(int modeIdx, boolean has, float score) {
        String k = "best_" + MODE_KEYS[modeIdx];
        float best = prefs.getFloat(k, -1f);
        boolean rec = has && (best < 0 || (MODE_LOWER[modeIdx] ? score < best : score > best));
        if (rec) {
            prefs.edit().putFloat(k, score).apply();
            return "НОВЫЙ ЛИЧНЫЙ РЕКОРД!";
        }
        return best < 0 ? "" : "Твой рекорд: " + formatScore(modeIdx, best);
    }

    static String formatScore(int modeIdx, float v) {
        if (modeIdx == 0) return MiniGame.time(v);
        if (modeIdx == 1) return "Монет: " + (int) v;
        return MiniGame.sec1(v) + " сек";
    }

    /** Сообщения от клиентов. */
    private void hostNet() {
        events.clear();
        server.drain(events);
        for (int i = 0; i < events.size(); i++) {
            Link l = (Link) events.get(i)[0];
            Net.Msg m = (Net.Msg) events.get(i)[1];
            if (m == null) {
                if (l.player != null) {
                    match.leave(l.player);
                    if (l.hello) say(null, l.nick + " вышел");
                }
                server.broadcast(fRoster());
                continue;
            }
            try {
                if (m.type == Net.HELLO && !l.hello) {
                    m.u8();
                    l.kind = m.u8();
                    l.nick = Net.cleanNick(m.utf());
                    Player p = new Player();
                    p.id = l.id;
                    p.nick = l.nick;
                    p.color = (l.id + 1) % SHIRTS.length;
                    p.remote = true;
                    l.player = p;
                    l.hello = true;
                    l.send(new Net.Writer(Net.WELCOME).u8(l.id).utf(me.nick).done());
                    server.broadcast(fRoster());
                    String dev = l.kind == Net.KIND_NOKIA ? " (Nokia)" : l.kind == Net.KIND_PC ? " (ПК)" : "";
                    say(null, l.nick + dev + " подключился");
                    if (state == PLAY && match.running) {
                        match.join(p);
                        l.send(fLevel(p));
                    }
                } else if (m.type == Net.STATE && l.player != null) {
                    Player p = l.player;
                    float x = m.pos(), y = m.pos(), z = m.pos();
                    int yaw = m.u8(), fl = m.u8();
                    if (p.grace > 0) continue; // ждём, пока клиент примет телепорт
                    p.x = x; p.y = y; p.z = z;
                    p.faceYaw = Net.byteYaw(yaw);
                    p.moving = (fl & Net.F_MOVING) != 0;
                    p.onGround = (fl & Net.F_GROUND) != 0;
                } else if (m.type == Net.BYE) {
                    l.close();
                }
            } catch (IOException e) {
                l.close();
            }
        }
    }

    // ---- Match.Out ----
    public void msg(Player to, String text) { say(to, text); }

    public void teleported(Player p) {
        if (!p.remote) return;
        Link l = linkOf(p);
        if (l != null) l.send(new Net.Writer(Net.TELEPORT).pos(p.x).pos(p.y).pos(p.z).done());
    }

    private void say(Player to, String text) {
        if (to == null) {
            flash(text, 2.5f);
            server.broadcast(new Net.Writer(Net.MSG).utf(text).done());
        } else if (to == me) {
            flash(text, 1.6f);
        } else if (to.remote) {
            Link l = linkOf(to);
            if (l != null) l.send(new Net.Writer(Net.MSG).utf(text).done());
        }
    }

    private Link linkOf(Player p) {
        Link[] ls = server.links();
        for (int i = 0; i < ls.length; i++) if (ls[i].player == p) return ls[i];
        return null;
    }

    private byte[] fRoster() {
        ArrayList<Player> list = lobbyHumans();
        list.addAll(bots);
        Net.Writer wr = new Net.Writer(Net.ROSTER).u8(list.size());
        for (int i = 0; i < list.size(); i++) {
            Player p = list.get(i);
            wr.u8(p.id).u8(p.color).u8(p.bot ? 1 : 0).utf(p.nick);
        }
        return wr.done();
    }

    private byte[] fLevel(Player p) {
        Net.Writer wr = new Net.Writer(Net.LEVEL).u8(match.modeIndex).pos(p.x).pos(p.y).pos(p.z);
        ArrayList<Box> bx = match.boxes;
        wr.u16(bx.size());
        for (int i = 0; i < bx.size(); i++) {
            Box b = bx.get(i);
            wr.pos(b.x0).pos(b.y0).pos(b.z0).pos(b.x1).pos(b.y1).pos(b.z1);
            wr.u8(Math.round(b.r * 255)).u8(Math.round(b.g * 255)).u8(Math.round(b.b * 255)).u8(b.kind);
        }
        return wr.done();
    }

    private byte[] fSnap(Player to) {
        Net.Writer wr = new Net.Writer(Net.SNAP).utf(match.mode.hud(to));
        ArrayList<Player> ps = match.players;
        wr.u8(ps.size());
        for (int i = 0; i < ps.size(); i++) {
            Player p = ps.get(i);
            int fl = (p.moving ? Net.F_MOVING : 0) | (p.alive ? Net.F_ALIVE : 0)
                    | (p.onGround ? Net.F_GROUND : 0) | (p.bot ? Net.F_BOT : 0);
            wr.u8(p.id).pos(p.x).pos(p.y).pos(p.z).u8(Net.yawByte(p.faceYaw)).u8(fl);
        }
        wr.u8(scene.nCoins);
        for (int i = 0; i < scene.nCoins; i++) wr.pos(scene.coins[i * 3]).pos(scene.coins[i * 3 + 1]).pos(scene.coins[i * 3 + 2]);
        wr.u8(scene.nRocks);
        for (int i = 0; i < scene.nRocks; i++) {
            wr.pos(scene.rocks[i * 4]).pos(scene.rocks[i * 4 + 1]).pos(scene.rocks[i * 4 + 2]);
            wr.u8(Math.min(255, Math.round(scene.rocks[i * 4 + 3] * 32f)));
        }
        return wr.done();
    }

    private void updateMenu() {
        in.setPlayMode(false);
        if (roomOpen && (wifiIp == null || time % 5f < 0.02f)) wifiIp = TcpHost.localIp();
        for (int t = 0; t < taps.size(); t++) {
            float[] tp = taps.get(t);
            for (int i = 0; i < 3; i++) {
                menuButton(i, r4);
                if (inRect(tp, r4)) { hostStart(i); return; }
            }
            for (int i = 0; i < 4; i++) {
                barButton(i, r4);
                if (!inRect(tp, r4)) continue;
                if (i == 0) {
                    botCount = (botCount + 1) % (BOT_NAMES.length + 1);
                    prefs.edit().putInt("bots", botCount).apply();
                    rebuildBots();
                    server.broadcast(fRoster());
                } else if (i == 1) {
                    if (platform != null) platform.askNick(me.nick);
                } else if (i == 2) {
                    toggleRoom();
                } else if (!connecting && platform != null) {
                    platform.chooseHost();
                }
                return;
            }
        }
    }

    private void readMove(float[] out) {
        camYaw -= in.takeLook() / w * 3.4f;
        in.getJoy(joy);
        float jx = joy[0], jy = joy[1];
        float fx = (float) Math.sin(camYaw), fz = (float) Math.cos(camYaw);
        float mx = fx * (-jy) - fz * jx;
        float mz = fz * (-jy) + fx * jx;
        float len = (float) Math.sqrt(mx * mx + mz * mz);
        if (len > 1f) { mx /= len; mz /= len; }
        out[0] = mx;
        out[1] = mz;
        out[2] = (in.takeJump() | in.jumpHeld()) ? 1 : 0;
    }

    private final float[] mv = new float[3];

    private void updatePlay(float dt) {
        in.setPlayMode(true);
        readMove(mv);
        if (me.alive) me.update(dt, match.boxes, mv[0], mv[1], mv[2] > 0);
        match.update(dt);
        for (int i = 0; i < match.players.size(); i++) {
            Player p = match.players.get(i);
            if (p.remote && p.moving) p.walkPhase += dt * 11f;
        }
        snapTimer -= dt;
        if (snapTimer <= 0) {
            snapTimer = 0.1f;
            scene.clearItems();
            match.mode.fillScene(scene);
            Link[] ls = server.links();
            for (int i = 0; i < ls.length; i++) {
                if (ls[i].hello && ls[i].player != null && match.players.contains(ls[i].player)) {
                    ls[i].sendLatest(fSnap(ls[i].player));
                }
            }
        }
        menuBtnRect(r4);
        for (int t = 0; t < taps.size(); t++) if (inRect(taps.get(t), r4)) { toLobby(); return; }
        if (match.over) hostFinish();
    }

    private void updateResult() {
        in.setPlayMode(false);
        for (int t = 0; t < taps.size(); t++) {
            float[] tp = taps.get(t);
            if (state == RESULT) {
                resultButton(0, 2, r4);
                if (inRect(tp, r4)) { hostStart(match.modeIndex); return; }
                resultButton(1, 2, r4);
                if (inRect(tp, r4)) { toLobby(); return; }
            } else {
                resultButton(0, 1, r4);
                if (inRect(tp, r4)) { state = CWAIT; return; }
            }
        }
    }

    // ===================== КЛИЕНТ =====================

    private void disconnect(String why) {
        if (link != null) {
            link.send(new Net.Writer(Net.BYE).done());
            final Link l = link;
            new Thread(new Runnable() {
                public void run() {
                    try { Thread.sleep(300); } catch (InterruptedException e) { /* ignore */ }
                    l.close();
                }
            }).start();
        }
        link = null;
        inbox = null;
        scene.avatars.clear();
        events.clear();
        server.drain(events); // старые события комнаты, пока мы были гостем, не нужны
        events.clear();
        state = MENU;
        in.setPlayMode(false);
        flash(why, 3f);
    }

    private String rosterNick(int id) {
        for (int i = 0; i < roster.size(); i++) if ((Integer) roster.get(i)[0] == id) return (String) roster.get(i)[3];
        return "?";
    }

    private int rosterColor(int id) {
        for (int i = 0; i < roster.size(); i++) if ((Integer) roster.get(i)[0] == id) return (Integer) roster.get(i)[1];
        return 0;
    }

    private void clientNet() {
        if (inbox == null) return;
        msgs.clear();
        inbox.drain(msgs);
        try {
            for (int i = 0; i < msgs.size(); i++) clientMsg(msgs.get(i));
        } catch (IOException e) {
            disconnect("Ошибка данных от хоста");
            return;
        }
        if (inbox != null && inbox.lost()) disconnect("Связь с хостом потеряна");
    }

    private void clientMsg(Net.Msg m) throws IOException {
        switch (m.type) {
            case Net.WELCOME:
                myId = m.u8();
                hostNick = m.utf();
                flash("Подключено к " + hostNick, 2.5f);
                break;
            case Net.ROSTER: {
                roster.clear();
                int n = m.u8();
                for (int i = 0; i < n; i++) {
                    int id = m.u8(), col = m.u8(), bot = m.u8();
                    roster.add(new Object[]{id, col, bot, m.utf()});
                }
                break;
            }
            case Net.LEVEL: {
                cMode = m.u8();
                float sx = m.pos(), sy = m.pos(), sz = m.pos();
                int n = m.u16();
                cBoxes.clear();
                for (int i = 0; i < n; i++) {
                    float x0 = m.pos(), y0 = m.pos(), z0 = m.pos(), x1 = m.pos(), y1 = m.pos(), z1 = m.pos();
                    int rgb = (m.u8() << 16) | (m.u8() << 8) | m.u8();
                    cBoxes.add(new Box(x0, y0, z0, x1, y1, z1, rgb, m.u8()));
                }
                me.spawn(sx, sy, sz, 0);
                me.alive = true;
                cAlive = true;
                cHud = "";
                scene.avatars.clear();
                scene.clearItems();
                camYaw = 0;
                in.reset();
                state = CPLAY;
                flash(match.modes[Math.min(2, cMode)].hint(), 3.5f);
                break;
            }
            case Net.SNAP: {
                cHud = m.utf();
                int n = m.u8();
                for (int i = 0; i < scene.avatars.size(); i++) scene.avatars.get(i).seen = false;
                for (int i = 0; i < n; i++) {
                    int id = m.u8();
                    float x = m.pos(), y = m.pos(), z = m.pos();
                    int yaw = m.u8(), fl = m.u8();
                    if (id == myId) {
                        cAlive = (fl & Net.F_ALIVE) != 0;
                        Scene.Avatar a = scene.byId(id, true);
                        a.seen = true;
                        continue;
                    }
                    Scene.Avatar a = scene.byId(id, true);
                    a.seen = true;
                    a.tx = x; a.ty = y; a.tz = z;
                    a.tyaw = Net.byteYaw(yaw);
                    a.moving = (fl & Net.F_MOVING) != 0;
                    a.alive = (fl & Net.F_ALIVE) != 0;
                    a.bot = (fl & Net.F_BOT) != 0;
                    a.nick = rosterNick(id);
                    a.color = rosterColor(id);
                }
                for (int i = scene.avatars.size() - 1; i >= 0; i--) if (!scene.avatars.get(i).seen) scene.avatars.remove(i);
                scene.clearItems();
                int nc = m.u8();
                for (int i = 0; i < nc; i++) scene.addCoin(m.pos(), m.pos(), m.pos());
                int nr = m.u8();
                for (int i = 0; i < nr; i++) {
                    float x = m.pos(), y = m.pos(), z = m.pos();
                    scene.addRock(x, y, z, m.u8() / 32f);
                }
                break;
            }
            case Net.TELEPORT:
                me.spawn(m.pos(), m.pos(), m.pos(), 0);
                camYaw = 0;
                break;
            case Net.MSG:
                flash(m.utf(), 2.2f);
                break;
            case Net.RESULT: {
                int mode = Math.min(2, m.u8());
                resTitle = m.utf();
                int n = m.u8();
                resRows.clear();
                resRecord = "";
                for (int i = 0; i < n; i++) {
                    int id = m.u8();
                    String nick = m.utf(), st = m.utf();
                    float raw = m.s32() / 100f;
                    boolean has = m.u8() != 0;
                    resRows.add(new String[]{(i + 1) + ". " + nick, st, id == myId ? "1" : ""});
                    if (id == myId) resRecord = saveRecord(mode, has, raw);
                }
                state = CRESULT;
                in.setPlayMode(false);
                break;
            }
            case Net.LOBBY:
                state = CWAIT;
                in.setPlayMode(false);
                break;
            default:
                break;
        }
    }

    private void updateCWait() {
        in.setPlayMode(false);
        for (int t = 0; t < taps.size(); t++) {
            resultButton(0, 1, r4);
            if (inRect(taps.get(t), r4)) { disconnect("Ты вышел из комнаты"); return; }
        }
    }

    private void updateCPlay(float dt) {
        in.setPlayMode(true);
        readMove(mv);
        me.alive = cAlive;
        if (cAlive) me.update(dt, cBoxes, mv[0], mv[1], mv[2] > 0);
        sendTimer -= dt;
        if (sendTimer <= 0 && link != null) {
            sendTimer = 0.066f;
            int fl = (me.moving ? Net.F_MOVING : 0) | (me.onGround ? Net.F_GROUND : 0);
            link.sendLatest(new Net.Writer(Net.STATE).pos(me.x).pos(me.y).pos(me.z)
                    .u8(Net.yawByte(me.faceYaw)).u8(fl).done());
        }
        menuBtnRect(r4);
        for (int t = 0; t < taps.size(); t++) if (inRect(taps.get(t), r4)) { disconnect("Ты вышел из комнаты"); return; }
    }

    // ===================== Сцена =====================

    private void smooth(Scene.Avatar a, float dt) {
        if (a.fresh) {
            a.x = a.tx; a.y = a.ty; a.z = a.tz; a.yaw = a.tyaw;
            a.fresh = false;
            return;
        }
        float k = Math.min(1f, dt * 14f);
        a.x += (a.tx - a.x) * k;
        a.y += (a.ty - a.y) * k;
        a.z += (a.tz - a.z) * k;
        float d = a.tyaw - a.yaw;
        while (d > Math.PI) d -= (float) (2 * Math.PI);
        while (d < -Math.PI) d += (float) (2 * Math.PI);
        a.yaw += d * k;
        if (a.moving) a.phase += dt * 11f;
    }

    private void buildScene(float dt) {
        if (state == PLAY || state == RESULT) {
            for (int i = 0; i < scene.avatars.size(); i++) scene.avatars.get(i).seen = false;
            for (int i = 0; i < match.players.size(); i++) {
                Player p = match.players.get(i);
                Scene.Avatar a = scene.byId(p.id, true);
                a.seen = true;
                a.nick = p.nick;
                a.color = p.color;
                a.bot = p.bot;
                a.self = p == me;
                a.alive = p.alive;
                a.moving = p.moving && p.onGround;
                if (p.remote) {
                    a.tx = p.x; a.ty = p.y; a.tz = p.z; a.tyaw = p.faceYaw;
                    smooth(a, dt);
                } else {
                    a.x = p.x; a.y = p.y; a.z = p.z; a.yaw = p.faceYaw; a.phase = p.walkPhase;
                }
            }
            for (int i = scene.avatars.size() - 1; i >= 0; i--) if (!scene.avatars.get(i).seen) scene.avatars.remove(i);
            scene.clearItems();
            if (match.mode != null) match.mode.fillScene(scene);
        } else if (state == CPLAY || state == CRESULT) {
            for (int i = 0; i < scene.avatars.size(); i++) {
                Scene.Avatar a = scene.avatars.get(i);
                if (a.id == myId) {
                    a.self = true;
                    a.nick = me.nick;
                    a.color = rosterColor(myId);
                    a.alive = cAlive;
                    a.x = me.x; a.y = me.y; a.z = me.z; a.yaw = me.faceYaw; a.phase = me.walkPhase;
                    a.moving = me.moving && me.onGround;
                } else {
                    smooth(a, dt);
                }
            }
        }
    }

    // ===================== Раскладка =====================

    private static boolean inRect(float[] t, float[] r) {
        return t[0] >= r[0] && t[0] <= r[0] + r[2] && t[1] >= r[1] && t[1] <= r[1] + r[3];
    }

    private void menuBtnRect(float[] r) {
        r[0] = 8 * s; r[1] = 8 * s; r[2] = 92 * s; r[3] = 40 * s;
    }

    private float menuTop() { return 10 * s + Gfx.lineH(1.0f * s) + 4 * s; }

    private float barH() { return 40 * s; }

    private void barButton(int i, float[] r) {
        float gap = 6 * s, bh = barH();
        float bw = (w - gap * 5) / 4f;
        r[0] = gap + i * (bw + gap); r[1] = h - bh - gap; r[2] = bw; r[3] = bh;
    }

    private void menuButton(int i, float[] r) {
        float x0 = w * 0.42f, x1 = w - 8 * s;
        float top = menuTop(), bottom = h - barH() - 14 * s, gap = 6 * s;
        float bh = Math.min(68 * s, (bottom - top - gap * 2) / 3f);
        r[0] = x0; r[1] = top + i * (bh + gap); r[2] = x1 - x0; r[3] = bh;
    }

    private void resultButton(int i, int count, float[] r) {
        float bw = Math.min(w * 0.3f, 200 * s), bh = 44 * s;
        float cx = count == 1 ? w / 2f : w / 2f + (i == 0 ? -1 : 1) * (bw / 2f + 8 * s);
        r[0] = cx - bw / 2f; r[1] = h - bh - 10 * s; r[2] = bw; r[3] = bh;
    }

    // ===================== Отрисовка =====================

    private void render() {
        GLES20.glClearColor(SKY_R, SKY_G, SKY_B, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        boolean world = state == PLAY || state == RESULT || state == CPLAY || state == CRESULT;
        if (world) renderWorld(); else renderMenu3D();

        g.beginUI();
        if (world) labels();
        switch (state) {
            case MENU: hudMenu(); break;
            case CWAIT: hudCWait(); break;
            case PLAY: case CPLAY: hudPlay(); break;
            default: hudResult(); break;
        }
        drawFlash();
        g.flushUI();
    }

    private ArrayList<Box> worldBoxes() { return isClient() ? cBoxes : match.boxes; }

    private void renderWorld() {
        float tx = me.x, ty = me.y + 1.4f, tz = me.z;
        float pitch = 0.42f, dist = 6.5f;
        float cp = (float) Math.cos(pitch);
        camX = tx - (float) Math.sin(camYaw) * cp * dist;
        camY = ty + (float) Math.sin(pitch) * dist;
        camZ = tz - (float) Math.cos(camYaw) * cp * dist;
        Mat4.lookAt(view, camX, camY, camZ, tx, ty, tz);
        Mat4.mul(vp, proj, view);

        ArrayList<Box> boxes = worldBoxes();
        g.begin3D(vp, camX, camY, camZ, 26f, 62f, SKY_R, SKY_G, SKY_B);
        float pulse = 0.8f + 0.2f * (float) Math.sin(time * 6f);
        for (int i = 0, n = boxes.size(); i < n; i++) {
            Box b = boxes.get(i);
            float dx = Math.max(Math.max(b.x0 - camX, camX - b.x1), 0f);
            float dy = Math.max(Math.max(b.y0 - camY, camY - b.y1), 0f);
            float dz = Math.max(Math.max(b.z0 - camZ, camZ - b.z1), 0f);
            if (dx * dx + dy * dy + dz * dz > 64f * 64f) continue;
            Mat4.box(model, (b.x0 + b.x1) / 2f, (b.y0 + b.y1) / 2f, (b.z0 + b.z1) / 2f,
                    b.x1 - b.x0, b.y1 - b.y0, b.z1 - b.z0);
            if (b.kind == Box.KILL) g.drawBox(model, b.r * pulse, b.g * pulse, b.b * pulse, 1f);
            else g.drawBox(model, b.r, b.g, b.b, 1f);
        }
        for (int i = 0; i < scene.nCoins; i++) {
            float bob = (float) Math.sin(time * 3f + i) * 0.12f;
            drawRotBox(scene.coins[i * 3], scene.coins[i * 3 + 1] + bob, scene.coins[i * 3 + 2],
                    time * 2.5f + i, 0.55f, 0.55f, 0.14f, 1f, 0.82f, 0.15f);
        }
        for (int i = 0; i < scene.nRocks; i++) {
            float sz = scene.rocks[i * 4 + 3];
            float c = 0.45f + (i % 3) * 0.07f;
            drawBoxAt(scene.rocks[i * 4], scene.rocks[i * 4 + 1] + sz / 2f, scene.rocks[i * 4 + 2], sz, sz, sz, c + 0.08f, c, c - 0.04f, 1f);
        }
        for (int i = 0; i < scene.avatars.size(); i++) {
            Scene.Avatar a = scene.avatars.get(i);
            if (!a.alive) continue;
            drawCharacter(a.x, a.y, a.z, a.yaw, a.phase, a.moving, a.color);
        }
        g.blend3D(true);
        for (int i = 0; i < scene.avatars.size(); i++) {
            Scene.Avatar a = scene.avatars.get(i);
            if (a.alive) drawShadow(boxes, a.x, a.y, a.z);
        }
        for (int i = 0; i < scene.nRocks; i++) {
            float a = 0.15f + 0.5f * (1f - Math.max(0f, scene.rocks[i * 4 + 1]) / DodgeGame.START_Y);
            float sz = scene.rocks[i * 4 + 3];
            drawBoxAt(scene.rocks[i * 4], 0.02f, scene.rocks[i * 4 + 2], sz, 0.02f, sz, 1f, 0.15f, 0.1f, a);
        }
        g.blend3D(false);
        g.end3D();
    }

    private void renderMenu3D() {
        Mat4.lookAt(view, 1.7f, 2.1f, 4.8f, 1.7f, 1.0f, 0f);
        Mat4.mul(vp, proj, view);
        g.begin3D(vp, 1.7f, 2.1f, 4.8f, 30f, 70f, SKY_R, SKY_G, SKY_B);
        drawBoxAt(0f, -0.5f, 0f, 3.2f, 1f, 3.2f, 0.36f, 0.72f, 0.33f, 1f);
        drawBoxAt(-3.5f, 0.5f, -5f, 2f, 3f, 2f, 0.95f, 0.8f, 0.25f, 1f);
        drawBoxAt(-1.2f, -0.5f, -7f, 3f, 3f, 3f, 0.6f, 0.4f, 0.9f, 1f);
        drawBoxAt(4.5f, 0f, -6f, 2.5f, 2f, 2.5f, 0.25f, 0.55f, 0.95f, 1f);
        drawBoxAt(7f, 1.5f, -9f, 2f, 5f, 2f, 0.3f, 0.8f, 0.85f, 1f);
        float bob = (float) Math.abs(Math.sin(time * 3.0)) * 0.25f;
        drawCharacter(0f, bob, 0f, time * 0.9f, time * 9f, true, me.color);
        g.end3D();
    }

    private void drawShadow(ArrayList<Box> boxes, float x, float y, float z) {
        float gy = -1e9f;
        for (int i = 0, n = boxes.size(); i < n; i++) {
            Box b = boxes.get(i);
            if (!b.solid()) continue;
            if (x > b.x0 && x < b.x1 && z > b.z0 && z < b.z1 && b.y1 <= y + 0.05f && b.y1 > gy) gy = b.y1;
        }
        if (gy < -1e8f) return;
        float a = Math.max(0.12f, 0.42f - (y - gy) * 0.04f);
        drawBoxAt(x, gy + 0.015f, z, 0.8f, 0.02f, 0.8f, 0f, 0f, 0f, a);
    }

    void drawCharacter(float x, float y, float z, float yaw, float phase, boolean walking, int color) {
        float[] shirt = SHIRTS[(color & 255) % SHIRTS.length];
        Mat4.transRotY(charM, x, y, z, yaw);
        float sw = walking ? (float) Math.sin(phase) * 0.17f : 0f;
        part(-0.15f, 0.4f, sw, 0.27f, 0.8f, 0.3f, 0.2f, 0.27f, 0.55f);
        part(0.15f, 0.4f, -sw, 0.27f, 0.8f, 0.3f, 0.2f, 0.27f, 0.55f);
        part(0f, 1.1f, 0f, 0.62f, 0.62f, 0.34f, shirt[0], shirt[1], shirt[2]);
        part(-0.42f, 1.1f, -sw, 0.2f, 0.62f, 0.24f, 1f, 0.78f, 0.6f);
        part(0.42f, 1.1f, sw, 0.2f, 0.62f, 0.24f, 1f, 0.78f, 0.6f);
        part(0f, 1.62f, 0f, 0.42f, 0.42f, 0.42f, 1f, 0.8f, 0.62f);
        part(-0.1f, 1.67f, 0.212f, 0.07f, 0.09f, 0.02f, 0.1f, 0.1f, 0.15f);
        part(0.1f, 1.67f, 0.212f, 0.07f, 0.09f, 0.02f, 0.1f, 0.1f, 0.15f);
        part(0f, 1.86f, -0.02f, 0.46f, 0.08f, 0.46f, 0.35f, 0.2f, 0.1f);
    }

    private void part(float cx, float cy, float cz, float sx, float sy, float sz, float r, float gg, float b) {
        Mat4.box(tmp, cx, cy, cz, sx, sy, sz);
        Mat4.mul(model, charM, tmp);
        g.drawBox(model, r, gg, b, 1f);
    }

    void drawBoxAt(float cx, float cy, float cz, float sx, float sy, float sz, float r, float gg, float b, float a) {
        Mat4.box(model, cx, cy, cz, sx, sy, sz);
        g.drawBox(model, r, gg, b, a);
    }

    void drawRotBox(float cx, float cy, float cz, float yaw, float sx, float sy, float sz, float r, float gg, float b) {
        Mat4.transRotY(charM, cx, cy, cz, yaw);
        Mat4.box(tmp, 0f, 0f, 0f, sx, sy, sz);
        Mat4.mul(model, charM, tmp);
        g.drawBox(model, r, gg, b, 1f);
    }

    /** Мировая точка -> экран. false, если за камерой. */
    private boolean project(float x, float y, float z, float[] out) {
        float cx = vp[0] * x + vp[4] * y + vp[8] * z + vp[12];
        float cy = vp[1] * x + vp[5] * y + vp[9] * z + vp[13];
        float cw = vp[3] * x + vp[7] * y + vp[11] * z + vp[15];
        if (cw < 0.2f) return false;
        out[0] = (cx / cw * 0.5f + 0.5f) * w;
        out[1] = (0.5f - cy / cw * 0.5f) * h;
        return true;
    }

    /** Ники над головами. */
    private void labels() {
        float sc = 0.42f * s;
        for (int i = 0; i < scene.avatars.size(); i++) {
            Scene.Avatar a = scene.avatars.get(i);
            if (!a.alive || a.self || a.nick.length() == 0) continue;
            float dx = a.x - camX, dy = a.y - camY, dz = a.z - camZ;
            if (dx * dx + dy * dy + dz * dz > 40f * 40f) continue;
            if (!project(a.x, a.y + 2.15f, a.z, pt)) continue;
            float tw = g.textW(a.nick, sc);
            float lh = Gfx.lineH(sc);
            g.rect(pt[0] - tw / 2f - 4 * s, pt[1] - lh, tw + 8 * s, lh * 0.95f, 0x80000000);
            g.text(a.nick, pt[0] - tw / 2f, pt[1] - lh, sc, a.bot ? 0xFFD0D8E8 : 0xFFFFFFFF);
        }
    }

    private void button(float[] r, String text, int bg, float sc) {
        g.rect(r[0], r[1], r[2], r[3], bg);
        g.rect(r[0], r[1] + r[3] - 3 * s, r[2], 3 * s, 0x40000000);
        float tw = g.textW(text, sc);
        while (tw > r[2] - 8 * s && sc > 0.2f * s) { sc *= 0.9f; tw = g.textW(text, sc); }
        g.textC(text, r[0] + r[2] / 2f, r[1] + (r[3] - Gfx.lineH(sc)) / 2f + 2 * s, sc, C_TEXT);
    }

    private void hudMenu() {
        float x0 = w * 0.42f, cx = (x0 + w - 8 * s) / 2f;
        g.textC("БЛОК ПАТИ", cx, 8 * s, 1.0f * s, C_GOLD);
        for (int i = 0; i < 3; i++) {
            menuButton(i, r4);
            g.rect(r4[0], r4[1], r4[2], r4[3], C_BTN);
            g.rect(r4[0], r4[1], 6 * s, r4[3], MODE_COLORS[i]);
            float nameSc = Math.min(0.66f * s, r4[3] * 0.55f / Gfx.CELL_H), subSc = nameSc * 0.65f;
            float total = Gfx.lineH(nameSc) + Gfx.lineH(subSc) * 0.9f;
            float ty = r4[1] + (r4[3] - total) / 2f + 2 * s;
            g.textS(MODE_NAMES[i], r4[0] + 16 * s, ty, nameSc, C_TEXT);
            float best = prefs.getFloat("best_" + MODE_KEYS[i], -1f);
            String sub = best < 0 ? match.modes[i].hint() : "Рекорд: " + formatScore(i, best);
            g.textS(sub, r4[0] + 16 * s, ty + Gfx.lineH(nameSc) * 0.95f, subSc, best < 0 ? C_DIM : C_GOLD);
        }
        String[] bar = {"Боты: " + botCount, "Ник", roomOpen ? "Комната: ВКЛ" : "Комната", connecting ? "..." : "Войти"};
        for (int i = 0; i < 4; i++) {
            barButton(i, r4);
            button(r4, bar[i], i == 2 && roomOpen ? 0xE02E8B57 : 0xD0303F5C, 0.46f * s);
        }
        // слева под человечком — ник и комната
        float lsc = 0.42f * s, lx = 10 * s, ly = h * 0.52f, lh = Gfx.lineH(lsc) * 0.92f;
        float maxY = h - barH() - 12 * s - lh;
        g.textS(me.nick.length() > 0 ? me.nick : "(без ника)", lx, ly, 0.55f * s, C_TEXT);
        ly += Gfx.lineH(0.55f * s);
        if (roomOpen) {
            g.textS(btStatus, lx, ly, lsc, C_DIM);
            ly += lh;
            if (wifiIp != null && ly < maxY) { g.textS("Wi-Fi: " + wifiIp, lx, ly, lsc, C_DIM); ly += lh; }
            ArrayList<Player> hs = lobbyHumans();
            if (ly < maxY) { g.textS("Игроков: " + hs.size() + (botCount > 0 ? ", ботов: " + botCount : ""), lx, ly, lsc, C_GOLD); ly += lh; }
            for (int i = 1; i < hs.size() && ly < maxY; i++) { g.textS("• " + hs.get(i).nick, lx, ly, lsc, C_TEXT); ly += lh; }
        } else if (connecting) {
            g.textS("Подключение...", lx, ly, lsc, C_GOLD);
        }
    }

    private void hudCWait() {
        float x0 = w * 0.42f, cx = (x0 + w - 8 * s) / 2f;
        g.rect(x0, 8 * s, w - x0 - 8 * s, h - 70 * s, 0xA0141C30);
        float y = 16 * s;
        g.textC(myId < 0 ? "Подключение..." : "Хост: " + hostNick, cx, y, 0.6f * s, C_GOLD);
        y += Gfx.lineH(0.6f * s);
        g.textC("Ждём, пока хост выберет игру", cx, y, 0.42f * s, C_DIM);
        y += Gfx.lineH(0.42f * s) * 1.3f;
        float lsc = 0.46f * s, lh = Gfx.lineH(lsc) * 0.95f;
        for (int i = 0; i < roster.size() && y < h - 80 * s; i++) {
            Object[] e = roster.get(i);
            int id = (Integer) e[0];
            String line = (String) e[3] + (id == myId ? "  (ты)" : "") + (id == 0 ? "  — хост" : "");
            g.textS(line, x0 + 14 * s, y, lsc, ((Integer) e[2]) != 0 ? C_DIM : C_TEXT);
            y += lh;
        }
        resultButton(0, 1, r4);
        button(r4, "Отключиться", C_BTN, 0.55f * s);
    }

    private void hudPlay() {
        String hud = isClient() ? cHud : match.mode.hud(me);
        float hsc = 0.55f * s;
        float tw = g.textW(hud, hsc);
        while (tw > w - 220 * s && hsc > 0.3f * s) { hsc *= 0.9f; tw = g.textW(hud, hsc); }
        g.textC(hud, w / 2f, 10 * s, hsc, C_TEXT);

        menuBtnRect(r4);
        g.rect(r4[0], r4[1], r4[2], r4[3], 0x90000000);
        float sc = 0.5f * s;
        g.textC(isClient() ? "ВЫЙТИ" : "МЕНЮ", r4[0] + r4[2] / 2f, r4[1] + (r4[3] - Gfx.lineH(sc)) / 2f + 2 * s, sc, C_TEXT);

        boolean canMove = isClient() ? cAlive : me.alive;
        if (!canMove) {
            g.textC("Ты выбыл — смотри, кто победит", w / 2f, h * 0.8f, 0.5f * s, C_GOLD);
            return;
        }
        in.getJoy(joy);
        float jr = Input.joyR(w, h);
        boolean active = joy[6] > 0;
        g.circle(joy[2], joy[3], jr, active ? 0x50FFFFFF : 0x28FFFFFF);
        g.circle(joy[4], joy[5], jr * 0.45f, active ? 0xA0FFFFFF : 0x50FFFFFF);
        float br = Input.jumpR(w, h);
        float bx = Input.jumpX(w, h), by = Input.jumpY(w, h);
        g.circle(bx, by, br, in.jumpHeld() ? 0xB0FFFFFF : 0x60FFFFFF);
        float asc = 1.1f * s;
        g.text("↑", bx - g.textW("↑", asc) / 2f, by - Gfx.lineH(asc) * 0.42f, asc, 0xC0203050);
    }

    private void drawFlash() {
        if (msgTime <= 0 || msg.length() == 0) return;
        int a = (int) (255 * Math.min(1f, msgTime * 2f));
        float msc = 0.52f * s;
        float tw = g.textW(msg, msc) + 24 * s;
        float y = h * 0.22f;
        g.rect(w / 2f - tw / 2f, y - 6 * s, tw, Gfx.lineH(msc) + 8 * s, (a * 140 / 255) << 24);
        g.textC(msg, w / 2f, y, msc, (a << 24) | 0xFFFFFF);
    }

    private void hudResult() {
        g.rect(0, 0, w, h, 0xB0000000);
        float y = 8 * s;
        g.textC(resTitle, w / 2f, y, 0.9f * s, C_GOLD);
        y += Gfx.lineH(0.9f * s);
        float bottom = h - 64 * s;
        int n = resRows.size();
        float rsc = Math.min(0.55f * s, (bottom - y - 22 * s) / Math.max(1, n) / Gfx.CELL_H);
        float lh = Gfx.lineH(rsc);
        float colL = w * 0.18f, colR = w * 0.82f;
        for (int i = 0; i < n; i++) {
            String[] r = resRows.get(i);
            int c = r[2].length() > 0 ? C_GOLD : C_TEXT;
            if (r[2].length() > 0) g.rect(colL - 8 * s, y, colR - colL + 16 * s, lh, 0x40FFD34A);
            g.textS(r[0], colL, y, rsc, c);
            g.textS(r[1], colR - g.textW(r[1], rsc), y, rsc, c);
            y += lh;
        }
        if (resRecord.length() > 0) g.textC(resRecord, w / 2f, bottom - Gfx.lineH(0.45f * s), 0.45f * s, C_GOLD);
        if (state == RESULT) {
            resultButton(0, 2, r4);
            button(r4, "ЕЩЁ РАЗ", 0xE02E8B57, 0.6f * s);
            resultButton(1, 2, r4);
            button(r4, "МЕНЮ", C_BTN, 0.6f * s);
        } else {
            resultButton(0, 1, r4);
            button(r4, "ОК", C_BTN, 0.6f * s);
        }
    }

    /** Закрыть всё при выходе из приложения. */
    void shutdown() {
        closeRoom();
        if (link != null) link.close();
    }
}
