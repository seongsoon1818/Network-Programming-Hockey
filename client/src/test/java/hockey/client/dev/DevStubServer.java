package hockey.client.dev;

import hockey.common.protocol.Message.Hello;
import hockey.common.protocol.Message.HelloFailReason;
import hockey.common.protocol.Message.HelloFailed;
import hockey.common.protocol.Message.Quit;
import hockey.common.protocol.Message.Request;
import hockey.common.protocol.Message.Welcome;
import hockey.common.protocol.MessageCodec;
import hockey.common.protocol.Protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 클라이언트 혼자 테스트하려고 만든 임시 서버. 실제 서버(server/)가 아니다.
 * 실제 서버의 HELLO 동작(공백 제거, 빈 닉네임 거절, 대소문자 무시 중복 검사, UUID 토큰)을 흉내 낸다.
 * 실제 서버 브랜치가 main에 합쳐지면 지운다.
 */
public final class DevStubServer implements Closeable {

    private final ServerSocket serverSocket;
    private final Set<String> nicknames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    public DevStubServer(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        Thread acceptor = new Thread(this::acceptLoop, "stub-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                Thread handler = new Thread(() -> handle(socket), "stub-client");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket socket) {
        String registered = null;
        try (socket) {
            socket.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            while (true) {
                Request request = MessageCodec.readRequest(in);
                if (request instanceof Quit) {
                    return;
                }
                String nickname = ((Hello) request).nickname().strip();
                HelloFailReason reject = null;
                synchronized (nicknames) {
                    if (registered != null) {
                        reject = HelloFailReason.ALREADY_REGISTERED;
                    } else if (nickname.isEmpty()) {
                        reject = HelloFailReason.INVALID_NICKNAME;
                    } else if (!nicknames.add(nickname)) {
                        reject = HelloFailReason.DUPLICATE_NICKNAME;
                    } else {
                        registered = nickname;
                    }
                }
                if (reject != null) {
                    MessageCodec.writeReply(out, new HelloFailed(reject));
                } else {
                    MessageCodec.writeReply(out, new Welcome(UUID.randomUUID().toString()));
                    System.out.println("[stub] 접속: " + nickname);
                }
            }
        } catch (IOException e) {
            // 연결 종료
        } finally {
            if (registered != null) {
                synchronized (nicknames) {
                    nicknames.remove(registered);
                }
                System.out.println("[stub] 종료: " + registered);
            }
        }
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Protocol.DEFAULT_PORT;
        DevStubServer server = new DevStubServer(port);
        System.out.println("[stub] 테스트용 서버 실행 중, 포트 " + server.port());
        Thread.currentThread().join();
    }
}
