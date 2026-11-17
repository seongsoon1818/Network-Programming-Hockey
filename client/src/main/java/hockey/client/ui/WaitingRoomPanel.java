package hockey.client.ui;

import hockey.client.model.RoomSettings;
import hockey.client.model.RoomSummary;
import hockey.client.model.RoomView;
import hockey.client.model.RoomView.Member;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 대기실: 방 코드, 두 자리, 준비/시작, 방장 설정.
 * 화면은 서버가 보낸 방 상태({@link RoomView})를 그대로 그리고, 버튼은 요청만 보낸다.
 * 준비 상태를 화면이 먼저 바꾸지 않는 이유는 두 사람의 화면이 서버 상태 하나만 따르게 하기 위해서다.
 *
 * 방장과 게스트: 방을 만들거나 초대를 보낸 사람이 방장, 코드·목록으로 들어오거나 초대를 수락한 사람이
 * 게스트다. 방장만 설정 변경, 초대, 내보내기, 시작을 할 수 있다.
 */
final class WaitingRoomPanel extends JPanel {
    interface Actions {
        void setReady(boolean ready);

        void changeSettings(RoomSettings settings);

        void startGame();

        /** 방장만: 로비 사용자를 이 방에 게스트로 초대한다. */
        void invite(String nickname);

        /** 방장만: 게스트를 방에서 내보낸다. */
        void kick(String nickname);

        void leaveRoom();
    }

    private final Actions actions;
    private final JLabel codeLabel = new JLabel();
    private final SlotPanel[] slots = {new SlotPanel(this::confirmKick), new SlotPanel(this::confirmKick)};
    private final JComboBox<Integer> scoreBox = new JComboBox<>(RoomSettings.TARGET_SCORES.toArray(new Integer[0]));
    private final JCheckBox publicBox = new JCheckBox("방 목록에 공개");
    private final JButton readyButton = new JButton("준비");
    private final JButton startButton = new JButton("게임 시작");
    private final JButton inviteButton = new JButton("초대");
    /** 초대할 수 있는 로비 사용자. ClientFrame이 접속자 목록을 받을 때마다 넣어 준다. */
    private List<String> lobbyUsers = List.of();
    private final JLabel statusLabel = new JLabel(" ");
    private RoomView room;
    /** showRoom이 콤보·체크박스 값을 바꾸는 동안 changeSettings 요청이 나가지 않게 막는다. */
    private boolean updating;

