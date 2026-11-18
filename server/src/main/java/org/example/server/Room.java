package org.example.server;

import hockey.common.protocol.Message.RoomInfo;
import hockey.common.protocol.Message.RoomMember;
import hockey.common.protocol.Message.RoomUpdate;

import java.util.ArrayList;
import java.util.List;

/** 방의 상태는 로비 스레드에서만 변경한다. 클라이언트에는 불변 스냅샷을 보낸다. */
final class Room {
    private static final List<Integer> TARGET_SCORES = List.of(3, 5, 7, 10);

    final String code;
    PlayerSession host;
    PlayerSession guest;
    int targetScore;
    boolean isPublic;
    boolean guestReady;
    boolean started;

    Room(String code, PlayerSession host, int targetScore, boolean isPublic) {
        this.code = code;
        this.host = host;
        this.targetScore = targetScore;
        this.isPublic = isPublic;
    }

    static boolean validScore(int score) {
        return TARGET_SCORES.contains(score);
    }

    RoomUpdate snapshot() {
        List<RoomMember> members = new ArrayList<>();
        members.add(new RoomMember(host.nickname, true, false));
        if (guest != null) {
            members.add(new RoomMember(guest.nickname, false, guestReady));
        }
        return new RoomUpdate(code, targetScore, isPublic, started, members);
    }

    RoomInfo summary() {
        return new RoomInfo(code, host.nickname, targetScore, guest == null ? 1 : 2);
    }

    void broadcast() {
        RoomUpdate update = snapshot();
        host.connection.send(update);
        if (guest != null) {
            guest.connection.send(update);
        }
    }
}
