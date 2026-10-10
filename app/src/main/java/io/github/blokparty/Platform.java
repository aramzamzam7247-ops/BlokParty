package io.github.blokparty;

/** То, что игра просит у Android (диалоги, Bluetooth). Ответы приходят через Game.post(...). */
interface Platform {
    /** Окно ввода ника. Результат: game.setNick(...). */
    void askNick(String current);

    /** Открыть комнату по Bluetooth: разрешения, включение, видимость. Результат: game.btRoom(ok, text). */
    void openBtRoom(NetServer server);

    void closeBtRoom();

    /** Выбрать хост (сопряжённый Bluetooth-телефон или IP по Wi-Fi) и подключиться.
     *  Результат: game.clientConnected(link) или game.connectFailed(text). */
    void chooseHost();
}
