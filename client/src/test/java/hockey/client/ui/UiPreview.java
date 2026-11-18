package hockey.client.ui;

import hockey.client.game.GamePanel;
import hockey.client.game.PracticeMatch;
import hockey.client.model.RoomSettings;
import hockey.client.model.RoomSummary;
import hockey.client.model.RoomView;
import hockey.client.model.RoomView.Member;
import hockey.client.net.Session;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 서버 없이 화면을 가짜 데이터로 띄워 보는 개발용 도구.
 *
 *   인자: lobby | room-host | room-guest | invite | kicked | game-p0 | game-p1
 *   room-host에서 "초대"는 상대가 곧바로 수락한 것처럼, "내보내기"는 서버가 받아들인 것처럼 흉내 낸다.
 *   인자: png <폴더>  → 창을 띄우지 않고 각 화면을 PNG로 저장
 *   인자: fps         → 게임 화면을 8초 띄워 초마다 틱·프레임 수를 찍고 종료
 */
public final class UiPreview {
    public static void main(String[] args) throws Exception {
        String what = args.length > 0 ? args[0] : "lobby";
        if (what.equals("png")) {
            File dir = new File(args.length > 1 ? args[1] : ".");
            SwingUtilities.invokeAndWait(() -> savePngs(dir));
            System.exit(0);
        }
        if (what.equals("fps")) {
            measureFps();
            return;
        }
        SwingUtilities.invokeLater(() -> open(what));
    }

    private static void measureFps() throws Exception {
        GamePanel game = new GamePanel(() -> {});
        JFrame frame = new JFrame("fps 측정");
        SwingUtilities.invokeAndWait(() -> {
            frame.setContentPane(game);
            frame.setSize(520, 860);
            frame.setVisible(true);
            // macOS는 뒤에 깔린 앱의 타이머와 화면 갱신을 늦춘다(App Nap). 앞으로 가져와야 실제 속도가 나온다.
            if (!Boolean.getBoolean("background")) {
                java.awt.Desktop.getDesktop().requestForeground(true);
            }
            game.start(new PracticeMatch(0, "chan", 99), "fps 측정");
        });
        // 처음 1~2초는 JIT 컴파일 전이라 느리므로 초 단위로 나눠 본다.
        long[] last = new long[2];
        for (int second = 1; second <= 8; second++) {
            Thread.sleep(1000);
            int now = second;
            SwingUtilities.invokeAndWait(() -> {
                System.out.printf("%d초째: 틱 %d개, 프레임 %d개%n", now,
                        game.tickCount() - last[0], game.frameCount() - last[1]);
                last[0] = game.tickCount();
                last[1] = game.frameCount();
            });
        }
        System.exit(0);
    }

    private static void open(String what) {
        JFrame frame = new JFrame("미리보기: " + what);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        switch (what) {
            case "lobby" -> frame.setContentPane(lobby());
            case "room-host" -> frame.setContentPane(room(true));
            case "room-guest" -> frame.setContentPane(room(false));
            case "invite" -> frame.setContentPane(lobby());
            case "kicked" -> {
                LobbyPanel lobby = lobby();
                lobby.showStatus("방장이 방에서 내보냈습니다.", true);
                frame.setContentPane(lobby);
            }
            case "game-p0", "game-p1" -> {
                GamePanel game = new GamePanel(() -> System.exit(0));
                frame.setContentPane(game);
                int me = what.endsWith("1") ? 1 : 0;
                game.start(new PracticeMatch(me, "chan", 5), "미리보기 · " + (me + 1) + "P 시점");
            }
            default -> throw new IllegalArgumentException("알 수 없는 인자: " + what
                    + " (가능: lobby, room-host, room-guest, invite, kicked, game-p0, game-p1, png <폴더>, fps)");
        }
        if (what.startsWith("game")) {
            frame.setSize(520, 860);
        } else {
            frame.setSize(680, 460);
        }
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
        if (what.equals("invite")) {
            InviteDialog.show(frame.getContentPane(), "seongjin", InviteDialog.DEFAULT_SECONDS,
                    accepted -> System.out.println(accepted ? "수락" : "거절"));
        }
    }

    private static LobbyPanel lobby() {
        LobbyPanel[] holder = new LobbyPanel[1];
        LobbyPanel lobby = new LobbyPanel(new LobbyPanel.Actions() {
            @Override
            public void createRoom(RoomSettings settings) {
                holder[0].showStatus("방 만들기 요청: " + settings, false);
            }

            @Override
            public void joinRoom(String code) {
                holder[0].showStatus("입장 요청: " + code, false);
            }

            @Override
            public void refreshRooms() {
                holder[0].setRooms(sampleRooms());
                holder[0].showStatus("방 목록을 새로 받았습니다.", false);
            }

            @Override
            public void invite(String nickname) {
                holder[0].showStatus(nickname + " 님에게 초대를 보냈습니다.", false);
            }

            @Override
            public void practice() {
                holder[0].showStatus("연습 모드 요청", false);
            }

            @Override
            public void disconnect() {
                System.exit(0);
            }
        });
        holder[0] = lobby;
        lobby.showSession(new Session("chan", "123e4567-e89b-12d3-a456-426614174000"));
        lobby.setRooms(sampleRooms());
        lobby.setUsers(List.of("chan", "seongjin", "player3", "하키왕"));
        return lobby;
    }

