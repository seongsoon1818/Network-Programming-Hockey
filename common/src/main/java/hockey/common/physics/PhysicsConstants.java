package hockey.common.physics;

/**
 * 물리 상수. (초안 — 팀 합의 전 임시 값.)
 *
 * 처음에는 경기장 480×800, 말렛 1800·퍽 2200px/s(제안서 값), 반발 0.9였다. 직접 해 보니
 * 퍽이 너무 빨라 따라가기 어렵고 말렛에 닿기만 해도 튀어 나가서, 퍽·말렛 크기는 그대로 두고
 * 경기장을 1.25배로 키우고 퍽 최고 속도와 반발을 낮췄다.
 *
 * 말렛 최고 속도는 크게 올렸다. 낮으면 마우스를 빨리 움직일 때 말렛이 커서를 일정한 속도로
 * 쫓아가다 뚝 멈춰 늦고 끊겨 보였다. 6000이면 한 틱(100px)에 대부분 커서 자리에 닿는다.
 * 그만큼 빨라진 말렛이 퍽을 스치고 지나가지 않게 서브스텝을 4에서 8로 늘렸다.
 *
 * 좌표계: 왼쪽 위가 (0,0), 단위 px. 경기장은 세로로 길다.
 *   - 플레이어 0: 아래쪽 절반, 아래 골대(y = FIELD_HEIGHT)를 지킨다.
 *   - 플레이어 1: 위쪽 절반, 위 골대(y = 0)를 지킨다.
 */
public final class PhysicsConstants {
    private PhysicsConstants() {}

    public static final int TICK_RATE = 60;
    public static final double DT = 1.0 / TICK_RATE;
    /**
     * 한 틱을 나눠 충돌을 보는 횟수. 서브스텝 하나에 말렛(6000/60/8 = 12.5px)과 퍽(약 3.3px)이
     * 함께 움직여도 16px 안이라 충돌 거리(말렛 + 퍽 반지름 = 50px)를 건너뛰지 못한다(4였을 때는 32px로 스치는 충돌을 놓쳤다).
     */
    public static final int SUBSTEPS = 8;

    public static final double FIELD_WIDTH = 600;
    public static final double FIELD_HEIGHT = 1000;

    public static final double PUCK_RADIUS = 16;
    /** 경기장을 키우며 화면에서 작아 보여 28에서 34로 키웠다(화면 속 크기가 원래와 비슷). */
    public static final double MALLET_RADIUS = 34;

    /**
     * 경기장 네 모서리를 둥글린 반지름. 직각 구석에서는 말렛이 퍽 뒤로 갈 수 없어, 퍽을 구석에
     * 대고 누르면 빠져나올 길이 말렛에 막혀 끼어 버렸다. 둥근 구석에서는 조금만 비껴 눌러도
     * 퍽이 곡면을 따라 옆으로 빠져나온다(실제 에어하키 테이블도 모서리가 둥글다).
     * 80은 너무 커서 구석에 들어간 퍽이 곡면을 따라 돌아 다음 벽을 타고 나갔다. 말렛 반지름보다
     * 12만 크면(말렛 28, 모서리 40) 다시 끼었고, 22 크면(28, 50) 끼지 않았다. 그래서 말렛 반지름 + 22.
     */
    public static final double CORNER_RADIUS = MALLET_RADIUS + 22;

    /** 경기장 폭의 1/3. */
    public static final double GOAL_WIDTH = 200;
    public static final double GOAL_LEFT = (FIELD_WIDTH - GOAL_WIDTH) / 2;
    public static final double GOAL_RIGHT = GOAL_LEFT + GOAL_WIDTH;

    public static final double MALLET_MAX_SPEED = 1800; // px/s
    public static final double PUCK_MAX_SPEED = 2200;   // px/s
    /**
     * 퍽을 칠 때 말렛이 들어가는 최고 속도. 퍽 최고 속도보다 빠르면 친 퍽을 다시 따라잡아
     * 옆으로 밀어내므로 겨눈 방향으로 안 나간다. 퍽에 닿지 않는 이동은 MALLET_MAX_SPEED로 빠르게.
     * 0.8배로 여유를 둔 것은 퍽이 옆으로 움직이던 중이면 속도 제한에 걸려 앞쪽 성분이 줄기 때문.
     */
    public static final double STRIKE_MAX_SPEED = PUCK_MAX_SPEED * 0.8;

    /** 벽·골대 기둥에 부딪힐 때 남는 속도 비율. 반사 각도는 그대로 두고 속도 전체에 곱한다. */
    public static final double WALL_RESTITUTION = 0.85;
    /** 말렛에 부딪힐 때의 반발 계수. 멈춘 퍽을 속도 v로 치면 퍽은 (1 + 이 값) × v로 나간다. */
    public static final double MALLET_RESTITUTION = 0.6;
    /** 틱마다 퍽 속도에 곱하는 값(마찰). 1초 뒤 약 74%가 남는다. */
    public static final double PUCK_DAMPING_PER_TICK = 0.995;
}
