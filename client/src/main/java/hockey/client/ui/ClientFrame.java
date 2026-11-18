package hockey.client.ui;

import hockey.client.game.GamePanel;
import hockey.client.game.PracticeMatch;
import hockey.client.model.RoomSettings;
import hockey.client.net.Connection;
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
    }

    private void handleMessage(Connection source, Reply message) {
        if (source != connection) {
            return; // 이미 끊은 연결에서 늦게 도착한 메시지
        }
        // 방·게임 메시지 형식이 정해지면 여기서 화면별로 넘긴다:
        //   방 목록 → lobbyPanel.setRooms, 접속자 목록 → lobbyPanel.setUsers,
        //   방 상태 → roomPanel.showRoom + show(CARD_ROOM), 초대 → InviteDialog.show(수락하면 게스트로 입장),
        //   내보내짐 → onKicked()
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

    /** 로비 버튼 처리. 방·초대는 서버 메시지 형식이 정해지면 connection.send로 바꾼다. */
    private final class LobbyHandler implements LobbyPanel.Actions {
        @Override
        public void createRoom(RoomSettings settings) {
            lobbyPanel.showStatus(NOT_YET, true);
        }

        @Override
        public void joinRoom(String code) {
            lobbyPanel.showStatus(NOT_YET, true);
        }

        @Override
        public void refreshRooms() {
            lobbyPanel.showStatus(NOT_YET, true);
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

    /** 대기실 버튼 처리. 서버 연동 전이라 나가기만 동작한다. 접속자 목록을 받으면 roomPanel.setLobbyUsers로도 넘긴다. */
    private final class RoomHandler implements WaitingRoomPanel.Actions {
        @Override
        public void setReady(boolean ready) {
            roomPanel.showStatus(NOT_YET, true);
        }

        @Override
        public void changeSettings(RoomSettings settings) {
            roomPanel.showStatus(NOT_YET, true);
        }

        @Override
        public void startGame() {
            roomPanel.showStatus(NOT_YET, true);
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
            show(CARD_LOBBY);
        }
    }
}
