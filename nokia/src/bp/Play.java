package bp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.game.GameCanvas;

/**
 * Игровой экран Nokia: вид сверху с объёмом (верх блока сдвинут вверх по высоте).
 * Все координаты — целые в 1/256 блока (Q8), так быстрее на Series 40.
 */
public class Play extends GameCanvas implements Runnable, CommandListener {
    static final int WAIT = 0, PLAY = 1, RESULT = 2;

    // физика (Q8)
    static final int HW = 77, PH = 461, G = 5632, JUMP = 2099, SPEED = 1408, VMIN = -8960;
    // отрисовка: пикселей на блок по горизонтали и по высоте
    static final int S = 14, K = 7;

    static final int[] SHIRTS = {0xFA8026, 0xE6333F, 0x40BF4D, 0x9959E6, 0x33BFD9, 0xF273B3, 0xF2D933, 0xEBEBEB};

    private final BlokMIDlet mid;
    private final NetLink net;
    private final String nick;
    private volatile boolean running = true;

    private int state = WAIT;
    private int myId = -1, mode;
    private String hostNick = "", hud = "";
    private boolean alive = true;

    // участники
    private final int[] rId = new int[16], rCol = new int[16];
    private final boolean[] rBot = new boolean[16];
    private final String[] rNick = new String[16];
    private int rN;

    // уровень
    private int nb;
    private int[] bx0 = new int[0], by0, bz0, bx1, by1, bz1, bcol, bkind, order;

    // я
    private int px, py, pz, vx, vy, vz, yawB;
    private boolean onGround;
    private int lastDx, lastDz, lastMoveT;

    // другие игроки
    private final int[] oId = new int[16], oX = new int[16], oY = new int[16], oZ = new int[16];
    private final int[] oTX = new int[16], oTY = new int[16], oTZ = new int[16];
    private final boolean[] oAlive = new boolean[16], oFresh = new boolean[16], oSeen = new boolean[16];
    private int on;

    // монетки и падающие блоки
    private final int[] cX = new int[16], cY = new int[16], cZ = new int[16];
    private int nc;
    private final int[] kX = new int[64], kY = new int[64], kZ = new int[64], kS = new int[64];
    private int nr;

    // клавиши
    private final int[] held = new int[6];
    private int nHeld;
    private boolean jumpReq;

    private String msg = "";
    private int msgUntil;

    // результаты
    private String resTitle = "";
    private final String[] resL = new String[16], resR = new String[16];
    private final boolean[] resMe = new boolean[16];
    private int resN;

