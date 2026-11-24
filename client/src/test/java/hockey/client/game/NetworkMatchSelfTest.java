package hockey.client.game;

import hockey.common.physics.GameState;
import hockey.common.rules.MatchEngine;
import hockey.common.rules.MatchEngine.Phase;
import hockey.common.rules.MatchSnapshot;

import java.util.ArrayDeque;
import java.util.Random;

import static hockey.common.physics.PhysicsConstants.*;

/**
 * 온라인 판을 소켓 없이 확인한다. 가짜 서버는 초안대로 움직인다:
 * 사람마다 가장 최근 입력 하나만 들고 매 틱 {@link MatchEngine}을 돌리고, 매 틱 상태와 처리한 입력 번호를 보내고,
 * 끝나면 GAME_OVER를 보낸다. 양쪽 방향에 같은 지연을 넣는다. 내 쪽과 상대 쪽 모두 연습 봇이 친다.
 */
public final class NetworkMatchSelfTest {
    private static final int TARGET = 3;

    public static void main(String[] args) {
        for (int me = 0; me < 2; me++) {
            for (int latency : new int[] {0, 3, 9}) {
                playsAFullGame(me, latency, 100 + me * 10 + latency);
            }
        }
        beforeFirstState();
        finishedStateWaitsForGameOver();
        opponentLeaves();
        iLeave();
        roomUpdateEndsGameWithoutGameOver();
        abortStopsTheGame();
        System.out.println("NetworkMatchSelfTest: 모두 통과");
    }

    /** 끝까지 한 판. 입력 번호, 서브 자리에 멈춘 퍽(예측도 규칙을 따름), 점수, 승패를 확인한다. */
    private static void playsAFullGame(int me, int latency, long seed) {
        FakeServer server = new FakeServer(me, latency, seed);
        NetworkMatch match = server.match;
        PracticeBot myBot = new PracticeBot(me);
        int lastTotal = 0;
        int frozenChecks = 0;
        for (int tick = 0; tick < 10 * 60 * TICK_RATE && !match.isOver(); tick++) {
            server.deliverToClient(tick);
            myBot.think(match.state());
            match.tick(myBot.targetX, myBot.targetY);
            if (!match.isOver()) {
                check(server.sent == tick + 1, "틱마다 입력 하나, 번호는 0부터: " + server.sent + " / " + (tick + 1));
            }
            server.tick(tick);

            int total = match.score(0) + match.score(1);
            check(total >= lastTotal, "점수는 줄지 않는다");
            lastTotal = total;

            // 멈춘 단계가 아직 한참 남았으면, 다시 돌린 입력을 다 써도 예측한 퍽은 서브 자리에 멈춰 있다.
            MatchSnapshot seen = server.lastDelivered;
            if (seen != null && seen.phase() != Phase.PLAYING && seen.phase() != Phase.FINISHED
                    && seen.phaseTicksLeft() > 2 * latency + 2 && !match.isOver()) {
                GameState p = new GameState();
                p.placePuckForServe(seen.server());
                GameState predicted = server.predictorState();
                check(predicted.puckX == p.puckX && predicted.puckY == p.puckY,
                        "멈춘 동안 예측한 퍽은 서브 자리: " + seen.phase());
                check(match.overlay() != null, "멈춘 동안 글씨가 있다");
                frozenChecks++;
            }
        }
        check(match.isOver(), "판이 끝난다 (me " + me + ", 지연 " + latency + ")");
        MatchEngine engine = server.engine;
        check(match.winner() == engine.winner(), "승자가 서버와 같다");
        check(match.score(0) == engine.score(0) && match.score(1) == engine.score(1), "최종 점수가 서버와 같다");
        check(Math.max(match.score(0), match.score(1)) == TARGET, "목표 점수에서 끝남");
        check(match.overlay().equals(engine.winner() == me ? "승리!" : "패배"), "승패 글씨");
        check(frozenChecks > 0, "멈춘 단계를 확인했다");

        int before = server.sent;
        match.tick(0, 0);
        check(server.sent == before, "끝난 뒤에는 입력을 보내지 않는다");
        System.out.printf("  me %d, 지연 %d틱: %d:%d, %.1f초%n",
                me, latency, engine.score(0), engine.score(1), engine.ticks() / (double) TICK_RATE);
    }

