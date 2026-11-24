package hockey.common.protocol;

import hockey.common.rules.MatchSnapshot;

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

    /** 방장만, 게임 전. 게스트를 방에서 내보낸다. */
    record Kick(String name) implements Request {}

    /** 로비 사람 또는 빈자리가 있는 방의 방장이 보낸다. 로비에서 보내면 서버가 비공개 방을 만든다. */
    record Invite(String name) implements Request {}

    /** 초대받은 사람의 응답. */
    record InviteReply(String inviter, boolean accept) implements Request {}

    /** 게임 중 매 틱 내 말렛 목표 위치. 번호는 0부터 1씩 는다. */
    record Input(int seq, double x, double y) implements Request {}

    /** 게임 중 나가기(기권). */
    record LeaveGame() implements Request {}

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
    /**
     * 입장 실패. 확정된 형식에서는 ROOM_FAILED(JOIN_ROOM, 사유)로 합쳤다.
     * 서버가 ROOM_FAILED로 바꿀 때까지 남겨 두고, 클라이언트는 둘 다 같은 안내로 처리한다.
     */
    record JoinFailed(RoomFailReason reason) implements Reply {}
    record RoomFailed(String command, RoomFailReason reason) implements Reply {}

    /** 시작 승인. side는 방장 0, 게스트 1이다. 이어서 매 틱 STATE가 온다. */
    record GameStart(String code, int side, int targetScore) implements Reply {}

    /** 로비에 있는(방에 없는) 사람 목록. */
    record UserList(List<String> names) implements Reply {
        public UserList {
            names = List.copyOf(names);
        }
    }

    /** 초대를 받았다. secondsLeft초 안에 INVITE_REPLY로 답한다. */
    record Invited(String inviter, int secondsLeft) implements Reply {}

    /** 내가 보낸 초대의 결과. */
    record InviteResult(String name, InviteOutcome outcome) implements Reply {}

    /** 받은 초대가 없어졌다(시간 초과, 초대한 사람이 나감). */
    record InviteCancelled(String inviter) implements Reply {}

    /**
     * 게임 중 매 틱 경기 상태.
     *
     * @param ackSeq 이 상태를 만들 때 쓴, 받는 사람의 마지막 INPUT 번호. 아직 없으면 -1.
     */
    record State(int ackSeq, MatchSnapshot snapshot) implements Reply {}

    /** 게임이 끝났다. 이어서 ROOM_UPDATE(시작됨 = false)가 온다. */
    record GameOver(int winner, int score0, int score1, GameOverReason reason) implements Reply {}

    enum InviteOutcome {
        ACCEPTED, DECLINED, EXPIRED, UNKNOWN;

        public static InviteOutcome fromWire(String value) {
            try {
                return valueOf(value);
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }
    }

    enum GameOverReason {
        NORMAL, FORFEIT, UNKNOWN;

        public static GameOverReason fromWire(String value) {
            try {
                return valueOf(value);
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }
    }

    enum RoomFailReason {
        NOT_REGISTERED, ALREADY_IN_ROOM, NOT_IN_ROOM, NOT_FOUND, FULL,
        STARTED, HOST_ONLY, GUEST_ONLY, NOT_READY, INVALID_SETTINGS,
        NO_SUCH_USER, USER_BUSY, NO_SUCH_INVITE, UNKNOWN;

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
