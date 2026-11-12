package hockey.common.physics;

/**
 * 물리 상수. (초안 — 속도 제한 두 개만 제안서 값이고 나머지는 팀 합의 전 임시 값.)
 *
 * 좌표계: 왼쪽 위가 (0,0), 단위 px. 경기장은 세로로 길다.
 *   - 플레이어 0: 아래쪽 절반, 아래 골대(y = FIELD_HEIGHT)를 지킨다.
 *   - 플레이어 1: 위쪽 절반, 위 골대(y = 0)를 지킨다.
 */
public final class PhysicsConstants {
    private PhysicsConstants() {}

    public static final int TICK_RATE = 60;
    public static final double DT = 1.0 / TICK_RATE;
    public static final int SUBSTEPS = 4;

    public static final double FIELD_WIDTH = 480;
    public static final double FIELD_HEIGHT = 800;

    public static final double PUCK_RADIUS = 16;
    public static final double MALLET_RADIUS = 28;

    public static final double GOAL_WIDTH = 160;
    public static final double GOAL_LEFT = (FIELD_WIDTH - GOAL_WIDTH) / 2;
    public static final double GOAL_RIGHT = GOAL_LEFT + GOAL_WIDTH;

    public static final double MALLET_MAX_SPEED = 1800; // px/s (제안서)
    public static final double PUCK_MAX_SPEED = 2200;   // px/s (제안서)

    /** 벽·골대 기둥에 부딪힐 때 남는 속도 비율. */
    public static final double WALL_RESTITUTION = 0.9;
    /** 말렛에 부딪힐 때의 반발 계수. */
    public static final double MALLET_RESTITUTION = 0.9;
    /** 틱마다 퍽 속도에 곱하는 값(마찰). 1초 뒤 약 74%가 남는다. */
    public static final double PUCK_DAMPING_PER_TICK = 0.995;
}
