package hockey.client.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;

/**
 * 초대 팝업. 남은 시간을 세다가 0이 되면 응답 없이 닫는다. 시간 초과 판정은 서버가 하고
 * INVITE_CANCELLED를 보낸다. 여기서 거절을 보내면 이미 끝난 초대라 NO_SUCH_INVITE가 돌아온다.
 * 모달이 아니다: 팝업이 떠 있는 동안에도 수신 메시지가 화면에 반영돼야 하기 때문이다.
 */
final class InviteDialog extends JDialog {
    /** 초대 만료 시간(15초). 실제 시간은 INVITED에 담겨 오고, 이 값은 미리보기용이다. */
    static final int DEFAULT_SECONDS = 15;

    private final JLabel countdownLabel = new JLabel();
    private final Timer timer = new Timer(1000, e -> onSecond());
    private final Consumer<Boolean> onAnswer;
    private int secondsLeft;
    private boolean answered;

    /**
     * @param onAnswer 수락이면 true, 거절·창 닫기면 false. 시간이 다 되면 부르지 않는다. 많아야 한 번 불린다.
     */
    static InviteDialog show(Component parent, String fromName, int seconds, Consumer<Boolean> onAnswer) {
        InviteDialog dialog = new InviteDialog(parent, fromName, seconds, onAnswer);
        dialog.setVisible(true);
        return dialog;
    }

    private InviteDialog(Component parent, String fromName, int seconds, Consumer<Boolean> onAnswer) {
        super(SwingUtilities.getWindowAncestor(parent), "초대", ModalityType.MODELESS);
        this.onAnswer = onAnswer;
        this.secondsLeft = seconds;

        JButton acceptButton = new JButton("수락");
        JButton declineButton = new JButton("거절");
        acceptButton.addActionListener(e -> answer(true));
        declineButton.addActionListener(e -> answer(false));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(acceptButton);
        buttons.add(declineButton);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 12, 20));
        content.add(new JLabel(fromName + " 님이 게임에 초대했습니다."), BorderLayout.NORTH);
        content.add(countdownLabel, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        updateCountdown();

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                answer(false);
            }
        });
        setResizable(false);
        pack();
        setLocationRelativeTo(parent);
        timer.start();
    }

    private void onSecond() {
        secondsLeft--;
        if (secondsLeft <= 0) {
            cancel();
        } else {
            updateCountdown();
        }
    }

    private void updateCountdown() {
        countdownLabel.setText(secondsLeft + "초 안에 응답하지 않으면 초대가 취소됩니다.");
    }

    private void answer(boolean accepted) {
        if (answered) {
            return;
        }
        answered = true;
        close();
        onAnswer.accept(accepted);
    }

    /** 응답 없이 닫는다(초대한 쪽이 취소했거나 연결이 끊긴 경우). */
    void cancel() {
        answered = true;
        close();
    }

    private void close() {
        timer.stop();
        dispose();
    }
}
