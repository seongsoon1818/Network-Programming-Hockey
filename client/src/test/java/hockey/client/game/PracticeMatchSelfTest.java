package hockey.client.game;

import hockey.common.physics.GameState;
import hockey.common.rules.MatchEngine;
import hockey.common.rules.ServeRules;

import java.util.Random;

import static hockey.common.physics.PhysicsConstants.*;

/** 연습 판을 화면 없이 돌려 규칙(카운트다운 → 진행 → 득점 → 종료 → 다시 시작, 서브)을 확인한다. */
public final class PracticeMatchSelfTest {
    public static void main(String[] args) {
        for (int me = 0; me < 2; me++) {
            for (int server = 0; server < 2; server++) {
                countdownHoldsPuck(me, server);
                playsToTheEnd(me, server);
            }
            holdingTooLongLosesServe(me);
        }
        firstServeIsRandom();
        System.out.println("PracticeMatchSelfTest: 통과");
    }

    private static void countdownHoldsPuck(int me, int server) {
        PracticeMatch match = serverIs(me, server);
        check(match.phase() == MatchEngine.Phase.COUNTDOWN, "시작은 카운트다운");
        check("3".equals(match.overlay()), "카운트다운은 3부터");
        check((match.server() == me ? "내 서브" : "상대 서브").equals(match.overlayDetail()), "서브하는 쪽 표시");
        double serveX = match.state().puckX;
        double serveY = match.state().puckY;
        check(ServeRules.sideOf(serveY) == match.server(), "퍽은 서브하는 쪽 진영에서 시작");
        // 카운트다운 동안 퍽 자리를 마우스로 겨눠도 퍽은 움직이지 않는다.
        for (int i = 0; i < 3 * TICK_RATE - 1; i++) {
            match.tick(serveX, serveY);
            GameState s = match.state();
            check(s.puckX == serveX && s.puckY == serveY, "카운트다운 중 퍽 고정");
            double gap = Math.hypot(s.malletX[me] - s.puckX, s.malletY[me] - s.puckY);
            check(gap >= MALLET_RADIUS + PUCK_RADIUS - 1e-6, "카운트다운 중 말렛이 퍽과 겹치지 않음: " + gap);
        }
        check("1".equals(match.overlay()), "마지막 1초 표시");
        match.tick(serveX, serveY);
        check(match.phase() == MatchEngine.Phase.PLAYING && match.overlay() == null, "카운트다운 뒤 진행");
    }

    private static void playsToTheEnd(int me, int server) {
        int target = 3;
        PracticeMatch match = serverIs(me, server);
        java.util.Random random = new java.util.Random(7 + me);
        double tx = FIELD_WIDTH / 2, ty = FIELD_HEIGHT / 2;
        int ticks = 0;
        int limit = 60 * TICK_RATE * 30;
        int goals = 0;
        while (match.phase() != MatchEngine.Phase.FINISHED && ticks < limit) {
            // 사람 흉내: 가끔 퍽 쪽으로, 가끔 아무 데나.
            if (ticks % 20 == 0) {
                GameState s = match.state();
                boolean chase = random.nextInt(3) > 0;
                tx = chase ? s.puckX : random.nextDouble() * FIELD_WIDTH;
                ty = chase ? s.puckY : random.nextDouble() * FIELD_HEIGHT;
            }
            int score0 = match.score(0);
            int score1 = match.score(1);
            match.tick(tx, ty);
            ticks++;
            GameState s = match.state();
            if (match.score(0) != score0 || match.score(1) != score1) {
                goals++;
                int scorer = match.score(0) != score0 ? 0 : 1;
                check(match.server() == 1 - scorer, "실점한 쪽이 서브");
                check(ServeRules.sideOf(s.puckY) == match.server(), "골 뒤 퍽은 실점한 쪽 진영");
            }
            check(s.puckX >= 0 && s.puckX <= FIELD_WIDTH, "퍽 x 범위");
            check(match.score(0) <= target && match.score(1) <= target, "점수는 목표를 넘지 않음");
        }
        check(match.phase() == MatchEngine.Phase.FINISHED, "30분 안에 판이 끝남(me=" + me + ")");
        int winner = match.score(0) == target ? 0 : 1;
        check(match.score(winner) == target && match.score(1 - winner) < target, "한쪽만 목표 점수");
        check((winner == me ? "승리!" : "패배").equals(match.overlay()), "결과 표시");
        check(goals == match.score(0) + match.score(1), "골마다 서브 확인");
        System.out.printf("  me=%d 첫 서브=%d: %d틱(%.0f초) 만에 %d:%d로 종료%n", me, server, ticks,
                ticks / 60.0, match.score(0), match.score(1));

        double frozenX = match.state().malletX[me];
        match.tick(0, 0);
        check(frozenX == match.state().malletX[me], "끝난 판은 진행하지 않음");
        match.onClick();
        check(match.phase() == MatchEngine.Phase.COUNTDOWN && match.score(0) == 0 && match.score(1) == 0,
                "클릭하면 다시 시작");
    }

