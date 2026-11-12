package hockey.client.net;

import hockey.common.protocol.Message.Hello;
import hockey.common.protocol.Message.HelloFailReason;
import hockey.common.protocol.Message.HelloFailed;
import hockey.common.protocol.Message.Quit;
import hockey.common.protocol.Message.Reply;
import hockey.common.protocol.Message.Request;
import hockey.common.protocol.Message.Unknown;
import hockey.common.protocol.Message.Welcome;
import hockey.common.protocol.MessageCodec;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Socket;

/**
 * 서버와의 TCP 연결 하나. HELLO를 마친 상태로만 만들어진다.
 *
 * 스레드: {@link #start}가 수신 스레드 하나를 띄우고, 송신은 호출한 스레드에서 바로 쓴다.
 */
public final class Connection implements Closeable {

    /** 수신 스레드에서 불린다. Swing을 건드리려면 invokeLater로 넘길 것. */
    public interface Listener {
        void onMessage(Reply message);

        /** 연결이 끝났을 때 한 번. 직접 close()한 경우에는 불리지 않는다. */
        void onDisconnected(IOException cause);
    }

    /** HELLO 결과. 성공이면 connection, 거절이면 failReason이 채워진다. */
    public record HelloResult(Connection connection, HelloFailReason failReason) {
        public boolean accepted() {
            return connection != null;
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int HELLO_TIMEOUT_MS = 5000;

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Session session;
    private volatile boolean closedByUs;

    private Connection(Socket socket, DataInputStream in, DataOutputStream out, Session session) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.session = session;
    }

    /**
     * 접속해서 HELLO를 주고받는다. 응답이 올 때까지 막히므로 EDT에서 부르면 안 된다.
     *
     * @throws IOException 접속 실패, 시간 초과, 프로토콜 오류
     */
    public static HelloResult connect(String host, int port, String nickname) throws IOException {
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            MessageCodec.writeRequest(out, new Hello(nickname));
            socket.setSoTimeout(HELLO_TIMEOUT_MS);
            Reply reply = MessageCodec.readReply(in);
            socket.setSoTimeout(0);

            switch (reply) {
                case Welcome welcome -> {
                    // 서버는 닉네임 앞뒤 공백을 떼고 등록한다.
                    Session session = new Session(nickname.strip(), welcome.token());
                    return new HelloResult(new Connection(socket, in, out, session), null);
                }
                case HelloFailed failed -> {
                    // 서버는 거절 후에도 연결을 열어 두지만, 클라이언트는 매번 새로 접속한다.
                    socket.close();
                    return new HelloResult(null, failed.reason());
                }
                case Unknown unknown -> throw new ProtocolException("HELLO 응답이 아닌 메시지: " + unknown.command());
            }
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    public Session session() {
        return session;
    }

    /** 수신 스레드를 시작한다. 한 번만 부를 것. */
    public void start(Listener listener) {
        Thread thread = new Thread(() -> receiveLoop(listener), "hockey-recv");
        thread.setDaemon(true);
        thread.start();
    }

    private void receiveLoop(Listener listener) {
        try {
            while (true) {
                Reply message = MessageCodec.readReply(in);
                if (!(message instanceof Unknown)) {
                    listener.onMessage(message);
                }
            }
        } catch (IOException e) {
            if (!closedByUs) {
                closeSocket();
                listener.onDisconnected(e);
            }
        }
    }

    public synchronized void send(Request message) throws IOException {
        MessageCodec.writeRequest(out, message);
    }

    /** QUIT을 보내고 닫는다. 서버가 의도적 종료와 끊김을 구분할 수 있게 한다. */
    @Override
    public void close() {
        if (closedByUs) {
            return;
        }
        closedByUs = true;
        try {
            send(new Quit());
        } catch (IOException ignored) {
            // 이미 끊긴 연결이면 보낼 수 없다.
        }
        closeSocket();
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
