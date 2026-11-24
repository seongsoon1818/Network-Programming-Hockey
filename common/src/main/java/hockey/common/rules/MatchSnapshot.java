package hockey.common.rules;

import hockey.common.rules.MatchEngine.Phase;

/**
 * 한 틱의 경기 상태. 서버가 클라이언트에게 보내는 상태 메시지의 내용이고,
 * {@link MatchEngine#snapshot()}으로 만들고 {@link MatchEngine#restore}로 되돌린다.
 *
 * 메시지 형식과는 분리돼 있다. 필드 순서는 docs의 STATE 초안에서 "처리한 입력 번호"만 뺀 것과 같다
 * (그 번호는 받는 사람마다 달라서 따로 보낸다). 말렛 속도는 다음 틱에 물리가 다시 계산하므로 넣지 않는다.
 *
 * @param tick           시작 뒤 진행한 틱 수
 * @param phase          경기 단계
 * @param phaseTicksLeft 지금 단계가 끝나기까지 남은 틱(카운트다운 "3, 2, 1" 등)
 * @param server         지금(또는 다음) 서브하는 플레이어
 * @param lastScorer     마지막으로 득점한 플레이어, 없으면 -1
 * @param holdSide       퍽이 머무는 진영(10초 제한을 재는 쪽), 없으면 -1
 * @param holdTicksLeft  서브권이 넘어가기까지 남은 틱
 */
public record MatchSnapshot(
        int tick,
        Phase phase,
        int phaseTicksLeft,
        int server,
        int lastScorer,
        int score0, int score1,
        double puckX, double puckY, double puckVx, double puckVy,
        double mallet0X, double mallet0Y, double mallet1X, double mallet1Y,
        int holdSide,
        int holdTicksLeft) {

    public MatchSnapshot {
        if (phase == null) {
            throw new IllegalArgumentException("단계가 없습니다");
        }
    }

    public int score(int player) {
        return player == 0 ? score0 : score1;
    }
}
