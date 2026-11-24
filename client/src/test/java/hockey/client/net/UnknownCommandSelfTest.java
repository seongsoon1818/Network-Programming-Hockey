package hockey.client.net;

import hockey.common.protocol.Message.Reply;
import hockey.common.protocol.Message.RoomLeft;
import hockey.common.protocol.Message.Welcome;
import hockey.common.protocol.MessageCodec;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 서버가 이 버전이 모르는 명령을 보내면 연결을 끊는지 확인한다. 모르는 명령은 필드 길이를 알 수 없어서,
 * 읽고 넘어가면 뒤 메시지가 어긋나기 때문이다. main을 실행하면 실패 시 예외로 끝난다.
 */
public final class UnknownCommandSelfTest {

    public static void main(String[] args) throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            CompletableFuture<Void> server = CompletableFuture.runAsync(() -> serve(listener));

            Connection.HelloResult hello = Connection.connect("localhost", listener.getLocalPort(), "Alice");
            check(hello.accepted(), "접속되어야 한다");
            CopyOnWriteArrayList<Reply> received = new CopyOnWriteArrayList<>();
            CompletableFuture<IOException> disconnected = new CompletableFuture<>();
            hello.connection().start(new Connection.Listener() {
                @Override
                public void onMessage(Reply message) {
                    received.add(message);
                }

                @Override
                public void onDisconnected(IOException cause) {
                    disconnected.complete(cause);
                }
            });

            IOException cause = disconnected.get(3, TimeUnit.SECONDS);
            check(cause instanceof ProtocolException, "모르는 명령이면 프로토콜 오류로 끊겨야 한다: " + cause);
            check(received.size() == 1 && received.get(0) instanceof RoomLeft,
                    "모르는 명령 앞의 메시지는 받고, 뒤의 메시지는 받지 않아야 한다: " + received);
            server.get(3, TimeUnit.SECONDS);
        }
        System.out.println("UnknownCommandSelfTest: 모두 통과");
    }

    /** HELLO를 받고 WELCOME, ROOM_LEFT, 모르는 명령(필드 두 개), ROOM_LEFT 순서로 보낸다. */
    private static void serve(ServerSocket listener) {
        try (Socket socket = listener.accept()) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            MessageCodec.readRequest(in);
            MessageCodec.writeReply(out, new Welcome(UUID.randomUUID().toString()));
            MessageCodec.writeReply(out, new RoomLeft());
            out.writeUTF("FUTURE_COMMAND");
            out.writeInt(7);
            out.writeUTF("값");
            MessageCodec.writeReply(out, new RoomLeft());
            // 클라이언트가 끊을 때까지 기다린다. 끊는 방식에 따라 EOF 대신 연결 리셋이 올 수도 있다.
            try {
                while (in.read() != -1) {
                }
            } catch (IOException ignored) {
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