    /** 내가 서브한 퍽을 치지 않고 10초 버티면 서브권이 상대에게 넘어간다. */
    private static void holdingTooLongLosesServe(int me) {
        PracticeMatch match = serverIs(me, me);
        // 내 골대 앞 구석에 가만히 있는다(퍽에 닿지 않는 자리).
        double homeX = MALLET_RADIUS;
        double homeY = me == 0 ? FIELD_HEIGHT - MALLET_RADIUS : MALLET_RADIUS;
        int countdown = 0;
        while (match.phase() == MatchEngine.Phase.COUNTDOWN) {
            match.tick(homeX, homeY);
            countdown++;
        }
        check(countdown == 3 * TICK_RATE, "카운트다운 3초");
        for (int i = 1; i < ServeRules.HOLD_LIMIT_TICKS; i++) {
            match.tick(homeX, homeY);
            check(match.phase() == MatchEngine.Phase.PLAYING, "10초 전에는 계속 진행: " + i);
            boolean warn = ServeRules.HOLD_LIMIT_TICKS - i <= PracticeMatch.HOLD_WARNING_TICKS;
            check((match.hint() != null) == warn, "남은 5초부터 안내: " + i + " " + match.hint());
        }
        check("1초 안에 상대 진영으로 넘기세요".equals(match.hint()), "마지막 안내 문구: " + match.hint());
        match.tick(homeX, homeY);
        check(match.phase() == MatchEngine.Phase.HOLD_TIMEOUT, "10초가 되면 시간 초과");
        check("시간 초과".equals(match.overlay()) && "상대 서브".equals(match.overlayDetail()), "시간 초과 표시");
        check(match.server() == 1 - me, "서브권이 상대에게");
        check(ServeRules.sideOf(match.state().puckY) == 1 - me, "퍽은 상대 진영에 놓임");
        check(match.score(0) == 0 && match.score(1) == 0, "시간 초과는 점수가 아님");
        check(match.hint() == null, "멈춘 동안 안내 없음");
    }

    /** 같은 난수 순서라도 양쪽이 고르게 첫 서브를 받는다. */
    private static void firstServeIsRandom() {
        int[] count = new int[2];
        Random random = new Random(1);
        for (int i = 0; i < 200; i++) {
            count[new PracticeMatch(0, "tester", 3, random).server()]++;
        }
        check(count[0] > 60 && count[1] > 60, "첫 서브가 한쪽으로 쏠리지 않음: " + count[0] + ":" + count[1]);
    }

    /** 첫 서브가 원하는 쪽이 될 때까지 시드를 바꿔 판을 만든다. */
    private static PracticeMatch serverIs(int me, int server) {
        for (long seed = 0; ; seed++) {
            PracticeMatch match = new PracticeMatch(me, "tester", 3, new Random(seed));
            if (match.server() == server) {
                return match;
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
