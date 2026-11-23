package hockey.client.game;

import hockey.common.rules.MatchEngine.Phase;

import static hockey.common.physics.PhysicsConstants.TICK_RATE;

/** 경기 단계를 화면 글씨로 바꾼다. 연습 판과 온라인 판이 같은 글씨를 쓰도록 모아 둔다. */
final class MatchText {
    /** 퍽을 넘기라는 안내를 띄우기 시작하는 남은 시간. */
    static final int HOLD_WARNING_TICKS = 5 * TICK_RATE;

    private MatchText() {}

    /** 화면 가운데 큰 글씨. 경기 중이면 null. */
    static String overlay(int me, Phase phase, int phaseTicksLeft, int lastScorer, int winner) {
        return switch (phase) {
            case COUNTDOWN -> String.valueOf(seconds(phaseTicksLeft));
            case GOAL -> lastScorer == me ? "득점!" : "실점";
            case HOLD_TIMEOUT -> "시간 초과";
            case FINISHED -> winner == me ? "승리!" : "패배";
            case PLAYING -> null;
        };
    }

    /** 멈춰 있는 동안 누가 서브하는지. 그 밖의 단계면 null. */
    static String serve(int me, Phase phase, int server) {
        return switch (phase) {
            case COUNTDOWN, GOAL, HOLD_TIMEOUT -> server == me ? "내 서브" : "상대 서브";
            case PLAYING, FINISHED -> null;
        };
    }

    /** 내 진영에 퍽을 오래 두고 있을 때의 경고. 없으면 null. */
    static String holdHint(int me, Phase phase, int holdSide, int holdTicksLeft) {
        if (phase != Phase.PLAYING || holdSide != me || holdTicksLeft > HOLD_WARNING_TICKS) {
            return null;
        }
        return seconds(holdTicksLeft) + "초 안에 상대 진영으로 넘기세요";
    }

    /** 남은 틱을 올림한 초. */
    private static int seconds(int ticks) {
        return (ticks + TICK_RATE - 1) / TICK_RATE;
    }
}
