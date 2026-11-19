package org.example.server;

import java.util.UUID;

/** 접속 후 로비 스레드가 관리하는 사용자 정보. room이 null이면 로비에 있다. */
final class PlayerSession {
    final ClientConnection connection;
    final String nickname;
    final UUID token = UUID.randomUUID();
    Room room;
    boolean watchingRooms;

    PlayerSession(ClientConnection connection, String nickname) {
        this.connection = connection;
        this.nickname = nickname;
    }
}
