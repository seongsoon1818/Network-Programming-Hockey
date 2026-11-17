package hockey.client.model;

/** 방 코드 입력 검사. 서버에 보내기 전에 형식만 걸러낸다(제안서: 4~5자). */
public final class RoomCodes {
    private RoomCodes() {}

    public static final int MIN_LENGTH = 4;
    public static final int MAX_LENGTH = 5;

    /** 앞뒤 공백을 떼고 대문자로 맞춘다. */
    public static String normalize(String input) {
        return input == null ? "" : input.strip().toUpperCase();
    }

    public static boolean isValid(String code) {
        if (code == null || code.length() < MIN_LENGTH || code.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (!(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9')) {
                return false;
            }
        }
        return true;
    }
}
