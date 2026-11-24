package hockey.common.protocol;

import hockey.common.protocol.Message.*;
import hockey.common.rules.MatchEngine.Phase;
import hockey.common.rules.MatchSnapshot;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.List;

/** 필드 순서와 자료형을 정의한다. 같은 스트림의 쓰기는 호출부에서 직렬화해야 한다. */
public final class MessageCodec {
    private MessageCodec() {}

    public static void writeRequest(DataOutputStream out, Request request) throws IOException {
        switch (request) {
            case Hello m -> {
                out.writeUTF(Protocol.HELLO);
                out.writeUTF(m.nickname());
            }
            case Quit m -> out.writeUTF(Protocol.QUIT);
            case CreateRoom m -> {
                out.writeUTF(Protocol.CREATE_ROOM);
                out.writeInt(m.targetScore());
                out.writeBoolean(m.isPublic());
            }
            case JoinRoom m -> {
                out.writeUTF(Protocol.JOIN_ROOM);
                out.writeUTF(m.code());
            }
            case ListRooms m -> out.writeUTF(Protocol.LIST_ROOMS);
            case Ready m -> {
                out.writeUTF(Protocol.READY);
                out.writeBoolean(m.ready());
            }
            case Settings m -> {
                out.writeUTF(Protocol.SETTINGS);
                out.writeInt(m.targetScore());
                out.writeBoolean(m.isPublic());
            }
            case Start m -> out.writeUTF(Protocol.START);
            case LeaveRoom m -> out.writeUTF(Protocol.LEAVE_ROOM);
            case Kick m -> {
                out.writeUTF(Protocol.KICK);
                out.writeUTF(m.name());
            }
            case Invite m -> {
                out.writeUTF(Protocol.INVITE);
                out.writeUTF(m.name());
            }
            case InviteReply m -> {
                out.writeUTF(Protocol.INVITE_REPLY);
                out.writeUTF(m.inviter());
                out.writeBoolean(m.accept());
            }
            case Input m -> {
                out.writeUTF(Protocol.INPUT);
                out.writeInt(m.seq());
                out.writeDouble(m.x());
                out.writeDouble(m.y());
            }
            case LeaveGame m -> out.writeUTF(Protocol.LEAVE_GAME);
        }
        out.flush();
    }

    public static Request readRequest(DataInputStream in) throws IOException {
        String command = in.readUTF();
        return switch (command) {
            case Protocol.HELLO -> new Hello(in.readUTF());
            case Protocol.QUIT -> new Quit();
            case Protocol.CREATE_ROOM -> new CreateRoom(in.readInt(), in.readBoolean());
            case Protocol.JOIN_ROOM -> new JoinRoom(in.readUTF());
            case Protocol.LIST_ROOMS -> new ListRooms();
            case Protocol.READY -> new Ready(in.readBoolean());
            case Protocol.SETTINGS -> new Settings(in.readInt(), in.readBoolean());
            case Protocol.START -> new Start();
            case Protocol.LEAVE_ROOM -> new LeaveRoom();
            case Protocol.KICK -> new Kick(in.readUTF());
            case Protocol.INVITE -> new Invite(in.readUTF());
            case Protocol.INVITE_REPLY -> new InviteReply(in.readUTF(), in.readBoolean());
            case Protocol.INPUT -> new Input(in.readInt(), in.readDouble(), in.readDouble());
            case Protocol.LEAVE_GAME -> new LeaveGame();
            default -> throw new ProtocolException("지원하지 않는 명령: " + command);
        };
    }

    public static Reply readReply(DataInputStream in) throws IOException {
        String command = in.readUTF();
        return switch (command) {
            case Protocol.WELCOME -> new Welcome(in.readUTF());
            case Protocol.HELLO_FAILED -> new HelloFailed(HelloFailReason.fromWire(in.readUTF()));
            case Protocol.ROOM_UPDATE -> readRoom(in);
            case Protocol.ROOM_LIST -> readRooms(in);
            case Protocol.ROOM_LEFT -> new RoomLeft();
            case Protocol.JOIN_FAILED -> new JoinFailed(RoomFailReason.fromWire(in.readUTF()));
            case Protocol.ROOM_FAILED -> new RoomFailed(in.readUTF(), RoomFailReason.fromWire(in.readUTF()));
            case Protocol.GAME_START -> new GameStart(in.readUTF(), in.readInt(), in.readInt());
            case Protocol.USER_LIST -> readUsers(in);
            case Protocol.INVITED -> new Invited(in.readUTF(), in.readInt());
            case Protocol.INVITE_RESULT -> new InviteResult(in.readUTF(), InviteOutcome.fromWire(in.readUTF()));
            case Protocol.INVITE_CANCELLED -> new InviteCancelled(in.readUTF());
            case Protocol.STATE -> readState(in);
            case Protocol.GAME_OVER -> new GameOver(in.readInt(), in.readInt(), in.readInt(),
                    GameOverReason.fromWire(in.readUTF()));
            // 모르는 명령은 필드 길이를 알 수 없어 뒤 메시지까지 어긋난다. 읽지 않고 연결을 끊게 한다.
            default -> throw new ProtocolException("지원하지 않는 서버 명령: " + command);
        };
    }

