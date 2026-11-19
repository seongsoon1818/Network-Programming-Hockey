package org.example.server;

import hockey.common.protocol.Message;
import hockey.common.protocol.MessageCodec;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

final class ClientConnection implements Runnable {
    private final Socket socket;
    private final Lobby lobby;
    private final BlockingQueue<Message.Reply> replies = new LinkedBlockingQueue<>();

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
                Message.Request request = MessageCodec.readRequest(in);
                if (request instanceof Message.Quit) {
                    return;
                }
                lobby.post(this, request);
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

    void send(Message.Reply reply) {
        replies.add(reply);
    }

    private void writeReplies() {
        // 연결별 송신 스레드만 쓰므로 방 상태와 응답의 필드가 섞이지 않는다.
        try {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            while (!Thread.currentThread().isInterrupted()) {
                MessageCodec.writeReply(out, replies.take());
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
