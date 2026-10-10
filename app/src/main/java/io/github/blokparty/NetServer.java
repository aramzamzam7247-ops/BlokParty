package io.github.blokparty;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedList;

/** Хост: список подключённых клиентов и очередь событий для игрового потока. */
final class NetServer implements Link.Sink {
    static final int MAX_CLIENTS = 7;

    private final ArrayList<Link> links = new ArrayList<Link>();
    private final LinkedList<Object[]> events = new LinkedList<Object[]>();

    /** Принять новое соединение (вызывается из потоков Bluetooth/TCP). */
    Link accept(InputStream in, OutputStream out, Runnable closer) {
        Link l = new Link(in, out, closer);
        synchronized (this) {
            if (links.size() >= MAX_CLIENTS) {
                try { closer.run(); } catch (Exception e) { /* ignore */ }
                return null;
            }
            l.id = freeId();
            links.add(l);
        }
        l.start(this);
        return l;
    }

    private int freeId() {
        for (int id = 1; id < 100; id++) {
            boolean used = false;
            for (int i = 0; i < links.size(); i++) if (links.get(i).id == id) { used = true; break; }
            if (!used) return id;
        }
        return 99;
    }

    public void onMsg(Link l, Net.Msg m) {
        synchronized (events) { events.add(new Object[]{l, m}); }
    }

    public void onLost(Link l) {
        synchronized (this) { links.remove(l); }
        synchronized (events) { events.add(new Object[]{l, null}); }
    }

    void drain(ArrayList<Object[]> out) {
        synchronized (events) {
            out.addAll(events);
            events.clear();
        }
    }

    synchronized Link[] links() {
        return links.toArray(new Link[links.size()]);
    }

    synchronized int count() {
        int n = 0;
        for (int i = 0; i < links.size(); i++) if (links.get(i).hello) n++;
        return n;
    }

    void broadcast(byte[] frame) {
        Link[] ls = links();
        for (int i = 0; i < ls.length; i++) if (ls[i].hello) ls[i].send(frame);
    }

    void closeAll() {
        Link[] ls = links();
        for (int i = 0; i < ls.length; i++) ls[i].close();
    }
}
