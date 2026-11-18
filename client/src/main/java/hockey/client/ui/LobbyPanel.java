package hockey.client.ui;

import hockey.client.model.RoomCodes;
import hockey.client.model.RoomSettings;
import hockey.client.model.RoomSummary;
import hockey.client.net.Session;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * 로비: 공개 방 목록, 방 만들기·코드 입장, 접속자 목록과 초대.
 * 화면은 요청을 {@link Actions}로 넘기기만 하고, 결과는 setRooms/setUsers/showStatus로 받는다.
 */
final class LobbyPanel extends JPanel {
    /** 로비에서 사용자가 할 수 있는 일. 서버 연동은 구현하는 쪽(ClientFrame)이 맡는다. */
    interface Actions {
        void createRoom(RoomSettings settings);

        void joinRoom(String code);

        void refreshRooms();

        /**
         * 로비에서 상대를 초대한다. 초대한 사람이 방장이 되도록, 서버가 내가 방장인 방을 만들고
         * 상대가 수락하면 게스트로 넣는다(나는 바로 대기실로 가서 기다린다).
         */
        void invite(String nickname);

        void practice();

        void disconnect();
    }

    private final Actions actions;
    private final JLabel nicknameLabel = new JLabel();
    private final JLabel tokenLabel = new JLabel();
    private final JLabel statusLabel = new JLabel(" ");
    private final RoomTableModel roomModel = new RoomTableModel();
    private final JTable roomTable = new JTable(roomModel);
    private final DefaultListModel<String> userModel = new DefaultListModel<>();
    private final JList<String> userList = new JList<>(userModel);
    private final JButton joinSelectedButton = new JButton("선택한 방 입장");
    private final JButton inviteButton = new JButton("초대");
    private String myName = "";

    LobbyPanel(Actions actions) {
        this.actions = actions;
        setLayout(new BorderLayout(12, 12));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        add(buildHeader(), BorderLayout.NORTH);
        add(buildRooms(), BorderLayout.CENTER);
        add(buildUsers(), BorderLayout.EAST);
        add(buildFooter(), BorderLayout.SOUTH);
        updateButtons();
    }

    private JPanel buildHeader() {
        JLabel title = new JLabel("로비");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        tokenLabel.setForeground(Color.GRAY);
        tokenLabel.setFont(tokenLabel.getFont().deriveFont(11f));

        JPanel info = new JPanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.add(nicknameLabel);
        info.add(tokenLabel);

        JButton disconnectButton = new JButton("연결 끊기");
        disconnectButton.addActionListener(e -> actions.disconnect());

        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.add(title, BorderLayout.WEST);
        header.add(info, BorderLayout.CENTER);
        header.add(disconnectButton, BorderLayout.EAST);
        return header;
    }

