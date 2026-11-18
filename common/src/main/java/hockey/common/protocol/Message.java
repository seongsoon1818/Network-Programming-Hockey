package hockey.common.protocol;

import java.util.List;

/** 서버·클라이언트가 주고받는 메시지. 직렬화는 {@link MessageCodec}이 맡는다. */
public sealed interface Message {

    /** 클라이언트가 보내는 메시지. */
    sealed interface Request extends Message {}

    /** 서버가 보내는 메시지. */
    sealed interface Reply extends Message {}

    record Hello(String nickname) implements Request {}

    /** 의도적 종료. 서버는 이 메시지를 받으면 연결을 정리한다. */
    record Quit() implements Request {}

    record CreateRoom(int targetScore, boolean isPublic) implements Request {}
    record JoinRoom(String code) implements Request {}
    record ListRooms() implements Request {}
    record Ready(boolean ready) implements Request {}
    record Settings(int targetScore, boolean isPublic) implements Request {}
    record Start() implements Request {}
    record LeaveRoom() implements Request {}

    record RoomMember(String name, boolean host, boolean ready) {}
    record RoomInfo(String code, String hostName, int targetScore, int playerCount) {}

    record RoomUpdate(String code, int targetScore, boolean isPublic,
                      boolean started, List<RoomMember> members) implements Reply {
        public RoomUpdate {
            members = List.copyOf(members);
        }
    }

    record RoomList(List<RoomInfo> rooms) implements Reply {
        public RoomList {
            rooms = List.copyOf(rooms);
        }
    }

    record RoomLeft() implements Reply {}
    record JoinFailed(RoomFailReason reason) implements Reply {}
    record RoomFailed(String command, RoomFailReason reason) implements Reply {}

    /** 시작 승인. side는 방장 0, 게스트 1이다. 경기 루프는 다음 단계에서 연결한다. */
    record GameStart(String code, int side, int targetScore) implements Reply {}

    enum RoomFailReason {
        NOT_REGISTERED, ALREADY_IN_ROOM, NOT_IN_ROOM, NOT_FOUND, FULL,
        STARTED, HOST_ONLY, GUEST_ONLY, NOT_READY, INVALID_SETTINGS, UNKNOWN;

        public static RoomFailReason fromWire(String value) {
            try {
                return valueOf(value);
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }
    }

    /** 접속 성공. token은 서버가 발급한 UUID 문자열. */
    record Welcome(String token) implements Reply {}

    record HelloFailed(HelloFailReason reason) implements Reply {}

    /** 이 버전이 모르는 서버 명령. 받은 쪽은 무시한다. */
    record Unknown(String command, String value) implements Reply {}

    enum HelloFailReason {
        ALREADY_REGISTERED,
        INVALID_NICKNAME,
        DUPLICATE_NICKNAME,
        UNKNOWN;

        public static HelloFailReason fromWire(String value) {
            for (HelloFailReason reason : values()) {
                if (reason.name().equals(value)) {
                    return reason;
                }
            }
            return UNKNOWN;
        }
    }
}
