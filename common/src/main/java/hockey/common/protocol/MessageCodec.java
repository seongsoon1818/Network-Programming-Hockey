package hockey.common.protocol;

import hockey.common.protocol.Message.Hello;
import hockey.common.protocol.Message.HelloFailReason;
import hockey.common.protocol.Message.HelloFailed;
import hockey.common.protocol.Message.Quit;
import hockey.common.protocol.Message.Reply;
import hockey.common.protocol.Message.Request;
import hockey.common.protocol.Message.Unknown;
import hockey.common.protocol.Message.Welcome;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ProtocolException;

/**
 * 메시지 ↔ 바이트 변환. 형식은 {@link Protocol} 참고.
 * write는 flush까지 하지만 동기화는 하지 않는다. 여러 스레드가 같은 스트림에 쓰면 호출부에서 잠가야 한다.
 */
public final class MessageCodec {
    private MessageCodec() {}

    // ---- 클라이언트 쪽 ----

    public static void writeRequest(DataOutputStream out, Request request) throws IOException {
        switch (request) {
            case Hello m -> {
                out.writeUTF(Protocol.HELLO);
                out.writeUTF(m.nickname());
            }
            case Quit m -> out.writeUTF(Protocol.QUIT);
        }
        out.flush();
    }

    /** 서버 메시지 하나를 읽는다. 서버는 항상 (명령, 값) 두 필드를 보낸다. */
    public static Reply readReply(DataInputStream in) throws IOException {
        String command = in.readUTF();
        String value = in.readUTF();
        return switch (command) {
            case Protocol.WELCOME -> new Welcome(value);
            case Protocol.HELLO_FAILED -> new HelloFailed(HelloFailReason.fromWire(value));
            default -> new Unknown(command, value);
        };
    }

    // ---- 서버 쪽 (지금은 테스트용 간이 서버만 쓴다) ----

    public static Request readRequest(DataInputStream in) throws IOException {
        String command = in.readUTF();
        return switch (command) {
            case Protocol.HELLO -> new Hello(in.readUTF());
            case Protocol.QUIT -> new Quit();
            default -> throw new ProtocolException("지원하지 않는 명령: " + command);
        };
    }

    public static void writeReply(DataOutputStream out, Reply reply) throws IOException {
        switch (reply) {
            case Welcome m -> {
                out.writeUTF(Protocol.WELCOME);
                out.writeUTF(m.token());
            }
            case HelloFailed m -> {
                out.writeUTF(Protocol.HELLO_FAILED);
                out.writeUTF(m.reason().name());
            }
            case Unknown m -> {
                out.writeUTF(m.command());
                out.writeUTF(m.value());
            }
        }
        out.flush();
    }
}
