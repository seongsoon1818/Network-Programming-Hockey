package hockey.client.model;

/** 방 목록의 한 줄. */
public record RoomSummary(String code, String hostName, int targetScore, int playerCount) {
    public static final int CAPACITY = 2;

    public boolean isFull() {
        return playerCount >= CAPACITY;
    }
}