    private JPanel buildRooms() {
        roomTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        roomTable.setFillsViewportHeight(true);
        roomTable.setRowHeight(24);
        roomTable.getTableHeader().setReorderingAllowed(false);
        roomTable.getSelectionModel().addListSelectionListener(e -> updateButtons());
        roomTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    joinSelected();
                }
            }
        });

        JButton createButton = new JButton("방 만들기");
        JButton codeButton = new JButton("코드로 입장");
        JButton refreshButton = new JButton("새로고침");
        createButton.addActionListener(e -> askCreateRoom());
        codeButton.addActionListener(e -> askRoomCode());
        refreshButton.addActionListener(e -> actions.refreshRooms());
        joinSelectedButton.addActionListener(e -> joinSelected());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(createButton);
        buttons.add(codeButton);
        buttons.add(joinSelectedButton);
        buttons.add(refreshButton);

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(sectionLabel("공개 방"), BorderLayout.NORTH);
        panel.add(new JScrollPane(roomTable), BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildUsers() {
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.addListSelectionListener(e -> updateButtons());
        inviteButton.addActionListener(e -> {
            String selected = userList.getSelectedValue();
            if (selected != null) {
                actions.invite(selected);
            }
        });
        JScrollPane scroll = new JScrollPane(userList);
        scroll.setPreferredSize(new Dimension(160, 0));

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(sectionLabel("접속자"), BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(inviteButton, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildFooter() {
        JButton practiceButton = new JButton("연습 모드");
        practiceButton.addActionListener(e -> actions.practice());
        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.add(statusLabel, BorderLayout.CENTER);
        footer.add(practiceButton, BorderLayout.EAST);
        return footer;
    }

    private static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    private void askCreateRoom() {
        JComboBox<Integer> scoreBox = new JComboBox<>(RoomSettings.TARGET_SCORES.toArray(new Integer[0]));
        scoreBox.setSelectedItem(RoomSettings.DEFAULT.targetScore());
        JCheckBox publicBox = new JCheckBox("방 목록에 공개", RoomSettings.DEFAULT.isPublic());
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 6));
        form.add(new JLabel("목표 점수"));
        form.add(scoreBox);
        form.add(publicBox);
        int answer = JOptionPane.showConfirmDialog(this, form, "방 만들기",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer == JOptionPane.OK_OPTION) {
            actions.createRoom(new RoomSettings((Integer) scoreBox.getSelectedItem(), publicBox.isSelected()));
        }
    }

    private void askRoomCode() {
        String input = JOptionPane.showInputDialog(this, "방 코드 (" + RoomCodes.MIN_LENGTH + "~"
                + RoomCodes.MAX_LENGTH + "자)", "코드로 입장", JOptionPane.PLAIN_MESSAGE);
        if (input == null) {
            return;
        }
        String code = RoomCodes.normalize(input);
        if (!RoomCodes.isValid(code)) {
            showStatus("방 코드는 영문·숫자 " + RoomCodes.MIN_LENGTH + "~" + RoomCodes.MAX_LENGTH + "자입니다.", true);
            return;
        }
        actions.joinRoom(code);
    }

    private void joinSelected() {
        int row = roomTable.getSelectedRow();
        if (row < 0) {
            return;
        }
        RoomSummary room = roomModel.rooms.get(row);
        if (room.isFull()) {
            showStatus("가득 찬 방입니다.", true);
            return;
        }
        actions.joinRoom(room.code());
    }

    private void updateButtons() {
        joinSelectedButton.setEnabled(roomTable.getSelectedRow() >= 0);
        String selected = userList.getSelectedValue();
        inviteButton.setEnabled(selected != null && !selected.equals(myName));
    }

    void showSession(Session session) {
        myName = session.nickname();
        nicknameLabel.setText("닉네임: " + session.nickname());
        tokenLabel.setText("토큰: " + session.token());
        setRooms(List.of());
        setUsers(List.of());
        showStatus(" ", false);
    }

    /** 방 목록을 바꾼다. 선택해 둔 방이 새 목록에도 있으면 선택을 유지한다. */
    void setRooms(List<RoomSummary> rooms) {
        int row = roomTable.getSelectedRow();
        String selectedCode = row >= 0 ? roomModel.rooms.get(row).code() : null;
        roomModel.rooms = List.copyOf(rooms);
        roomModel.fireTableDataChanged();
        for (int i = 0; i < rooms.size(); i++) {
            if (rooms.get(i).code().equals(selectedCode)) {
                roomTable.setRowSelectionInterval(i, i);
            }
        }
        updateButtons();
    }

    void setUsers(List<String> users) {
        String selected = userList.getSelectedValue();
        userModel.clear();
        for (String user : users) {
            userModel.addElement(user);
        }
        userList.setSelectedValue(selected, false);
        updateButtons();
    }

    void showStatus(String message, boolean error) {
        statusLabel.setForeground(error ? new Color(0xC62828) : Color.DARK_GRAY);
        statusLabel.setText(message == null || message.isEmpty() ? " " : message);
    }

    private static final class RoomTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"방 코드", "방장", "목표 점수", "인원"};
        private List<RoomSummary> rooms = List.of();

        @Override
        public int getRowCount() {
            return rooms.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            RoomSummary room = rooms.get(row);
            return switch (column) {
                case 0 -> room.code();
                case 1 -> room.hostName();
                case 2 -> room.targetScore() + "점";
                default -> room.playerCount() + "/" + RoomSummary.CAPACITY;
            };
        }
    }
}
