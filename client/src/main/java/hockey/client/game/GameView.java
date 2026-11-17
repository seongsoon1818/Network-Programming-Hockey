package hockey.client.game;

import hockey.common.physics.GameState;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

import static hockey.common.physics.PhysicsConstants.*;

/**
 * 경기장을 그리고 마우스 위치를 경기장 좌표로 바꾼다.
 *
 * 물리 좌표는 두 플레이어가 같다(0번이 아래). 1번 플레이어의 화면은 180도 돌려 그려서
 * 누구든 자기 말렛이 아래에 보인다. 마우스 좌표도 같은 변환을 거꾸로 적용한다.
 */
final class GameView extends JComponent {
    private static final Color BACKGROUND = new Color(0x0B1220);
    private static final Color TABLE = new Color(0x13233B);
    private static final Color LINE = new Color(0x35527A);
    private static final Color WALL = new Color(0xC9D6E8);
    private static final Color PUCK = new Color(0xF5D547);
    private static final Color[] PLAYER = {new Color(0xE5484D), new Color(0x3B82F6)};
    private static final Color TEXT = new Color(0xE8EEF7);

    /** 위아래 이름·점수 줄의 높이(px). */
    private static final int STRIP = 44;
    private static final int MARGIN = 14;
    private static final double WALL_THICKNESS = 6;

    private final GameState shown = new GameState();
    private Match match;
    // 미리 그려 둔 이미지(장치 픽셀 크기)와 그때의 조건
    private BufferedImage table;
    private BufferedImage puckSprite;
    private final BufferedImage[] malletSprite = new BufferedImage[2];
    private double imageScale;
    private int imageViewer = -1;
    private boolean mouseSeen;
    private int mouseX, mouseY;