    public static void writeReply(DataOutputStream out, Reply reply) throws IOException {
        switch (reply) {
            case Welcome m -> {
                out.writeUTF(Protocol.WELCOME);
                out.writeUTF(m.token());
            }
            case HelloFailed m -> {
                out.writeUTF(Protocol.HELLO_FAILED);
                out.writeUTF(m.reason().name());
            }
            case RoomUpdate m -> {
                out.writeUTF(Protocol.ROOM_UPDATE);
                out.writeUTF(m.code());
                out.writeInt(m.targetScore());
                out.writeBoolean(m.isPublic());
                out.writeBoolean(m.started());
                out.writeInt(m.members().size());
                for (RoomMember member : m.members()) {
                    out.writeUTF(member.name());
                    out.writeBoolean(member.host());
                    out.writeBoolean(member.ready());
                }
            }
            case RoomList m -> {
                out.writeUTF(Protocol.ROOM_LIST);
                out.writeInt(m.rooms().size());
                for (RoomInfo room : m.rooms()) {
                    out.writeUTF(room.code());
                    out.writeUTF(room.hostName());
                    out.writeInt(room.targetScore());
                    out.writeInt(room.playerCount());
                }
            }
            case RoomLeft m -> out.writeUTF(Protocol.ROOM_LEFT);
            case JoinFailed m -> {
                out.writeUTF(Protocol.JOIN_FAILED);
                out.writeUTF(m.reason().name());
            }
            case RoomFailed m -> {
                out.writeUTF(Protocol.ROOM_FAILED);
                out.writeUTF(m.command());
                out.writeUTF(m.reason().name());
            }
            case GameStart m -> {
                out.writeUTF(Protocol.GAME_START);
                out.writeUTF(m.code());
                out.writeInt(m.side());
                out.writeInt(m.targetScore());
            }
            case UserList m -> {
                out.writeUTF(Protocol.USER_LIST);
                out.writeInt(m.names().size());
                for (String name : m.names()) {
                    out.writeUTF(name);
                }
            }
            case Invited m -> {
                out.writeUTF(Protocol.INVITED);
                out.writeUTF(m.inviter());
                out.writeInt(m.secondsLeft());
            }
            case InviteResult m -> {
                out.writeUTF(Protocol.INVITE_RESULT);
                out.writeUTF(m.name());
                out.writeUTF(m.outcome().name());
            }
            case InviteCancelled m -> {
                out.writeUTF(Protocol.INVITE_CANCELLED);
                out.writeUTF(m.inviter());
            }
            case State m -> writeState(out, m);
            case GameOver m -> {
                out.writeUTF(Protocol.GAME_OVER);
                out.writeInt(m.winner());
                out.writeInt(m.score0());
                out.writeInt(m.score1());
                out.writeUTF(m.reason().name());
            }
        }
        out.flush();
    }

    /** STATE 18개 필드. 순서는 형식 문서의 'STATE 필드' 시트와 같다. */
    private static void writeState(DataOutputStream out, State m) throws IOException {
        MatchSnapshot s = m.snapshot();
        out.writeUTF(Protocol.STATE);
        out.writeInt(s.tick());
        out.writeInt(m.ackSeq());
        out.writeUTF(s.phase().name());
        out.writeInt(s.phaseTicksLeft());
        out.writeInt(s.server());
        out.writeInt(s.lastScorer());
        out.writeInt(s.score0());
        out.writeInt(s.score1());
        out.writeDouble(s.puckX());
        out.writeDouble(s.puckY());
        out.writeDouble(s.puckVx());
        out.writeDouble(s.puckVy());
        out.writeDouble(s.mallet0X());
        out.writeDouble(s.mallet0Y());
        out.writeDouble(s.mallet1X());
        out.writeDouble(s.mallet1Y());
        out.writeInt(s.holdSide());
        out.writeInt(s.holdTicksLeft());
    }

    private static State readState(DataInputStream in) throws IOException {
        int tick = in.readInt();
        int ackSeq = in.readInt();
        String phaseName = in.readUTF();
        Phase phase;
        try {
            phase = Phase.valueOf(phaseName);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("모르는 경기 단계: " + phaseName);
        }
        MatchSnapshot snapshot = new MatchSnapshot(tick, phase, in.readInt(), in.readInt(), in.readInt(),
                in.readInt(), in.readInt(),
                in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(),
                in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(),
                in.readInt(), in.readInt());
        return new State(ackSeq, snapshot);
    }

    private static UserList readUsers(DataInputStream in) throws IOException {
        int count = readCount(in);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add(in.readUTF());
        }
        return new UserList(names);
    }

    private static RoomUpdate readRoom(DataInputStream in) throws IOException {
        String code = in.readUTF();
        int score = in.readInt();
        boolean isPublic = in.readBoolean();
        boolean started = in.readBoolean();
        int count = readCount(in);
        if (count > 2) {
            throw new ProtocolException("방 인원이 2명을 초과했습니다.");
        }
        List<RoomMember> members = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            members.add(new RoomMember(in.readUTF(), in.readBoolean(), in.readBoolean()));
        }
        return new RoomUpdate(code, score, isPublic, started, members);
    }

    private static RoomList readRooms(DataInputStream in) throws IOException {
        int count = readCount(in);
        List<RoomInfo> rooms = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rooms.add(new RoomInfo(in.readUTF(), in.readUTF(), in.readInt(), in.readInt()));
        }
        return new RoomList(rooms);
    }

    private static int readCount(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0) {
            throw new ProtocolException("목록 개수가 음수입니다.");
        }
        return count;
    }
}
