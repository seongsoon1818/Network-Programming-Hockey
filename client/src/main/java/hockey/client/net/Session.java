package hockey.client.net;

/** 접속에 성공한 내 정보. token은 서버가 WELCOME으로 발급한 UUID 문자열이다. */
public record Session(String nickname, String token) {}
