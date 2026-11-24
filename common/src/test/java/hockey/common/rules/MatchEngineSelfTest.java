package hockey.common.rules;

import hockey.common.physics.GameState;
import hockey.common.rules.MatchEngine.Phase;

import java.util.Random;

import static hockey.common.physics.PhysicsConstants.*;

/** 경기 규칙만 확인한다(봇·화면 없이). main을 실행하면 실패 시 예외로 끝난다. */
public final class MatchEngineSelfTest {
    public static void main(String[] args) {
        for (int server = 0; server < 2; server++) {
            countdownHoldsPuckOnServerSide(server);
            holdingTooLongPassesServe(server);
        }
        for (int scorer = 0; scorer < 2; scorer++) {
            goalGivesServeToLoser(scorer);
        }
        finishesAtTargetScore();
        forfeitEndsMatch();
        firstServeIsRandom();
        sameSeedSameGame();
        restartResets();
        snapshotRestoreContinuesTheSameGame();
        System.out.println("MatchEngineSelfTest: 모두 통과");
    }

    /** 각자 자기 골대 쪽 구석에 가만히 있는다(퍽에 닿지 않는 자리). */
    private static final double[] HOME_X = {MALLET_RADIUS + 60, FIELD_WIDTH - MALLET_RADIUS - 60};
    private static final double[] HOME_Y = {FIELD_HEIGHT - MALLET_RADIUS - 60, MALLET_RADIUS + 60};

    private static int idle(MatchEngine m) {
        return m.tick(HOME_X[0], HOME_Y[0], HOME_X[1], HOME_Y[1]);
    }

    /** 첫 서브가 원하는 쪽이 될 때까지 시드를 바꿔 판을 만든다. */
    private static MatchEngine serverIs(int server, int targetScore) {
        for (long seed = 0; ; seed++) {
            MatchEngine m = new MatchEngine(targetScore, new Random(seed));
            if (m.server() == server) {
                return m;
            }
        }
    }

    private static void skipCountdown(MatchEngine m) {
        while (m.phase() == Phase.COUNTDOWN) {
            idle(m);
        }
    }

    /** 카운트다운 3초 동안 퍽은 서브하는 쪽 진영에 멈춰 있고, 퍽을 겨눠도 말렛이 닿지 못한다. */
    private static void countdownHoldsPuckOnServerSide(int server) {
        MatchEngine m = serverIs(server, 5);
        check(m.phase() == Phase.COUNTDOWN, "시작은 카운트다운");
        check(m.phaseTicksLeft() == MatchEngine.COUNTDOWN_TICKS, "카운트다운 3초");
        check(m.lastScorer() == -1 && m.winner() == -1, "득점·승자 없음");
        GameState s = m.state();
        double x = s.puckX;
        double y = s.puckY;
        check(ServeRules.sideOf(y) == server, "퍽은 서브하는 쪽 진영");
        for (int i = 0; i < MatchEngine.COUNTDOWN_TICKS - 1; i++) {
            m.tick(x, y, x, y); // 둘 다 퍽을 겨눈다
            check(s.puckX == x && s.puckY == y, "카운트다운 중 퍽 고정");
            for (int p = 0; p < 2; p++) {
                double gap = Math.hypot(s.malletX[p] - x, s.malletY[p] - y);
                check(gap >= MALLET_RADIUS + PUCK_RADIUS - 1e-6, "카운트다운 중 말렛이 퍽과 겹치지 않음");
            }
        }
        check(m.phase() == Phase.COUNTDOWN && m.phaseTicksLeft() == 1, "마지막 틱 전까지 카운트다운");
        m.tick(x, y, x, y);
        check(m.phase() == Phase.PLAYING && m.phaseTicksLeft() == 0, "카운트다운 뒤 경기");
    }

