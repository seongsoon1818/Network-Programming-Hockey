package hockey.common.protocol;

import java.util.Locale;
import java.util.regex.Pattern;

/** 닉네임 규칙. (초안: 2~12자, 한글·영문·숫자·밑줄) 서버와 클라이언트가 같은 규칙을 쓴다. */
public final class Nicknames {
    private Nicknames() {}

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 12;

    private static final Pattern ALLOWED = Pattern.compile("[가-힣A-Za-z0-9_]{" + MIN_LENGTH + "," + MAX_LENGTH + "}");

    public static boolean isValid(String nickname) {
        return nickname != null && ALLOWED.matcher(nickname).matches();
    }

    /** 중복 검사용 키. 대소문자를 구분하지 않는다. */
    public static String key(String nickname) {
        return nickname.toLowerCase(Locale.ROOT);
    }
}
