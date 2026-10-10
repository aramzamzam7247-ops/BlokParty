package io.github.blokparty;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

public class MainActivity extends Activity implements Platform {

    private static final int REQ_PERM = 1, REQ_ENABLE = 2, REQ_VISIBLE = 3;
    private static final UUID BT_UUID = UUID.fromString(Net.UUID_STR);

    private GLSurfaceView view;
    private Game game;
    private Input input;
    private SharedPreferences prefs;

    private BluetoothAdapter bt;
    private volatile BluetoothServerSocket btServer;
    private Runnable afterReady;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = getSharedPreferences("blokparty", Context.MODE_PRIVATE);
        input = new Input();
        game = new Game(input, prefs);
        game.platform = this;
        bt = BluetoothAdapter.getDefaultAdapter();

        view = new GameView(this, input);
        view.setEGLContextClientVersion(2);
        view.setEGLConfigChooser(new LightConfigChooser());
        view.setPreserveEGLContextOnPause(true);
        view.setRenderer(new GameRenderer(game, input));
        setContentView(view);

        if (game.needsNick()) askNick("");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && Build.VERSION.SDK_INT >= 19) {
            view.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        game.shutdown();
        closeBtRoom();
    }

    @Override
    public void onBackPressed() {
        if (game.state == Game.MENU) super.onBackPressed();
        else input.pressBack();
    }

    // ===================== Ник =====================

    public void askNick(final String current) {
        runOnUiThread(new Runnable() {
            public void run() {
                final EditText e = new EditText(MainActivity.this);
                e.setSingleLine(true);
                e.setInputType(InputType.TYPE_CLASS_TEXT);
                e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(14)});
                e.setText(current);
                e.setSelection(e.getText().length());
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Твой ник")
                        .setMessage("Его увидят все игроки над твоим персонажем")
                        .setView(e)
                        .setCancelable(current.length() > 0)
                        .setPositiveButton("Готово", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                final String nick = e.getText().toString();
                                game.post(new Runnable() {
                                    public void run() { game.setNick(nick); }
                                });
                            }
                        })
                        .show();
            }
        });
    }

    // ===================== Bluetooth: разрешения =====================

    private String[] btPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{"android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE"};
        }
        return new String[0];
    }

    /** Разрешения + включённый Bluetooth, потом выполнить action (в UI-потоке). */
    private void withBluetooth(Runnable action) {
        if (bt == null) {
            post(false, "На телефоне нет Bluetooth");
            return;
        }
        afterReady = action;
        if (Build.VERSION.SDK_INT >= 23) {
            ArrayList<String> need = new ArrayList<String>();
            for (String p : btPermissions()) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
            }
            if (!need.isEmpty()) {
                requestPermissions(need.toArray(new String[need.size()]), REQ_PERM);
                return;
            }
        }
        ensureEnabled();
    }

    private void ensureEnabled() {
        try {
            if (!bt.isEnabled()) {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE);
                return;
            }
        } catch (SecurityException e) {
            post(false, "Нет разрешения на Bluetooth");
            return;
        }
        runReady();
    }

    private void runReady() {
        Runnable r = afterReady;
        afterReady = null;
        if (r != null) r.run();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERM) return;
        for (int r : results) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                afterReady = null;
                post(false, "Без разрешения Bluetooth работает только Wi-Fi");
                return;
            }
        }
        ensureEnabled();
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code == REQ_ENABLE) {
            boolean on;
            try { on = bt.isEnabled(); } catch (SecurityException e) { on = false; }
            if (on) runReady();
            else { afterReady = null; post(false, "Bluetooth не включён"); }
        }
    }

    private void post(final boolean ok, final String text) {
        game.post(new Runnable() {
            public void run() { game.btRoom(ok, text); }
        });
    }

    // ===================== Комната (хост) =====================

    public void openBtRoom(final NetServer server) {
        runOnUiThread(new Runnable() {
            public void run() {
                withBluetooth(new Runnable() {
                    public void run() { startBtServer(server); }
                });
            }
        });
    }

    private void startBtServer(final NetServer server) {
        if (btServer != null) return;
        try {
            btServer = bt.listenUsingInsecureRfcommWithServiceRecord("BlokParty", BT_UUID);
        } catch (IOException e) {
            post(false, "Bluetooth: не удалось открыть");
            return;
        } catch (SecurityException e) {
            post(false, "Нет разрешения на Bluetooth");
            return;
        }
        final BluetoothServerSocket ss = btServer;
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (btServer == ss) {
                    final BluetoothSocket s;
                    try {
                        s = ss.accept();
                    } catch (IOException e) {
                        return;
                    }
                    try {
                        server.accept(s.getInputStream(), s.getOutputStream(), btCloser(s));
                    } catch (IOException e) {
                        btCloser(s).run();
                    }
                }
            }
        }, "bt-accept");
        t.setDaemon(true);
        t.start();
        post(true, "Bluetooth: ждём игроков");
        // чтобы Nokia и ПК нашли телефон без сопряжения — делаем его видимым на 5 минут
        try {
            Intent vis = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            vis.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivityForResult(vis, REQ_VISIBLE);
        } catch (Exception e) {
            // не страшно: сопряжённые устройства подключатся и так
        }
    }

    public void closeBtRoom() {
        BluetoothServerSocket ss = btServer;
        btServer = null;
        if (ss != null) {
            try { ss.close(); } catch (IOException e) { /* ignore */ }
        }
    }

    private static Runnable btCloser(final BluetoothSocket s) {
        return new Runnable() {
            public void run() {
                try { s.close(); } catch (IOException e) { /* ignore */ }
            }
        };
    }

    // ===================== Подключение (гость) =====================

    public void chooseHost() {
        runOnUiThread(new Runnable() {
            public void run() {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Подключиться к другу")
                        .setItems(new String[]{"По Bluetooth", "По Wi-Fi (ввести IP)"}, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                if (which == 0) {
                                    withBluetooth(new Runnable() {
                                        public void run() { pickBtDevice(); }
                                    });
                                } else {
                                    askIp();
                                }
                            }
                        })
                        .show();
            }
        });
    }

    private void pickBtDevice() {
        final ArrayList<BluetoothDevice> list = new ArrayList<BluetoothDevice>();
        ArrayList<String> names = new ArrayList<String>();
        try {
            Set<BluetoothDevice> bonded = bt.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    list.add(d);
                    String n = d.getName();
                    names.add(n == null ? d.getAddress() : n);
                }
            }
        } catch (SecurityException e) {
            post(false, "Нет разрешения на Bluetooth");
            return;
        }
        if (list.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Нет сопряжённых устройств")
                    .setMessage("Сначала соедини телефоны в настройках Bluetooth (сопряжение), потом попробуй снова.")
                    .setPositiveButton("Понятно", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Телефон с открытой комнатой")
                .setItems(names.toArray(new String[names.size()]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { connectBt(list.get(which)); }
                })
                .show();
    }

    private void connectBt(final BluetoothDevice dev) {
        game.post(new Runnable() {
            public void run() { game.setConnecting(); }
        });
        new Thread(new Runnable() {
            public void run() {
                BluetoothSocket s = null;
                try {
                    try { bt.cancelDiscovery(); } catch (SecurityException e) { /* ignore */ }
                    try {
                        s = dev.createInsecureRfcommSocketToServiceRecord(BT_UUID);
                        s.connect();
                    } catch (IOException first) {
                        if (s != null) try { s.close(); } catch (IOException e) { /* ignore */ }
                        s = dev.createRfcommSocketToServiceRecord(BT_UUID);
                        s.connect();
                    }
                    final Link l = new Link(s.getInputStream(), s.getOutputStream(), btCloser(s));
                    game.post(new Runnable() {
                        public void run() { game.clientConnected(l); }
                    });
                } catch (Exception e) {
                    if (s != null) try { s.close(); } catch (IOException x) { /* ignore */ }
                    failed("Не удалось подключиться. На том телефоне открыта комната?");
                }
            }
        }, "bt-connect").start();
    }

    private void askIp() {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setHint("192.168.1.5");
        e.setText(prefs.getString("lastIp", ""));
        new AlertDialog.Builder(this)
                .setTitle("IP хоста")
                .setMessage("Его видно в меню хоста под ником, когда комната открыта")
                .setView(e)
                .setPositiveButton("Подключиться", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        final String ip = e.getText().toString().trim();
                        prefs.edit().putString("lastIp", ip).apply();
                        game.post(new Runnable() {
                            public void run() { game.setConnecting(); }
                        });
                        new Thread(new Runnable() {
                            public void run() {
                                try {
                                    final Link l = TcpHost.connect(ip);
                                    game.post(new Runnable() {
                                        public void run() { game.clientConnected(l); }
                                    });
                                } catch (Exception ex) {
                                    failed("Не удалось подключиться к " + ip);
                                }
                            }
                        }, "tcp-connect").start();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void failed(final String text) {
        game.post(new Runnable() {
            public void run() { game.connectFailed(text); }
        });
    }

    // ===================== Экран =====================

    static final class GameView extends GLSurfaceView {
        private final Input input;

        GameView(Context c, Input input) {
            super(c);
            this.input = input;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            input.onTouch(e);
            return true;
        }
    }

    static final class LightConfigChooser implements GLSurfaceView.EGLConfigChooser {
        private static final int EGL_OPENGL_ES2_BIT = 4;

        @Override
        public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
            int[] attribs = {
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL10.EGL_DEPTH_SIZE, 16,
                    EGL10.EGL_NONE
            };
            int[] num = new int[1];
            egl.eglChooseConfig(display, attribs, null, 0, num);
            if (num[0] <= 0) throw new IllegalArgumentException("Нет подходящей конфигурации OpenGL ES 2.0");
            EGLConfig[] configs = new EGLConfig[num[0]];
            egl.eglChooseConfig(display, attribs, configs, num[0], num);

            EGLConfig best = null;
            int bestScore = Integer.MIN_VALUE;
            int[] v = new int[1];
            for (EGLConfig c : configs) {
                int r = get(egl, display, c, EGL10.EGL_RED_SIZE, v);
                int g = get(egl, display, c, EGL10.EGL_GREEN_SIZE, v);
                int b = get(egl, display, c, EGL10.EGL_BLUE_SIZE, v);
                int a = get(egl, display, c, EGL10.EGL_ALPHA_SIZE, v);
                int d = get(egl, display, c, EGL10.EGL_DEPTH_SIZE, v);
                int samples = get(egl, display, c, EGL10.EGL_SAMPLES, v);
                if (r < 5 || g < 6 || b < 5 || d < 16) continue;
                int score = 0;
                if (r == 5 && g == 6 && b == 5) score += 100;
                if (a == 0) score += 20;
                if (d == 16) score += 10;
                if (samples > 0) score -= 200;
                if (score > bestScore) { bestScore = score; best = c; }
            }
            return best != null ? best : configs[0];
        }

        private static int get(EGL10 egl, EGLDisplay d, EGLConfig c, int attr, int[] v) {
            return egl.eglGetConfigAttrib(d, c, attr, v) ? v[0] : 0;
        }
    }
}
