package bp;

import java.util.Vector;

import javax.bluetooth.DeviceClass;
import javax.bluetooth.DiscoveryAgent;
import javax.bluetooth.DiscoveryListener;
import javax.bluetooth.LocalDevice;
import javax.bluetooth.RemoteDevice;
import javax.bluetooth.ServiceRecord;
import javax.bluetooth.UUID;
import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Gauge;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;
import javax.microedition.rms.RecordStore;

/** Блок Пати для Nokia: ник, поиск хоста по Bluetooth, подключение. */
public class BlokMIDlet extends MIDlet implements CommandListener, DiscoveryListener {
    static final String UUID_HEX = "6b0f3a528d1e4c1b9e571b10c0a7b1a7";

    private Display display;
    private String nick = "";

    private List menu, devList;
    private TextBox nickBox;
    private Form waitForm;

    private final Command cOk = new Command("ОК", Command.OK, 1);
    private final Command cBack = new Command("Назад", Command.BACK, 2);
    private final Command cExit = new Command("Выход", Command.EXIT, 2);
    private final Command cAgain = new Command("Искать ещё", Command.SCREEN, 3);

    private DiscoveryAgent agent;
    private final Vector devices = new Vector();
    private final Vector names = new Vector();
    private volatile String serviceUrl;
    private volatile boolean searching;
    private Play play;

    protected void startApp() {
        if (display != null) return;
        display = Display.getDisplay(this);
        nick = loadNick();
        menu = new List("Блок Пати", List.IMPLICIT);
        menu.append("Играть по Bluetooth", null);
        menu.append("Сменить ник", null);
        menu.append("Выход", null);
        menu.setCommandListener(this);
        if (nick.length() == 0) showNick();
        else display.setCurrent(menu);
    }

    protected void pauseApp() { }

    protected void destroyApp(boolean unconditional) { }

    // ===================== Ник =====================

    private void showNick() {
        nickBox = new TextBox("Твой ник", nick, 14, TextField.ANY);
        nickBox.addCommand(cOk);
        nickBox.setCommandListener(this);
        display.setCurrent(nickBox);
    }

