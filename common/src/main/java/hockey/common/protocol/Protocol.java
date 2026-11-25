package hockey.common.protocol;

/**
 * 프로토콜 상수. 서버(feat/server/connection)의 형식을 따른다.
 *
 * 명령은 writeUTF이며 이후 필드 순서와 자료형은 MessageCodec이 정의한다.
 * HELLO, WELCOME, HELLO_FAILED, QUIT은 기존 접속 형식을 유지한다.
 */
public final class Protocol {
    private Protocol() {}

    public static final int DEFAULT_PORT = 5000;

    // C→S
    public static final String HELLO = "HELLO";
    public static final String QUIT = "QUIT";
    public static final String CREATE_ROOM = "CREATE_ROOM";
    public static final String JOIN_ROOM = "JOIN_ROOM";
    public static final String LIST_ROOMS = "LIST_ROOMS";
    public static final String READY = "READY";
    public static final String SETTINGS = "SETTINGS";
    public static final String START = "START";
    public static final String LEAVE_ROOM = "LEAVE_ROOM";
    public static final String KICK = "KICK";
    public static final String INVITE = "INVITE";
    public static final String INVITE_REPLY = "INVITE_REPLY";
    public static final String INPUT = "INPUT";
    public static final String LEAVE_GAME = "LEAVE_GAME";

    // S→C
    public static final String WELCOME = "WELCOME";
    public static final String HELLO_FAILED = "HELLO_FAILED";
    public static final String ROOM_UPDATE = "ROOM_UPDATE";
    public static final String ROOM_LIST = "ROOM_LIST";
    public static final String ROOM_LEFT = "ROOM_LEFT";
    public static final String ROOM_FAILED = "ROOM_FAILED";
    public static final String GAME_START = "GAME_START";
    public static final String USER_LIST = "USER_LIST";
    public static final String INVITED = "INVITED";
    public static final String INVITE_RESULT = "INVITE_RESULT";
    public static final String INVITE_CANCELLED = "INVITE_CANCELLED";
    public static final String STATE = "STATE";
    public static final String GAME_OVER = "GAME_OVER";
}
