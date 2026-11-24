package hockey.client.game;

import hockey.common.physics.GameState;
import hockey.common.rules.MatchEngine.Phase;
import hockey.common.rules.MatchSnapshot;

/**
 * 서버와 하는 판. 매 틱 내 입력을 서버로 보내면서 {@link Predictor}로 먼저 그리고, 서버 상태가 오면 맞춘다.
 *
 * 위치는 예측한 값을 그리지만 점수·단계 글씨는 서버가 보낸 값만 쓴다. 예측으로 골을 먼저 띄웠다가
 * 서버에서는 막혀 글씨를 되돌리는 일이 없게 하기 위해서다.
 *
 * 메시지 형식과는 분리돼 있다. 보내는 쪽은 {@link Link}로 받고, 받은 메시지는 수신부가 풀어서
 * {@link #onState}·{@link #onGameOver}를 부른다. 모든 메서드는 EDT에서 부른다(수신 스레드는 invokeLater로 넘긴다).
 */
public final class NetworkMatch implements Match {
    /** 서버로 보내는 쪽. 메시지 형식이 정해지면 연결 객체가 구현한다. */
    public interface Link {
        /** 이번 틱의 내 말렛 목표 위치. 번호는 0부터 1씩 는다. */
        void sendInput(long seq, double x, double y);

        /** 기권. */
        void leaveGame();
    }

    private final int me;
    private final String[] names = new String[2];
    private final int targetScore;
    private final Link link;
    private final Predictor predictor;
    /** 마지막으로 받은 서버 상태. 아직 없으면 null. */
    private MatchSnapshot latest;
    private boolean over;
    private boolean left;
    /** 승패 없이 끝남(연결 끊김 등). 화면에 띄울 글씨. */
    private String abortedTitle;
    private String abortedDetail;
    private int winner = -1;
    private boolean forfeit;
    private final int[] finalScore = new int[2];

    public NetworkMatch(int me, String myName, String opponentName, int targetScore, Link link) {
        this.me = me;
        this.names[me] = myName;
        this.names[1 - me] = opponentName;
        this.targetScore = targetScore;
        this.link = link;
        this.predictor = new Predictor(me, targetScore);
    }

    @Override
    public void tick(double targetX, double targetY) {
        if (isFinished() || left) {
            return;
        }
        long seq = predictor.tick(targetX, targetY);
        link.sendInput(seq, targetX, targetY);
    }

    /**
     * 서버 상태를 받았다.
     *
     * @param ackSeq 이 상태에 반영된 내 마지막 입력 번호. 아직 없으면 -1.
     */
    public void onState(MatchSnapshot snapshot, long ackSeq) {
        if (isFinished()) {
            return;
        }
        predictor.onSnapshot(snapshot, ackSeq);
        latest = snapshot;
    }

    /** 게임이 끝났다. 이후의 상태·입력은 무시한다. */
    public void onGameOver(int winner, int score0, int score1, boolean forfeit) {
        if (isFinished()) {
            return;
        }
        over = true;
        this.winner = winner;
        this.forfeit = forfeit;
        finalScore[0] = score0;
        finalScore[1] = score1;
    }

    /**
     * 방 상태를 받았다. 시작한 방이 시작 전 상태로 돌아왔는데 GAME_OVER가 없었다면 서버가 경기를 끝낸 것이다.
     * 상대가 방에서 사라졌으면 기권승으로, 아니면 승패 없이 끝낸다. GAME_OVER가 먼저 왔으면 아무것도 하지 않는다.
     */
    public void onRoomUpdate(boolean started, boolean opponentInRoom) {
        if (started || isFinished()) {
            return;
        }
        if (opponentInRoom) {
            onAborted("경기 종료", "나가기를 누르면 대기실로 돌아갑니다");
        } else {
            onGameOver(me, score(0), score(1), true);
        }
    }

    /** 승패 없이 끝낸다(서버 연결이 끊김 등). 이후의 상태·입력은 무시한다. */
    public void onAborted(String title, String detail) {
        if (isFinished()) {
            return;
        }
        abortedTitle = title;
        abortedDetail = detail;
    }

    /** 내가 나간다(기권). 한 번만 보낸다. 끝난 판이면 보내지 않는다. */
    public void leave() {
        if (isFinished() || left) {
            return;
        }
        left = true;
        link.leaveGame();
    }

    /** 승패가 정해졌는가(GAME_OVER를 받았는가). */
    public boolean isOver() {
        return over;
    }

    /** 더 진행하지 않는가. 승패가 정해졌거나 승패 없이 끝났으면 true. 나가기에 확인이 필요 없다. */
    public boolean isFinished() {
        return over || abortedTitle != null;
    }

    /** 이긴 플레이어. 끝나지 않았으면 -1. */
    public int winner() {
        return winner;
    }

    @Override
    public int me() {
        return me;
    }

    @Override
    public GameState state() {
        return predictor.view();
    }

    @Override
    public int score(int player) {
        if (over) {
            return finalScore[player];
        }
        return latest == null ? 0 : latest.score(player);
    }

    @Override
    public String name(int player) {
        return names[player];
    }

    @Override
    public int targetScore() {
        return targetScore;
    }

    @Override
    public String overlay() {
        if (over) {
            return winner == me ? "승리!" : "패배";
        }
        if (abortedTitle != null) {
            return abortedTitle;
        }
        if (latest == null) {
            return "대기 중";
        }
        if (latest.phase() == Phase.FINISHED) {
            return null; // 승패(기권 포함)는 GAME_OVER가 알려 준다.
        }
        return MatchText.overlay(me, latest.phase(), latest.phaseTicksLeft(), latest.lastScorer(), -1);
    }

    @Override
    public String overlayDetail() {
        if (over) {
            return (forfeit && winner == me ? "상대가 나갔습니다. " : "") + "나가기를 누르면 대기실로 돌아갑니다";
        }
        if (abortedTitle != null) {
            return abortedDetail;
        }
        if (latest == null) {
            return "서버가 경기를 시작하기를 기다립니다";
        }
        return MatchText.serve(me, latest.phase(), latest.server());
    }

    @Override
    public String hint() {
        if (isFinished() || latest == null) {
            return null;
        }
        return MatchText.holdHint(me, latest.phase(), latest.holdSide(), latest.holdTicksLeft());
    }

    /** 보정 오차를 빼고 예측한 상태(테스트용). */
    GameState predictedState() {
        return predictor.predicted();
    }
}
