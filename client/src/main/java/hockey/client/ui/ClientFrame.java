package hockey.client.ui;

import hockey.client.game.GamePanel;
import hockey.client.game.PracticeMatch;
import hockey.client.model.RoomSettings;
import hockey.client.model.RoomSummary;
import hockey.client.model.RoomView;
import hockey.client.net.Connection;
import hockey.common.protocol.Message.*;
import hockey.common.protocol.Message.Reply;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 메인 창. 화면(접속 → 로비 → 대기실 → 게임) 전환과 현재 연결을 관리한다. 모든 메서드는 EDT에서 돈다. */
public final class ClientFrame extends JFrame {
    private static final String CARD_CONNECT = "connect";
    private static final String CARD_LOBBY = "lobby";
    private static final String CARD_ROOM = "room";
    private static final String CARD_GAME = "game";
    private static final String NOT_YET = "서버가 아직 지원하지 않는 기능입니다.";
    private static final int PRACTICE_TARGET_SCORE = 5;

    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final ConnectPanel connectPanel = new ConnectPanel(this::onConnected, this::startPractice);
    private final LobbyPanel lobbyPanel = new LobbyPanel(new LobbyHandler());
    private final WaitingRoomPanel roomPanel = new WaitingRoomPanel(new RoomHandler());
    private final GamePanel gamePanel = new GamePanel(this::exitGame);
    private Connection connection;
    // 버튼을 누른 순서대로 전송하면서 EDT의 화면 처리를 계속한다.
    private final ExecutorService requests = Executors.newSingleThreadExecutor(task -> {
        Thread sender = new Thread(task, "lobby-requests");
        sender.setDaemon(true);
        return sender;
    });
    private String currentCard = CARD_CONNECT;
    /** 게임 화면에서 나갈 때 돌아갈 화면과 창 크기. */
    private String cardBeforeGame = CARD_CONNECT;
    private Dimension sizeBeforeGame;