    private static void beforeFirstState() {
        NetworkMatch match = new NetworkMatch(0, "나", "상대", TARGET, new CountingLink());
        check("대기 중".equals(match.overlay()) && match.overlayDetail() != null, "첫 상태 전 안내(글씨가 있어야 화면에 뜬다)");
        check(match.score(0) == 0 && match.score(1) == 0 && match.hint() == null, "첫 상태 전 점수 0");
        check(match.name(0).equals("나") && match.name(1).equals("상대"), "이름");
        NetworkMatch top = new NetworkMatch(1, "나", "상대", TARGET, new CountingLink());
        check(top.name(1).equals("나") && top.name(0).equals("상대"), "1번이면 이름 자리가 바뀐다");
    }

    /** FINISHED 상태가 GAME_OVER보다 먼저 와도 승패를 짐작해 띄우지 않는다(기권이면 점수로 알 수 없다). */
    private static void finishedStateWaitsForGameOver() {
        NetworkMatch match = new NetworkMatch(0, "나", "상대", TARGET, new CountingLink());
        MatchEngine engine = new MatchEngine(TARGET, new Random(1));
        engine.forfeit(1);
        match.onState(engine.snapshot(), -1);
        check(match.overlay() == null && !match.isOver(), "GAME_OVER 전에는 승패 글씨 없음");
        match.onGameOver(0, 0, 0, true);
        check("승리!".equals(match.overlay()) && "상대가 나갔습니다. 나가기를 누르면 대기실로 돌아갑니다".equals(match.overlayDetail()), "기권승 글씨");
    }

    private static void opponentLeaves() {
        FakeServer server = new FakeServer(1, 3, 7);
        NetworkMatch match = server.match;
        for (int tick = 0; tick < 6 * TICK_RATE; tick++) {
            server.deliverToClient(tick);
            match.tick(FIELD_WIDTH / 2, 120);
            if (tick == 4 * TICK_RATE) {
                server.engine.forfeit(0); // 상대(0번)의 연결이 끊김
            }
            server.tick(tick);
        }
        check(match.isOver() && match.winner() == 1, "상대가 나가면 내가 이긴다");
        check("승리!".equals(match.overlay()) && "상대가 나갔습니다. 나가기를 누르면 대기실로 돌아갑니다".equals(match.overlayDetail()), "기권승 글씨");
    }

    private static void iLeave() {
        CountingLink link = new CountingLink();
        NetworkMatch match = new NetworkMatch(0, "나", "상대", TARGET, link);
        match.tick(1, 2);
        match.leave();
        match.leave();
        match.tick(3, 4);
        check(link.leaves == 1, "기권은 한 번만 보낸다");
        check(link.inputs == 1, "나간 뒤에는 입력을 보내지 않는다");
    }

    /** GAME_OVER 없이 방이 시작 전으로 돌아오면(지금 서버) 상대가 나갔으면 기권승, 아니면 승패 없이 끝낸다. */
    private static void roomUpdateEndsGameWithoutGameOver() {
        CountingLink link = new CountingLink();
        NetworkMatch gone = new NetworkMatch(0, "나", "상대", TARGET, link);
        gone.onRoomUpdate(true, true);
        check(!gone.isFinished(), "시작한 방 상태는 경기를 끝내지 않는다");
        gone.onRoomUpdate(false, false);
        check(gone.isOver() && gone.winner() == 0 && "승리!".equals(gone.overlay()), "상대가 사라지면 기권승");

        NetworkMatch stays = new NetworkMatch(1, "나", "상대", TARGET, link);
        stays.onRoomUpdate(false, true);
        check(stays.isFinished() && !stays.isOver() && "경기 종료".equals(stays.overlay()), "상대가 있으면 승패 없이 끝");

        NetworkMatch done = new NetworkMatch(0, "나", "상대", TARGET, link);
        done.onGameOver(1, 1, 3, false);
        done.onRoomUpdate(false, false);
        check(done.winner() == 1 && "패배".equals(done.overlay()), "GAME_OVER가 먼저 왔으면 그대로");
    }

