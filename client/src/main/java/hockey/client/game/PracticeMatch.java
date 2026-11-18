package hockey.client.game;

import hockey.common.physics.GameState;
import hockey.common.physics.Physics;
import hockey.common.rules.ServeRules;

import java.util.Random;
import java.util.random.RandomGenerator;

import static hockey.common.physics.PhysicsConstants.*;

/**
 * 서버 없이 혼자 하는 연습 판. 서버가 쓸 것과 같은 {@link Physics}를 그대로 돌리므로
 * 조작감과 화면을 서버 없이 확인할 수 있다. 서브 규칙은 {@link ServeRules}를 따르고,
 * 카운트다운·득점 후 대기 시간은 연습용 임시 값이다.
 */
public final class PracticeMatch implements Match {
    enum Phase { COUNTDOWN, PLAYING, GOAL, HOLD_TIMEOUT, FINISHED }

    private static final int COUNTDOWN_TICKS = 3 * TICK_RATE;
    private static final int GOAL_TICKS = TICK_RATE + TICK_RATE / 2;
    /** 퍽을 넘기라는 안내를 띄우기 시작하는 남은 시간. */
    static final int HOLD_WARNING_TICKS = 5 * TICK_RATE;
    /** 퍽이 멈춰 있는 동안 말렛이 퍽에 닿지 못하게 두는 거리. */
    private static final double KEEP_AWAY = MALLET_RADIUS + PUCK_RADIUS + 4;

    private final int me;
    private final String myName;
    private final int targetScore;
    private final GameState state = new GameState();
    private final PracticeBot bot;
    private final RandomGenerator random;
    private final ServeRules.HoldTimer holdTimer = new ServeRules.HoldTimer();
    private final int[] score = new int[2];
    private Phase phase;
    private int phaseTicks;
    private int lastScorer = -1;
    private int server;

    public PracticeMatch(int me, String myName, int targetScore) {
        this(me, myName, targetScore, new Random());
    }

    /** 첫 서브를 정할 난수를 고정할 수 있다(테스트용). */
    PracticeMatch(int me, String myName, int targetScore, RandomGenerator random) {
        this.me = me;
        this.random = random;
        this.myName = myName;
        this.targetScore = targetScore;
        this.bot = new PracticeBot(1 - me);
        restart();
    }

    private void restart() {
        state.reset();
        score[0] = score[1] = 0;
        lastScorer = -1;
        serve(ServeRules.firstServer(random), Phase.COUNTDOWN, COUNTDOWN_TICKS);
    }

    /** 퍽을 서브하는 쪽 진영에 놓고, 정해진 시간 동안 멈춰 둔다. */
    private void serve(int nextServer, Phase next, int ticks) {
        server = nextServer;
        state.placePuckForServe(server);
        enter(next, ticks);
    }

    private void enter(Phase next, int ticks) {
        phase = next;
        phaseTicks = ticks;
    }

    @Override
    public void tick(double targetX, double targetY) {
        if (phase == Phase.FINISHED) {
            return;
        }
        bot.think(state);
        double[] tx = new double[2];
        double[] ty = new double[2];
        tx[me] = targetX;
        ty[me] = targetY;
        tx[1 - me] = bot.targetX;
        ty[1 - me] = bot.targetY;

        if (phase != Phase.PLAYING) {
            // 퍽은 서브 자리에 멈춰 있고 말렛만 움직인다.
            keepAwayFromPuck(tx, ty, 0);
            keepAwayFromPuck(tx, ty, 1);
            Physics.step(state, tx[0], ty[0], tx[1], ty[1]);
            state.placePuckForServe(server);
            if (--phaseTicks <= 0) {
                holdTimer.reset();
                enter(Phase.PLAYING, 0);
            }
            return;
        }

        int events = Physics.step(state, tx[0], ty[0], tx[1], ty[1]);
        if ((events & Physics.EVENT_GOAL_MASK) != 0) {
            lastScorer = (events & Physics.EVENT_GOAL_P0) != 0 ? 0 : 1;
            score[lastScorer]++;
            int next = ServeRules.serverAfterGoal(lastScorer);
            if (score[lastScorer] >= targetScore) {
                serve(next, Phase.FINISHED, 0);
            } else {
                serve(next, Phase.GOAL, GOAL_TICKS);
            }
            return;
        }
        int holder = holdTimer.update(state.puckY);
        if (holder != -1) {
            serve(1 - holder, Phase.HOLD_TIMEOUT, GOAL_TICKS);
        }
    }

    /** 목표가 퍽과 너무 가까우면 퍽 바깥으로 밀어낸다. */
    private void keepAwayFromPuck(double[] tx, double[] ty, int i) {
        double dx = tx[i] - state.puckX;
        double dy = ty[i] - state.puckY;
        double dist = Math.hypot(dx, dy);
        if (dist >= KEEP_AWAY) {
            return;
        }
        if (dist < 1e-6) {
            dx = 0;
            dy = i == 0 ? 1 : -1;
            dist = 1;
        }
        tx[i] = state.puckX + dx / dist * KEEP_AWAY;
        ty[i] = state.puckY + dy / dist * KEEP_AWAY;
    }

    Phase phase() {
        return phase;
    }

    /** 지금(또는 곧) 서브하는 플레이어. */
    int server() {
        return server;
    }

    @Override
    public int me() {
        return me;
    }

    @Override
    public GameState state() {
        return state;
    }

    @Override
    public int score(int player) {
        return score[player];
    }

    @Override
    public String name(int player) {
        return player == me ? myName : "연습 상대";
    }

    @Override
    public int targetScore() {
        return targetScore;
    }

    @Override
    public String overlay() {
        return switch (phase) {
            case COUNTDOWN -> String.valueOf((phaseTicks + TICK_RATE - 1) / TICK_RATE);
            case GOAL -> lastScorer == me ? "득점!" : "실점";
            case HOLD_TIMEOUT -> "시간 초과";
            case FINISHED -> lastScorer == me ? "승리!" : "패배";
            case PLAYING -> null;
        };
    }

    @Override
    public String overlayDetail() {
        return switch (phase) {
            case COUNTDOWN, GOAL, HOLD_TIMEOUT -> server == me ? "내 서브" : "상대 서브";
            case FINISHED -> "경기장을 클릭하면 다시 시작합니다";
            case PLAYING -> null;
        };
    }

    @Override
    public String hint() {
        if (phase != Phase.PLAYING || holdTimer.side() != me) {
            return null;
        }
        int left = holdTimer.remainingTicks();
        if (left > HOLD_WARNING_TICKS) {
            return null;
        }
        return ((left + TICK_RATE - 1) / TICK_RATE) + "초 안에 상대 진영으로 넘기세요";
    }

    @Override
    public void onClick() {
        if (phase == Phase.FINISHED) {
            restart();
        }
    }
}
