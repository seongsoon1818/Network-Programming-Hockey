package hockey.common.protocol;

import hockey.common.protocol.Message.*;
import hockey.common.rules.MatchEngine;
import hockey.common.rules.MatchEngine.Phase;
import hockey.common.rules.MatchSnapshot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.List;
import java.util.Random;

/**
 * 모든 메시지를 쓰고 다시 읽어 같은 값이 나오는지, 그리고 STATE의 바이트 배치가 형식 문서와 같은지 확인한다.
 * main을 실행하면 실패 시 예외로 끝난다.
 */
public final class MessageCodecSelfTest {

    public static void main(String[] args) throws IOException {
        requestsRoundTrip();
        repliesRoundTrip();
        stateLayout();
        unknownValues();
        System.out.println("MessageCodecSelfTest: 모두 통과");
    }

    private static void requestsRoundTrip() throws IOException {
        List<Request> requests = List.of(
                new Hello("chan"), new Quit(), new CreateRoom(5, false), new JoinRoom("K7Q2M"), new ListRooms(),
                new Ready(true), new Settings(7, true), new Start(), new LeaveRoom(),
                new Kick("bob"), new Invite("밥_2"), new InviteReply("chan", true), new InviteReply("chan", false),
                new Input(0, 300.0, 900.0), new Input(Integer.MAX_VALUE, 0.25, 999.75), new LeaveGame());
        for (Request request : requests) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            MessageCodec.writeRequest(new DataOutputStream(bytes), request);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
            check(MessageCodec.readRequest(in).equals(request), "요청 왕복: " + request);
            check(in.available() == 0, "남는 바이트 없음: " + request);
        }
    }

    private static void repliesRoundTrip() throws IOException {
        MatchEngine engine = new MatchEngine(5, new Random(1));
        for (int i = 0; i < 400; i++) {
            engine.tick(300 + i % 50, 850, 300 - i % 40, 150);
        }
        List<Reply> replies = List.of(
                new Welcome("token"), new HelloFailed(HelloFailReason.INVALID_NICKNAME),
                new RoomUpdate("K7Q2M", 5, false, true,
                        List.of(new RoomMember("chan", true, false), new RoomMember("bob", false, true))),
                new RoomList(List.of(new RoomInfo("K7Q2M", "chan", 5, 1))), new RoomLeft(),
                new RoomFailed(Protocol.JOIN_ROOM, RoomFailReason.FULL),
                new RoomFailed(Protocol.INVITE, RoomFailReason.USER_BUSY),
                new RoomFailed(Protocol.INVITE_REPLY, RoomFailReason.NO_SUCH_INVITE),
                new RoomFailed(Protocol.KICK, RoomFailReason.NO_SUCH_USER),
                new GameStart("K7Q2M", 1, 5),
                new UserList(List.of()), new UserList(List.of("chan", "bob", "하키왕")),
                new Invited("chan", 15),
                new InviteResult("bob", InviteOutcome.ACCEPTED), new InviteResult("bob", InviteOutcome.DECLINED),
                new InviteResult("bob", InviteOutcome.EXPIRED), new InviteCancelled("chan"),
                new State(-1, new MatchEngine(3, new Random(2)).snapshot()), new State(399, engine.snapshot()),
                new GameOver(0, 5, 3, GameOverReason.NORMAL), new GameOver(1, 2, 4, GameOverReason.FORFEIT));
        for (Reply reply : replies) {
            byte[] bytes = write(reply);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            check(MessageCodec.readReply(in).equals(reply), "응답 왕복: " + reply);
            check(in.available() == 0, "남는 바이트 없음: " + reply);
        }
    }

    /** STATE는 명령, int 2개, UTF 단계, int 5개, double 8개, int 2개 순서다(형식 문서 'STATE 필드' 시트). */
    private static void stateLayout() throws IOException {
        MatchSnapshot s = new MatchSnapshot(42, Phase.GOAL, 90, 1, 0, 1, 0,
                300, 250, 0, 0, 300, 900, 310, 100, -1, 600);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(write(new State(17, s))));
        check(in.readUTF().equals("STATE"), "명령");
        check(in.readInt() == 42, "1 틱");
        check(in.readInt() == 17, "2 처리한 입력 번호");
        check(in.readUTF().equals("GOAL"), "3 단계");
        int[] ints = {90, 1, 0, 1, 0};
        for (int i = 0; i < ints.length; i++) {
            check(in.readInt() == ints[i], (i + 4) + "번 필드");
        }
        double[] doubles = {300, 250, 0, 0, 300, 900, 310, 100};
        for (int i = 0; i < doubles.length; i++) {
            check(in.readDouble() == doubles[i], (i + 9) + "번 필드");
        }
        check(in.readInt() == -1, "17 붙잡은 쪽");
        check(in.readInt() == 600, "18 붙잡기 남은 틱");
        check(in.available() == 0, "18개 필드 뒤에 남는 바이트 없음");
    }

    private static void unknownValues() throws IOException {
        // 모르는 사유·결과 문자열은 UNKNOWN으로 읽는다(필드 길이는 같으므로 뒤 메시지가 어긋나지 않는다).
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF(Protocol.INVITE_RESULT);
        out.writeUTF("bob");
        out.writeUTF("LATER");
        out.writeUTF(Protocol.GAME_OVER);
        out.writeInt(0);
        out.writeInt(1);
        out.writeInt(0);
        out.writeUTF("DRAW");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        check(MessageCodec.readReply(in).equals(new InviteResult("bob", InviteOutcome.UNKNOWN)), "모르는 초대 결과");
        check(MessageCodec.readReply(in).equals(new GameOver(0, 1, 0, GameOverReason.UNKNOWN)), "모르는 종료 사유");

        // 모르는 경기 단계는 화면에 띄울 수 없으므로 프로토콜 오류다.
        byte[] state = write(new State(0, new MatchEngine(3, new Random(3)).snapshot()));
        byte[] tampered = replace(state, "COUNTDOWN", "OVERTIME!");
        try {
            MessageCodec.readReply(new DataInputStream(new ByteArrayInputStream(tampered)));
            throw new AssertionError("모르는 단계는 예외여야 한다");
        } catch (ProtocolException expected) {
        }
    }

    private static byte[] write(Reply reply) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MessageCodec.writeReply(new DataOutputStream(bytes), reply);
        return bytes.toByteArray();
    }

    /** 같은 길이의 ASCII 문자열을 바꿔 넣는다. */
    private static byte[] replace(byte[] data, String from, String to) {
        byte[] a = from.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] b = to.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + a.length <= data.length; i++) {
            for (int j = 0; j < a.length; j++) {
                if (data[i + j] != a[j]) continue outer;
            }
            byte[] copy = data.clone();
            System.arraycopy(b, 0, copy, i, b.length);
            return copy;
        }
        throw new AssertionError("찾지 못함: " + from);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
