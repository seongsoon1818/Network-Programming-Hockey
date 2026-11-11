package org.example.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class HockeyServer {
    private static final int DEFAULT_PORT = 5000;

    public static void main(String[] args) throws IOException {
        int port;
        try {
            port = args.length == 0 ? DEFAULT_PORT : Integer.parseInt(args[0]);
            if (args.length > 1 || port < 0 || port > 65535) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            System.err.println("사용법: HockeyServer [포트 번호 0~65535]");
            System.exit(2);
            return;
        }

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            Lobby lobby = new Lobby();
            Thread lobbyThread = new Thread(lobby, "lobby");
            lobbyThread.start();
            try {
                System.out.println("에어하키 서버 시작 (포트 " + serverSocket.getLocalPort() + ")");
                while (true) {
                    Socket socket = serverSocket.accept();
                    try {
                        socket.setTcpNoDelay(true);
                        ClientConnection client = new ClientConnection(socket, lobby);
                        Thread receiver = new Thread(client, "receiver-" + socket.getPort());
                        receiver.setDaemon(true);
                        receiver.start();
                    } catch (IOException e) {
                        socket.close();
                        System.err.println("클라이언트 연결 준비 실패: " + e.getMessage());
                    }
                }
            } finally {
                lobbyThread.interrupt();
            }
        }
    }
}
