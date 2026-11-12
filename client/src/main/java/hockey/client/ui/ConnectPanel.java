package hockey.client.ui;

import hockey.client.net.Connection;
import hockey.client.net.Connection.HelloResult;
import hockey.common.protocol.Message.HelloFailReason;
import hockey.common.protocol.Nicknames;
import hockey.common.protocol.Protocol;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** 첫 화면: 서버 주소와 닉네임을 받아 접속한다. */
final class ConnectPanel extends JPanel {
    private final JTextField hostField = new JTextField("localhost", 16);
    private final JTextField portField = new JTextField(String.valueOf(Protocol.DEFAULT_PORT), 16);
    private final JTextField nicknameField = new JTextField(16);
    private final JButton connectButton = new JButton("접속");
    private final JLabel statusLabel = new JLabel(" ");
    private final Consumer<Connection> onConnected;

    ConnectPanel(Consumer<Connection> onConnected) {
        this.onConnected = onConnected;
        setLayout(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(24, 32, 24, 32));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = new JLabel("네트워크 에어하키");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        add(title, c);

        c.gridwidth = 1;
        addRow(c, 1, "서버 주소", hostField);
        addRow(c, 2, "포트", portField);
        addRow(c, 3, "닉네임", nicknameField);

        c.gridx = 0;
        c.gridy = 4;
        c.gridwidth = 2;
        add(connectButton, c);
        c.gridy = 5;
        add(statusLabel, c);

        connectButton.addActionListener(e -> connect());
        nicknameField.addActionListener(e -> connect());
    }

    private void addRow(GridBagConstraints c, int row, String label, JTextField field) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        add(field, c);
    }

    /** 다른 화면에서 돌아올 때 보여줄 안내. */
    void showMessage(String message, boolean error) {
        statusLabel.setForeground(error ? new Color(0xC62828) : Color.DARK_GRAY);
        statusLabel.setText(message == null || message.isEmpty() ? " " : message);
    }

    private void connect() {
        if (!connectButton.isEnabled()) {
            return;
        }
        String host = hostField.getText().trim();
        String nickname = nicknameField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            port = -1;
        }
        if (host.isEmpty()) {
            showMessage("서버 주소를 입력하세요.", true);
            return;
        }
        if (port < 1 || port > 65535) {
            showMessage("포트는 1~65535 사이 숫자여야 합니다.", true);
            return;
        }
        if (!Nicknames.isValid(nickname)) {
            showMessage(invalidNicknameMessage(), true);
            return;
        }

        setBusy(true);
        showMessage("접속 중…", false);
        final int finalPort = port;
        new SwingWorker<HelloResult, Void>() {
            @Override
            protected HelloResult doInBackground() throws Exception {
                return Connection.connect(host, finalPort, nickname);
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    HelloResult result = get();
                    if (result.accepted()) {
                        showMessage(" ", false);
                        onConnected.accept(result.connection());
                    } else {
                        showMessage(describe(result.failReason()), true);
                    }
                } catch (ExecutionException e) {
                    showMessage(describe(e.getCause()), true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    private void setBusy(boolean busy) {
        connectButton.setEnabled(!busy);
        hostField.setEnabled(!busy);
        portField.setEnabled(!busy);
        nicknameField.setEnabled(!busy);
    }

    private static String invalidNicknameMessage() {
        return "닉네임은 " + Nicknames.MIN_LENGTH + "~" + Nicknames.MAX_LENGTH + "자의 한글·영문·숫자·밑줄만 쓸 수 있습니다.";
    }

    private static String describe(HelloFailReason reason) {
        return switch (reason) {
            case DUPLICATE_NICKNAME -> "이미 사용 중인 닉네임입니다.";
            case INVALID_NICKNAME -> invalidNicknameMessage();
            case ALREADY_REGISTERED -> "이미 접속된 연결입니다.";
            case UNKNOWN -> "서버가 접속을 거절했습니다.";
        };
    }

    private static String describe(Throwable cause) {
        if (cause instanceof UnknownHostException) {
            return "서버 주소를 찾을 수 없습니다.";
        }
        if (cause instanceof ConnectException) {
            return "서버에 연결할 수 없습니다. 주소와 포트를 확인하세요.";
        }
        if (cause instanceof SocketTimeoutException) {
            return "서버 응답이 없습니다.";
        }
        return "접속 실패: " + cause.getMessage();
    }
}
