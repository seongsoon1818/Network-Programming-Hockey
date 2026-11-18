package hockey.common.physics;

import static hockey.common.physics.PhysicsConstants.*;

import java.util.Random;

/** 외부 라이브러리 없이 돌리는 물리 검증. main을 실행하면 실패 시 예외로 끝난다. */
public final class PhysicsSelfTest {

    public static void main(String[] args) {
        wallBounce();
        wallBounceKeepsAngle();
        malletSpeedLimitAndHalfCourt();
        malletHitsPuck();
        goalScoredThroughMouth();
        postDeflectsPuck();
        pinnedPuckPushesMalletBack();
        pinnedPuckDoesNotSpeedUp();
        puckEscapesCorner();
        roundCornersHoldPuckAndMallet();
        offCenterHitGoesAlongContactLine();
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

    /** 벽에 비스듬히 맞으면 같은 각도로 나가고 속도만 WALL_RESTITUTION만큼 준다(벽과 점점 나란해지지 않는다). */
    private static void wallBounceKeepsAngle() {
        GameState s = new GameState();
        s.puckX = FIELD_WIDTH - 100;
        s.puckY = FIELD_HEIGHT * 0.7;
        s.puckVx = 800;
        s.puckVy = -400;
        double inAngle = Math.atan2(Math.abs(s.puckVx), Math.abs(s.puckVy));
        double inSpeed = Math.hypot(s.puckVx, s.puckVy);
        int events = 0;
        for (int i = 0; i < 20 && (events & Physics.EVENT_WALL_HIT) == 0; i++) {
            events |= idle(s);
        }
        check((events & Physics.EVENT_WALL_HIT) != 0, "오른쪽 벽에 맞아야 한다");
        double outAngle = Math.atan2(Math.abs(s.puckVx), Math.abs(s.puckVy));
        double outSpeed = Math.hypot(s.puckVx, s.puckVy);
        check(s.puckVx < 0 && s.puckVy < 0, "왼쪽 위로 튕겨야 한다");
        check(Math.abs(outAngle - inAngle) < 1e-9, "반사 각도가 그대로여야 한다");
        // 맞은 틱까지 감쇠가 몇 번 들어갔는지 모르니 범위로 본다.
        check(outSpeed <= inSpeed * WALL_RESTITUTION + 1e-6 && outSpeed > inSpeed * WALL_RESTITUTION * 0.9,
                "속도는 WALL_RESTITUTION만큼 준다: " + outSpeed);
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
        // 처음 맞은 틱에서 본다(더 돌리면 퍽이 위 골대까지 가서 멈출 수 있다).
        int events = 0;
        for (int i = 0; i < 30 && (events & Physics.EVENT_MALLET_HIT) == 0; i++) {
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

    /** 벽에 붙은 퍽을 말렛으로 밀어도 퍽이 말렛보다 빨라지지 않는다(예전에는 9배까지 빨라졌다). */
    private static void pinnedPuckDoesNotSpeedUp() {
        double push = 200;
        // 오른쪽 벽, 아래 벽(둥근 모서리와 골대 입구 사이의 평평한 곳)에서 각각 정면으로 밀어붙인다.
        double[][] cases = {
                {FIELD_WIDTH - PUCK_RADIUS, FIELD_HEIGHT * 0.65, 1, 0},
                {(CORNER_RADIUS + GOAL_LEFT) / 2, FIELD_HEIGHT - PUCK_RADIUS, 0, 1},
        };
        for (double[] c : cases) {
            GameState s = new GameState();
            s.puckX = c[0];
            s.puckY = c[1];
            s.malletX[0] = c[0] - c[2] * 50;
            s.malletY[0] = c[1] - c[3] * 50;
            double fastest = 0;
            for (int tick = 0; tick < 60; tick++) {
                double tx = s.malletX[0] + c[2] * push * DT;
                double ty = s.malletY[0] + c[3] * push * DT;
                Physics.step(s, tx, ty, s.malletX[1], s.malletY[1]);
                fastest = Math.max(fastest, Math.hypot(s.puckVx, s.puckVy));
            }
            check(fastest <= push + 1e-6, "벽에 낀 퍽이 미는 말렛보다 빨라지면 안 된다: " + fastest);
        }
    }

    /**
     * 구석의 퍽을 계속 눌러도 퍽이 둥근 곡면을 따라 빠져나온다. 직각 구석이었을 때는 말렛이
     * 퍽 뒤로 갈 수 없어 그대로 끼었다. 사람처럼 퍽 근처를 조금씩 흔들며 겨눈다.
     */
    private static void puckEscapesCorner() {
        Random random = new Random(1);
        double deep = (CORNER_RADIUS - PUCK_RADIUS) / Math.sqrt(2);
        for (int corner = 0; corner < 2; corner++) {
            double cx = corner == 0 ? CORNER_RADIUS - deep : FIELD_WIDTH - CORNER_RADIUS + deep;
            double cy = FIELD_HEIGHT - CORNER_RADIUS + deep;
            for (int trial = 0; trial < 50; trial++) {
                GameState s = new GameState();
                s.puckX = cx;
                s.puckY = cy;
                s.malletX[0] = cx + (corner == 0 ? 1 : -1) * 80;
                s.malletY[0] = cy - 80;
                int tick = 0;
                while (Math.hypot(s.puckX - cx, s.puckY - cy) < 120 && tick < 3 * TICK_RATE) {
                    double tx = s.puckX + (random.nextDouble() - 0.5) * 30;
                    double ty = s.puckY + (random.nextDouble() - 0.5) * 30;
                    Physics.step(s, tx, ty, s.malletX[1], s.malletY[1]);
                    tick++;
                }
                check(tick < 3 * TICK_RATE, "구석의 퍽이 3초 안에 빠져나와야 한다(corner " + corner + ", trial " + trial + ")");
            }
        }
    }

    /** 퍽과 말렛 모두 둥근 모서리 밖으로 나가지 않는다. */
    private static void roundCornersHoldPuckAndMallet() {
        // 퍽만 왼쪽 아래 모서리로 대각선으로 쏜다(말렛은 반대편에 가만히).
        GameState s = new GameState();
        s.puckX = 200;
        s.puckY = FIELD_HEIGHT - 200;
        s.puckVx = -1200;
        s.puckVy = 1200;
        s.malletX[0] = FIELD_WIDTH - 100;
        int events = 0;
        for (int tick = 0; tick < 30; tick++) {
            events |= idle(s);
            checkInsideCorner(s.puckX, s.puckY, PUCK_RADIUS, "퍽", tick);
        }
        check((events & Physics.EVENT_WALL_HIT) != 0, "모서리에 맞으면 벽 충돌 이벤트");

        // 말렛을 모서리 너머로 끌어도 곡면 안에 머문다.
        s = new GameState();
        s.puckX = FIELD_WIDTH - 100; // 말렛 경로에서 치움
        for (int tick = 0; tick < 30; tick++) {
            Physics.step(s, 0, FIELD_HEIGHT, s.malletX[1], s.malletY[1]);
            checkInsideCorner(s.malletX[0], s.malletY[0], MALLET_RADIUS, "말렛", tick);
        }
        check(s.malletX[0] < CORNER_RADIUS && s.malletY[0] > FIELD_HEIGHT - CORNER_RADIUS, "말렛이 모서리까지 감");
    }

    /** 왼쪽 아래 모서리 사분면에 있으면 곡면 안쪽인지 본다. */
    private static void checkInsideCorner(double x, double y, double radius, String what, int tick) {
        double ox = x - CORNER_RADIUS;
        double oy = y - (FIELD_HEIGHT - CORNER_RADIUS);
        if (ox < 0 && oy > 0) {
            check(Math.hypot(ox, oy) <= CORNER_RADIUS - radius + 1e-6, what + "이 모서리 곡면 밖으로 나감 (tick " + tick + ")");
        }
    }

    /**
     * 멈춘 퍽을 옆으로 b만큼 비껴 치면 퍽은 맞닿은 순간의 중심선 방향(수직에서 asin(b/44)도)으로
     * 나가야 한다. 말렛이 아무리 빨라도, 어느 시점에 닿든 같아야 한다. 예전에는 빠르게 칠수록
     * 옆으로 더 나갔다(6000px/s로 10px 비껴 치면 13도가 아니라 최대 57도).
     */
    private static void offCenterHitGoesAlongContactLine() {
        double contact = MALLET_RADIUS + PUCK_RADIUS;
        for (double speed : new double[]{600, 3000, MALLET_MAX_SPEED}) {
            for (double offset : new double[]{0, 10, 25, 38}) {
                double expected = Math.toDegrees(Math.asin(offset / contact));
                for (double phase = 0; phase < 1; phase += 0.1) {
                    GameState s = new GameState();
                    s.puckX = FIELD_WIDTH / 2 + offset;
                    s.puckY = FIELD_HEIGHT * 0.6;
                    s.malletX[0] = FIELD_WIDTH / 2;
                    s.malletY[0] = s.puckY + contact + 5 + phase * speed * DT;
                    for (int tick = 0; tick < 30; tick++) {
                        int events = Physics.step(s, FIELD_WIDTH / 2, s.malletY[0] - speed * DT, s.malletX[1], s.malletY[1]);
                        if ((events & Physics.EVENT_MALLET_HIT) != 0) {
                            break;
                        }
                    }
                    double actual = Math.toDegrees(Math.atan2(s.puckVx, -s.puckVy));
                    check(Math.abs(actual - expected) < 0.5, "비껴 친 각도: 속도 " + speed + ", 비낀 거리 " + offset
                            + ", 기대 " + expected + "도, 실제 " + actual + "도");
                }
            }
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