    private static List<RoomSummary> sampleRooms() {
        return List.of(
                new RoomSummary("AB12", "seongjin", 5, 1),
                new RoomSummary("K7Q9X", "player3", 7, 2),
                new RoomSummary("HKY1", "하키왕", 3, 1));
    }

    /** 버튼을 누르면 서버 대신 여기서 방 상태를 바꿔 다시 그린다. */
    private static WaitingRoomPanel room(boolean asHost) {
        String me = "chan";
        WaitingRoomPanel[] holder = new WaitingRoomPanel[1];
        RoomView[] current = new RoomView[1];
        WaitingRoomPanel panel = new WaitingRoomPanel(new WaitingRoomPanel.Actions() {
            @Override
            public void setReady(boolean ready) {
                List<Member> members = new ArrayList<>();
                for (Member m : current[0].members()) {
                    members.add(m.name().equals(me) ? new Member(m.name(), m.host(), ready) : m);
                }
                update(new RoomView(current[0].code(), current[0].settings(), members, me));
            }

            @Override
            public void changeSettings(RoomSettings settings) {
                update(new RoomView(current[0].code(), settings, current[0].members(), me));
            }

            @Override
            public void startGame() {
                holder[0].showStatus("게임 시작 요청", false);
            }

            @Override
            public void invite(String nickname) {
                List<Member> members = new ArrayList<>(current[0].members());
                members.add(new Member(nickname, false, false));
                update(new RoomView(current[0].code(), current[0].settings(), members, me));
                holder[0].showStatus(nickname + " 님이 초대를 수락했습니다(흉내).", false);
            }

            @Override
            public void kick(String nickname) {
                List<Member> members = new ArrayList<>(current[0].members());
                members.removeIf(m -> m.name().equals(nickname));
                update(new RoomView(current[0].code(), current[0].settings(), members, me));
                holder[0].showStatus(nickname + " 님을 내보냈습니다(흉내).", false);
            }

            @Override
            public void leaveRoom() {
                System.exit(0);
            }

            private void update(RoomView next) {
                current[0] = next;
                holder[0].showRoom(next);
            }
        });
        holder[0] = panel;
        panel.setLobbyUsers(List.of("chan", "seongjin", "player3", "하키왕"));
        current[0] = asHost
                ? new RoomView("AB12", RoomSettings.DEFAULT,
                        List.of(new Member(me, true, false), new Member("seongjin", false, true)), me)
                : new RoomView("AB12", new RoomSettings(7, false),
                        List.of(new Member("seongjin", true, false), new Member(me, false, false)), me);
        panel.showRoom(current[0]);
        return panel;
    }

    private static void savePngs(File dir) {
        dir.mkdirs();
        save(lobby(), 680, 460, new File(dir, "lobby.png"));
        save(room(true), 680, 460, new File(dir, "room-host.png"));
        save(room(false), 680, 460, new File(dir, "room-guest.png"));
        for (int me = 0; me < 2; me++) {
            GamePanel game = new GamePanel(() -> {});
            PracticeMatch match = new PracticeMatch(me, "chan", 5);
            game.start(match, "미리보기 · " + (me + 1) + "P 시점");
            game.stop();
            // 화면 없이 4초 진행해 진행 중인 장면을 만든다.
            for (int i = 0; i < 240; i++) {
                match.tick(300, me == 0 ? 500 : 300);
            }
            game.start(match, "미리보기 · " + (me + 1) + "P 시점");
            save(game, 520, 860, new File(dir, "game-p" + me + ".png"));
            game.stop();
        }
        saveServePngs(dir);
    }

    /** 서브 장면: 시작 카운트다운과, 내 서브를 붙잡고 있을 때 뜨는 경고. */
    private static void saveServePngs(File dir) {
        PracticeMatch match;
        do {
            match = new PracticeMatch(0, "chan", 5);
        } while (!"내 서브".equals(match.overlayDetail()));
        GamePanel game = new GamePanel(() -> {});
        game.start(match, "미리보기 · 서브 카운트다운");
        save(game, 520, 860, new File(dir, "game-serve.png"));
        game.stop();

        // 카운트다운 3초 + 8초 동안 퍽을 치지 않으면 남은 2초 경고가 뜬다.
        for (int i = 0; i < 11 * 60; i++) {
            match.tick(40, 760);
        }
        game.start(match, "미리보기 · 서브 시간 경고");
        save(game, 520, 860, new File(dir, "game-hint.png"));
        game.stop();
    }

    private static void save(JPanel panel, int width, int height, File file) {
        JFrame frame = new JFrame();
        frame.setContentPane(panel);
        frame.getContentPane().setPreferredSize(new java.awt.Dimension(width, height));
        frame.pack();
        BufferedImage image = new BufferedImage(width * 2, height * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.scale(2, 2);
        panel.paint(g);
        g.dispose();
        try {
            ImageIO.write(image, "png", file);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        frame.dispose();
        System.out.println("저장: " + file);
    }
}
