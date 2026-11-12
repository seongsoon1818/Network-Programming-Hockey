package hockey.common.physics;

import static hockey.common.physics.PhysicsConstants.*;

import java.util.Random;

/** 외부 라이브러리 없이 돌리는 물리 검증. main을 실행하면 실패 시 예외로 끝난다. */
public final class PhysicsSelfTest {

    public static void main(String[] args) {
        wallBounce();
        malletSpeedLimitAndHalfCourt();
        malletHitsPuck();
        goalScoredThroughMouth();
        postDeflectsPuck();
        pinnedPuckPushesMalletBack();
        randomPlayStaysInBoundsAndDeterministic();
        System.out.println("PhysicsSelfTest: 모두 통과");
    }

    private static int idle(GameState s) {
        return Physics.step(s, s.malletX[0], s.malletY[0], s.malletX[1], s.malletY[1]);
    }

    private static void wallBounce() {
        GameState s = new GameState();
        s.puckVx = 600;
        int events = 0;
        for (int i = 0; i < 60; i++) events |= idle(s);
        check((events & Physics.EVENT_WALL_HIT) != 0, "벽 충돌 이벤트");
        check(s.puckVx < 0, "오른쪽 벽에 튕긴 뒤 왼쪽으로 가야 한다");
        check(s.puckX <= FIELD_WIDTH - PUCK_RADIUS, "벽 밖으로 나가면 안 된다");
    }

    private static void malletSpeedLimitAndHalfCourt() {
        GameState s = new GameState();
        s.puckX = 50; // 말렛 경로에서 치움
        s.puckY = 50;
        double beforeX = s.malletX[0], beforeY = s.malletY[0];
        Physics.step(s, 0, 0, s.malletX[1], s.malletY[1]);
        double moved = Math.hypot(s.malletX[0] - beforeX, s.malletY[0] - beforeY);
        check(moved <= MALLET_MAX_SPEED * DT + 1e-9, "말렛 속도 제한: " + moved);
        for (int i = 0; i < 120; i++) Physics.step(s, 0, 0, s.malletX[1], s.malletY[1]);
        check(s.malletY[0] >= FIELD_HEIGHT / 2 + MALLET_RADIUS - 1e-9, "말렛은 중앙선을 넘지 못한다");
        check(s.malletX[0] >= MALLET_RADIUS - 1e-9, "말렛은 벽을 넘지 못한다");
    }

    private static void malletHitsPuck() {
        GameState s = new GameState();
        s.puckY = FIELD_HEIGHT / 2 + 100; // 플레이어 0 진영에 멈춰 있는 퍽
        int events = 0;
        for (int i = 0; i < 30; i++) {
            events |= Physics.step(s, FIELD_WIDTH / 2, FIELD_HEIGHT / 2, s.malletX[1], s.malletY[1]);
        }
        check((events & Physics.EVENT_MALLET_HIT) != 0, "말렛 충돌 이벤트");
        check(s.puckVy < 0, "아래에서 친 퍽은 위로 가야 한다");
        double gap = Math.hypot(s.puckX - s.malletX[0], s.puckY - s.malletY[0]);
        check(gap >= PUCK_RADIUS + MALLET_RADIUS - 1e-6, "퍽과 말렛이 겹치면 안 된다");
    }

    private static void goalScoredThroughMouth() {
        GameState s = new GameState();
        s.malletX[0] = MALLET_RADIUS; // 골대 앞에서 치움
        s.puckVy = 1500;
        int events = 0;
        for (int i = 0; i < 120 && (events & Physics.EVENT_GOAL_MASK) == 0; i++) events = idle(s);
        check((events & Physics.EVENT_GOAL_P1) != 0, "아래 골대에 들어가면 플레이어 1 득점");

        s.reset();
        s.malletX[1] = MALLET_RADIUS;
        s.puckVy = -1500;
        events = 0;
        for (int i = 0; i < 120 && (events & Physics.EVENT_GOAL_MASK) == 0; i++) events = idle(s);
        check((events & Physics.EVENT_GOAL_P0) != 0, "위 골대에 들어가면 플레이어 0 득점");
    }