    GameView() {
        setOpaque(true);
        setPreferredSize(new Dimension(
                (int) (FIELD_WIDTH * 0.8) + 2 * MARGIN,
                (int) (FIELD_HEIGHT * 0.8) + 2 * (STRIP + MARGIN)));
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                mouseSeen = true;
                mouseX = e.getX();
                mouseY = e.getY();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                mouseMoved(e);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (match != null) {
                    match.onClick();
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    void setMatch(Match match) {
        this.match = match;
        mouseSeen = false;
        if (match != null) {
            shown.copyFrom(match.state());
        }
    }

    /**
     * 그릴 상태를 정한다. 물리는 60Hz 고정이고 화면 갱신 시점은 그 사이 어디든 될 수 있으므로
     * 직전 틱과 현재 틱 사이를 alpha(0~1)만큼 섞는다.
     */
    void setFrame(GameState previous, GameState current, double alpha) {
        shown.puckX = mix(previous.puckX, current.puckX, alpha);
        shown.puckY = mix(previous.puckY, current.puckY, alpha);
        // 골 뒤 리셋처럼 한 틱에 멀리 옮겨진 경우는 섞지 않는다.
        if (Math.hypot(current.puckX - previous.puckX, current.puckY - previous.puckY) > PUCK_MAX_SPEED * DT * 2) {
            shown.puckX = current.puckX;
            shown.puckY = current.puckY;
        }
        for (int i = 0; i < 2; i++) {
            shown.malletX[i] = mix(previous.malletX[i], current.malletX[i], alpha);
            shown.malletY[i] = mix(previous.malletY[i], current.malletY[i], alpha);
        }
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** 내 말렛의 목표 위치를 경기장 좌표로 out[0], out[1]에 넣는다. 마우스가 아직 안 들어왔으면 현재 말렛 위치. */
    void readTarget(double[] out) {
        int me = match.me();
        if (!mouseSeen) {
            out[0] = match.state().malletX[me];
            out[1] = match.state().malletY[me];
            return;
        }
        double scale = scale();
        double x = (mouseX - originX(scale)) / scale;
        double y = (mouseY - originY(scale)) / scale;
        if (me == 1) {
            x = FIELD_WIDTH - x;
            y = FIELD_HEIGHT - y;
        }
        out[0] = x;
        out[1] = y;
    }

    private double scale() {
        double w = getWidth() - 2.0 * MARGIN;
        double h = getHeight() - 2.0 * (STRIP + MARGIN);
        return Math.max(0.1, Math.min(w / FIELD_WIDTH, h / FIELD_HEIGHT));
    }

    private double originX(double scale) {
        return (getWidth() - FIELD_WIDTH * scale) / 2;
    }

    private double originY(double scale) {
        return (getHeight() - FIELD_HEIGHT * scale) / 2;
    }

    /**
     * 한 프레임을 그린다.
     *
     * 움직이지 않는 것(경기장)과 모양이 변하지 않는 것(말렛, 퍽)은 이미지로 한 번만 그려 두고,
     * 프레임마다 그 이미지를 찍기만 한다. 처음에는 프레임마다 도형을 새로 그렸는데, macOS에서
     * 안티에일리어싱 도형과 큰 이미지 전송이 프레임당 18~33ms 걸려 30fps밖에 나오지 않았다.
     * 바뀌지 않는 이미지는 Java2D가 그래픽 카드에 올려 두므로 찍는 비용이 거의 없다.
     */
    @Override
    protected void paintComponent(Graphics graphics) {
        if (match == null) {
            graphics.setColor(BACKGROUND);
            graphics.fillRect(0, 0, getWidth(), getHeight());
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        // 고해상도(Retina) 화면에서는 1pt가 장치 픽셀 2개다. 이미지는 장치 픽셀 크기로 만들어야 흐려지지 않는다.
        AffineTransform screen = g.getTransform();
        double device = screen.getScaleX();
        double scale = scale();
        double ox = originX(scale);
        double oy = originY(scale);
        prepareImages(device, scale, ox, oy);

        // 이미지는 장치 픽셀 좌표에 1:1로 찍는다(확대·축소 없이).
        g.setTransform(AffineTransform.getTranslateInstance(screen.getTranslateX(), screen.getTranslateY()));
        g.drawImage(table, 0, 0, null);
        double k = scale * device;
        // 골라인을 넘어가는 퍽이 벽 밖으로 삐져나와 보이지 않게 경기장+골대 깊이로 자른다.
        g.clipRect((int) Math.round(ox * device), (int) Math.round((oy - WALL_THICKNESS * scale) * device),
                (int) Math.round(FIELD_WIDTH * k), (int) Math.round((FIELD_HEIGHT + 2 * WALL_THICKNESS) * k));
        for (int i = 0; i < 2; i++) {
            stamp(g, malletSprite[i], shown.malletX[i], shown.malletY[i], ox, oy, k, device);
        }
        stamp(g, puckSprite, shown.puckX, shown.puckY, ox, oy, k, device);
        g.dispose();

        g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int top = (int) Math.round(oy);
        int bottom = (int) Math.round(oy + FIELD_HEIGHT * scale);
        int opponent = 1 - match.me();
        paintStrip(g, opponent, top - MARGIN - STRIP, (int) ox, (int) (FIELD_WIDTH * scale));
        paintStrip(g, match.me(), bottom + MARGIN, (int) ox, (int) (FIELD_WIDTH * scale));
        paintOverlay(g, (top + bottom) / 2, scale);
        g.dispose();
    }

    /** 경기장 좌표 (x, y)가 중심이 되도록 이미지를 찍는다. 1번 플레이어 화면이면 좌표를 뒤집는다. */
    private void stamp(Graphics2D g, BufferedImage sprite, double x, double y,
                       double ox, double oy, double k, double device) {
        if (match.me() == 1) {
            x = FIELD_WIDTH - x;
            y = FIELD_HEIGHT - y;
        }
        int px = (int) Math.round(ox * device + x * k - sprite.getWidth() / 2.0);
        int py = (int) Math.round(oy * device + y * k - sprite.getHeight() / 2.0);
        g.drawImage(sprite, px, py, null);
    }

    /** 창 크기·시점·화면 배율이 바뀌었을 때만 이미지를 다시 만든다. */
    private void prepareImages(double device, double scale, double ox, double oy) {
        int width = Math.max(1, (int) Math.ceil(getWidth() * device));
        int height = Math.max(1, (int) Math.ceil(getHeight() * device));
        int me = match.me();
        if (table != null && table.getWidth() == width && table.getHeight() == height
                && imageScale == scale * device && imageViewer == me) {
            return;
        }
        imageScale = scale * device;
        imageViewer = me;

        table = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = quality(table.createGraphics());
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, width, height);
        g.scale(device, device);
        g.translate(ox, oy);
        g.scale(scale, scale);
        if (me == 1) {
            g.rotate(Math.PI, FIELD_WIDTH / 2, FIELD_HEIGHT / 2);
        }
        paintTable(g);
        g.dispose();

        for (int i = 0; i < 2; i++) {
            malletSprite[i] = sprite(MALLET_RADIUS, imageScale);
            g = quality(malletSprite[i].createGraphics());
            double c = malletSprite[i].getWidth() / 2.0;
            g.translate(c, c);
            g.scale(imageScale, imageScale);
            g.setColor(PLAYER[i].darker());
            g.fill(circle(0, 0, MALLET_RADIUS));
            g.setColor(PLAYER[i]);
            g.fill(circle(0, 0, MALLET_RADIUS - 4));
            g.setColor(PLAYER[i].darker());
            g.fill(circle(0, 0, MALLET_RADIUS * 0.4));
            g.dispose();
        }
        puckSprite = sprite(PUCK_RADIUS, imageScale);
        g = quality(puckSprite.createGraphics());
        double c = puckSprite.getWidth() / 2.0;
        g.translate(c, c);
        g.scale(imageScale, imageScale);
        g.setColor(PUCK.darker());
        g.fill(circle(0, 0, PUCK_RADIUS));
        g.setColor(PUCK);
        g.fill(circle(0, 0, PUCK_RADIUS - 3));
        g.dispose();
    }

    private static BufferedImage sprite(double radius, double pixelsPerUnit) {
        int size = (int) Math.ceil(2 * radius * pixelsPerUnit) + 2;
        return new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    }

    private static Graphics2D quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    /** 경기장 좌표로 그린다. */
    private void paintTable(Graphics2D g) {
        g.setColor(TABLE);
        g.fill(new Rectangle2D.Double(0, 0, FIELD_WIDTH, FIELD_HEIGHT));

        g.setColor(LINE);
        g.setStroke(new BasicStroke(3));
        g.draw(new Line2D.Double(0, FIELD_HEIGHT / 2, FIELD_WIDTH, FIELD_HEIGHT / 2));
        double r = 70;
        g.draw(new Ellipse2D.Double(FIELD_WIDTH / 2 - r, FIELD_HEIGHT / 2 - r, 2 * r, 2 * r));
        // 골대 앞 반원
        double crease = GOAL_WIDTH / 2 + 20;
        g.draw(new java.awt.geom.Arc2D.Double(FIELD_WIDTH / 2 - crease, -crease, 2 * crease, 2 * crease,
                180, 180, java.awt.geom.Arc2D.OPEN));
        g.draw(new java.awt.geom.Arc2D.Double(FIELD_WIDTH / 2 - crease, FIELD_HEIGHT - crease, 2 * crease, 2 * crease,
                0, 180, java.awt.geom.Arc2D.OPEN));

        // 벽: 골대 입구만 비운다. 입구는 지키는 플레이어 색으로 표시한다.
        double t = WALL_THICKNESS;
        g.setColor(WALL);
        g.fill(new Rectangle2D.Double(-t, -t, t, FIELD_HEIGHT + 2 * t));
        g.fill(new Rectangle2D.Double(FIELD_WIDTH, -t, t, FIELD_HEIGHT + 2 * t));
        g.fill(new Rectangle2D.Double(0, -t, GOAL_LEFT, t));
        g.fill(new Rectangle2D.Double(GOAL_RIGHT, -t, FIELD_WIDTH - GOAL_RIGHT, t));
        g.fill(new Rectangle2D.Double(0, FIELD_HEIGHT, GOAL_LEFT, t));
        g.fill(new Rectangle2D.Double(GOAL_RIGHT, FIELD_HEIGHT, FIELD_WIDTH - GOAL_RIGHT, t));
        g.setColor(withAlpha(PLAYER[1], 150));
        g.fill(new Rectangle2D.Double(GOAL_LEFT, -t, GOAL_WIDTH, t));
        g.setColor(withAlpha(PLAYER[0], 150));
        g.fill(new Rectangle2D.Double(GOAL_LEFT, FIELD_HEIGHT, GOAL_WIDTH, t));
    }

    private static Ellipse2D circle(double x, double y, double r) {
        return new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r);
    }

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    /** 이름(왼쪽)과 점수(오른쪽) 한 줄. */
    private void paintStrip(Graphics2D g, int player, int y, int x, int width) {
        int centerY = y + STRIP / 2;
        g.setColor(PLAYER[player]);
        g.fillOval(x + 2, centerY - 7, 14, 14);

        g.setColor(TEXT);
        g.setFont(getFont().deriveFont(Font.PLAIN, 16f));
        FontMetrics fm = g.getFontMetrics();
        String name = match.name(player) + (player == match.me() ? " (나)" : "");
        g.drawString(name, x + 24, centerY + (fm.getAscent() - fm.getDescent()) / 2);

        g.setFont(getFont().deriveFont(Font.BOLD, 26f));
        fm = g.getFontMetrics();
        String score = String.valueOf(match.score(player));
        g.drawString(score, x + width - fm.stringWidth(score) - 2, centerY + (fm.getAscent() - fm.getDescent()) / 2);
    }

    private void paintOverlay(Graphics2D g, int centerY, double scale) {
        String text = match.overlay();
        if (text == null) {
            return;
        }
        String detail = match.overlayDetail();
        g.setFont(getFont().deriveFont(Font.BOLD, (float) (72 * scale)));
        FontMetrics fm = g.getFontMetrics();
        int boxHeight = fm.getHeight() + (detail == null ? 16 : 48);
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(0, centerY - boxHeight / 2, getWidth(), boxHeight);
        g.setColor(TEXT);
        int baseline = centerY - boxHeight / 2 + 8 + fm.getAscent();
        g.drawString(text, (getWidth() - fm.stringWidth(text)) / 2, baseline);
        if (detail != null) {
            g.setFont(getFont().deriveFont(Font.PLAIN, 14f));
            FontMetrics small = g.getFontMetrics();
            g.drawString(detail, (getWidth() - small.stringWidth(detail)) / 2, baseline + fm.getDescent() + 22);
        }
    }
}
