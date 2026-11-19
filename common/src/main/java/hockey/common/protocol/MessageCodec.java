package hockey.common.protocol;

import hockey.common.protocol.Message.*;

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
            default -> new Unknown(command, in.readUTF());
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
            case Unknown m -> {
                out.writeUTF(m.command());
                out.writeUTF(m.value());
            }
        }
        out.flush();
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
