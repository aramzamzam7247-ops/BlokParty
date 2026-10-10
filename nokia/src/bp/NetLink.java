package bp;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Vector;

import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;

/** Соединение с хостом по Bluetooth. Кадр: u8 тип, u16 длина, данные. */
public class NetLink implements Runnable {
    private StreamConnection conn;
    private DataInputStream in;
    private DataOutputStream out;
    private final Vector inbox = new Vector();
    volatile boolean dead;

    public NetLink(String url) throws IOException {
        conn = (StreamConnection) Connector.open(url);
        in = conn.openDataInputStream();
        out = conn.openDataOutputStream();
        new Thread(this).start();
    }

    public void run() {
        try {
            while (!dead) {
                int type = in.readUnsignedByte();
                int len = in.readUnsignedShort();
                byte[] d = new byte[len + 1];
                d[0] = (byte) type;
                in.readFully(d, 1, len);
                synchronized (inbox) {
                    inbox.addElement(d);
                }
            }
        } catch (IOException e) {
            // связь потеряна
        }
        dead = true;
    }

    /** Следующее сообщение (первый байт — тип) или null. */
    public byte[] poll() {
        synchronized (inbox) {
            if (inbox.isEmpty()) return null;
            byte[] d = (byte[]) inbox.elementAt(0);
            inbox.removeElementAt(0);
            return d;
        }
    }

    public synchronized void send(byte[] frame) {
        if (dead) return;
        try {
            out.write(frame);
            out.flush();
        } catch (IOException e) {
            dead = true;
        }
    }

    public void close() {
        dead = true;
        try { in.close(); } catch (Exception e) { }
        try { out.close(); } catch (Exception e) { }
        try { conn.close(); } catch (Exception e) { }
    }

    /** Собрать кадр из полезной нагрузки. */
    public static byte[] frame(int type, ByteArrayOutputStream payload) {
        byte[] p = payload.toByteArray();
        byte[] f = new byte[p.length + 3];
        f[0] = (byte) type;
        f[1] = (byte) (p.length >> 8);
        f[2] = (byte) p.length;
        System.arraycopy(p, 0, f, 3, p.length);
        return f;
    }

    public static byte[] hello(String nick) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bo);
        try {
            d.writeByte(1);      // версия протокола
            d.writeByte(1);      // Nokia
            d.writeUTF(nick);
        } catch (IOException e) { }
        return frame(1, bo);
    }

    public static byte[] bye() {
        return frame(3, new ByteArrayOutputStream());
    }
}
