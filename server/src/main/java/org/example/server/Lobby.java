package org.example.server;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

final class Lobby implements Runnable {
    private final BlockingQueue<Runnable> events = new LinkedBlockingQueue<>();
    // 로비 스레드만 두 맵을 변경한다. 등록 전은 CONNECTED, 등록 후는 LOBBY 상태다.
    private final Map<ClientConnection, String> registeredNames = new HashMap<>();
    private final Map<String, UUID> tokensByName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    void postHello(ClientConnection client, String nickname) {
        events.add(() -> register(client, nickname));
    }

    void postDisconnect(ClientConnection client) {
        events.add(() -> unregister(client));
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                events.take().run();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void register(ClientConnection client, String rawName) {
        if (registeredNames.containsKey(client)) {
            client.send("HELLO_FAILED", "ALREADY_REGISTERED");
            return;
        }

        String nickname = rawName.strip();
        if (nickname.isEmpty()) {
            client.send("HELLO_FAILED", "INVALID_NICKNAME");
            return;
        }
        // 이 검사와 등록은 한 로비 이벤트 안에서 실행되어 동시 접속에도 한 명만 성공한다.
        if (tokensByName.containsKey(nickname)) {
            client.send("HELLO_FAILED", "DUPLICATE_NICKNAME");
            return;
        }

        UUID token = UUID.randomUUID();
        registeredNames.put(client, nickname);
        tokensByName.put(nickname, token);
        client.send("WELCOME", token.toString());
    }

    private void unregister(ClientConnection client) {
        String nickname = registeredNames.remove(client);
        if (nickname != null) {
            tokensByName.remove(nickname);
        }
    }
}