    /** 골이 나면 득점한 쪽 점수가 오르고, 실점한 쪽 진영에 퍽을 두고 1.5초 멈춘 뒤 이어 간다. */
    private static void goalGivesServeToLoser(int scorer) {
        MatchEngine m = serverIs(scorer, 5);
        skipCountdown(m);
        // 득점하는 쪽이 지키는 반대편 골대로 퍽을 곧장 보낸다(0번은 위 골대 y=0으로 넣어야 득점).
        GameState s = m.state();
        s.puckX = FIELD_WIDTH / 2;
        s.puckY = scorer == 0 ? 80 : FIELD_HEIGHT - 80;
        s.puckVx = 0;
        s.puckVy = scorer == 0 ? -1500 : 1500;
        int ticks = 0;
        while (m.phase() == Phase.PLAYING && ticks++ < 60) {
            m.tick(0, FIELD_HEIGHT, FIELD_WIDTH, 0); // 말렛은 골대에서 먼 구석으로 비켜 둔다
        }
        check(m.phase() == Phase.GOAL, "골 뒤 GOAL 단계: " + m.phase());
        check(m.score(scorer) == 1 && m.score(1 - scorer) == 0, "득점한 쪽 점수만 오름");
        check(m.lastScorer() == scorer, "마지막 득점");
        check(m.server() == 1 - scorer, "실점한 쪽이 서브");
        check(ServeRules.sideOf(s.puckY) == 1 - scorer, "퍽은 실점한 쪽 진영");
        check(m.phaseTicksLeft() == MatchEngine.PAUSE_TICKS, "1.5초 멈춤");
        for (int i = 0; i < MatchEngine.PAUSE_TICKS; i++) {
            idle(m);
        }
        check(m.phase() == Phase.PLAYING, "멈춘 뒤 경기 계속");
    }

    /** 목표 점수에 닿으면 끝나고, 더 진행하지 않는다. */
    private static void finishesAtTargetScore() {
        MatchEngine m = serverIs(0, 1);
        skipCountdown(m);
        GameState s = m.state();
        s.puckX = FIELD_WIDTH / 2;
        s.puckY = 80;
        s.puckVy = -1500;
        for (int i = 0; i < 60 && m.phase() == Phase.PLAYING; i++) {
            m.tick(0, FIELD_HEIGHT, FIELD_WIDTH, 0);
        }
        check(m.phase() == Phase.FINISHED, "목표 점수 1에 닿으면 끝");
        check(m.winner() == 0 && !m.forfeited(), "0번이 정상 승리");
        int before = m.ticks();
        double x = s.malletX[0];
        check(m.tick(0, 0, 0, 0) == 0, "끝난 판은 이벤트 없음");
        check(m.ticks() == before && s.malletX[0] == x, "끝난 판은 진행하지 않음");
    }

    /** 퍽을 치지 않고 10초 붙잡고 있으면 서브권이 상대에게 넘어간다. 점수는 그대로. */
    private static void holdingTooLongPassesServe(int server) {
        MatchEngine m = serverIs(server, 5);
        skipCountdown(m);
        for (int i = 1; i < ServeRules.HOLD_LIMIT_TICKS; i++) {
            idle(m);
            check(m.phase() == Phase.PLAYING, "10초 전에는 계속 진행: " + i);
            check(m.holdSide() == server, "퍽은 서브한 쪽 진영");
            check(m.holdTicksLeft() == ServeRules.HOLD_LIMIT_TICKS - i, "남은 틱: " + i);
        }
        idle(m);
        check(m.phase() == Phase.HOLD_TIMEOUT, "10초가 되면 시간 초과");
        check(m.server() == 1 - server, "서브권이 상대에게");
        check(ServeRules.sideOf(m.state().puckY) == 1 - server, "퍽은 상대 진영");
        check(m.score(0) == 0 && m.score(1) == 0 && m.lastScorer() == -1, "시간 초과는 점수가 아님");
        check(m.phaseTicksLeft() == MatchEngine.PAUSE_TICKS, "1.5초 멈춤");
    }

    /** 기권하면 상대가 이기고 끝난다. 끝난 뒤 기권은 무시한다. */
    private static void forfeitEndsMatch() {
        MatchEngine m = serverIs(0, 5);
        skipCountdown(m);
        m.forfeit(1);
        check(m.phase() == Phase.FINISHED && m.winner() == 0 && m.forfeited(), "1번 기권 → 0번 승리");
        m.forfeit(0);
        check(m.winner() == 0, "끝난 뒤 기권은 무시");
    }

    /** 첫 서브가 한쪽으로 쏠리지 않는다. */
    private static void firstServeIsRandom() {
        int[] count = new int[2];
        Random random = new Random(1);
        for (int i = 0; i < 200; i++) {
            count[new MatchEngine(3, random).server()]++;
        }
        check(count[0] > 60 && count[1] > 60, "첫 서브가 한쪽으로 쏠리지 않음: " + count[0] + ":" + count[1]);
    }