    private final Font fs = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Font fb = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_SMALL);
    private final Command cExit = new Command("Выход", Command.EXIT, 1);
    private final Command cOk = new Command("ОК", Command.OK, 1);
    private int frameNo;

    public Play(BlokMIDlet mid, NetLink net, String nick) {
        super(false);
        this.mid = mid;
        this.net = net;
        this.nick = nick;
        setFullScreenMode(true);
        addCommand(cExit);
        setCommandListener(this);
        net.send(NetLink.hello(nick));
        new Thread(this).start();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cOk) {
            removeCommand(cOk);
            state = WAIT;
        } else if (c == cExit) {
            quit("Ты вышел из комнаты");
        }
    }

    private void quit(String why) {
        if (!running) return;
        running = false;
        net.send(NetLink.bye());
        net.close();
        mid.backToMenu(why);
    }

    // ===================== Цикл =====================

    public void run() {
        Graphics g = getGraphics();
        int last = (int) System.currentTimeMillis();
        int sendAt = 0;
        while (running) {
            int now = (int) System.currentTimeMillis();
            int dt = now - last;
            last = now;
            if (dt > 100) dt = 100;
            if (dt < 0) dt = 0;

            try {
                byte[] m;
                while ((m = net.poll()) != null) handle(m);
            } catch (IOException e) {
                quit("Ошибка данных от хоста");
                return;
            }
            if (net.dead) {
                quit("Связь с хостом потеряна");
                return;
            }

            if (state == PLAY) {
                step(dt, now);
                if (now - sendAt >= 100) {
                    sendAt = now;
                    sendState();
                }
                smoothOthers();
            }
            frameNo++;
            try {
                render(g, now);
            } catch (Exception e) {
                // на всякий случай не роняем игру из-за отрисовки
            }
            flushGraphics();
            int spent = (int) System.currentTimeMillis() - now;
            int sleep = 66 - spent;
            if (sleep < 5) sleep = 5;
            try { Thread.sleep(sleep); } catch (InterruptedException e) { }
        }
    }

    // ===================== Клавиши =====================

    protected void keyPressed(int key) {
        if (key == KEY_NUM5 || (key != KEY_NUM2 && key != KEY_NUM8 && key != KEY_NUM4 && key != KEY_NUM6
                && key < 0 && getGameAction(key) == FIRE)) {
            jumpReq = true;
            return;
        }
        if (key == KEY_NUM0 && state == RESULT) {
            commandAction(cOk, this);
            return;
        }
        synchronized (held) {
            for (int i = 0; i < nHeld; i++) if (held[i] == key) return;
            if (nHeld < held.length) held[nHeld++] = key;
        }
    }

    protected void keyReleased(int key) {
        synchronized (held) {
            for (int i = 0; i < nHeld; i++) {
                if (held[i] == key) {
                    held[i] = held[--nHeld];
                    return;
                }
            }
        }
    }

    /** Направление по клавишам: биты 1 — вверх (+Z), 2 — вниз, 4 — влево (+X), 8 — вправо. */
    private int dirMask() {
        int m = 0;
        synchronized (held) {
            for (int i = 0; i < nHeld; i++) {
                int k = held[i];
                switch (k) {
                    case KEY_NUM2: m |= 1; break;
                    case KEY_NUM8: m |= 2; break;
                    case KEY_NUM4: m |= 4; break;
                    case KEY_NUM6: m |= 8; break;
                    case KEY_NUM1: m |= 5; break;
                    case KEY_NUM3: m |= 9; break;
                    case KEY_NUM7: m |= 6; break;
                    case KEY_NUM9: m |= 10; break;
                    default: {
                        int ga = getGameAction(k);
                        if (ga == UP) m |= 1;
                        else if (ga == DOWN) m |= 2;
                        else if (ga == LEFT) m |= 4;
                        else if (ga == RIGHT) m |= 8;
                    }
                }
            }
        }
        return m;
    }

    // ===================== Физика =====================

    private void step(int dt, int now) {
        int m = dirMask();
        int dx = 0, dz = 0;
        if ((m & 1) != 0) dz += 256;
        if ((m & 2) != 0) dz -= 256;
        if ((m & 4) != 0) dx += 256;
        if ((m & 8) != 0) dx -= 256;
        if (dx != 0 && dz != 0) { dx = dx * 181 / 256; dz = dz * 181 / 256; }
        boolean jump = jumpReq;
        jumpReq = false;
        if (!alive) { dx = 0; dz = 0; jump = false; }
        if (dx != 0 || dz != 0) {
            lastDx = dx; lastDz = dz; lastMoveT = now;
            yawB = yawFor(dx, dz);
        }
        int tvx = dx * SPEED / 256, tvz = dz * SPEED / 256;
        if (onGround) {
            int k = Math.min(dt * 14, 1000);
            vx += (tvx - vx) * k / 1000;
            vz += (tvz - vz) * k / 1000;
        } else if (dx != 0 || dz != 0) {
            int k = Math.min(dt * 5, 1000);
            vx += (tvx - vx) * k / 1000;
            vz += (tvz - vz) * k / 1000;
        }
        if (jump && onGround) {
            vy = JUMP;
            onGround = false;
            if (dx == 0 && dz == 0 && now - lastMoveT < 400) {
                // прыжок с разбега: на кнопочном телефоне трудно жать две клавиши сразу
                vx = lastDx * SPEED / 256;
                vz = lastDz * SPEED / 256;
            }
        }
        int steps = (dt + 15) / 16;
        if (steps < 1) steps = 1;
        int h = dt / steps;
        for (int i = 0; i < steps; i++) {
            vy -= G * h / 1000;
            if (vy < VMIN) vy = VMIN;
            move(0, vx * h / 1000);
            move(2, vz * h / 1000);
            onGround = false;
            move(1, vy * h / 1000);
        }
    }

    private static int yawFor(int dx, int dz) {
        if (dz > 0) return dx > 0 ? 32 : (dx < 0 ? 224 : 0);
        if (dz < 0) return dx > 0 ? 96 : (dx < 0 ? 160 : 128);
        return dx > 0 ? 64 : 192;
    }

    private void move(int axis, int d) {
        if (d == 0) return;
        if (axis == 0) px += d;
        else if (axis == 1) py += d;
        else pz += d;
        for (int i = 0; i < nb; i++) {
            if (bkind[i] == 4) continue;
            if (px + HW <= bx0[i] || px - HW >= bx1[i] || py + PH <= by0[i] || py >= by1[i]
                    || pz + HW <= bz0[i] || pz - HW >= bz1[i]) continue;
            if (axis == 0) {
                px = d > 0 ? bx0[i] - HW - 1 : bx1[i] + HW + 1;
                vx = 0;
            } else if (axis == 2) {
                pz = d > 0 ? bz0[i] - HW - 1 : bz1[i] + HW + 1;
                vz = 0;
            } else {
                if (d < 0) {
                    py = by1[i];
                    onGround = true;
                } else {
                    py = by0[i] - PH - 1;
                }
                vy = 0;
            }
        }
    }

    private void sendState() {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bo);
        try {
            d.writeShort(px >> 2);
            d.writeShort(py >> 2);
            d.writeShort(pz >> 2);
            d.writeByte(yawB);
            int fl = 0;
            if (vx != 0 || vz != 0) fl |= 1;
            if (onGround) fl |= 4;
            d.writeByte(fl);
        } catch (IOException e) { }
        net.send(NetLink.frame(2, bo));
    }

    private void smoothOthers() {
        for (int i = 0; i < on; i++) {
            if (oFresh[i]) {
                oX[i] = oTX[i]; oY[i] = oTY[i]; oZ[i] = oTZ[i];
                oFresh[i] = false;
            } else {
                oX[i] += (oTX[i] - oX[i]) / 2;
                oY[i] += (oTY[i] - oY[i]) / 2;
                oZ[i] += (oTZ[i] - oZ[i]) / 2;
            }
        }
    }

    // ===================== Сеть =====================

    private void handle(byte[] m) throws IOException {
        int type = m[0] & 255;
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(m, 1, m.length - 1));
        switch (type) {
            case 10: // WELCOME
                myId = d.readUnsignedByte();
                hostNick = d.readUTF();
                flash("Подключено к " + hostNick, 2500);
                break;
            case 11: { // ROSTER
                int n = d.readUnsignedByte();
                rN = 0;
                for (int i = 0; i < n; i++) {
                    int id = d.readUnsignedByte(), col = d.readUnsignedByte(), bot = d.readUnsignedByte();
                    String nk = d.readUTF();
                    if (rN < 16) {
                        rId[rN] = id; rCol[rN] = col; rBot[rN] = bot != 0; rNick[rN] = nk;
                        rN++;
                    }
                }
                break;
            }
            case 12: { // LEVEL
                mode = d.readUnsignedByte();
                px = d.readShort() * 4; py = d.readShort() * 4; pz = d.readShort() * 4;
                int n = d.readUnsignedShort();
                bx0 = new int[n]; by0 = new int[n]; bz0 = new int[n];
                bx1 = new int[n]; by1 = new int[n]; bz1 = new int[n];
                bcol = new int[n]; bkind = new int[n]; order = new int[n];
                for (int i = 0; i < n; i++) {
                    bx0[i] = d.readShort() * 4; by0[i] = d.readShort() * 4; bz0[i] = d.readShort() * 4;
                    bx1[i] = d.readShort() * 4; by1[i] = d.readShort() * 4; bz1[i] = d.readShort() * 4;
                    int r = d.readUnsignedByte(), gg = d.readUnsignedByte(), b = d.readUnsignedByte();
                    bcol[i] = (r << 16) | (gg << 8) | b;
                    bkind[i] = d.readUnsignedByte();
                }
                nb = n;
                sortBoxes();
                vx = vy = vz = 0;
                onGround = false;
                alive = true;
                on = 0;
                nc = 0;
                nr = 0;
                hud = "";
                state = PLAY;
                removeCommand(cOk);
                String[] hints = {"Добеги до золотого финиша", "Собери больше всех монет", "Не попади под блоки"};
                flash(hints[mode % 3] + ". 2468 - идти, 5 - прыжок", 4000);
                break;
            }
            case 13: { // SNAP
                hud = d.readUTF();
                int n = d.readUnsignedByte();
                for (int j = 0; j < on; j++) oSeen[j] = false;
                for (int i = 0; i < n; i++) {
                    int id = d.readUnsignedByte();
                    int x = d.readShort() * 4, y = d.readShort() * 4, z = d.readShort() * 4;
                    d.readUnsignedByte();
                    int fl = d.readUnsignedByte();
                    if (id == myId) {
                        alive = (fl & 2) != 0;
                        continue;
                    }
                    int slot = -1;
                    for (int j = 0; j < on; j++) {
                        if (oId[j] == id) { slot = j; break; }
                    }
                    if (slot < 0) {
                        if (on >= 16) continue;
                        slot = on++;
                        oId[slot] = id;
                        oFresh[slot] = true;
                    }
                    oSeen[slot] = true;
                    oTX[slot] = x; oTY[slot] = y; oTZ[slot] = z;
                    oAlive[slot] = (fl & 2) != 0;
                }
                // убираем тех, кого больше нет в матче
                int k = 0;
                for (int j = 0; j < on; j++) {
                    if (!oSeen[j]) continue;
                    if (k != j) swapOther(j, k);
                    k++;
                }
                on = k;
                nc = d.readUnsignedByte();
                for (int i = 0; i < nc; i++) {
                    int x = d.readShort() * 4, y = d.readShort() * 4, z = d.readShort() * 4;
                    if (i < 16) { cX[i] = x; cY[i] = y; cZ[i] = z; }
                }
                if (nc > 16) nc = 16;
                nr = d.readUnsignedByte();
                for (int i = 0; i < nr; i++) {
                    int x = d.readShort() * 4, y = d.readShort() * 4, z = d.readShort() * 4;
                    int sz = d.readUnsignedByte() * 8;
                    if (i < 64) { kX[i] = x; kY[i] = y; kZ[i] = z; kS[i] = sz; }
                }
                if (nr > 64) nr = 64;
                break;
            }
            case 14: // TELEPORT
                px = d.readShort() * 4; py = d.readShort() * 4; pz = d.readShort() * 4;
                vx = vy = vz = 0;
                break;
            case 15: // MSG
                flash(d.readUTF(), 2500);
                break;
            case 16: { // RESULT
                d.readUnsignedByte();
                resTitle = d.readUTF();
                int n = d.readUnsignedByte();
                resN = 0;
                for (int i = 0; i < n; i++) {
                    int id = d.readUnsignedByte();
                    String nk = d.readUTF(), st = d.readUTF();
                    d.readInt();
                    d.readUnsignedByte();
                    if (resN < 16) {
                        resL[resN] = (i + 1) + ". " + nk;
                        resR[resN] = st;
                        resMe[resN] = id == myId;
                        resN++;
                    }
                }
                state = RESULT;
                addCommand(cOk);
                break;
            }
            case 17: // LOBBY
                state = WAIT;
                removeCommand(cOk);
                break;
            default:
                break;
        }
    }

    private void swapOther(int a, int b) {
        int t;
        t = oId[a]; oId[a] = oId[b]; oId[b] = t;
        t = oX[a]; oX[a] = oX[b]; oX[b] = t;
        t = oY[a]; oY[a] = oY[b]; oY[b] = t;
        t = oZ[a]; oZ[a] = oZ[b]; oZ[b] = t;
        t = oTX[a]; oTX[a] = oTX[b]; oTX[b] = t;
        t = oTY[a]; oTY[a] = oTY[b]; oTY[b] = t;
        t = oTZ[a]; oTZ[a] = oTZ[b]; oTZ[b] = t;
        boolean bb = oAlive[a]; oAlive[a] = oAlive[b]; oAlive[b] = bb;
        bb = oFresh[a]; oFresh[a] = oFresh[b]; oFresh[b] = bb;
        bb = oSeen[a]; oSeen[a] = oSeen[b]; oSeen[b] = bb;
    }

    /** Порядок рисования: большой пол первым, дальше — от дальних к ближним, от низких к высоким. */
    private void sortBoxes() {
        for (int i = 0; i < nb; i++) order[i] = i;
        for (int i = 1; i < nb; i++) {
            int v = order[i];
            int j = i - 1;
            while (j >= 0 && before(v, order[j])) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = v;
        }
    }

    private boolean before(int a, int b) {
        boolean fa = isFloor(a), fb2 = isFloor(b);
        if (fa != fb2) return fa;
        if (bz1[a] != bz1[b]) return bz1[a] > bz1[b];
        return by1[a] < by1[b];
    }

    private boolean isFloor(int i) {
        int sx = (bx1[i] - bx0[i]) >> 8, sz = (bz1[i] - bz0[i]) >> 8;
        return bkind[i] == 4 || sx * sz >= 100;
    }

    private void flash(String s, int ms) {
        msg = s;
        msgUntil = (int) System.currentTimeMillis() + ms;
    }

    private String rosterNick(int id) {
        for (int i = 0; i < rN; i++) if (rId[i] == id) return rNick[i];
        return "?";
    }

    private int rosterCol(int id) {
        for (int i = 0; i < rN; i++) if (rId[i] == id) return rCol[i];
        return 0;
    }

    // ===================== Отрисовка =====================

    private static int shade(int c, int pct) {
        int r = ((c >> 16) & 255) * pct / 100, g = ((c >> 8) & 255) * pct / 100, b = (c & 255) * pct / 100;
        if (r > 255) r = 255;
        if (g > 255) g = 255;
        if (b > 255) b = 255;
        return (r << 16) | (g << 8) | b;
    }

    private void render(Graphics g, int now) {
        int w = getWidth(), h = getHeight();
        g.setColor(0x8CC2F5);
        g.fillRect(0, 0, w, h);
        if (state == WAIT) {
            renderWait(g, w, h);
        } else {
            renderWorld(g, w, h);
            renderHud(g, w, h);
            if (state == RESULT) renderResult(g, w, h);
        }
        if (now - msgUntil < 0 && msg.length() > 0) {
            g.setFont(fb);
            int tw = fb.stringWidth(msg);
            int y = h / 4;
            if (tw > w - 8) {
                drawWrapped(g, msg, w / 2, y, w - 12, 0xFFFFFF);
            } else {
                g.setColor(0x000000);
                g.fillRect((w - tw) / 2 - 4, y - 2, tw + 8, fb.getHeight() + 4);
                g.setColor(0xFFFFFF);
                g.drawString(msg, w / 2, y, Graphics.TOP | Graphics.HCENTER);
            }
        }
    }

    private void drawWrapped(Graphics g, String s, int cx, int y, int maxW, int color) {
        int start = 0;
        int lh = fb.getHeight();
        while (start < s.length()) {
            int end = s.length();
            while (end > start + 1 && fb.substringWidth(s, start, end - start) > maxW) {
                int sp = s.lastIndexOf(' ', end - 1);
                end = sp > start ? sp : end - 1;
            }
            String line = s.substring(start, end).trim();
            int tw = fb.stringWidth(line);
            g.setColor(0x000000);
            g.fillRect(cx - tw / 2 - 4, y - 1, tw + 8, lh + 2);
            g.setColor(color);
            g.drawString(line, cx, y, Graphics.TOP | Graphics.HCENTER);
            y += lh + 2;
            start = end;
            while (start < s.length() && s.charAt(start) == ' ') start++;
        }
    }

    private int ox, oy, camX, camY, camZ;

    private int sx(int x) { return ox - (((x - camX) * S) >> 8); }

    private int sy(int z, int y) { return oy + (((camZ - z) * S) >> 8) - (((y - camY) * K) >> 8); }

    private void renderWorld(Graphics g, int w, int h) {
        ox = w / 2;
        oy = h * 58 / 100;
        camX = px; camY = py; camZ = pz;
        int pulse = 80 + (frameNo % 8 < 4 ? 20 : 0);
        for (int n = 0; n < nb; n++) {
            int i = order[n];
            if (bkind[i] == 4) continue;
            int l = sx(bx1[i]), r = sx(bx0[i]);
            int far = sy(bz1[i], by1[i]), near = sy(bz0[i], by1[i]);
            int side = ((by1[i] - by0[i]) * K) >> 8;
            if (side > 3 * K) side = 3 * K;
            if (r < 0 || l > w || far > h || near + side < 0) continue;
            int c = bcol[i];
            if (bkind[i] == 1) c = shade(c, pulse);
            if (l < -10) l = -10;
            if (r > w + 10) r = w + 10;
            if (far < -10) far = -10;
            g.setColor(shade(c, 62));
            g.fillRect(l, near, r - l, side);
            g.setColor(c);
            g.fillRect(l, far, r - l, near - far);
            g.setColor(shade(c, 78));
            g.drawRect(l, far, r - l - 1, near - far - 1);
        }
        // монетки
        for (int i = 0; i < nc; i++) {
            int x = sx(cX[i]), y = sy(cZ[i], cY[i]);
            int rr = 4 + ((frameNo + i * 3) % 6 < 3 ? 1 : 0);
            g.setColor(0xFFD126);
            g.fillArc(x - rr, y - rr, rr * 2, rr * 2, 0, 360);
            g.setColor(0xB38A00);
            g.drawArc(x - rr, y - rr, rr * 2, rr * 2, 0, 360);
        }
        // падающие блоки: красная метка на полу и сам блок над ней
        for (int i = 0; i < nr; i++) {
            int half = kS[i] / 2;
            int l = sx(kX[i] + half), r = sx(kX[i] - half);
            int far = sy(kZ[i] + half, 0), near = sy(kZ[i] - half, 0);
            g.setColor(kY[i] < 6 * 256 ? 0xFF3322 : 0xCC6655);
            g.drawRect(l, far, r - l, near - far);
            g.drawRect(l + 1, far + 1, r - l - 2, near - far - 2);
            int top = sy(kZ[i] + half, kY[i] + kS[i]), bot = sy(kZ[i] - half, kY[i] + kS[i]);
            g.setColor(0x8C8078);
            g.fillRect(l, top, r - l, bot - top);
            g.setColor(0x5C534D);
            g.drawRect(l, top, r - l - 1, bot - top - 1);
        }
        // другие игроки
        g.setFont(fs);
        for (int i = 0; i < on; i++) {
            if (!oAlive[i]) continue;
            drawMan(g, oX[i], oY[i], oZ[i], SHIRTS[rosterCol(oId[i]) % 8], false);
            int x = sx(oX[i]), y = sy(oZ[i], oY[i] + 2 * 256);
            String nk = rosterNick(oId[i]);
            int tw = fs.stringWidth(nk);
            g.setColor(0x000000);
            g.fillRect(x - tw / 2 - 2, y - fs.getHeight(), tw + 4, fs.getHeight());
            g.setColor(0xFFFFFF);
            g.drawString(nk, x, y, Graphics.BOTTOM | Graphics.HCENTER);
        }
        if (alive) drawMan(g, px, py, pz, SHIRTS[rosterCol(myId) % 8], true);
    }

    private void drawMan(Graphics g, int x, int y, int z, int shirt, boolean me) {
        // тень на блоке под ногами
        int gy = -100000;
        for (int i = 0; i < nb; i++) {
            if (bkind[i] == 4) continue;
            if (x > bx0[i] && x < bx1[i] && z > bz0[i] && z < bz1[i] && by1[i] <= y + 12 && by1[i] > gy) gy = by1[i];
        }
        if (gy > -100000) {
            int shx = sx(x), shy = sy(z, gy);
            g.setColor(0x333333);
            g.fillArc(shx - 5, shy - 3, 10, 6, 0, 360);
        }
        int bx = sx(x), byy = sy(z, y + 230);
        g.setColor(me ? 0xFFFFFF : 0x202020);
        g.fillArc(bx - 7, byy - 7, 14, 14, 0, 360);
        g.setColor(shirt);
        g.fillArc(bx - 6, byy - 6, 12, 12, 0, 360);
        g.setColor(0xFFCC9E);
        g.fillArc(bx - 3, byy - 9, 6, 6, 0, 360);
    }

    private void renderHud(Graphics g, int w, int h) {
        g.setFont(fs);
        int lh = fs.getHeight();
        g.setColor(0x000000);
        g.fillRect(0, 0, w, lh + 2);
        g.setColor(0xFFFFFF);
        String s = hud;
        while (s.length() > 1 && fs.stringWidth(s) > w - 4) s = s.substring(0, s.length() - 1);
        g.drawString(s, 2, 1, Graphics.TOP | Graphics.LEFT);
        if (!alive && state == PLAY) {
            g.setFont(fb);
            g.setColor(0x000000);
            g.fillRect(0, h - fb.getHeight() - 4, w, fb.getHeight() + 4);
            g.setColor(0xFFD34A);
            g.drawString("Ты выбыл. Смотри, кто победит", w / 2, h - 2, Graphics.BOTTOM | Graphics.HCENTER);
        }
    }

    private void renderResult(Graphics g, int w, int h) {
        g.setColor(0x101828);
        g.fillRect(4, 16, w - 8, h - 32);
        g.setFont(fb);
        g.setColor(0xFFD34A);
        g.drawString(resTitle, w / 2, 20, Graphics.TOP | Graphics.HCENTER);
        g.setFont(fs);
        int lh = fs.getHeight() + 1;
        int y = 22 + fb.getHeight() + 4;
        for (int i = 0; i < resN && y < h - 40; i++) {
            g.setColor(resMe[i] ? 0xFFD34A : 0xFFFFFF);
            g.drawString(resL[i], 10, y, Graphics.TOP | Graphics.LEFT);
            g.drawString(resR[i], w - 10, y, Graphics.TOP | Graphics.RIGHT);
            y += lh;
        }
        g.setColor(0xB8C6E0);
        g.drawString("0 или ОК — дальше", w / 2, h - 20, Graphics.BOTTOM | Graphics.HCENTER);
    }

    private void renderWait(Graphics g, int w, int h) {
        g.setColor(0x5DBB4F);
        g.fillRect(0, h * 3 / 4, w, h / 4);
        g.setFont(fb);
        g.setColor(0x14204A);
        g.drawString("БЛОК ПАТИ", w / 2, 6, Graphics.TOP | Graphics.HCENTER);
        int y = 10 + fb.getHeight();
        g.setFont(fs);
        g.drawString(myId < 0 ? "Подключение..." : "Хост: " + hostNick, w / 2, y, Graphics.TOP | Graphics.HCENTER);
        y += fs.getHeight();
        g.drawString("Ждём, пока хост выберет игру", w / 2, y, Graphics.TOP | Graphics.HCENTER);
        y += fs.getHeight() + 6;
        for (int i = 0; i < rN && y < h - 20; i++) {
            String line = rNick[i];
            if (rId[i] == myId) line = line + " (ты)";
            if (rId[i] == 0) line = line + " - хост";
            g.setColor(rBot[i] ? 0x3A4A6A : 0x000000);
            g.drawString(line, 12, y, Graphics.TOP | Graphics.LEFT);
            y += fs.getHeight();
        }
        // прыгающий человечек
        int by = h * 3 / 4 - 10 - ((frameNo % 10) < 5 ? (frameNo % 5) * 2 : (5 - frameNo % 5) * 2);
        g.setColor(0xFA8026);
        g.fillArc(w / 2 - 8, by - 8, 16, 16, 0, 360);
        g.setColor(0xFFCC9E);
        g.fillArc(w / 2 - 4, by - 12, 8, 8, 0, 360);
    }
}
