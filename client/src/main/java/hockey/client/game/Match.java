package hockey.client.game;

import hockey.common.physics.GameState;

/**
 * 게임 화면이 그리는 한 판. 화면(GamePanel)은 판이 로컬 연습인지 서버 대결인지 모른 채
 * 60Hz로 {@link #tick}을 부르고 {@link #state()}를 그린다. 모든 메서드는 EDT에서 불린다.
 */
public interface Match {
    /** 내 플레이어 번호(0: 아래, 1: 위). 1이면 화면을 뒤집어 그린다. */
    int me();

    /** 한 틱(1/60초) 진행한다. 인자는 내 말렛의 목표 위치(경기장 좌표). */
    void tick(double targetX, double targetY);

    /** 지금 그릴 상태. 호출부는 읽기만 한다. */
    GameState state();

    int score(int player);

    String name(int player);

    int targetScore();

    /** 화면 가운데 크게 띄울 글자(카운트다운, 득점 등). 없으면 null. */
    String overlay();

    /** overlay 아래 작은 안내 글자. 없으면 null. */
    default String overlayDetail() {
        return null;
    }

    /** 내 이름 자리에 잠깐 띄울 짧은 경고(예: 서브권이 넘어가기까지 남은 시간). 없으면 null. */
    default String hint() {
        return null;
    }

    /** 경기장을 클릭했을 때(예: 끝난 연습 판 다시 시작). */
    default void onClick() {}
}
