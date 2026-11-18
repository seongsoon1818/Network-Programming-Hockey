package hockey.common.rules;

import java.util.random.RandomGenerator;

import static hockey.common.physics.PhysicsConstants.FIELD_HEIGHT;
import static hockey.common.physics.PhysicsConstants.TICK_RATE;

/**
 * 서브(선공) 규칙. 서버와 연습 모드가 같은 규칙을 쓰도록 common에 둔다.
 *
 * <ul>
 *   <li>첫 서브는 무작위로 정한다. 방장이라고 유리하지 않게 한다.</li>
 *   <li>골이 나면 실점한 쪽이 서브한다.</li>
 *   <li>서브할 때 퍽은 서브하는 쪽 진영에 멈춰 둔다
 *       ({@link hockey.common.physics.GameState#placePuckForServe}). 중앙에 두면 지연 시간이
 *       짧은 쪽이 먼저 닿아 불공평하다.</li>
 *   <li>퍽이 한쪽 진영에 {@link #HOLD_LIMIT_SECONDS}초 넘게 머물면 상대에게 서브권이 간다.</li>
 * </ul>
 */
public final class ServeRules {
    private ServeRules() {}

    public static final int HOLD_LIMIT_SECONDS = 10;
    public static final int HOLD_LIMIT_TICKS = HOLD_LIMIT_SECONDS * TICK_RATE;

    public static int firstServer(RandomGenerator random) {
        return random.nextInt(2);
    }

    public static int serverAfterGoal(int scorer) {
        return 1 - scorer;
    }

    /** 퍽이 있는 진영의 주인(0: 아래, 1: 위). 정확히 중앙선 위면 -1. */
    public static int sideOf(double puckY) {
        if (puckY > FIELD_HEIGHT / 2) {
            return 0;
        }
        if (puckY < FIELD_HEIGHT / 2) {
            return 1;
        }
        return -1;
    }

    /** 퍽이 한쪽 진영에 머문 시간을 잰다. 경기 중에만 매 틱 {@link #update}를 부른다. */
    public static final class HoldTimer {
        private int side = -1;
        private int ticks;

        /** 서브 직후처럼 처음부터 다시 잰다. */
        public void reset() {
            side = -1;
            ticks = 0;
        }

        /**
         * 한 틱 진행한다.
         *
         * @return 시간을 넘긴 플레이어 번호. 없으면 -1. 돌려준 뒤에는 처음부터 다시 잰다.
         */
        public int update(double puckY) {
            int now = sideOf(puckY);
            if (now == -1) {
                now = side; // 정확히 중앙선 위에서는 이전 진영으로 계속 센다.
                if (now == -1) {
                    return -1;
                }
            }
            if (now != side) {
                side = now;
                ticks = 0;
            }
            if (++ticks >= HOLD_LIMIT_TICKS) {
                int over = side;
                reset();
                return over;
            }
            return -1;
        }

        /** 지금 퍽이 머무는 진영. 아직 모르면 -1. */
        public int side() {
            return side;
        }

        /** 서브권이 넘어가기까지 남은 틱. */
        public int remainingTicks() {
            return side == -1 ? HOLD_LIMIT_TICKS : HOLD_LIMIT_TICKS - ticks;
        }
    }
}