    /** 같은 시드와 같은 입력이면 같은 판이 된다(서버와 다시 돌린 결과가 같아야 한다). */
    private static void sameSeedSameGame() {
        MatchEngine a = new MatchEngine(3, new Random(42));
        MatchEngine b = new MatchEngine(3, new Random(42));
        Random inputs = new Random(9);
        for (int t = 0; t < 20 * TICK_RATE; t++) {
            double x0 = inputs.nextDouble() * FIELD_WIDTH, y0 = inputs.nextDouble() * FIELD_HEIGHT;
            double x1 = inputs.nextDouble() * FIELD_WIDTH, y1 = inputs.nextDouble() * FIELD_HEIGHT;
            int ea = a.tick(x0, y0, x1, y1);
            int eb = b.tick(x0, y0, x1, y1);
            GameState sa = a.state(), sb = b.state();
            check(ea == eb && sa.puckX == sb.puckX && sa.puckY == sb.puckY && sa.malletX[0] == sb.malletX[0]
                    && a.phase() == b.phase() && a.score(0) == b.score(0) && a.score(1) == b.score(1)
                    && a.server() == b.server(), "같은 입력 같은 결과 (tick " + t + ")");
        }
    }

    /** 다시 시작하면 점수·득점·승자·틱이 처음으로 돌아가고 카운트다운부터 한다. */
    private static void restartResets() {
        MatchEngine m = serverIs(1, 5);
        skipCountdown(m);
        m.forfeit(0);
        m.restart();
        check(m.phase() == Phase.COUNTDOWN && m.phaseTicksLeft() == MatchEngine.COUNTDOWN_TICKS, "카운트다운부터");
        check(m.score(0) == 0 && m.score(1) == 0 && m.lastScorer() == -1, "점수 초기화");
        check(m.winner() == -1 && !m.forfeited() && m.ticks() == 0, "승자·틱 초기화");
        check(ServeRules.sideOf(m.state().puckY) == m.server(), "퍽은 새 서브 쪽 진영");
    }

    /**
     * 상태를 담아 다른 엔진에 되돌리면, 같은 입력으로 계속 돌렸을 때 원래 엔진과 똑같이 진행한다.
     * 클라이언트 예측이 서버 상태에서 다시 계산할 때 기대는 성질이다. 모든 단계를 거치는지도 센다.
     */
    private static void snapshotRestoreContinuesTheSameGame() {
        MatchEngine a = new MatchEngine(99, new Random(5));
        MatchEngine b = new MatchEngine(99, new Random(6));
        Random inputs = new Random(7);
        boolean[] seen = new boolean[Phase.values().length];
        for (int t = 0; t < 120 * TICK_RATE; t++) {
            if (t % 37 == 0) {
                b.restore(a.snapshot());
                check(b.snapshot().equals(a.snapshot()), "되돌린 직후 같은 상태 (tick " + t + ")");
            }
            // 가끔은 퍽을 쫓아 골·시간 초과가 나게 한다.
            boolean chase = t % 120 < 60;
            double x0 = chase ? a.state().puckX : inputs.nextDouble() * FIELD_WIDTH;
            double y0 = chase ? a.state().puckY + 30 : inputs.nextDouble() * FIELD_HEIGHT;
            double x1 = inputs.nextDouble() * FIELD_WIDTH, y1 = inputs.nextDouble() * FIELD_HEIGHT;
            int ea = a.tick(x0, y0, x1, y1);
            int eb = b.tick(x0, y0, x1, y1);
            check(ea == eb && a.snapshot().equals(b.snapshot()), "되돌린 뒤 같은 진행 (tick " + t + ")");
            seen[a.phase().ordinal()] = true;
        }
        check(seen[Phase.COUNTDOWN.ordinal()] && seen[Phase.PLAYING.ordinal()] && seen[Phase.GOAL.ordinal()]
                && seen[Phase.HOLD_TIMEOUT.ordinal()], "카운트다운·경기·골·시간 초과를 모두 거침");

        // 끝난 상태도 되돌릴 수 있고, 목표 점수로 승자를 안다.
        MatchEngine done = serverIs(0, 1);
        skipCountdown(done);
        done.state().puckX = FIELD_WIDTH / 2;
        done.state().puckY = 80;
        done.state().puckVy = -1500;
        for (int i = 0; i < 60 && done.phase() == Phase.PLAYING; i++) {
            done.tick(0, FIELD_HEIGHT, FIELD_WIDTH, 0);
        }
        MatchEngine copy = new MatchEngine(1, new Random(0));
        copy.restore(done.snapshot());
        check(copy.phase() == Phase.FINISHED && copy.winner() == 0, "끝난 상태 되돌리기");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
