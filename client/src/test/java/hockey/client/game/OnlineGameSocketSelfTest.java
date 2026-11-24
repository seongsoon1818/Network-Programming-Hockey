package hockey.client.game;

import hockey.client.net.Connection;
import hockey.common.protocol.Message.*;
import hockey.common.protocol.MessageCodec;
import hockey.common.rules.MatchEngine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 실제 소켓으로 온라인 경기를 돌린다. 클라이언트 두 개(Connection + NetworkMatch + 연습 봇)가
 * 형식 문서대로 경기를 진행하는 간이 서버에 붙어 INPUT을 보내고 STATE·GAME_OVER를 받는다.
 * 시간을 줄이려고 서버와 클라이언트 모두 1밀리초마다 한 틱씩 돈다. main을 실행하면 실패 시 예외로 끝난다.
 */
public final class OnlineGameSocketSelfTest {
    private static final long TIME_LIMIT_MS = 60_000;

    public static void main(String[] args) throws Exception {
        normalGame();
        forfeit();
        System.out.println("OnlineGameSocketSelfTest: 모두 통과");
    }

    /** 목표 점수에 닿을 때까지 한 판. 두 클라이언트의 결과가 서버와 같아야 한다. */
    private static void normalGame() throws Exception {
        try (GameServer server = new GameServer(1, -1)) {
            Player host = new Player(server.port(), "chan");
            Player guest = new Player(server.port(), "bob");
            runUntilOver(host, guest);

            MatchEngine engine = server.engine;
            for (Player p : new Player[] {host, guest}) {
                NetworkMatch m = p.match;
                check(m.isOver(), p.name + ": 판이 끝난다");
                check(m.winner() == engine.winner(), p.name + ": 승자가 서버와 같다");
                check(m.score(0) == engine.score(0) && m.score(1) == engine.score(1), p.name + ": 점수가 서버와 같다");
                check(m.overlay().equals(engine.winner() == m.me() ? "승리!" : "패배"), p.name + ": 승패 글씨");
                check(p.states > 100, p.name + ": STATE를 받았다 " + p.states);
                check(p.lastAck >= 0, p.name + ": 서버가 내 입력을 처리했다");
                check(p.reason == GameOverReason.NORMAL, p.name + ": 정상 종료");
            }
            check(host.match.me() == 0 && guest.match.me() == 1, "번호는 GAME_START대로");
            System.out.printf("  정상 종료: %d:%d, 서버 %d틱, 입력 %d·%d개%n", engine.score(0), engine.score(1),
                    engine.ticks(), server.inputs[0], server.inputs[1]);
            host.close();
            guest.close();
        }
    }

    /** 300틱 뒤 게스트가 나간다. 둘 다 GAME_OVER(FORFEIT)를 받고 방장이 이긴다. */
    private static void forfeit() throws Exception {
        try (GameServer server = new GameServer(10, 1)) {
            Player host = new Player(server.port(), "chan");
            Player guest = new Player(server.port(), "bob");
            guest.leaveAfter = 300;
            runUntilOver(host, guest);

            check(host.match.isOver() && host.match.winner() == 0, "방장이 기권승");
            check(host.reason == GameOverReason.FORFEIT && guest.reason == GameOverReason.FORFEIT, "둘 다 FORFEIT");
            check(host.match.overlayDetail().startsWith("상대가 나갔습니다"), "방장 화면 안내: " + host.match.overlayDetail());
            check(server.leftSide == 1, "서버가 게스트의 LEAVE_GAME을 받았다");
            int sent = guest.sentAfterLeave;
            check(sent == 0, "나간 뒤에는 INPUT을 보내지 않는다: " + sent);
            System.out.println("  기권: 방장 승리, 서버 " + server.engine.ticks() + "틱");
            host.close();
            guest.close();
        }
    }

    private static void runUntilOver(Player a, Player b) throws Exception {
        long end = System.currentTimeMillis() + TIME_LIMIT_MS;
        while (!(a.done() && b.done())) {
            check(System.currentTimeMillis() < end, "시간 안에 끝나야 한다");
            a.tick();
            b.tick();
            Thread.sleep(1);
        }
    }

    /** 클라이언트 하나. 수신 스레드가 넣은 메시지를 이 스레드에서 꺼내 처리한다(화면의 EDT 역할). */
    private static final class Player implements NetworkMatch.Link {
        final String name;
        final Connection connection;
        final Queue<Reply> inbox = new ConcurrentLinkedQueue<>();
        NetworkMatch match;
        PracticeBot bot;
        int ticks;
        int states;
        long lastAck = -1;
        GameOverReason reason;
        int leaveAfter = -1;
        boolean left;
        int sentAfterLeave;

        Player(int port, String name) throws IOException {
            this.name = name;
            connection = Connection.connect("localhost", port, name).connection();
            connection.start(new Connection.Listener() {
                @Override
                public void onMessage(Reply message) {
                    inbox.add(message);
                }

                @Override
                public void onDisconnected(IOException cause) {
                    throw new AssertionError(name + ": 연결이 끊기면 안 된다", cause);
                }
            });
        }