    private String loadNick() {
        try {
            RecordStore rs = RecordStore.openRecordStore("bpnick", true);
            String s = "";
            if (rs.getNumRecords() > 0) {
                byte[] b = rs.getRecord(1);
                if (b != null) s = new String(b, "UTF-8");
            }
            rs.closeRecordStore();
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    private void saveNick(String s) {
        try {
            RecordStore rs = RecordStore.openRecordStore("bpnick", true);
            byte[] b = s.getBytes("UTF-8");
            if (rs.getNumRecords() > 0) rs.setRecord(1, b, 0, b.length);
            else rs.addRecord(b, 0, b.length);
            rs.closeRecordStore();
        } catch (Exception e) { }
    }

    // ===================== Команды =====================

    public void commandAction(Command c, Displayable d) {
        if (d == nickBox) {
            String s = nickBox.getString().trim();
            if (s.length() == 0) s = "Игрок";
            nick = s;
            saveNick(s);
            display.setCurrent(menu);
        } else if (d == menu) {
            int i = menu.getSelectedIndex();
            if (c == cExit || i == 2) {
                notifyDestroyed();
            } else if (i == 1) {
                showNick();
            } else {
                startSearch();
            }
        } else if (d == devList) {
            if (c == cBack) {
                stopSearch();
                display.setCurrent(menu);
            } else if (c == cAgain) {
                startSearch();
            } else {
                int i = devList.getSelectedIndex();
                if (i >= 0 && i < devices.size()) connectTo((RemoteDevice) devices.elementAt(i));
            }
        } else if (d == waitForm && c == cBack) {
            stopSearch();
            display.setCurrent(menu);
        }
    }

    private void showWait(String text) {
        waitForm = new Form("Блок Пати");
        waitForm.append(text);
        waitForm.append(new Gauge(null, false, Gauge.INDEFINITE, Gauge.CONTINUOUS_RUNNING));
        waitForm.addCommand(cBack);
        waitForm.setCommandListener(this);
        display.setCurrent(waitForm);
    }

    void backToMenu(String why) {
        play = null;
        Alert a = new Alert("Блок Пати", why, null, AlertType.INFO);
        a.setTimeout(2500);
        display.setCurrent(a, menu);
    }

    private void error(String text) {
        Alert a = new Alert("Ошибка", text, null, AlertType.ERROR);
        a.setTimeout(Alert.FOREVER);
        display.setCurrent(a, devList != null ? (Displayable) devList : (Displayable) menu);
    }

    // ===================== Поиск по Bluetooth =====================

    private void startSearch() {
        devices.removeAllElements();
        names.removeAllElements();
        devList = new List("Где открыта комната?", List.IMPLICIT);
        devList.addCommand(cBack);
        devList.addCommand(cAgain);
        devList.setCommandListener(this);
        try {
            agent = LocalDevice.getLocalDevice().getDiscoveryAgent();
            addKnown(agent.retrieveDevices(DiscoveryAgent.PREKNOWN));
            addKnown(agent.retrieveDevices(DiscoveryAgent.CACHED));
            searching = true;
            agent.startInquiry(DiscoveryAgent.GIAC, this);
            showWait("Ищем телефоны рядом... На хосте должна быть открыта комната.");
        } catch (Exception e) {
            searching = false;
            if (devices.size() > 0) showList();
            else error("Не удалось включить поиск Bluetooth. Включи Bluetooth в настройках телефона.");
        }
    }

    private void addKnown(RemoteDevice[] list) {
        if (list == null) return;
        for (int i = 0; i < list.length; i++) addDevice(list[i]);
    }

    private void addDevice(RemoteDevice d) {
        for (int i = 0; i < devices.size(); i++) {
            if (((RemoteDevice) devices.elementAt(i)).getBluetoothAddress().equals(d.getBluetoothAddress())) return;
        }
        devices.addElement(d);
        names.addElement(d.getBluetoothAddress());
    }

    private void stopSearch() {
        if (searching && agent != null) {
            try { agent.cancelInquiry(this); } catch (Exception e) { }
        }
        searching = false;
    }

    public void deviceDiscovered(RemoteDevice d, DeviceClass c) {
        addDevice(d);
    }

    public void inquiryCompleted(int type) {
        searching = false;
        // имена спрашиваем после поиска — во время поиска некоторые телефоны не отвечают
        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < devices.size(); i++) {
                    try {
                        String n = ((RemoteDevice) devices.elementAt(i)).getFriendlyName(false);
                        if (n != null && n.length() > 0) names.setElementAt(n, i);
                    } catch (Exception e) { }
                }
                showList();
            }
        }).start();
    }

    private void showList() {
        devList.deleteAll();
        for (int i = 0; i < names.size(); i++) devList.append((String) names.elementAt(i), null);
        if (devices.size() == 0) devList.setTitle("Никого не нашли");
        display.setCurrent(devList);
    }

    private void connectTo(final RemoteDevice dev) {
        stopSearch();
        showWait("Подключаемся...");
        serviceUrl = null;
        try {
            UUID[] uuids = {new UUID(UUID_HEX, false)};
            agent.searchServices(null, uuids, dev, this);
        } catch (Exception e) {
            error("Не удалось связаться с этим телефоном.");
        }
    }

    public void servicesDiscovered(int transId, ServiceRecord[] recs) {
        if (recs != null && recs.length > 0 && serviceUrl == null) {
            serviceUrl = recs[0].getConnectionURL(ServiceRecord.NOAUTHENTICATE_NOENCRYPT, false);
        }
    }

    public void serviceSearchCompleted(int transId, int respCode) {
        final String url = serviceUrl;
        if (url == null) {
            error("На этом телефоне нет открытой комнаты Блок Пати.");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    NetLink link = new NetLink(url);
                    play = new Play(BlokMIDlet.this, link, nick);
                    display.setCurrent(play);
                } catch (Exception e) {
                    error("Не удалось подключиться.");
                }
            }
        }).start();
    }
}
