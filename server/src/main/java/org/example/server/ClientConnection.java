package org.example.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

final class ClientConnection implements Runnable {
    private record Reply(String command, String value) {}

    private final Socket socket;
    private final Lobby lobby;
    private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();

    ClientConnection(Socket socket, Lobby lobby) {
        this.socket = socket;
        this.lobby = lobby;
    }

    @Override
    public void run() {
        Thread sender = new Thread(this::writeReplies, "sender-" + socket.getPort());
        sender.start();
        try (socket; DataInputStream in = new DataInputStream(socket.getInputStream())) {
            while (true) {
                String command = in.readUTF();
                switch (command) {
                    case "HELLO" -> lobby.postHello(this, in.readUTF());
                    case "QUIT" -> { return; }
                    default -> throw new ProtocolException("지원하지 않는 명령입니다.");
                }
            }
        } catch (EOFException e) {
            // 상대가 소켓을 닫으면 아래에서 로비 등록도 정리한다.
        } catch (IOException e) {
            System.err.println("클라이언트 수신 종료: " + e.getMessage());
        } finally {
            sender.interrupt();
            lobby.postDisconnect(this);
        }
    }

    void send(String command, String value) {
        replies.add(new Reply(command, value));
    }

    private void writeReplies() {
        // 이 연결의 출력은 송신 스레드 하나가 맡아 두 UTF 필드의 순서를 지킨다.
        try {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            while (!Thread.currentThread().isInterrupted()) {
                Reply reply = replies.take();
                out.writeUTF(reply.command());
                out.writeUTF(reply.value());
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!socket.isClosed()) {
                System.err.println("클라이언트 송신 종료: " + e.getMessage());
            }
        } finally {
            // 송신 실패 때에도 소켓을 닫아 readUTF()에서 기다리는 수신 스레드를 깨운다.
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println("소켓 정리 실패: " + e.getMessage());
            }
        }
    }
}
