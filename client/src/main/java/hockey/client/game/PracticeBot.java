package hockey.client.game;

import hockey.common.physics.GameState;

import static hockey.common.physics.PhysicsConstants.*;

/** 연습 모드 상대. 퍽이 자기 진영에 오면 쳐내고, 아니면 골대 앞을 지킨다. */
final class PracticeBot {
    /** 사람이 이길 수 있도록 말렛 제한 속도(1800)보다 훨씬 느리게 움직인다. */
    private static final double SPEED = 520;

    private final int player;
    double targetX, targetY;

    PracticeBot(int player) {
        this.player = player;
    }

    /** 이번 틱의 목표 위치를 targetX, targetY에 채운다. */
    void think(GameState s) {
        // 위쪽 플레이어 기준으로 계산하고, 아래쪽이면 y를 뒤집는다.
        double puckX = s.puckX;
        double puckY = mirror(s.puckY);
        double malletX = s.malletX[player];
        double malletY = mirror(s.malletY[player]);

        double wantX, wantY;
        if (puckY > FIELD_HEIGHT / 2 + PUCK_RADIUS) {
            // 퍽이 상대 진영: 퍽과 골대 사이에서 기다린다.
            wantX = Math.clamp(puckX, GOAL_LEFT + 10, GOAL_RIGHT - 10);
            wantY = MALLET_RADIUS * 2.5;
        } else if (malletY < puckY - 6 || puckY < MALLET_RADIUS + PUCK_RADIUS) {
            // 퍽 뒤(골대 쪽)에 있으면 그대로 밀어 친다. 뒷벽에 붙은 퍽은 옆에서라도 건드린다.
            wantX = puckX;
            wantY = puckY;
        } else {
            // 퍽이 내 뒤에 있다: 자책골이 되지 않게 옆으로 돌아 뒤로 간다.
            double side = malletX < puckX ? -1 : 1;
            wantX = puckX + side * (MALLET_RADIUS + PUCK_RADIUS + 8);
            wantY = Math.max(MALLET_RADIUS, puckY - 50);
        }

        double dx = wantX - malletX;
        double dy = wantY - malletY;
        double dist = Math.hypot(dx, dy);
        double maxStep = SPEED * DT;
        if (dist > maxStep) {
            dx *= maxStep / dist;
            dy *= maxStep / dist;
        }
        targetX = malletX + dx;
        targetY = mirror(malletY + dy);
    }

    private double mirror(double y) {
        return player == 1 ? y : FIELD_HEIGHT - y;
    }
}
