package hockey.common.rules;

import hockey.common.physics.GameState;
import hockey.common.physics.Physics;

import java.util.random.RandomGenerator;

import static hockey.common.physics.PhysicsConstants.MALLET_RADIUS;
import static hockey.common.physics.PhysicsConstants.PUCK_RADIUS;
import static hockey.common.physics.PhysicsConstants.TICK_RATE;

/**
 * 한 판의 경기 규칙. 서버 경기 루프와 클라이언트 연습 모드가 같은 규칙을 쓰도록 common에 둔다.
 *
 * 매 틱 두 사람의 말렛 목표 위치를 {@link #tick}에 넣으면 물리를 한 틱 돌리고, 단계(카운트다운 → 경기 →
 * 골 대기 / 시간 초과 → … → 종료), 서브({@link ServeRules}), 점수를 진행한다. 화면에 띄울 글씨는 여기
 * 두지 않는다. 클라이언트가 이 값들로 만든다.
 *
 * 같은 난수(첫 서브)와 같은 입력이면 항상 같은 결과가 나온다. 스레드 안전하지 않으니 한 스레드에서만 부른다.
 */
public final class MatchEngine {
    public enum Phase {
        /** 시작 직후. 퍽은 서브하는 쪽 진영에 멈춰 있고 말렛만 움직인다. */
        COUNTDOWN,
        /** 경기 중. */
        PLAYING,
        /** 골이 난 뒤 잠깐 멈춤. 퍽은 실점한 쪽(다음 서브) 진영에 멈춰 있다. */
        GOAL,
        /** 퍽을 한 진영에 너무 오래 둬서 서브권이 넘어간 뒤 잠깐 멈춤. */
        HOLD_TIMEOUT,
        /** 끝. 더 진행하지 않는다. */
        FINISHED
    }

    public static final int COUNTDOWN_TICKS = 3 * TICK_RATE;
    /** 골·시간 초과 뒤 멈춰 있는 시간. */
    public static final int PAUSE_TICKS = TICK_RATE + TICK_RATE / 2;
    /** 퍽이 멈춰 있는 동안 말렛이 퍽에 닿지 못하게 두는 거리. */
    private static final double KEEP_AWAY = MALLET_RADIUS + PUCK_RADIUS + 4;

    private final int targetScore;
    private final RandomGenerator random;
    private final GameState state = new GameState();
    private final ServeRules.HoldTimer holdTimer = new ServeRules.HoldTimer();
    private final int[] score = new int[2];
    private final double[] targetX = new double[2];
    private final double[] targetY = new double[2];
    private Phase phase;
    private int phaseTicks;
    private int server;
    private int lastScorer;
    private int winner;
    private boolean forfeited;
    private int ticks;

    /**
     * @param targetScore 먼저 이 점수에 닿으면 이긴다.
     * @param random      첫 서브를 정할 난수. 서버는 그냥 {@code new Random()}, 테스트는 시드를 고정한다.
     */
    public MatchEngine(int targetScore, RandomGenerator random) {
        if (targetScore <= 0) {
            throw new IllegalArgumentException("목표 점수는 1 이상이어야 합니다: " + targetScore);
        }
        this.targetScore = targetScore;
        this.random = random;
        restart();
    }

    /** 점수를 0:0으로 되돌리고 첫 서브를 새로 정해 카운트다운부터 다시 시작한다. */
    public void restart() {
        state.reset();
        score[0] = score[1] = 0;
        lastScorer = -1;
        winner = -1;
        forfeited = false;
        ticks = 0;
        serve(ServeRules.firstServer(random), Phase.COUNTDOWN, COUNTDOWN_TICKS);
    }

