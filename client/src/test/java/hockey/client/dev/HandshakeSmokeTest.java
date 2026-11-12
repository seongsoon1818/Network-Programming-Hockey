package hockey.client.dev;

import hockey.client.net.Connection;
import hockey.client.net.Connection.HelloResult;
import hockey.common.protocol.Message.HelloFailReason;

import java.util.UUID;

/**
 * 1단계 완료 기준(2명 접속, 토큰 발급)을 확인한다. main을 실행하면 실패 시 예외로 끝난다.
 *
 * 인자 없이 실행하면 간이 서버를 띄워서 검사하고,
 * "호스트 포트"를 주면 이미 떠 있는 서버(실제 서버)를 상대로 검사한다.
 */
public final class HandshakeSmokeTest {

    public static void main(String[] args) throws Exception {
        if (args.length == 2) {
            run(args[0], Integer.parseInt(args[1]));
        } else {
            try (DevStubServer server = new DevStubServer(0)) {
                run("localhost", server.port());
            }
        }
        System.out.println("HandshakeSmokeTest: 모두 통과");
    }

    private static void run(String host, int port) throws Exception {
        HelloResult a = Connection.connect(host, port, "Alice");
        HelloResult b = Connection.connect(host, port, "  밥_2 ");
        check(a.accepted() && b.accepted(), "두 명 모두 접속되어야 한다");
        String tokenA = a.connection().session().token();
        String tokenB = b.connection().session().token();
        UUID.fromString(tokenA); // UUID 형식이 아니면 예외
        UUID.fromString(tokenB);
        check(!tokenA.equals(tokenB), "서로 다른 토큰 발급");
        check(b.connection().session().nickname().equals("밥_2"), "닉네임 앞뒤 공백 제거");

        HelloResult duplicate = Connection.connect(host, port, "aLiCe");
        check(duplicate.failReason() == HelloFailReason.DUPLICATE_NICKNAME, "대소문자만 다른 닉네임은 중복");
        HelloResult empty = Connection.connect(host, port, "   ");
        check(empty.failReason() == HelloFailReason.INVALID_NICKNAME, "빈 닉네임 거절");

        // QUIT을 보내고 나가면 닉네임이 풀린다.
        a.connection().close();
        HelloResult reuse = null;
        for (int i = 0; i < 20 && (reuse == null || !reuse.accepted()); i++) {
            Thread.sleep(50);
            reuse = Connection.connect(host, port, "alice");
        }
        check(reuse != null && reuse.accepted(), "접속 종료 후 닉네임 재사용");

        b.connection().close();
        reuse.connection().close();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