    private static void postDeflectsPuck() {
        GameState s = new GameState();
        s.malletX[1] = FIELD_WIDTH - MALLET_RADIUS;
        s.puckX = GOAL_LEFT + 4; // 왼쪽 기둥 바로 안쪽에서 위로
        s.puckY = 200;
        s.puckVy = -900;
        int events = 0;
        for (int i = 0; i < 40; i++) {
            events |= idle(s);
            if ((events & Physics.EVENT_GOAL_MASK) != 0) break;
        }
        check((events & Physics.EVENT_GOAL_MASK) == 0, "기둥에 맞으면 골이 아니다");
        check((events & Physics.EVENT_WALL_HIT) != 0, "기둥 충돌 이벤트");
    }

    /** 벽에 붙은 퍽을 말렛으로 계속 밀어도 둘이 겹치지 않아야 한다. */
    private static void pinnedPuckPushesMalletBack() {
        GameState s = new GameState();
        s.puckX = PUCK_RADIUS;
        s.puckY = 600;
        s.malletX[0] = 200;
        s.malletY[0] = 600;
        for (int tick = 0; tick < 90; tick++) {
            Physics.step(s, 0, 600, s.malletX[1], s.malletY[1]);
            double gap = Math.hypot(s.puckX - s.malletX[0], s.puckY - s.malletY[0]);
            check(gap >= PUCK_RADIUS + MALLET_RADIUS - 1e-6, "퍽과 말렛이 겹침 (tick " + tick + "): " + gap);
            check(s.puckX >= PUCK_RADIUS - 1e-6, "퍽이 벽 안으로 들어감 (tick " + tick + ")");
        }
    }

    private static void randomPlayStaysInBoundsAndDeterministic() {
        GameState a = new GameState();
        GameState b = new GameState();
        Random inputA = new Random(42);
        Random inputB = new Random(42);
        a.puckVx = b.puckVx = 700;
        a.puckVy = b.puckVy = -900;
        double[] t = new double[4];
        double[] u = new double[4];
        int goals = 0;
        for (int tick = 0; tick < 200_000; tick++) {
            if (tick % 7 == 0) {
                for (int i = 0; i < 4; i++) {
                    t[i] = inputA.nextDouble() * (i % 2 == 0 ? FIELD_WIDTH : FIELD_HEIGHT);
                    u[i] = inputB.nextDouble() * (i % 2 == 0 ? FIELD_WIDTH : FIELD_HEIGHT);
                }
            }
            int ea = Physics.step(a, t[0], t[1], t[2], t[3]);
            int eb = Physics.step(b, u[0], u[1], u[2], u[3]);
            check(ea == eb && a.puckX == b.puckX && a.puckY == b.puckY
                    && a.puckVx == b.puckVx && a.puckVy == b.puckVy, "같은 입력이면 결과가 같아야 한다 (tick " + tick + ")");
            check(!Double.isNaN(a.puckX) && !Double.isNaN(a.puckY), "NaN (tick " + tick + ")");
            check(Math.hypot(a.puckVx, a.puckVy) <= PUCK_MAX_SPEED + 1e-6, "퍽 속도 제한 (tick " + tick + ")");
            if ((ea & Physics.EVENT_GOAL_MASK) != 0) {
                goals++;
                a.resetPuck();
                b.resetPuck();
            } else {
                check(a.puckX >= PUCK_RADIUS - 1e-6 && a.puckX <= FIELD_WIDTH - PUCK_RADIUS + 1e-6
                        && a.puckY >= 0 && a.puckY <= FIELD_HEIGHT, "퍽이 경기장 밖 (tick " + tick + "): " + a.puckX + "," + a.puckY);
            }
        }
        check(goals > 0, "무작위 플레이에서 골이 한 번은 나야 한다");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