    public ClientFrame() {
        super("네트워크 에어하키");
        root.add(connectPanel, CARD_CONNECT);
        root.add(lobbyPanel, CARD_LOBBY);
        root.add(roomPanel, CARD_ROOM);
        root.add(gamePanel, CARD_GAME);
        setContentPane(root);
        setMinimumSize(new Dimension(420, 340));
        setSize(680, 460);
        setLocationByPlatform(true);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                gamePanel.stop();
                closeConnection();
                requests.shutdownNow();
            }
        });
    }

    private void show(String card) {
        currentCard = card;
        cards.show(root, card);
    }

    private void onConnected(Connection newConnection) {
        connection = newConnection;
        lobbyPanel.showSession(newConnection.session());
        show(CARD_LOBBY);
        newConnection.start(new Connection.Listener() {
            @Override
            public void onMessage(Reply message) {
                SwingUtilities.invokeLater(() -> handleMessage(newConnection, message));
            }

            @Override
            public void onDisconnected(IOException cause) {
                SwingUtilities.invokeLater(() -> handleDisconnected(newConnection));
            }
        });
        sendRequest(new ListRooms());
    }

    private void handleMessage(Connection source, Reply message) {
        if (source != connection) {
            return; // 이미 끊은 연결에서 늦게 도착한 메시지
        }
        switch (message) {
            case RoomList m -> lobbyPanel.setRooms(m.rooms().stream()
                    .map(room -> new RoomSummary(room.code(), room.hostName(), room.targetScore(), room.playerCount()))
                    .toList());
            case RoomUpdate m -> {
                RoomView view = new RoomView(m.code(), new RoomSettings(m.targetScore(), m.isPublic()),
                        m.members().stream().map(member -> new RoomView.Member(member.name(), member.host(), member.ready()))
                                .toList(), source.session().nickname(), m.started());
                roomPanel.showRoom(view);
                show(CARD_ROOM);
            }
            case RoomLeft m -> {
                show(CARD_LOBBY);
                lobbyPanel.showStatus("방에서 나왔습니다.", false);
                sendRequest(new ListRooms());
            }
            case JoinFailed m -> {
                showRoomError(m.reason());
                sendRequest(new ListRooms());
            }
            case RoomFailed m -> showRoomError(m.reason());
            case GameStart m -> roomPanel.showStatus("시작 승인 완료 · " + (m.side() + 1)
                    + "P · 온라인 경기 연결은 다음 단계에서 진행합니다.", false);
            default -> { }
        }
    }

    private void sendRequest(Request request) {
        Connection source = connection;
        if (source == null) {
            return;
        }
        requests.execute(() -> {
            try {
                source.send(request);
            } catch (IOException e) {
                source.close();
                SwingUtilities.invokeLater(() -> handleDisconnected(source));
            }
        });
    }

    private void showRoomError(RoomFailReason reason) {
        String text = switch (reason) {
            case NOT_REGISTERED -> "먼저 접속해 주세요.";
            case ALREADY_IN_ROOM -> "현재 방에서 나온 뒤 다시 요청해 주세요.";
            case NOT_IN_ROOM -> "먼저 방에 입장해 주세요.";
            case NOT_FOUND -> "해당 코드의 방이 없습니다.";
            case FULL -> "가득 찬 방입니다.";
            case STARTED -> "이미 시작한 방입니다.";
            case HOST_ONLY -> "방장만 사용할 수 있습니다.";
            case GUEST_ONLY -> "게스트만 준비 상태를 변경할 수 있습니다.";
            case NOT_READY -> "상대가 입장하고 준비해야 시작할 수 있습니다.";
            case INVALID_SETTINGS -> "목표 점수는 3, 5, 7, 10 중에서 선택해 주세요.";
            case UNKNOWN -> "요청을 처리하지 못했습니다.";
        };
        if (currentCard.equals(CARD_ROOM)) {
            roomPanel.showStatus(text, true);
        } else {
            lobbyPanel.showStatus(text, true);
        }
    }

    private void handleDisconnected(Connection source) {
        if (source != connection) {
            return;
        }
        connection = null;
        if (currentCard.equals(CARD_GAME)) {
            // 연습 중이면 판은 그대로 두고, 나갈 때 접속 화면으로 가게만 한다.
            cardBeforeGame = CARD_CONNECT;
        } else {
            show(CARD_CONNECT);
        }
        connectPanel.showMessage("서버와의 연결이 끊어졌습니다.", true);
    }

    private void disconnect() {
        closeConnection();
        connectPanel.showMessage(" ", false);
        show(CARD_CONNECT);
    }

    private void closeConnection() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    /** 방장이 나를 내보냈을 때. 서버 메시지 형식이 정해지면 handleMessage에서 부른다. */
    void onKicked() {
        if (currentCard.equals(CARD_GAME)) {
            cardBeforeGame = CARD_LOBBY;
        } else {
            show(CARD_LOBBY);
        }
        lobbyPanel.showStatus("방장이 방에서 내보냈습니다.", true);
    }

    private void startPractice() {
        String name = connection != null ? connection.session().nickname() : "나";
        enterGame();
        gamePanel.start(new PracticeMatch(0, name, PRACTICE_TARGET_SCORE),
                "연습 모드 · " + PRACTICE_TARGET_SCORE + "점 내기");
    }

    /** 게임 화면으로 바꾸고, 세로로 긴 경기장이 들어가게 창을 키운다. */
    private void enterGame() {
        cardBeforeGame = currentCard;
        sizeBeforeGame = getSize();
        show(CARD_GAME);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int height = Math.min(900, screen.height);
        int width = Math.min(Math.max(getWidth(), 520), screen.width);
        setBounds(getX(), Math.min(getY(), screen.y + screen.height - height), width, height);
    }

    private void exitGame() {
        gamePanel.stop();
        if (sizeBeforeGame != null) {
            setSize(sizeBeforeGame);
        }
        show(cardBeforeGame);
    }

    /** 로비 버튼의 요청은 서버 응답이 도착한 뒤 화면에 반영한다. */
    private final class LobbyHandler implements LobbyPanel.Actions {
        @Override
        public void createRoom(RoomSettings settings) {
            sendRequest(new CreateRoom(settings.targetScore(), settings.isPublic()));
        }

        @Override
        public void joinRoom(String code) {
            sendRequest(new JoinRoom(code));
        }

        @Override
        public void refreshRooms() {
            sendRequest(new ListRooms());
        }

        @Override
        public void invite(String nickname) {
            lobbyPanel.showStatus(NOT_YET, true);
        }

        @Override
        public void practice() {
            startPractice();
        }

        @Override
        public void disconnect() {
            ClientFrame.this.disconnect();
        }
    }

    /** 준비·설정·시작·퇴장을 요청하고 서버가 확정한 방 상태를 기다린다. */
    private final class RoomHandler implements WaitingRoomPanel.Actions {
        @Override
        public void setReady(boolean ready) {
            sendRequest(new Ready(ready));
        }

        @Override
        public void changeSettings(RoomSettings settings) {
            sendRequest(new Settings(settings.targetScore(), settings.isPublic()));
        }

        @Override
        public void startGame() {
            sendRequest(new Start());
        }

        @Override
        public void invite(String nickname) {
            roomPanel.showStatus(NOT_YET, true);
        }

        @Override
        public void kick(String nickname) {
            roomPanel.showStatus(NOT_YET, true);
        }

        @Override
        public void leaveRoom() {
            sendRequest(new LeaveRoom());
        }
    }
}
