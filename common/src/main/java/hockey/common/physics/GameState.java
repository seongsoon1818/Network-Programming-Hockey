package hockey.common.physics;

import static hockey.common.physics.PhysicsConstants.FIELD_HEIGHT;
import static hockey.common.physics.PhysicsConstants.FIELD_WIDTH;
import static hockey.common.physics.PhysicsConstants.MALLET_RADIUS;

/** 한 틱의 물리 상태. 점수·시간 같은 경기 규칙은 여기 두지 않는다. */
public final class GameState {
    public double puckX, puckY, puckVx, puckVy;

    /** 인덱스 = 플레이어 번호(0: 아래, 1: 위). */
    public final double[] malletX = new double[2];
    public final double[] malletY = new double[2];
    public final double[] malletVx = new double[2];
    public final double[] malletVy = new double[2];

    public GameState() {
        reset();
    }

    /** 퍽은 중앙, 말렛은 각자 골대 앞으로. */
    public void reset() {
        resetPuck();
        malletX[0] = FIELD_WIDTH / 2;
        malletY[0] = FIELD_HEIGHT - MALLET_RADIUS * 3;
        malletX[1] = FIELD_WIDTH / 2;
        malletY[1] = MALLET_RADIUS * 3;
        malletVx[0] = malletVy[0] = malletVx[1] = malletVy[1] = 0;
    }

    public void resetPuck() {
        puckX = FIELD_WIDTH / 2;
        puckY = FIELD_HEIGHT / 2;
        puckVx = 0;
        puckVy = 0;
    }

    public void copyFrom(GameState other) {
        puckX = other.puckX;
        puckY = other.puckY;
        puckVx = other.puckVx;
        puckVy = other.puckVy;
        for (int i = 0; i < 2; i++) {
            malletX[i] = other.malletX[i];
            malletY[i] = other.malletY[i];
            malletVx[i] = other.malletVx[i];
            malletVy[i] = other.malletVy[i];
        }
    }
}