    /** 연결이 끊기면 멈추고, 입력·기권을 보내지 않고, 늦게 온 상태·GAME_OVER도 무시한다. */
    private static void abortStopsTheGame() {
        CountingLink link = new CountingLink();
        NetworkMatch match = new NetworkMatch(0, "나", "상대", TARGET, link);
        match.tick(1, 2);
        match.onAborted("연결 끊김", "접속 화면으로");
        match.tick(3, 4);
        match.leave();
        match.onGameOver(0, 3, 0, false);
        check(link.inputs == 1 && link.leaves == 0, "끝난 뒤에는 보내지 않는다");
        check(match.isFinished() && !match.isOver() && "연결 끊김".equals(match.overlay())
                && "접속 화면으로".equals(match.overlayDetail()) && match.hint() == null, "끊김 글씨");
    }

    private static final class CountingLink implements NetworkMatch.Link {
        int inputs;
        int leaves;

        @Override
        public void sendInput(long seq, double x, double y) {
            inputs++;
        }

        @Override
        public void leaveGame() {
            leaves++;
        }
    }

    private record Input(int arriveTick, long seq, double x, double y) {}

    private record ToClient(int arriveTick, Runnable deliver) {}

    /** 소켓 대신 틱 단위 지연 큐로 잇는 가짜 서버. */
    private static final class FakeServer implements NetworkMatch.Link {
        final int me;
        final int latency;
        final MatchEngine engine;
        final PracticeBot opponent;
        final NetworkMatch match;
        final ArrayDeque<Input> toServer = new ArrayDeque<>();
        final ArrayDeque<ToClient> toClient = new ArrayDeque<>();
        int sent;
        int clientTick;
        long lastSeq = -1;
        double inputX, inputY;
        boolean overSent;
        MatchSnapshot lastDelivered;

        FakeServer(int me, int latency, long seed) {
            this.me = me;
            this.latency = latency;
            this.engine = new MatchEngine(TARGET, new Random(seed));
            this.opponent = new PracticeBot(1 - me);
            this.match = new NetworkMatch(me, "나", "상대", TARGET, this);
            inputX = engine.state().malletX[me];
            inputY = engine.state().malletY[me];
        }

        @Override
        public void sendInput(long seq, double x, double y) {
            check(seq == sent, "입력 번호는 1씩 는다");
            sent++;
            toServer.add(new Input(clientTick + latency, seq, x, y));
        }

        @Override
        public void leaveGame() {
            toServer.add(new Input(clientTick + latency, -2, 0, 0));
        }

        /** 클라이언트 틱 전에 도착한 메시지를 넘긴다(수신 스레드가 invokeLater로 넘기는 것과 같다). */
        void deliverToClient(int tick) {
            clientTick = tick;
            while (!toClient.isEmpty() && toClient.peek().arriveTick() <= tick) {
                toClient.poll().deliver().run();
            }
        }

        void tick(int tick) {
            while (!toServer.isEmpty() && toServer.peek().arriveTick() <= tick) {
                Input in = toServer.poll();
                if (in.seq() == -2) {
                    engine.forfeit(me);
                } else {
                    lastSeq = in.seq(); // 가장 최근 입력 하나만 든다.
                    inputX = in.x();
                    inputY = in.y();
                }
            }
            if (engine.phase() == Phase.FINISHED) {
                sendGameOver(tick);
                return;
            }
            opponent.think(engine.state());
            if (me == 0) {
                engine.tick(inputX, inputY, opponent.targetX, opponent.targetY);
            } else {
                engine.tick(opponent.targetX, opponent.targetY, inputX, inputY);
            }
            MatchSnapshot snapshot = engine.snapshot();
            long ack = lastSeq;
            toClient.add(new ToClient(tick + 1 + latency, () -> {
                lastDelivered = snapshot;
                match.onState(snapshot, ack);
            }));
            if (engine.phase() == Phase.FINISHED) {
                sendGameOver(tick);
            }
        }

        private void sendGameOver(int tick) {
            if (overSent) {
                return;
            }
            overSent = true;
            int winner = engine.winner();
            int s0 = engine.score(0);
            int s1 = engine.score(1);
            boolean forfeit = engine.forfeited();
            toClient.add(new ToClient(tick + 1 + latency, () -> match.onGameOver(winner, s0, s1, forfeit)));
        }

        /** 클라이언트가 예측한 상태(보정 오차 없이). */
        GameState predictorState() {
            return match.predictedState();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
