package io.github.blokparty;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Enumeration;

/** Приём подключений по Wi-Fi (TCP) — для ПК и второго Android, если Bluetooth неудобен. */
final class TcpHost implements Runnable {
    private final NetServer srv;
    private ServerSocket ss;

    TcpHost(NetServer srv) { this.srv = srv; }

    synchronized boolean start() {
        if (ss != null) return true;
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(Net.TCP_PORT));
            ss = s;
        } catch (IOException e) {
            return false;
        }
        Thread t = new Thread(this, "tcp-accept");
        t.setDaemon(true);
        t.start();
        return true;
    }

    public void run() {
        ServerSocket s = ss;
        while (s != null) {
            final Socket c;
            try {
                c = s.accept();
            } catch (IOException e) {
                return;
            }
            try {
                c.setTcpNoDelay(true);
                srv.accept(c.getInputStream(), c.getOutputStream(), closer(c));
            } catch (IOException e) {
                closer(c).run();
            }
        }
    }

    synchronized void stop() {
        if (ss != null) {
            try { ss.close(); } catch (IOException e) { /* ignore */ }
            ss = null;
        }
    }

    static Runnable closer(final Socket c) {
        return new Runnable() {
            public void run() {
                try { c.close(); } catch (IOException e) { /* ignore */ }
            }
        };
    }

    /** Подключиться к хосту по Wi-Fi. */
    static Link connect(String host) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host.trim(), Net.TCP_PORT), 6000);
        s.setTcpNoDelay(true);
        return new Link(s.getInputStream(), s.getOutputStream(), closer(s));
    }

    /** IP-адрес телефона в локальной сети (чтобы показать его в меню). */
    static String localIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception e) {
            // нет сети
        }
        return null;
    }
}
