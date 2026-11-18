package hockey.client.game;

import hockey.common.physics.GameState;
import hockey.common.physics.Physics;

/**
 * 클라이언트 예측·보정.
 *
 * 서버 상태는 지연 때문에 늘 과거의 것이다. 그래서 클라이언트는 자기 입력을 보내는 동시에
 * 같은 {@link Physics}로 먼저 계산해 그리고(예측), 서버 상태가 오면 그 상태에서 아직 서버가
 * 처리하지 않은 내 입력만 다시 돌려 맞춘다(보정). 보정으로 생긴 위치 차이는 한 번에 옮기지 않고
 * 몇 프레임에 걸쳐 줄인다.
 *
 * 프로토콜과는 분리돼 있다. 필요한 것은 "서버 상태"와 "그 상태에 반영된 내 마지막 입력 번호"뿐이다.
 * 메시지 형식이 정해지면 수신부에서 {@link #onSnapshot}만 부르면 된다. 모든 메서드는 EDT에서 부른다.
 */
public final class Predictor {
    /** 다시 돌릴 수 있는 입력 개수(틱). 256틱 ≈ 4.3초 지연까지. */
    private static final int HISTORY = 256;
    /** 보정 오차가 이보다 크면 부드럽게 옮기지 않고 바로 맞춘다(골 뒤 리셋 등). */
    private static final double SNAP_DISTANCE = 120;
    /** 틱마다 남기는 오차 비율. 0.8이면 약 10틱(0.17초) 뒤 10%가 남는다. */
    private static final double ERROR_DECAY = 0.8;

    private final int me;
    private final GameState predicted = new GameState();
    private final GameState view = new GameState();
    private final double[] inputX = new double[HISTORY];
    private final double[] inputY = new double[HISTORY];
    private long nextSeq;
    private double opponentX, opponentY;
    // 화면에만 더하는 오차(예측 위치 → 직전까지 그리던 위치)
    private double puckErrX, puckErrY, opponentErrX, opponentErrY;

    public Predictor(int me) {
        this.me = me;
        opponentX = predicted.malletX[1 - me];
        opponentY = predicted.malletY[1 - me];
    }

    /**
     * 내 입력으로 한 틱 예측한다.
     *
     * @return 이 입력의 번호. 서버에 입력과 함께 보내고, 서버는 상태를 보낼 때 마지막으로 처리한 번호를 돌려준다.
     */
    public long tick(double targetX, double targetY) {
        long seq = nextSeq++;
        inputX[(int) (seq % HISTORY)] = targetX;
        inputY[(int) (seq % HISTORY)] = targetY;
        step(targetX, targetY);
        puckErrX *= ERROR_DECAY;
        puckErrY *= ERROR_DECAY;
        opponentErrX *= ERROR_DECAY;
        opponentErrY *= ERROR_DECAY;
        return seq;
    }

    /**
     * 서버 상태를 받아 보정한다.
     *
     * @param ackSeq 서버 상태에 반영된 내 마지막 입력 번호. 아직 하나도 반영되지 않았으면 -1.
     */
    public void onSnapshot(GameState server, long ackSeq) {
        int opp = 1 - me;
        double shownPuckX = predicted.puckX + puckErrX;
        double shownPuckY = predicted.puckY + puckErrY;
        double shownOppX = predicted.malletX[opp] + opponentErrX;
        double shownOppY = predicted.malletY[opp] + opponentErrY;

        predicted.copyFrom(server);
        // 상대 입력은 알 수 없으므로 마지막으로 본 자리에 머문다고 가정한다.
        opponentX = server.malletX[opp];
        opponentY = server.malletY[opp];
        long from = Math.max(Math.max(ackSeq + 1, 0), nextSeq - HISTORY);
        for (long seq = from; seq < nextSeq; seq++) {
            int i = (int) (seq % HISTORY);
            step(inputX[i], inputY[i]);
        }

        puckErrX = shownPuckX - predicted.puckX;
        puckErrY = shownPuckY - predicted.puckY;
        if (Math.hypot(puckErrX, puckErrY) > SNAP_DISTANCE) {
            puckErrX = puckErrY = 0;
        }
        opponentErrX = shownOppX - predicted.malletX[opp];
        opponentErrY = shownOppY - predicted.malletY[opp];
        if (Math.hypot(opponentErrX, opponentErrY) > SNAP_DISTANCE) {
            opponentErrX = opponentErrY = 0;
        }
    }

    private void step(double targetX, double targetY) {
        int events = me == 0
                ? Physics.step(predicted, targetX, targetY, opponentX, opponentY)
                : Physics.step(predicted, opponentX, opponentY, targetX, targetY);
        if ((events & Physics.EVENT_GOAL_MASK) != 0) {
            // 임시 규칙: 골이면 퍽을 가운데로. 골 뒤 진행(정지·카운트다운)은 서버 규칙이 정해지면 맞춘다.
            predicted.resetPuck();
        }
    }

    /** 보정 오차를 뺀 예측 상태(테스트·디버그용). */
    public GameState predicted() {
        return predicted;
    }

    /** 화면에 그릴 상태. 퍽과 상대 말렛에 남은 보정 오차를 더한 것. */
    public GameState view() {
        int opp = 1 - me;
        view.copyFrom(predicted);
        view.puckX += puckErrX;
        view.puckY += puckErrY;
        view.malletX[opp] += opponentErrX;
        view.malletY[opp] += opponentErrY;
        return view;
    }

    /** 화면 보정 오차의 크기(px). 예측이 정확하면 0이다. */
    public double visualError() {
        return Math.hypot(puckErrX, puckErrY);
    }
}
