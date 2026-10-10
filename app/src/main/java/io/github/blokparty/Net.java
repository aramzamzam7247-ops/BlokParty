package io.github.blokparty;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Сетевой протокол «Блок Пати». Одинаковый для Android, Nokia (J2ME) и ПК.
 * Кадр: u8 тип, u16 длина, данные. Все числа big-endian, строки — writeUTF.
 * Координаты передаются как s16 в 1/64 блока.
 */
final class Net {
    private Net() {}

    static final String UUID_STR = "6b0f3a52-8d1e-4c1b-9e57-1b10c0a7b1a7";
    static final int TCP_PORT = 5577;
    static final int PROTO = 1;

    // клиент -> хост
    static final int HELLO = 1, STATE = 2, BYE = 3;
    // хост -> клиент
    static final int WELCOME = 10, ROSTER = 11, LEVEL = 12, SNAP = 13, TELEPORT = 14, MSG = 15, RESULT = 16, LOBBY = 17;

    static final int KIND_ANDROID = 0, KIND_NOKIA = 1, KIND_PC = 2;

    static final int F_MOVING = 1, F_ALIVE = 2, F_GROUND = 4, F_BOT = 8;

    static int q(float v) {
        int i = Math.round(v * 64f);
        if (i > 32767) i = 32767;
        if (i < -32768) i = -32768;
        return i;
    }

    static float uq(int v) { return v / 64f; }

    static int yawByte(float yaw) {
        return ((int) Math.floor(yaw / (2 * Math.PI) * 256.0)) & 255;
    }

    static float byteYaw(int b) { return (float) (b * (2 * Math.PI / 256.0)); }

    /** Сборка одного кадра. */
    static final class Writer {
        private final int type;
        private final ByteArrayOutputStream bo = new ByteArrayOutputStream(64);
        private final DataOutputStream d = new DataOutputStream(bo);

        Writer(int type) { this.type = type; }

        Writer u8(int v) { try { d.writeByte(v); } catch (IOException e) { throw new RuntimeException(e); } return this; }
        Writer s16(int v) { try { d.writeShort(v); } catch (IOException e) { throw new RuntimeException(e); } return this; }
        Writer u16(int v) { return s16(v); }
        Writer s32(int v) { try { d.writeInt(v); } catch (IOException e) { throw new RuntimeException(e); } return this; }
        Writer pos(float v) { return s16(q(v)); }

        Writer utf(String s) {
            if (s == null) s = "";
            if (s.length() > 200) s = s.substring(0, 200);
            try { d.writeUTF(s); } catch (IOException e) { throw new RuntimeException(e); }
            return this;
        }

        byte[] done() {
            byte[] p = bo.toByteArray();
            byte[] f = new byte[p.length + 3];
            f[0] = (byte) type;
            f[1] = (byte) (p.length >> 8);
            f[2] = (byte) p.length;
            System.arraycopy(p, 0, f, 3, p.length);
            return f;
        }
    }

    /** Полученное сообщение. */
    static final class Msg {
        final int type;
        final byte[] data;
        private DataInputStream d;

        Msg(int type, byte[] data) { this.type = type; this.data = data; }

        DataInputStream in() {
            if (d == null) d = new DataInputStream(new ByteArrayInputStream(data));
            return d;
        }

        int u8() throws IOException { return in().readUnsignedByte(); }
        int s16() throws IOException { return in().readShort(); }
        int u16() throws IOException { return in().readUnsignedShort(); }
        int s32() throws IOException { return in().readInt(); }
        float pos() throws IOException { return uq(in().readShort()); }
        String utf() throws IOException { return in().readUTF(); }
    }

    static Msg read(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        int len = in.readUnsignedShort();
        byte[] data = new byte[len];
        in.readFully(data);
        return new Msg(type, data);
    }

    /** Ник: обрезаем пробелы, ограничиваем длину. */
    static String cleanNick(String s) {
        if (s == null) return "Игрок";
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < s.length() && b.length() < 14; i++) {
            char c = s.charAt(i);
            if (c >= ' ' && c != 127) b.append(c);
        }
        String r = b.toString().trim();
        return r.length() == 0 ? "Игрок" : r;
    }
}
