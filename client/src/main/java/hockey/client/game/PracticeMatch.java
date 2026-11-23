package hockey.client.game;

import hockey.common.physics.GameState;
import hockey.common.rules.MatchEngine;
import hockey.common.rules.MatchEngine.Phase;

import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * 서버 없이 혼자 하는 연습 판. 경기 규칙은 서버가 쓸 것과 같은 {@link MatchEngine}이고,
 * 상대 말렛만 {@link PracticeBot}이 움직인다. 이 클래스는 그 결과를 화면 글씨로 바꾼다.
 */
public final class PracticeMatch implements Match {
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
        return MatchText.overlay(me, engine.phase(), engine.phaseTicksLeft(), engine.lastScorer(), engine.winner());
    }

    @Override
    public String overlayDetail() {
        if (engine.phase() == Phase.FINISHED) {
            return "경기장을 클릭하면 다시 시작합니다";
        }
        return MatchText.serve(me, engine.phase(), engine.server());
    }

    @Override
    public String hint() {
        return MatchText.holdHint(me, engine.phase(), engine.holdSide(), engine.holdTicksLeft());
    }

    @Override
    public void onClick() {
        if (engine.phase() == Phase.FINISHED) {
            engine.restart();
        }
    }
}
