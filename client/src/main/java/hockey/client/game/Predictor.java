package hockey.client.game;

import hockey.common.physics.GameState;
import hockey.common.rules.MatchEngine;
import hockey.common.rules.MatchSnapshot;

import java.util.Random;

/**
 * 클라이언트 예측·보정.
 *
 * 서버 상태는 지연 때문에 늘 과거의 것이다. 그래서 클라이언트는 자기 입력을 보내는 동시에
 * 서버와 같은 {@link MatchEngine}으로 먼저 계산해 그리고(예측), 서버 상태가 오면 그 상태에서 아직 서버가
 * 처리하지 않은 내 입력만 다시 돌려 맞춘다(보정). 경기 규칙까지 같은 코드로 돌리므로 골·카운트다운 동안
 * 퍽이 서브 자리에 멈추는 것도 서버와 같다. 보정으로 생긴 위치 차이는 한 번에 옮기지 않고 몇 프레임에 걸쳐 줄인다.
 *
 * 프로토콜과는 분리돼 있다. 필요한 것은 "서버 상태"와 "그 상태에 반영된 내 마지막 입력 번호"뿐이다.
 * 모든 메서드는 EDT에서 부른다.
 */
public final class Predictor {
    /** 다시 돌릴 수 있는 입력 개수(틱). 256틱 ≈ 4.3초 지연까지. */
    private static final int HISTORY = 256;
    /** 보정 오차가 이보다 크면 부드럽게 옮기지 않고 바로 맞춘다(골 뒤 리셋 등). */
    private static final double SNAP_DISTANCE = 120;
    /** 틱마다 남기는 오차 비율. 0.8이면 약 10틱(0.17초) 뒤 10%가 남는다. */
    private static final double ERROR_DECAY = 0.8;

    private final int me;
    private final MatchEngine predicted;
    private final GameState view = new GameState();
    private final double[] inputX = new double[HISTORY];
    private final double[] inputY = new double[HISTORY];
    private long nextSeq;
    private double opponentX, opponentY;
    // 화면에만 더하는 오차(예측 위치 → 직전까지 그리던 위치)
    private double puckErrX, puckErrY, opponentErrX, opponentErrY;

    public Predictor(int me, int targetScore) {
        this.me = me;
        // 첫 서브를 정하는 난수는 의미 없다. 첫 서버 상태가 오면 통째로 덮어쓴다.
        this.predicted = new MatchEngine(targetScore, new Random(0));
        opponentX = state().malletX[1 - me];
        opponentY = state().malletY[1 - me];
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
    public void onSnapshot(MatchSnapshot server, long ackSeq) {
        int opp = 1 - me;
        GameState s = state();
        double shownPuckX = s.puckX + puckErrX;
        double shownPuckY = s.puckY + puckErrY;
        double shownOppX = s.malletX[opp] + opponentErrX;
        double shownOppY = s.malletY[opp] + opponentErrY;

        predicted.restore(server);
        // 상대 입력은 알 수 없으므로 마지막으로 본 자리에 머문다고 가정한다.
        opponentX = s.malletX[opp];
        opponentY = s.malletY[opp];
        long from = Math.max(Math.max(ackSeq + 1, 0), nextSeq - HISTORY);
        for (long seq = from; seq < nextSeq; seq++) {
            int i = (int) (seq % HISTORY);
            step(inputX[i], inputY[i]);
        }

        puckErrX = shownPuckX - s.puckX;
        puckErrY = shownPuckY - s.puckY;
        if (Math.hypot(puckErrX, puckErrY) > SNAP_DISTANCE) {
            puckErrX = puckErrY = 0;
        }
        opponentErrX = shownOppX - s.malletX[opp];
        opponentErrY = shownOppY - s.malletY[opp];
        if (Math.hypot(opponentErrX, opponentErrY) > SNAP_DISTANCE) {
            opponentErrX = opponentErrY = 0;
        }
    }

    private void step(double targetX, double targetY) {
        if (me == 0) {
            predicted.tick(targetX, targetY, opponentX, opponentY);
        } else {
            predicted.tick(opponentX, opponentY, targetX, targetY);
        }
    }

    private GameState state() {
        return predicted.state();
    }

    /** 보정 오차를 뺀 예측 상태(테스트·디버그용). */
    public GameState predicted() {
        return state();
    }

    /** 화면에 그릴 상태. 퍽과 상대 말렛에 남은 보정 오차를 더한 것. */
    public GameState view() {
        int opp = 1 - me;
        view.copyFrom(state());
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