    WaitingRoomPanel(Actions actions) {
        this.actions = actions;
        setLayout(new BorderLayout(12, 12));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        JLabel title = new JLabel("대기실");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        codeLabel.setFont(codeLabel.getFont().deriveFont(Font.BOLD, 18f));
        JButton copyButton = new JButton("코드 복사");
        copyButton.addActionListener(e -> copyCode());
        JPanel codeRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        codeRow.add(codeLabel);
        codeRow.add(copyButton);
        JPanel header = new JPanel(new BorderLayout());
        header.add(title, BorderLayout.WEST);
        header.add(codeRow, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        JPanel slotRow = new JPanel(new GridLayout(1, 2, 12, 0));
        slotRow.add(slots[0]);
        slotRow.add(slots[1]);

        JPanel settings = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        settings.add(new JLabel("목표 점수"));
        settings.add(scoreBox);
        settings.add(publicBox);
        scoreBox.addActionListener(e -> sendSettings());
        publicBox.addActionListener(e -> sendSettings());

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(slotRow);
        center.add(Box.createVerticalStrut(12));
        center.add(settings);
        center.add(Box.createVerticalGlue());
        add(center, BorderLayout.CENTER);

        JButton leaveButton = new JButton("나가기");
        readyButton.addActionListener(e -> {
            Member me = room == null ? null : room.me();
            if (me != null) {
                actions.setReady(!me.ready());
            }
        });
        startButton.addActionListener(e -> actions.startGame());
        inviteButton.addActionListener(e -> askInvite());
        leaveButton.addActionListener(e -> actions.leaveRoom());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(inviteButton);
        buttons.add(readyButton);
        buttons.add(startButton);
        buttons.add(leaveButton);
        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.add(statusLabel, BorderLayout.CENTER);
        footer.add(buttons, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
    }

    void showRoom(RoomView newRoom) {
        room = newRoom;
        updating = true;
        try {
            codeLabel.setText("방 코드 " + newRoom.code());
            boolean host = newRoom.amHost();
            for (int i = 0; i < slots.length; i++) {
                Member member = i < newRoom.members().size() ? newRoom.members().get(i) : null;
                boolean isMe = member != null && member.name().equals(newRoom.myName());
                // 방장 화면에서만, 그리고 게스트 자리에만 내보내기 버튼이 보인다.
                slots[i].show(member, isMe, host && member != null && !member.host());
            }
            scoreBox.setSelectedItem(newRoom.settings().targetScore());
            publicBox.setSelected(newRoom.settings().isPublic());
            scoreBox.setEnabled(host);
            publicBox.setEnabled(host);

            // 방장은 준비 대신 시작 버튼을 쓴다.
            Member me = newRoom.me();
            readyButton.setVisible(!host);
            readyButton.setText(me != null && me.ready() ? "준비 취소" : "준비");
            startButton.setVisible(host);
            inviteButton.setVisible(host);
            inviteButton.setEnabled(newRoom.members().size() < RoomSummary.CAPACITY);
            startButton.setEnabled(newRoom.canStart());
            statusLabel.setForeground(Color.DARK_GRAY);
            statusLabel.setText(describe(newRoom, host));
        } finally {
            updating = false;
        }
    }

    private static String describe(RoomView room, boolean host) {
        if (room.members().size() < RoomSummary.CAPACITY) {
            return host ? "상대를 기다리는 중입니다. 방 코드를 알려 주거나 초대하세요." : "방장을 기다리는 중입니다.";
        }
        if (room.canStart()) {
            return host ? "상대가 준비했습니다. 게임을 시작하세요." : "방장이 시작하기를 기다리는 중입니다.";
        }
        return host ? "상대가 준비하기를 기다리는 중입니다." : "준비 버튼을 누르세요.";
    }

    void showStatus(String message, boolean error) {
        statusLabel.setForeground(error ? new Color(0xC62828) : Color.DARK_GRAY);
        statusLabel.setText(message == null || message.isEmpty() ? " " : message);
    }

    void setLobbyUsers(List<String> users) {
        lobbyUsers = List.copyOf(users);
    }

    /** 방에 없는 로비 사용자 중 한 명을 골라 초대한다. */
    private void askInvite() {
        if (room == null || !room.amHost()) {
            return;
        }
        List<String> candidates = new ArrayList<>();
        for (String user : lobbyUsers) {
            boolean inRoom = room.members().stream().anyMatch(m -> m.name().equalsIgnoreCase(user));
            if (!inRoom) {
                candidates.add(user);
            }
        }
        if (candidates.isEmpty()) {
            showStatus("초대할 수 있는 접속자가 없습니다.", true);
            return;
        }
        Object choice = JOptionPane.showInputDialog(this, "초대할 사람", "초대",
                JOptionPane.PLAIN_MESSAGE, null, candidates.toArray(), candidates.get(0));
        if (choice != null) {
            actions.invite((String) choice);
        }
    }

    private void confirmKick(String nickname) {
        if (room == null || !room.amHost()) {
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this, nickname + " 님을 방에서 내보낼까요?", "내보내기",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.OK_OPTION) {
            actions.kick(nickname);
        }
    }

    private void sendSettings() {
        if (updating || room == null || !room.amHost()) {
            return;
        }
        RoomSettings wanted = new RoomSettings((Integer) scoreBox.getSelectedItem(), publicBox.isSelected());
        if (!wanted.equals(room.settings())) {
            actions.changeSettings(wanted);
        }
    }

    private void copyCode() {
        if (room != null) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(room.code()), null);
            showStatus("방 코드를 복사했습니다.", false);
        }
    }

    /** 플레이어 한 자리. 방장 화면의 게스트 자리에는 내보내기 버튼이 붙는다. */
    private static final class SlotPanel extends JPanel {
        private final JLabel nameLabel = new JLabel();
        private final JLabel stateLabel = new JLabel();
        private final JButton kickButton = new JButton("내보내기");
        private String memberName;

        SlotPanel(Consumer<String> onKick) {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color.LIGHT_GRAY),
                    BorderFactory.createEmptyBorder(16, 16, 16, 16)));
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 16f));
            kickButton.addActionListener(e -> {
                if (memberName != null) {
                    onKick.accept(memberName);
                }
            });
            add(nameLabel);
            add(Box.createVerticalStrut(8));
            add(stateLabel);
            add(Box.createVerticalStrut(12));
            add(kickButton);
        }

        void show(Member member, boolean isMe, boolean canKick) {
            memberName = member == null ? null : member.name();
            kickButton.setVisible(canKick);
            if (member == null) {
                nameLabel.setText("빈 자리");
                nameLabel.setForeground(Color.GRAY);
                stateLabel.setText(" ");
                return;
            }
            nameLabel.setForeground(Color.BLACK);
            nameLabel.setText(member.name() + (isMe ? " (나)" : ""));
            if (member.host()) {
                stateLabel.setForeground(Color.DARK_GRAY);
                stateLabel.setText("방장");
            } else if (member.ready()) {
                stateLabel.setForeground(new Color(0x2E7D32));
                stateLabel.setText("게스트 · 준비 완료");
            } else {
                stateLabel.setForeground(Color.GRAY);
                stateLabel.setText("게스트 · 준비 전");
            }
        }
    }
}
