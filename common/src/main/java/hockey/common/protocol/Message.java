package hockey.common.protocol;

/** 서버·클라이언트가 주고받는 메시지. 직렬화는 {@link MessageCodec}이 맡는다. */
public sealed interface Message {

    /** 클라이언트가 보내는 메시지. */
    sealed interface Request extends Message {}

    /** 서버가 보내는 메시지. */
    sealed interface Reply extends Message {}

    record Hello(String nickname) implements Request {}

    /** 의도적 종료. 서버는 이 메시지를 받으면 연결을 정리한다. */
    record Quit() implements Request {}

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
