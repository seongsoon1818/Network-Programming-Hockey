package hockey.client.game;

import hockey.common.physics.GameState;

import static hockey.common.physics.PhysicsConstants.*;

/** 연습 판을 화면 없이 돌려 규칙(카운트다운 → 진행 → 득점 → 종료 → 다시 시작)을 확인한다. */
public final class PracticeMatchSelfTest {
    public static void main(String[] args) {
        for (int me = 0; me < 2; me++) {
            countdownHoldsPuck(me);
            playsToTheEnd(me);
        }
        System.out.println("PracticeMatchSelfTest: 통과");
    }

    private static void countdownHoldsPuck(int me) {
        PracticeMatch match = new PracticeMatch(me, "tester", 3);
        check(match.phase() == PracticeMatch.Phase.COUNTDOWN, "시작은 카운트다운");
        check("3".equals(match.overlay()), "카운트다운은 3부터");
        // 카운트다운 동안 퍽 자리를 마우스로 겨눠도 퍽은 움직이지 않는다.
        for (int i = 0; i < 3 * TICK_RATE - 1; i++) {
            match.tick(FIELD_WIDTH / 2, FIELD_HEIGHT / 2);
            GameState s = match.state();
            check(s.puckX == FIELD_WIDTH / 2 && s.puckY == FIELD_HEIGHT / 2, "카운트다운 중 퍽 고정");
            double gap = Math.hypot(s.malletX[me] - s.puckX, s.malletY[me] - s.puckY);
            check(gap >= MALLET_RADIUS + PUCK_RADIUS - 1e-6, "카운트다운 중 말렛이 퍽과 겹치지 않음: " + gap);
        }
        check("1".equals(match.overlay()), "마지막 1초 표시");
        match.tick(FIELD_WIDTH / 2, FIELD_HEIGHT / 2);
        check(match.phase() == PracticeMatch.Phase.PLAYING && match.overlay() == null, "카운트다운 뒤 진행");
    }

    private static void playsToTheEnd(int me) {
        int target = 3;
        PracticeMatch match = new PracticeMatch(me, "tester", target);
        java.util.Random random = new java.util.Random(7 + me);
        double tx = FIELD_WIDTH / 2, ty = FIELD_HEIGHT / 2;
        int ticks = 0;
        int limit = 60 * TICK_RATE * 30;
        while (match.phase() != PracticeMatch.Phase.FINISHED && ticks < limit) {
            // 사람 흉내: 가끔 퍽 쪽으로, 가끔 아무 데나.
            if (ticks % 20 == 0) {
                GameState s = match.state();
                boolean chase = random.nextInt(3) > 0;
                tx = chase ? s.puckX : random.nextDouble() * FIELD_WIDTH;
                ty = chase ? s.puckY : random.nextDouble() * FIELD_HEIGHT;
            }
            match.tick(tx, ty);
            ticks++;
            GameState s = match.state();
            check(s.puckX >= 0 && s.puckX <= FIELD_WIDTH, "퍽 x 범위");
            check(match.score(0) <= target && match.score(1) <= target, "점수는 목표를 넘지 않음");
        }
        check(match.phase() == PracticeMatch.Phase.FINISHED, "30분 안에 판이 끝남(me=" + me + ")");
        int winner = match.score(0) == target ? 0 : 1;
        check(match.score(winner) == target && match.score(1 - winner) < target, "한쪽만 목표 점수");
        check((winner == me ? "승리!" : "패배").equals(match.overlay()), "결과 표시");
        System.out.printf("  me=%d: %d틱(%.0f초) 만에 %d:%d로 종료%n", me, ticks, ticks / 60.0,
                match.score(0), match.score(1));

        double frozenX = match.state().malletX[me];
        match.tick(0, 0);
        check(frozenX == match.state().malletX[me], "끝난 판은 진행하지 않음");
        match.onClick();
        check(match.phase() == PracticeMatch.Phase.COUNTDOWN && match.score(0) == 0 && match.score(1) == 0,
                "클릭하면 다시 시작");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