        void tick() {
            for (Reply message; (message = inbox.poll()) != null; ) {
                switch (message) {
                    case GameStart m -> {
                        match = new NetworkMatch(m.side(), name, "상대", m.targetScore(), this);
                        bot = new PracticeBot(m.side());
                    }
                    case State m -> {
                        states++;
                        lastAck = m.ackSeq();
                        match.onState(m.snapshot(), m.ackSeq());
                    }
                    case GameOver m -> {
                        reason = m.reason();
                        match.onGameOver(m.winner(), m.score0(), m.score1(), m.reason() == GameOverReason.FORFEIT);
                    }
                    default -> { }
                }
            }
            if (match == null) {
                return;
            }
            if (ticks++ == leaveAfter) {
                match.leave();
                left = true;
            }
            bot.think(match.state());
            match.tick(bot.targetX, bot.targetY);
        }

        boolean done() {
            return reason != null;
        }

        @Override
        public void sendInput(long seq, double x, double y) {
            if (left) {
                sentAfterLeave++;
            }
            send(new Input((int) seq, x, y));
        }

        @Override
        public void leaveGame() {
            send(new LeaveGame());
        }

        private void send(Request request) {
            try {
                connection.send(request);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        void close() {
            connection.close();
        }
    }

    /**
     * 형식 문서대로 경기를 진행하는 간이 서버. 두 명이 접속하면 GAME_START를 보내고 1밀리초마다 한 틱 돌린다.
     * 사람마다 가장 최근 INPUT 하나만 들고, 그 번호를 STATE의 처리한 입력 번호로 돌려준다.
     */
    private static final class GameServer implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0);
        final MatchEngine engine;
        final DataOutputStream[] outs = new DataOutputStream[2];
        final double[] targetX = new double[2];
        final double[] targetY = new double[2];
        final int[] lastSeq = {-1, -1};
        final int[] inputs = new int[2];
        volatile int leftSide = -1;
        final AtomicBoolean closed = new AtomicBoolean();
        final Thread loop;

        GameServer(int targetScore, long seed) throws IOException {
            engine = new MatchEngine(targetScore, new Random(seed));
            for (int side = 0; side < 2; side++) {
                targetX[side] = engine.state().malletX[side];
                targetY[side] = engine.state().malletY[side];
            }
            loop = new Thread(this::run, "test-game-server");
            loop.setDaemon(true);
            loop.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        private void run() {
            try {
                Socket[] sockets = new Socket[2];
                for (int side = 0; side < 2; side++) {
                    sockets[side] = listener.accept();
                    sockets[side].setTcpNoDelay(true);
                    DataInputStream in = new DataInputStream(new BufferedInputStream(sockets[side].getInputStream()));
                    outs[side] = new DataOutputStream(new BufferedOutputStream(sockets[side].getOutputStream()));
                    MessageCodec.readRequest(in); // HELLO
                    MessageCodec.writeReply(outs[side], new Welcome(UUID.randomUUID().toString()));
                    int s = side;
                    Thread reader = new Thread(() -> read(s, in), "test-server-read-" + side);
                    reader.setDaemon(true);
                    reader.start();
                }
                for (int side = 0; side < 2; side++) {
                    MessageCodec.writeReply(outs[side], new GameStart("TEST1", side, engine.targetScore()));
                }
                while (!closed.get()) {
                    synchronized (this) {
                        if (leftSide != -1) {
                            engine.forfeit(leftSide);
                            broadcast(new GameOver(engine.winner(), engine.score(0), engine.score(1),
                                    GameOverReason.FORFEIT));
                            return;
                        }
                        engine.tick(targetX[0], targetY[0], targetX[1], targetY[1]);
                        for (int side = 0; side < 2; side++) {
                            MessageCodec.writeReply(outs[side], new State(lastSeq[side], engine.snapshot()));
                        }
                        if (engine.phase() == MatchEngine.Phase.FINISHED) {
                            broadcast(new GameOver(engine.winner(), engine.score(0), engine.score(1),
                                    GameOverReason.NORMAL));
                            return;
                        }
                    }
                    Thread.sleep(1);
                }
            } catch (IOException | InterruptedException e) {
                if (!closed.get()) {
                    e.printStackTrace();
                }
            }
        }

        private void broadcast(Reply reply) throws IOException {
            for (DataOutputStream out : outs) {
                MessageCodec.writeReply(out, reply);
            }
        }

        private void read(int side, DataInputStream in) {
            try {
                while (true) {
                    Request request = MessageCodec.readRequest(in);
                    synchronized (this) {
                        switch (request) {
                            case Input m -> {
                                check(m.seq() == lastSeq[side] + 1, "입력 번호는 1씩 는다: " + m.seq());
                                lastSeq[side] = m.seq();
                                targetX[side] = m.x();
                                targetY[side] = m.y();
                                inputs[side]++;
                            }
                            case LeaveGame m -> leftSide = side;
                            case Quit m -> {
                                return;
                            }
                            default -> throw new AssertionError("예상하지 못한 요청: " + request);
                        }
                    }
                }
            } catch (IOException e) {
                // 테스트가 끝나 연결이 닫혔다.
            }
        }

        @Override
        public void close() throws Exception {
            closed.set(true);
            listener.close();
            loop.join(2000);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
