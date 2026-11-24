package org.example.server;

import hockey.common.protocol.Message.*;
import hockey.common.protocol.MessageCodec;
import hockey.common.rules.MatchEngine.Phase;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** 실제 서버 프로세스와 TCP로 검사한다. main을 직접 실행하며 실패하면 AssertionError로 끝난다. */
public final class LobbySelfTest {
    public static void main(String[] args) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process server = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                HockeyServer.class.getName(), "0").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (BufferedReader log = server.inputReader();
             var reader = Executors.newSingleThreadExecutor()) {
            String firstLine = reader.submit(log::readLine).get(10, TimeUnit.SECONDS);
            check(firstLine != null, "서버 실행");
            int port = Integer.parseInt(firstLine.replaceAll("[^0-9]", ""));
            reader.submit(() -> {
                while (log.readLine() != null) { }
                return null;
            });
            try {
                registration(port);
                waitingRoom(port);
                normalGame(port);
                concurrentJoin(port);
                disconnectCleanup(port);
                System.out.println("LobbySelfTest: 접속·방·권한·동시 입장·연결 종료 모두 통과");
            } finally {
                server.destroy();
                if (!server.waitFor(5, TimeUnit.SECONDS)) {
                    server.destroyForcibly().waitFor();
                }
            }
        } finally {
            if (server.isAlive()) {
                server.destroyForcibly().waitFor();
            }
        }
    }

    private static void registration(int port) throws Exception {
        try (Peer idle = new Peer(port); Peer first = new Peer(port); Peer second = new Peer(port)) {
            idle.out.writeUTF("HELLO");
            idle.out.flush();
            first.send(new Hello("  Hockey  "));
            String token = first.next(Welcome.class).token();
            UUID.fromString(token);
            second.send(new CreateRoom(5, true));
            second.failure("CREATE_ROOM", RoomFailReason.NOT_REGISTERED);
            second.send(new Hello("hOcKeY"));
            check(second.next(HelloFailed.class).reason() == HelloFailReason.DUPLICATE_NICKNAME, "닉네임 중복");
            second.send(new Hello(" "));
            check(second.next(HelloFailed.class).reason() == HelloFailReason.INVALID_NICKNAME, "빈 이름");
            second.send(new Hello("bad name"));
            check(second.next(HelloFailed.class).reason() == HelloFailReason.INVALID_NICKNAME, "허용되지 않는 이름");
            second.send(new Hello("성진"));
            check(!token.equals(second.next(Welcome.class).token()), "토큰 분리");
            second.send(new Hello("다른이름"));
            check(second.next(HelloFailed.class).reason() == HelloFailReason.ALREADY_REGISTERED, "이중 등록");
        }
    }

    private static void waitingRoom(int port) throws Exception {
        try (Peer host = login(port, "방장"); Peer guest = login(port, "손님"); Peer observer = login(port, "관찰자")) {
            observer.send(new ListRooms());
            observer.next(RoomList.class);
            host.send(new Ready(true));
            host.failure("READY", RoomFailReason.NOT_IN_ROOM);
            host.send(new CreateRoom(4, true));
            host.failure("CREATE_ROOM", RoomFailReason.INVALID_SETTINGS);
            host.send(new CreateRoom(5, true));
            RoomUpdate room = host.next(RoomUpdate.class);
            String code = room.code();
            check(code.matches("[A-HJKMNP-Z2-9]{5}"), "혼동 문자 없는 5자 코드");
            check(room.members().equals(List.of(new RoomMember("방장", true, false))), "생성자는 방장");
            observer.listWith(list -> list.rooms().stream().anyMatch(r -> r.code().equals(code) && r.playerCount() == 1));

            host.send(new CreateRoom(5, true));
            host.failure("CREATE_ROOM", RoomFailReason.ALREADY_IN_ROOM);
            host.send(new Start());
            host.failure("START", RoomFailReason.NOT_READY);
            host.send(new Ready(true));
            host.failure("READY", RoomFailReason.GUEST_ONLY);
            guest.send(new JoinRoom("XXXXX"));
            guest.failure("JOIN_ROOM", RoomFailReason.NOT_FOUND);
            guest.send(new JoinRoom(" " + code.toLowerCase(Locale.ROOT) + " "));
            sameRoom(host, guest, r -> r.members().size() == 2);
            observer.listWith(list -> list.rooms().stream().anyMatch(r -> r.code().equals(code) && r.playerCount() == 2));
            observer.send(new JoinRoom(code));
            observer.failure("JOIN_ROOM", RoomFailReason.FULL);
            guest.send(new JoinRoom(code));
            guest.failure("JOIN_ROOM", RoomFailReason.ALREADY_IN_ROOM);
            guest.send(new Settings(7, false));
            guest.failure("SETTINGS", RoomFailReason.HOST_ONLY);
            guest.send(new Start());
            guest.failure("START", RoomFailReason.HOST_ONLY);
            host.send(new Start());
            host.failure("START", RoomFailReason.NOT_READY);

            guest.send(new Ready(true));
            sameRoom(host, guest, r -> r.members().get(1).ready());
            host.send(new Settings(5, true));
            sameRoom(host, guest, r -> r.members().get(1).ready());
            host.send(new Settings(-1, false));
            host.failure("SETTINGS", RoomFailReason.INVALID_SETTINGS);
            host.send(new Settings(7, false));
            sameRoom(host, guest, r -> r.targetScore() == 7 && !r.isPublic() && !r.members().get(1).ready());
            observer.listWith(list -> list.rooms().stream().noneMatch(r -> r.code().equals(code)));
            guest.send(new LeaveRoom());
            guest.next(RoomLeft.class);
            host.next(RoomUpdate.class);
            guest.send(new JoinRoom(code));
            sameRoom(host, guest, r -> !r.isPublic() && r.members().size() == 2);

            guest.send(new Ready(true));
            sameRoom(host, guest, r -> r.members().get(1).ready());
            guest.send(new Ready(false));
            sameRoom(host, guest, r -> !r.members().get(1).ready());
            host.send(new Settings(7, true));
            sameRoom(host, guest, RoomUpdate::isPublic);
            guest.send(new Ready(true));
            sameRoom(host, guest, r -> r.members().get(1).ready());
            host.send(new Start());
            sameRoom(host, guest, RoomUpdate::started);
            check(host.next(GameStart.class).equals(new GameStart(code, 0, 7)), "방장 진영");
            check(guest.next(GameStart.class).equals(new GameStart(code, 1, 7)), "게스트 진영");
            observer.listWith(list -> list.rooms().stream().noneMatch(r -> r.code().equals(code)));
            host.send(new Start());
            host.failure("START", RoomFailReason.STARTED);
            host.send(new Settings(5, true));
            host.failure("SETTINGS", RoomFailReason.STARTED);
            guest.send(new Ready(false));
            guest.failure("READY", RoomFailReason.STARTED);
            observer.send(new JoinRoom(code));
            observer.failure("JOIN_ROOM", RoomFailReason.STARTED);

            check(host.next(State.class).ackSeq() == -1 && guest.next(State.class).ackSeq() == -1, "초기 입력 번호");
            host.send(new Input(5, 100, 900));
            guest.send(new Input(9, 500, 100));
            host.send(new Input(6, Double.NaN, 750));
            host.send(new Input(4, 500, 750));
            State a = host.stateWithAck(5);
            State b = guest.stateWithAck(9);
            check(a.snapshot().tick() > 0 && b.snapshot().tick() > 0, "틱 진행");
            host.send(new Input(7, 100, 900));
            check(host.stateWithAck(7).ackSeq() == 7, "유효 입력으로 보정");

            host.send(new LeaveRoom());
            host.failure("LEAVE_ROOM", RoomFailReason.STARTED);
            guest.send(new LeaveGame());
            GameOver over = new GameOver(0, 0, 0, GameOverReason.FORFEIT);
            check(host.next(GameOver.class).equals(over) && guest.next(GameOver.class).equals(over), "게스트 기권");
            sameRoom(host, guest, r -> !r.started() && !r.members().get(1).ready());

            guest.send(new Ready(true));
            sameRoom(host, guest, r -> r.members().get(1).ready());
            host.send(new Start());
            sameRoom(host, guest, RoomUpdate::started);
            host.next(GameStart.class);
            guest.next(GameStart.class);
            check(guest.next(State.class).ackSeq() == -1, "재대결 입력 번호 초기화");
            host.send(new Quit());
            check(guest.next(GameOver.class).equals(new GameOver(1, 0, 0, GameOverReason.FORFEIT)), "방장 연결 종료");

            RoomUpdate promoted = guest.next(RoomUpdate.class);
            check(!promoted.started() && promoted.members().equals(List.of(new RoomMember("손님", true, false))),
                    "방장 승계와 준비 초기화");
            observer.listWith(list -> list.rooms().stream().anyMatch(r -> r.code().equals(code) && r.hostName().equals("손님")));
            guest.send(new LeaveRoom());
            guest.next(RoomLeft.class);
            observer.listWith(list -> list.rooms().stream().noneMatch(r -> r.code().equals(code)));
            observer.send(new JoinRoom(code));
            observer.failure("JOIN_ROOM", RoomFailReason.NOT_FOUND);
            observer.send(new LeaveRoom());
            observer.failure("LEAVE_ROOM", RoomFailReason.NOT_IN_ROOM);
        }
    }

    private static void normalGame(int port) throws Exception {
        try (Peer host = login(port, "득점방장"); Peer guest = login(port, "득점손님")) {
            host.send(new CreateRoom(3, false));
            guest.send(new JoinRoom(host.next(RoomUpdate.class).code()));
            sameRoom(host, guest, r -> r.members().size() == 2);
            guest.send(new Ready(true));
            sameRoom(host, guest, r -> r.members().get(1).ready());
            host.send(new Start());
            sameRoom(host, guest, RoomUpdate::started);
            host.next(GameStart.class);
            guest.next(GameStart.class);

            // 서브 쪽은 정면으로 슛하고, 상대 말렛은 골대 중앙에서 비켜 준다.
            for (int seq = 0; seq < 1_500; seq++) {
                State first = host.next(State.class);
                State second = guest.next(State.class);
                var state = first.snapshot();
                check(state.equals(second.snapshot()), "동일한 틱의 물리 상태와 점수");
                if (state.phase() == Phase.FINISHED) {
                    GameOver result = host.next(GameOver.class);
                    check(result.equals(guest.next(GameOver.class)), "양쪽 경기 결과 일치");
                    check(result.reason() == GameOverReason.NORMAL && state.score(result.winner()) == 3,
                            "목표 점수 도달로 정상 종료");
                    check(result.score0() == state.score0() && result.score1() == state.score1(), "최종 점수 일치");
                    sameRoom(host, guest, r -> !r.started() && !r.members().get(1).ready());
                    return;
                }
                boolean playing = state.phase() == Phase.PLAYING;
                host.send(new Input(seq, state.server() == 0 ? 300 : 40,
                        state.server() == 0 && playing ? 510 : 900));
                guest.send(new Input(seq, state.server() == 1 ? 300 : 40,
                        state.server() == 1 && playing ? 490 : 100));
            }
            throw new AssertionError("목표 점수까지 경기를 완료하지 못했습니다.");
        }
    }

    private static void concurrentJoin(int port) throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            for (int attempt = 0; attempt < 10; attempt++) {
                try (Peer host = login(port, "raceHost" + attempt);
                     Peer left = login(port, "raceA" + attempt);
                     Peer right = login(port, "raceB" + attempt)) {
                    host.send(new CreateRoom(3, true));
                    String code = host.next(RoomUpdate.class).code();
                    CyclicBarrier barrier = new CyclicBarrier(2);
                    var first = workers.submit(() -> {
                        barrier.await();
                        left.send(new JoinRoom(code));
                        return left.read();
                    });
                    var second = workers.submit(() -> {
                        barrier.await();
                        right.send(new JoinRoom(code));
                        return right.read();
                    });
                    List<Reply> replies = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
                    check(replies.stream().filter(r -> r instanceof RoomUpdate).count() == 1, "마지막 자리 한 명 성공");
                    check(replies.stream().filter(r -> r.equals(new RoomFailed("JOIN_ROOM", RoomFailReason.FULL))).count() == 1,
                            "동시 입장 한 명 거절");
                    check(host.next(RoomUpdate.class).members().size() == 2, "정원 2명");
                }
            }
        }
    }

    private static void disconnectCleanup(int port) throws Exception {
        try (Peer host = login(port, "끊김방장"); Peer guest = login(port, "끊김손님");
             Peer other = login(port, "다른방장")) {
            host.send(new CreateRoom(5, true));
            String code = host.next(RoomUpdate.class).code();
            other.send(new CreateRoom(10, false));
            String otherCode = other.next(RoomUpdate.class).code();
            check(!code.equals(otherCode), "방 코드 분리");
            guest.send(new JoinRoom(code));
            sameRoom(host, guest, r -> r.code().equals(code));
            guest.socket.close();
            check(host.next(RoomUpdate.class).members().size() == 1, "EOF 게스트 퇴장");
            host.send(new Quit());
            check(host.in.read() == -1, "QUIT 연결 닫기");
            other.send(new Settings(3, false));
            RoomUpdate ownRoom = other.next(RoomUpdate.class);
            check(ownRoom.code().equals(otherCode) && ownRoom.members().size() == 1, "다른 방 상태 유지");
        }
        try (Peer host = login(port, "승계방장"); Peer guest = login(port, "승계손님")) {
            host.send(new CreateRoom(5, true));
            guest.send(new JoinRoom(host.next(RoomUpdate.class).code()));
            sameRoom(host, guest, r -> r.members().size() == 2);
            host.socket.close();
            check(guest.next(RoomUpdate.class).members().equals(List.of(new RoomMember("승계손님", true, false))),
                    "EOF 방장 승계");
        }
    }

    private static Peer login(int port, String nickname) throws IOException {
        Peer peer = new Peer(port);
        peer.send(new Hello(nickname));
        peer.next(Welcome.class);
        return peer;
    }

    private static void sameRoom(Peer host, Peer guest, Predicate<RoomUpdate> condition) throws IOException {
        RoomUpdate first = host.next(RoomUpdate.class);
        RoomUpdate second = guest.next(RoomUpdate.class);
        check(first.equals(second) && condition.test(first), "양쪽의 같은 방 상태 " + first);
    }

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static final class Peer implements AutoCloseable {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;

        Peer(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(5000);
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
        }

        void send(Request request) throws IOException {
            MessageCodec.writeRequest(out, request);
        }

        Reply read() throws IOException {
            return MessageCodec.readReply(in);
        }

        <T extends Reply> T next(Class<T> type) throws IOException {
            Reply reply = read();
            while ((reply instanceof RoomList && type != RoomList.class)
                    || (reply instanceof State && type != State.class)) {
                reply = read();
            }
            check(type.isInstance(reply), "예상 " + type.getSimpleName() + ", 수신 " + reply);
            return type.cast(reply);
        }

        State stateWithAck(int expected) throws IOException {
            for (int i = 0; i < 60; i++) {
                State state = next(State.class);
                check(state.ackSeq() <= expected, "잘못된 입력 번호 " + state.ackSeq());
                if (state.ackSeq() == expected) {
                    return state;
                }
            }
            throw new AssertionError("입력 응답이 오지 않았습니다: " + expected);
        }

        void failure(String command, RoomFailReason reason) throws IOException {
            check(next(RoomFailed.class).equals(new RoomFailed(command, reason)), command + " 실패 사유 " + reason);
        }

        void listWith(Predicate<RoomList> condition) throws IOException {
            RoomList list;
            do {
                list = next(RoomList.class);
            } while (!condition.test(list));
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
