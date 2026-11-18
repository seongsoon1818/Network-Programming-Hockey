package hockey.client.game;

import hockey.common.physics.GameState;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Toolkit;

import static hockey.common.physics.PhysicsConstants.DT;
import static hockey.common.physics.PhysicsConstants.TICK_RATE;

/**
 * 게임 화면. 고정 60Hz로 판을 진행시키고 60fps로 그린다.
 *
 * Swing Timer는 정확히 1/60초마다 울리지 않으므로, 실제로 흐른 시간을 모아 두었다가
 * 1/60초가 찰 때마다 한 틱씩 진행한다(고정 타임스텝). 틱과 그리기가 모두 EDT에서 돌아
 * 상태를 잠글 필요가 없다.
 */
public final class GamePanel extends JPanel {
    private static final long FRAME_NANOS = 1_000_000_000L / TICK_RATE;
    /** 창이 멈췄다 돌아왔을 때 한꺼번에 따라잡는 틱 수의 상한. */
    private static final double MAX_CATCH_UP = 0.25;

    private final GameView view = new GameView();
    private final JLabel titleLabel = new JLabel(" ");
    private final Timer timer = new Timer(4, e -> onTimer());
    private final GameState previous = new GameState();
    private final double[] target = new double[2];
    private Match match;
    private long lastNanos;
    private long nextPaintNanos;
    private double pending;
    private long tickCount;
    private long frameCount;

    public GamePanel(Runnable onExit) {
        setLayout(new BorderLayout());
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        JButton exitButton = new JButton("나가기");
        exitButton.setFocusable(false);
        exitButton.addActionListener(e -> onExit.run());
        bar.add(titleLabel, BorderLayout.CENTER);
        bar.add(exitButton, BorderLayout.EAST);
        add(bar, BorderLayout.NORTH);
        add(view, BorderLayout.CENTER);
    }

    /** 판을 시작한다. 이미 돌고 있던 판은 버린다. */
    public void start(Match newMatch, String title) {
        match = newMatch;
        titleLabel.setText(title);
        view.setMatch(newMatch);
        previous.copyFrom(newMatch.state());
        pending = 0;
        tickCount = frameCount = 0;
        lastNanos = nextPaintNanos = System.nanoTime();
        timer.start();
    }

    public void stop() {
        timer.stop();
        match = null;
        view.setMatch(null);
    }

    private void onTimer() {
        if (match == null) {
            return;
        }
        long now = System.nanoTime();
        pending = Math.min(pending + (now - lastNanos) / 1e9, MAX_CATCH_UP);
        lastNanos = now;
        while (pending >= DT) {
            previous.copyFrom(match.state());
            view.readTarget(target);
            match.tick(target[0], target[1]);
            pending -= DT;
            tickCount++;
        }
        if (now - nextPaintNanos >= 0) {
            // 타이머는 4~5ms 단위로만 울려 프레임 시각이 조금씩 늦는다. 다음 프레임 시각을 "지금 + 1/60초"가
            // 아니라 "예정 시각 + 1/60초"로 잡아야 늦은 만큼이 쌓이지 않고 평균 60fps가 유지된다.
            nextPaintNanos += FRAME_NANOS;
            if (now - nextPaintNanos > 2 * FRAME_NANOS) {
                nextPaintNanos = now + FRAME_NANOS; // 크게 밀렸으면 따라잡으려 하지 않는다.
            }
            view.setFrame(previous, match.state(), pending / DT);
            view.paintImmediately(0, 0, view.getWidth(), view.getHeight());
            Toolkit.getDefaultToolkit().sync();
            frameCount++;
        }
    }

    /** 시작 후 진행한 틱 수(측정용). */
    public long tickCount() {
        return tickCount;
    }

    /** 시작 후 그린 프레임 수(측정용). */
    public long frameCount() {
        return frameCount;
    }
}
