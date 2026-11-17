package hockey.client.game;

import hockey.common.physics.GameState;
import hockey.common.physics.Physics;

import java.util.ArrayDeque;
import java.util.Random;

import static hockey.common.physics.PhysicsConstants.*;

/**
 * 예측·보정을 가짜 서버와 지연을 넣어 확인한다(소켓 없이 틱 단위로 흉내 낸다).
 *
 * 비교 기준: 클라이언트가 입력 n까지 예측한 상태 ↔ 서버가 입력 n까지 처리한 뒤의 실제 상태.
 * 상대가 가만히 있으면 둘은 같아야 하고(같은 Physics, 같은 입력), 상대가 움직이면
 * 상대 입력을 모르는 만큼만 달라야 한다.
 *
 * "같다"의 허용 오차가 0이 아닌 이유: 클라이언트는 상대의 목표 위치를 모르고 말렛 위치만 안다.
 * 말렛은 서브스텝 덧셈의 반올림 때문에 목표와 1e-13px쯤 어긋날 수 있어, 그 말렛에 퍽이 맞으면
 * 같은 크기의 차이가 생긴다.
 */
public final class PredictorSelfTest {
    private static final int TICKS = 60 * TICK_RATE;
    private static final double EXACT = 1e-6;

    public static void main(String[] args) {
        // 상대가 움직이지 않으면 지연이 얼마든 예측이 서버와 일치한다.
        for (int latency : new int[] {0, 3, 9}) {
            Result r = run(0, latency, 3, false, 11);
            check(r.maxPuckError < EXACT, "상대 정지, 지연 " + latency + "틱: 예측 오차 " + r.maxPuckError);
            check(r.maxVisualError < EXACT, "상대 정지: 화면 보정 오차 " + r.maxVisualError);
        }
        Result asTop = run(1, 3, 3, false, 12);
        check(asTop.maxPuckError < EXACT, "1번 플레이어로도 일치: " + asTop.maxPuckError);

        // 상대가 움직이면 오차가 생기지만, 예측 없이 마지막 서버 상태만 그릴 때보다 작아야 한다.
        System.out.println("  지연(왕복)  평균 오차(예측)  평균 오차(예측 없음)");
        for (int latency : new int[] {1, 3, 6, 9}) {
            Result r = run(0, latency, 3, true, 21);
            System.out.printf("  %4dms      %6.1fpx        %6.1fpx%n",
                    Math.round(latency * 2 * 1000.0 / TICK_RATE), r.meanPuckError, r.meanStaleError);
            check(r.meanPuckError < r.meanStaleError, "지연 " + latency + "틱: 예측이 더 정확해야 함");
        }
        System.out.println("PredictorSelfTest: 통과");
    }

    private record Result(double maxPuckError, double meanPuckError, double meanStaleError, double maxVisualError) {}

    private record Snapshot(int arriveTick, GameState state, long ackSeq) {}

    /**
     * @param latency       한 방향 지연(틱)
     * @param snapshotEvery 서버가 상태를 보내는 간격(틱). 3이면 20Hz.
     */
    private static Result run(int me, int latency, int snapshotEvery, boolean opponentMoves, long seed) {
        Random random = new Random(seed);
        GameState server = new GameState();
        Predictor predictor = new Predictor(me);
        ArrayDeque<Snapshot> inFlight = new ArrayDeque<>();
        GameState lastReceived = new GameState();

        double[] inputX = new double[TICKS];
        double[] inputY = new double[TICKS];
        // 서버가 입력 n까지 처리한 뒤의 퍽 위치(비교용)
        double[] truthX = new double[TICKS];
        double[] truthY = new double[TICKS];
        boolean[] truthKnown = new boolean[TICKS];
        double[] predictedX = new double[TICKS];
        double[] predictedY = new double[TICKS];
        double[] staleX = new double[TICKS];
        double[] staleY = new double[TICKS];

        int opp = 1 - me;
        double myX = server.malletX[me], myY = server.malletY[me];
        double oppX = server.malletX[opp], oppY = server.malletY[opp];
        double maxVisual = 0;

        for (int tick = 0; tick < TICKS; tick++) {
            // 클라이언트: 도착한 상태를 먼저 반영하고, 입력을 만들어 예측한다.
            while (!inFlight.isEmpty() && inFlight.peek().arriveTick() <= tick) {
                Snapshot snapshot = inFlight.poll();
                predictor.onSnapshot(snapshot.state(), snapshot.ackSeq());
                lastReceived.copyFrom(snapshot.state());
            }
            if (tick % 15 == 0) {
                // 사람 흉내: 0.25초마다 퍽 근처 또는 임의 위치를 겨눈다.
                GameState seen = predictor.predicted();
                boolean chase = random.nextBoolean();
                myX = chase ? seen.puckX : random.nextDouble() * FIELD_WIDTH;
                myY = chase ? seen.puckY : random.nextDouble() * FIELD_HEIGHT;
                if (opponentMoves) {
                    boolean oppChase = random.nextBoolean();
                    oppX = oppChase ? server.puckX : random.nextDouble() * FIELD_WIDTH;
                    oppY = oppChase ? server.puckY : random.nextDouble() * FIELD_HEIGHT;
                }
            }
            inputX[tick] = myX;
            inputY[tick] = myY;
            long seq = predictor.tick(myX, myY);
            check(seq == tick, "입력 번호는 틱과 같다");
            predictedX[tick] = predictor.predicted().puckX;
            predictedY[tick] = predictor.predicted().puckY;
            staleX[tick] = lastReceived.puckX;
            staleY[tick] = lastReceived.puckY;
            maxVisual = Math.max(maxVisual, predictor.visualError());

            // 서버: latency틱 전에 보낸 입력이 지금 도착한다. 아직 없으면 말렛은 제자리.
            int arrived = tick - latency;
            double sx = arrived >= 0 ? inputX[arrived] : server.malletX[me];
            double sy = arrived >= 0 ? inputY[arrived] : server.malletY[me];
            int events = me == 0
                    ? Physics.step(server, sx, sy, oppX, oppY)
                    : Physics.step(server, oppX, oppY, sx, sy);
            if ((events & Physics.EVENT_GOAL_MASK) != 0) {
                server.resetPuck();
            }
            if (arrived >= 0) {
                truthX[arrived] = server.puckX;
                truthY[arrived] = server.puckY;
                truthKnown[arrived] = true;
            }
            if (tick % snapshotEvery == 0) {
                GameState copy = new GameState();
                copy.copyFrom(server);
                inFlight.add(new Snapshot(tick + 1 + latency, copy, Math.max(arrived, -1)));
            }
        }

        double max = 0, sum = 0, staleSum = 0;
        int count = 0;
        for (int i = 0; i < TICKS; i++) {
            if (!truthKnown[i]) {
                continue;
            }
            double error = Math.hypot(predictedX[i] - truthX[i], predictedY[i] - truthY[i]);
            max = Math.max(max, error);
            sum += error;
            staleSum += Math.hypot(staleX[i] - truthX[i], staleY[i] - truthY[i]);
            count++;
        }
        return new Result(max, sum / count, staleSum / count, maxVisual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
