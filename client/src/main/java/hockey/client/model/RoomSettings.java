package hockey.client.model;

import java.util.List;

/** 방장이 정하는 방 설정. 선택지는 팀 합의 전 임시 값이다. */
public record RoomSettings(int targetScore, boolean isPublic) {
    public static final List<Integer> TARGET_SCORES = List.of(3, 5, 7, 10);
    public static final RoomSettings DEFAULT = new RoomSettings(5, true);
}
