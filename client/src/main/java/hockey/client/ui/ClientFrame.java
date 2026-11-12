package hockey.client.ui;

import hockey.client.net.Connection;
import hockey.common.protocol.Message.Reply;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;

/** 메인 창. 화면(접속 → 로비 → …) 전환과 현재 연결을 관리한다. 모든 메서드는 EDT에서 돈다. */
public final class ClientFrame extends JFrame {
    private static final String CARD_CONNECT = "connect";
    private static final String CARD_LOBBY = "lobby";

    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final ConnectPanel connectPanel = new ConnectPanel(this::onConnected);
    private final LobbyPanel lobbyPanel = new LobbyPanel(this::leave);
    private Connection connection;

    public ClientFrame() {
        super("네트워크 에어하키");
        root.add(connectPanel, CARD_CONNECT);
        root.add(lobbyPanel, CARD_LOBBY);
        setContentPane(root);
        setMinimumSize(new Dimension(420, 340));
        setLocationByPlatform(true);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeConnection();
            }
        });
        pack();
    }

    private void onConnected(Connection newConnection) {
        connection = newConnection;
        lobbyPanel.showSession(newConnection.session());
        cards.show(root, CARD_LOBBY);
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
        // 2단계부터 방·게임 메시지를 여기서 화면별로 넘긴다.
    }

    private void handleDisconnected(Connection source) {
        if (source != connection) {
            return;
        }
        connection = null;
        connectPanel.showMessage("서버와의 연결이 끊어졌습니다.", true);
        cards.show(root, CARD_CONNECT);
    }

    private void leave() {
        closeConnection();
        connectPanel.showMessage(" ", false);
        cards.show(root, CARD_CONNECT);
    }

    private void closeConnection() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }
}
