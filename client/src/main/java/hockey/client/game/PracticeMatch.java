package hockey.client.game;

import hockey.common.physics.GameState;
import hockey.common.rules.MatchEngine;
import hockey.common.rules.MatchEngine.Phase;

import java.util.Random;
import java.util.random.RandomGenerator;

import static hockey.common.physics.PhysicsConstants.TICK_RATE;

/**
 * 서버 없이 혼자 하는 연습 판. 경기 규칙은 서버가 쓸 것과 같은 {@link MatchEngine}이고,
 * 상대 말렛만 {@link PracticeBot}이 움직인다. 이 클래스는 그 결과를 화면 글씨로 바꾼다.
 */
public final class PracticeMatch implements Match {
    /** 퍽을 넘기라는 안내를 띄우기 시작하는 남은 시간. */
    static final int HOLD_WARNING_TICKS = 5 * TICK_RATE;

    private final int me;
    private final String myName;
    private final MatchEngine engine;
    private final PracticeBot bot;

    public PracticeMatch(int me, String myName, int targetScore) {
        this(me, myName, targetScore, new Random());
    }

    /** 첫 서브를 정할 난수를 고정할 수 있다(테스트용). */
    PracticeMatch(int me, String myName, int targetScore, RandomGenerator random) {
        this.me = me;
        this.myName = myName;
        this.engine = new MatchEngine(targetScore, random);
        this.bot = new PracticeBot(1 - me);
    }

    @Override
    public void tick(double targetX, double targetY) {
        if (engine.phase() == Phase.FINISHED) {
            return;
        }
        bot.think(engine.state());
        if (me == 0) {
            engine.tick(targetX, targetY, bot.targetX, bot.targetY);
        } else {
            engine.tick(bot.targetX, bot.targetY, targetX, targetY);
        }
    }

    Phase phase() {
        return engine.phase();
    }

    /** 지금(또는 곧) 서브하는 플레이어. */
    int server() {
        return engine.server();
    }

    @Override
    public int me() {
        return me;
    }

    @Override
    public GameState state() {
        return engine.state();
    }

    @Override
    public int score(int player) {
        return engine.score(player);
    }

    @Override
    public String name(int player) {
        return player == me ? myName : "연습 상대";
    }

    @Override
    public int targetScore() {
        return engine.targetScore();
    }

    @Override
    public String overlay() {
        return switch (engine.phase()) {
            case COUNTDOWN -> String.valueOf((engine.phaseTicksLeft() + TICK_RATE - 1) / TICK_RATE);
            case GOAL -> engine.lastScorer() == me ? "득점!" : "실점";
            case HOLD_TIMEOUT -> "시간 초과";
            case FINISHED -> engine.winner() == me ? "승리!" : "패배";
            case PLAYING -> null;
        };
    }

    @Override
    public String overlayDetail() {
        return switch (engine.phase()) {
            case COUNTDOWN, GOAL, HOLD_TIMEOUT -> engine.server() == me ? "내 서브" : "상대 서브";
            case FINISHED -> "경기장을 클릭하면 다시 시작합니다";
            case PLAYING -> null;
        };
    }

    @Override
    public String hint() {
        if (engine.phase() != Phase.PLAYING || engine.holdSide() != me) {
            return null;
        }
        int left = engine.holdTicksLeft();
        if (left > HOLD_WARNING_TICKS) {
            return null;
        }
        return ((left + TICK_RATE - 1) / TICK_RATE) + "초 안에 상대 진영으로 넘기세요";
    }

    @Override
    public void onClick() {
        if (engine.phase() == Phase.FINISHED) {
            engine.restart();
        }
    }
}
