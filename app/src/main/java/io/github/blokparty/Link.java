package io.github.blokparty;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedList;

/**
 * Одно соединение (Bluetooth или TCP — неважно, только потоки).
 * Читает в своём потоке, пишет в своём, чтобы игра никогда не ждала сеть.
 */
final class Link {
    interface Sink {
        void onMsg(Link l, Net.Msg m);
        void onLost(Link l);
    }

    private final DataInputStream in;
    private final OutputStream out;
    private final Runnable closer;
    private Sink sink;

    // данные хоста о клиенте
    int id;
    String nick = "?";
    int kind;
    boolean hello;
    Player player;

    private final Object lock = new Object();
    private final LinkedList<byte[]> queue = new LinkedList<byte[]>();
    private byte[] latest;
    private volatile boolean dead;

    Link(InputStream in, OutputStream out, Runnable closer) {
        this.in = new DataInputStream(new BufferedInputStream(in, 2048));
        this.out = out;
        this.closer = closer;
    }

    void start(Sink s) {
        sink = s;
        Thread r = new Thread(new Runnable() {
            public void run() { readLoop(); }
        }, "link-read");
        r.setDaemon(true);
        r.start();
        Thread w = new Thread(new Runnable() {
            public void run() { writeLoop(); }
        }, "link-write");
        w.setDaemon(true);
        w.start();
    }

    private void readLoop() {
        try {
            while (!dead) {
                Net.Msg m = Net.read(in);
                sink.onMsg(this, m);
            }
        } catch (IOException e) {
            // соединение закрыто
        } catch (RuntimeException e) {
            // повреждённые данные
        } finally {
            kill();
        }
    }

    private void writeLoop() {
        try {
            while (true) {
                byte[] f;
                synchronized (lock) {
                    while (queue.isEmpty() && latest == null && !dead) lock.wait();
                    if (dead) break;
                    if (!queue.isEmpty()) {
                        f = queue.removeFirst();
                    } else {
                        f = latest;
                        latest = null;
                    }
                }
                out.write(f);
                out.flush();
            }
        } catch (Exception e) {
            // ошибка записи
        } finally {
            kill();
        }
    }

    /** Надёжное сообщение (по очереди). */
    void send(byte[] frame) {
        synchronized (lock) {
            if (queue.size() < 300) queue.add(frame);
            lock.notifyAll();
        }
    }

    /** Только самое свежее (снимки мира, позиция игрока). */
    void sendLatest(byte[] frame) {
        synchronized (lock) {
            latest = frame;
            lock.notifyAll();
        }
    }

    boolean isDead() { return dead; }

    void close() { kill(); }

    private void kill() {
        boolean was;
        synchronized (lock) {
            was = dead;
            dead = true;
            lock.notifyAll();
        }
        if (!was) {
            try { closer.run(); } catch (Exception e) { /* уже закрыто */ }
            if (sink != null) sink.onLost(this);
        }
    }

    /** Простой приёмник для клиента: всё складывается в очередь и забирается игровым потоком. */
    static final class Inbox implements Sink {
        private final LinkedList<Net.Msg> q = new LinkedList<Net.Msg>();
        private volatile boolean lost;

        public void onMsg(Link l, Net.Msg m) {
            synchronized (q) { q.add(m); }
        }

        public void onLost(Link l) { lost = true; }

        boolean lost() { return lost; }

        void drain(ArrayList<Net.Msg> out) {
            synchronized (q) {
                out.addAll(q);
                q.clear();
            }
        }
    }
}