    /**
     * 한 틱(1/60초) 진행한다. 끝난 판이면 아무것도 하지 않는다.
     *
     * @return 이번 틱의 물리 이벤트 비트({@link Physics#EVENT_WALL_HIT} 등). 소리·효과에 쓸 수 있다.
     */
    public int tick(double target0X, double target0Y, double target1X, double target1Y) {
        if (phase == Phase.FINISHED) {
            return 0;
        }
        ticks++;
        targetX[0] = target0X;
        targetY[0] = target0Y;
        targetX[1] = target1X;
        targetY[1] = target1Y;

        if (phase != Phase.PLAYING) {
            // 퍽은 서브 자리에 멈춰 있고 말렛만 움직인다.
            keepAwayFromPuck(0);
            keepAwayFromPuck(1);
            int events = Physics.step(state, targetX[0], targetY[0], targetX[1], targetY[1]);
            state.placePuckForServe(server);
            if (--phaseTicks <= 0) {
                holdTimer.reset();
                enter(Phase.PLAYING, 0);
            }
            return events;
        }

        int events = Physics.step(state, targetX[0], targetY[0], targetX[1], targetY[1]);
        if ((events & Physics.EVENT_GOAL_MASK) != 0) {
            lastScorer = (events & Physics.EVENT_GOAL_P0) != 0 ? 0 : 1;
            score[lastScorer]++;
            int next = ServeRules.serverAfterGoal(lastScorer);
            if (score[lastScorer] >= targetScore) {
                winner = lastScorer;
                serve(next, Phase.FINISHED, 0);
            } else {
                serve(next, Phase.GOAL, PAUSE_TICKS);
            }
            return events;
        }
        int holder = holdTimer.update(state.puckY);
        if (holder != -1) {
            serve(1 - holder, Phase.HOLD_TIMEOUT, PAUSE_TICKS);
        }
        return events;
    }

    /** 기권이나 연결 끊김. 상대가 이기고 판이 끝난다. 이미 끝난 판이면 아무것도 하지 않는다. */
    public void forfeit(int loser) {
        if (phase == Phase.FINISHED) {
            return;
        }
        winner = 1 - loser;
        forfeited = true;
        enter(Phase.FINISHED, 0);
    }

    /** 퍽을 서브하는 쪽 진영에 놓고 단계를 바꾼다. */
    private void serve(int nextServer, Phase next, int pauseTicks) {
        server = nextServer;
        state.placePuckForServe(server);
        enter(next, pauseTicks);
    }

    private void enter(Phase next, int pauseTicks) {
        phase = next;
        phaseTicks = pauseTicks;
    }

    /** 목표가 멈춘 퍽과 너무 가까우면 퍽 바깥으로 밀어낸다. */
    private void keepAwayFromPuck(int player) {
        double dx = targetX[player] - state.puckX;
        double dy = targetY[player] - state.puckY;
        double dist = Math.hypot(dx, dy);
        if (dist >= KEEP_AWAY) {
            return;
        }
        if (dist < 1e-6) {
            dx = 0;
            dy = player == 0 ? 1 : -1;
            dist = 1;
        }
        targetX[player] = state.puckX + dx / dist * KEEP_AWAY;
        targetY[player] = state.puckY + dy / dist * KEEP_AWAY;
    }

    /** 지금 상태. 호출부는 읽기만 한다. */
    public GameState state() {
        return state;
    }

    public Phase phase() {
        return phase;
    }

    /** 지금 단계가 끝나기까지 남은 틱. 경기 중·종료면 0. */
    public int phaseTicksLeft() {
        return phaseTicks;
    }

    /** 지금(또는 다음) 서브하는 플레이어. */
    public int server() {
        return server;
    }

    /** 마지막으로 득점한 플레이어. 아직 없으면 -1. */
    public int lastScorer() {
        return lastScorer;
    }

    public int score(int player) {
        return score[player];
    }

    public int targetScore() {
        return targetScore;
    }

    /** 이긴 플레이어. 끝나지 않았으면 -1. */
    public int winner() {
        return winner;
    }

    /** 기권·연결 끊김으로 끝났는가. */
    public boolean forfeited() {
        return forfeited;
    }

    /** 퍽이 머무는 진영(10초 제한을 재는 쪽). 아직 모르면 -1. */
    public int holdSide() {
        return holdTimer.side();
    }

    /** 서브권이 넘어가기까지 남은 틱. */
    public int holdTicksLeft() {
        return holdTimer.remainingTicks();
    }

    /** 시작(또는 다시 시작) 뒤 진행한 틱 수. */
    public int ticks() {
        return ticks;
    }
}
