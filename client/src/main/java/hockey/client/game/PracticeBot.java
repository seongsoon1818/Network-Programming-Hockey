package hockey.client.game;

import hockey.common.physics.GameState;

import static hockey.common.physics.PhysicsConstants.*;

/** 연습 모드 상대. 퍽이 자기 진영에 오면 쳐내고, 아니면 골대 앞을 지킨다. 퍽은 조금 늦게 본다. */
final class PracticeBot {
    /** 사람이 이길 수 있도록 말렛 제한 속도보다 훨씬 느리게 움직인다. 경기장 크기에 맞춰 잡은 값. */
    private static final double SPEED = 650;
    /**
     * 퍽을 이만큼(틱) 늦게 본다. 사람은 보고 움직이기까지 시간이 걸리는데 봇은 매 틱 정확히 알아서,
     * 지연이 없을 때는 최고 속도 슛도 93% 막았다(사실상 못 이김). 8틱은 조금 쉬워서 6틱(0.1초)으로
     * 줄였다. 좋은 슛은 여전히 셋 중 하나 이상 들어가고 느린 슛은 막는다.
     */
    static final int REACTION_TICKS = 6;

    private final int player;
    double targetX, targetY;
    /** 최근 퍽 위치(고리 버퍼). REACTION_TICKS 전 위치를 꺼내 쓴다. */
    private final double[] seenX = new double[REACTION_TICKS + 1];
    private final double[] seenY = new double[REACTION_TICKS + 1];
    private int seen;

    PracticeBot(int player) {
        this.player = player;
    }

    /** 이번 틱의 목표 위치를 targetX, targetY에 채운다. */
    void think(GameState s) {
        seenX[seen % seenX.length] = s.puckX;
        seenY[seen % seenY.length] = s.puckY;
        seen++;
        int delayed = (seen - 1 - Math.min(REACTION_TICKS, seen - 1)) % seenX.length;

        // 위쪽 플레이어 기준으로 계산하고, 아래쪽이면 y를 뒤집는다.
        double puckX = seenX[delayed];
        double puckY = mirror(seenY[delayed]);
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
