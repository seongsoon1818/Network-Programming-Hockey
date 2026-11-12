package hockey.common.protocol;

/**
 * 프로토콜 상수. 서버(feat/server/connection)의 형식을 따른다.
 *
 * 모든 필드는 DataOutputStream.writeUTF 문자열이다.
 *   - C→S: 명령 + 명령별 인자   (HELLO 닉네임 / QUIT)
 *   - S→C: 명령 + 값, 항상 두 필드 (WELCOME 토큰 / HELLO_FAILED 사유)
 */
public final class Protocol {
    private Protocol() {}

    public static final int DEFAULT_PORT = 5000;

    // C→S
    public static final String HELLO = "HELLO";
    public static final String QUIT = "QUIT";

    // S→C
    public static final String WELCOME = "WELCOME";
    public static final String HELLO_FAILED = "HELLO_FAILED";
}
