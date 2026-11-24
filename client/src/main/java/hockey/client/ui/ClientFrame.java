package hockey.client.ui;

import hockey.client.game.GamePanel;
import hockey.client.game.NetworkMatch;
import hockey.client.game.PracticeMatch;
import hockey.client.model.RoomSettings;
import hockey.client.model.RoomSummary;
import hockey.client.model.RoomView;
import hockey.client.net.Connection;
import hockey.common.protocol.Message.*;
import hockey.common.protocol.Message.Reply;
import hockey.common.protocol.Protocol;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 메인 창. 화면(접속 → 로비 → 대기실 → 게임) 전환과 현재 연결을 관리한다. 모든 메서드는 EDT에서 돈다. */
public final class ClientFrame extends JFrame {
    private static final String CARD_CONNECT = "connect";
    private static final String CARD_LOBBY = "lobby";
    private static final String CARD_ROOM = "room";
    private static final String CARD_GAME = "game";
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
    /** 마지막으로 받은 방 상태. 게임을 시작할 때 상대 이름을 여기서 찾는다. 방 밖이면 null. */
    private RoomView lastRoom;
    /** 진행 중인 온라인 경기. 연습 중이거나 게임 화면이 아니면 null. */
    private NetworkMatch onlineMatch;
    /** 방 나가기를 요청했는가. 요청하지 않았는데 ROOM_LEFT가 오면 내보내진 것이다. */
    private boolean leaveRequested;
    /** 떠 있는 초대 팝업과 초대한 사람. 없으면 null. */
    private InviteDialog inviteDialog;
    private String inviteFrom;
    /** 로비에서 초대를 보내 방이 만들어지길 기다리는 상대. 대기실에 응답 대기 안내를 띄운다. */
    private String pendingInvitee;

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
        // 온라인 경기 중에는 창을 닫기 전에 기권패를 확인한다.
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (!confirmForfeit("창을 닫으면 기권패가 됩니다.")) {
                    return;
                }
                gamePanel.stop();
                closeConnection();
                requests.shutdownNow();
                dispose();
                System.exit(0);
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
                lastRoom = view;
                roomPanel.showRoom(view);
                if (pendingInvitee != null && view.amHost() && view.members().size() == 1) {
                    roomPanel.showStatus(pendingInvitee + " 님에게 초대를 보냈습니다. 응답을 기다립니다.", false);
                }
                if (currentCard.equals(CARD_GAME)) {
                    // 경기 화면은 그대로 두고, 나가면 대기실로 돌아가게만 한다.
                    cardBeforeGame = CARD_ROOM;
                    if (onlineMatch != null) {
                        onlineMatch.onRoomUpdate(m.started(), view.members().size() > 1);
                    }
                } else {
                    show(CARD_ROOM);
                }
            }
            case RoomLeft m -> {
                lastRoom = null;
                pendingInvitee = null;
                boolean requested = leaveRequested;
                leaveRequested = false;
                if (!requested) {
                    onKicked();
                    return;
                }
                show(CARD_LOBBY);
                lobbyPanel.showStatus("방에서 나왔습니다.", false);
                sendRequest(new ListRooms());
            }
            case JoinFailed m -> {
                showRoomError(m.reason());
                sendRequest(new ListRooms());
            }
            case RoomFailed m -> {
                if (m.command().equals(Protocol.INVITE)) {
                    pendingInvitee = null;
                }
                showRoomError(m.reason());
                if (m.command().equals(Protocol.JOIN_ROOM)) {
                    sendRequest(new ListRooms());
                }
            }
            case GameStart m -> startOnlineGame(m);
            case State m -> {
                if (onlineMatch != null) {
                    onlineMatch.onState(m.snapshot(), m.ackSeq());
                }
            }
            case GameOver m -> {
                if (onlineMatch != null) {
                    onlineMatch.onGameOver(m.winner(), m.score0(), m.score1(), m.reason() == GameOverReason.FORFEIT);
                }
            }
            case UserList m -> {
                // 나는 초대할 수 없으므로 뺀다.
                String me = source.session().nickname();
                List<String> others = m.names().stream().filter(name -> !name.equalsIgnoreCase(me)).toList();
                lobbyPanel.setUsers(others);
                roomPanel.setLobbyUsers(others);
            }
            case Invited m -> showInvite(m.inviter(), m.secondsLeft());
            case InviteCancelled m -> {
                if (inviteDialog != null && m.inviter().equals(inviteFrom)) {
                    closeInvite();
                    lobbyPanel.showStatus(m.inviter() + " 님의 초대가 끝났습니다.", false);
                }
            }
            case InviteResult m -> {
                pendingInvitee = null;
                String text = switch (m.outcome()) {
                    case ACCEPTED -> m.name() + " 님이 초대를 수락했습니다.";
                    case DECLINED -> m.name() + " 님이 초대를 거절했습니다.";
                    case EXPIRED -> m.name() + " 님이 응답하지 않아 초대가 끝났습니다.";
                    case UNKNOWN -> m.name() + " 님에게 보낸 초대가 끝났습니다.";
                };
                showStatus(text, m.outcome() != InviteOutcome.ACCEPTED);
            }
            default -> { }
        }
    }

    /** 초대 팝업을 띄운다. 한 사람이 받는 초대는 동시에 하나라서, 남아 있던 팝업은 닫는다. */
    private void showInvite(String inviter, int seconds) {
        closeInvite();
        inviteFrom = inviter;
        inviteDialog = InviteDialog.show(root, inviter, seconds, accepted -> {
            inviteDialog = null;
            inviteFrom = null;
            sendRequest(new InviteReply(inviter, accepted));
        });
    }

    private void closeInvite() {
        if (inviteDialog != null) {
            inviteDialog.cancel();
            inviteDialog = null;
            inviteFrom = null;
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
        showStatus(switch (reason) {
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
            case NO_SUCH_USER -> "접속해 있지 않은 사람입니다.";
            case USER_BUSY -> "상대가 지금 초대를 받을 수 없습니다.";
            case NO_SUCH_INVITE -> "초대가 끝났습니다.";
            case UNKNOWN -> "요청을 처리하지 못했습니다.";
        }, true);
    }

    /** 지금 보이는 로비·대기실 아래 안내 줄에 띄운다. 게임 화면이면 나갔을 때 보이도록 돌아갈 화면에 띄운다. */
    private void showStatus(String text, boolean error) {
        String card = currentCard.equals(CARD_GAME) ? cardBeforeGame : currentCard;
        if (card.equals(CARD_ROOM)) {
            roomPanel.showStatus(text, error);
        } else {
            lobbyPanel.showStatus(text, error);
        }
    }

    private void handleDisconnected(Connection source) {
        if (source != connection) {
            return;
        }
        connection = null;
        lastRoom = null;
        leaveRequested = false;
        pendingInvitee = null;
        closeInvite();
        if (currentCard.equals(CARD_GAME)) {
            // 연습 판은 그대로 두고, 온라인 판은 멈춘다. 나가면 접속 화면으로 간다.
            cardBeforeGame = CARD_CONNECT;
            if (onlineMatch != null) {
                onlineMatch.onAborted("연결 끊김", "나가기를 누르면 접속 화면으로 돌아갑니다");
            }
        } else {
            show(CARD_CONNECT);
        }
        connectPanel.showMessage("서버와의 연결이 끊어졌습니다.", true);
    }

    private void disconnect() {
        closeConnection();
        lastRoom = null;
        pendingInvitee = null;
        closeInvite();
        connectPanel.showMessage(" ", false);
        show(CARD_CONNECT);
    }

    private void closeConnection() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    /** 방장이 나를 내보냈을 때. 내가 요청하지 않았는데 ROOM_LEFT가 오면 부른다. */
    private void onKicked() {
        if (currentCard.equals(CARD_GAME)) {
            cardBeforeGame = CARD_LOBBY;
            if (onlineMatch != null) {
                onlineMatch.onAborted("경기 종료", "방에서 내보내졌습니다");
            }
        } else {
            show(CARD_LOBBY);
        }
        lobbyPanel.showStatus("방장이 방에서 내보냈습니다.", true);
        sendRequest(new ListRooms());
    }

    /** 서버가 경기 시작을 알렸다. 상대 이름은 직전에 받은 방 상태에서 찾는다. */
    private void startOnlineGame(GameStart m) {
        String myName = connection.session().nickname();
        String opponent = "상대";
        if (lastRoom != null) {
            for (RoomView.Member member : lastRoom.members()) {
                if (!member.name().equals(myName)) {
                    opponent = member.name();
                }
            }
        }
        onlineMatch = new NetworkMatch(m.side(), myName, opponent, m.targetScore(), new ServerLink());
        enterGame();
        cardBeforeGame = CARD_ROOM;
        gamePanel.start(onlineMatch, "온라인 경기 · " + m.targetScore() + "점 내기 · 방 " + m.code());
    }

    /**
     * 진행 중인 온라인 경기가 있으면 기권패를 확인한다.
     *
     * @return 나가도 되면 true. 나가기로 했으면 기권도 보낸다.
     */
    private boolean confirmForfeit(String message) {
        if (onlineMatch == null || onlineMatch.isFinished()) {
            return true;
        }
        Object[] options = {"나가기 (기권)", "계속하기"};
        int choice = JOptionPane.showOptionDialog(this, message + "\n경기에서 나갈까요?", "경기 나가기",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        if (choice != 0) {
            return false;
        }
        onlineMatch.leave();
        return true;
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
        if (!confirmForfeit("지금 나가면 기권패가 됩니다.")) {
            return;
        }
        onlineMatch = null;
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
            // 서버가 내가 방장인 비공개 방을 만들어 ROOM_UPDATE를 보내고, 상대에게 초대를 보낸다.
            pendingInvitee = nickname;
            lobbyPanel.showStatus(nickname + " 님에게 초대를 보냅니다.", false);
            sendRequest(new Invite(nickname));
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
            roomPanel.showStatus(nickname + " 님에게 초대를 보냈습니다. 응답을 기다립니다.", false);
            sendRequest(new Invite(nickname));
        }

        @Override
        public void kick(String nickname) {
            sendRequest(new Kick(nickname));
        }

        @Override
        public void leaveRoom() {
            leaveRequested = true;
            sendRequest(new LeaveRoom());
        }
    }

    /**
     * 온라인 경기에서 서버로 보내는 쪽. 다른 요청과 같은 전송 스레드를 거쳐서 EDT가 소켓 쓰기를 기다리지 않고,
     * 마지막 INPUT 뒤에 LEAVE_GAME이 가는 순서도 지켜진다.
     */
    private final class ServerLink implements NetworkMatch.Link {
        @Override
        public void sendInput(long seq, double x, double y) {
            sendRequest(new Input((int) seq, x, y));
        }

        @Override
        public void leaveGame() {
            sendRequest(new LeaveGame());
        }
    }
}
