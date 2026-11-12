package hockey.client.ui;

import hockey.client.net.Session;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Font;

/** 접속 후 화면. 1단계에서는 접속 정보만 보여준다(방 만들기·목록은 2단계). */
final class LobbyPanel extends JPanel {
    private final JLabel nicknameLabel = new JLabel();
    private final JLabel tokenLabel = new JLabel();

    LobbyPanel(Runnable onLeave) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(24, 32, 24, 32));

        JLabel title = new JLabel("로비");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        JButton leaveButton = new JButton("연결 끊기");
        leaveButton.addActionListener(e -> onLeave.run());

        add(title);
        add(Box.createVerticalStrut(16));
        add(nicknameLabel);
        add(Box.createVerticalStrut(6));
        add(tokenLabel);
        add(Box.createVerticalGlue());
        add(leaveButton);
    }

    void showSession(Session session) {
        nicknameLabel.setText("닉네임: " + session.nickname());
        tokenLabel.setText("토큰: " + session.token());
    }
}
