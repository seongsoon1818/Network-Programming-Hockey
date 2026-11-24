package org.example.server;

import hockey.common.protocol.Message.*;
import hockey.common.protocol.Nicknames;
import hockey.common.protocol.Protocol;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

final class Lobby implements Runnable {
    private static final String CODE_CHARACTERS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private final SecureRandom random = new SecureRandom();
    private final BlockingQueue<Runnable> events = new LinkedBlockingQueue<>();
    // 등록, 방 입장, 준비, 종료는 모두 이 로비 스레드에서 순서대로 처리한다.
    private final Map<ClientConnection, PlayerSession> players = new HashMap<>();
    private final Map<String, PlayerSession> playersByName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, Room> rooms = new LinkedHashMap<>();

    void post(ClientConnection client, Request request) {
        events.add(() -> handle(client, request));
    }

    void postDisconnect(ClientConnection client) {
        events.add(() -> unregister(client));
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                events.take().run();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handle(ClientConnection client, Request request) {
        if (request instanceof Hello hello) {
            register(client, hello.nickname());
            return;
        }
        // INPUT은 GAME_OVER를 받기 직전까지 올 수 있다.
        PlayerSession player = players.get(client);
        if (request instanceof Input input) {
            if (player != null && player.room != null && player.room.started()) {
                player.room.game.input(player.room.host == player ? 0 : 1, input);
            }
            return;
        }
        if (player == null) {
            fail(client, commandOf(request), RoomFailReason.NOT_REGISTERED);
            return;
        }
        switch (request) {
            case CreateRoom m -> createRoom(player, m);
            case JoinRoom m -> joinRoom(player, m.code());
            case ListRooms m -> {
                player.watchingRooms = true;
                client.send(roomList());
            }
            case Ready m -> setReady(player, m.ready());
            case Settings m -> changeSettings(player, m);
            case Start m -> startGame(player);
            case LeaveGame m -> {
                Room room = player.room;
                if (room != null && room.started()) {
                    room.game.forfeit(room.host == player ? 0 : 1);
                }
            }
            case LeaveRoom m -> {
                if (requireWaitingRoom(player, Protocol.LEAVE_ROOM) != null) {
                    leaveRoom(player);
                    client.send(new RoomLeft());
                    publishRooms();
                }
            }
            case Kick m -> {
                if (requireWaitingRoom(player, Protocol.KICK) != null) {
                    fail(client, Protocol.KICK, RoomFailReason.UNKNOWN);
                }
            }
            case Invite m -> fail(client, Protocol.INVITE, RoomFailReason.UNKNOWN);
            case InviteReply m -> fail(client, Protocol.INVITE_REPLY, RoomFailReason.NO_SUCH_INVITE);
            default -> throw new IllegalArgumentException("로비에서 처리할 수 없는 요청입니다.");
        }
    }

    private static String commandOf(Request request) {
        return switch (request) {
            case Hello m -> Protocol.HELLO;
            case Quit m -> Protocol.QUIT;
            case CreateRoom m -> Protocol.CREATE_ROOM;
            case JoinRoom m -> Protocol.JOIN_ROOM;
            case ListRooms m -> Protocol.LIST_ROOMS;
            case Ready m -> Protocol.READY;
            case Settings m -> Protocol.SETTINGS;
            case Start m -> Protocol.START;
            case LeaveRoom m -> Protocol.LEAVE_ROOM;
            case Kick m -> Protocol.KICK;
            case Invite m -> Protocol.INVITE;
            case InviteReply m -> Protocol.INVITE_REPLY;
            case Input m -> Protocol.INPUT;
            case LeaveGame m -> Protocol.LEAVE_GAME;
        };
    }

    private void register(ClientConnection client, String rawName) {
        if (players.containsKey(client)) {
            client.send(new HelloFailed(HelloFailReason.ALREADY_REGISTERED));
            return;
        }
        String nickname = rawName.strip();
        if (!Nicknames.isValid(nickname)) {
            client.send(new HelloFailed(HelloFailReason.INVALID_NICKNAME));
            return;
        }
        if (playersByName.containsKey(nickname)) {
            client.send(new HelloFailed(HelloFailReason.DUPLICATE_NICKNAME));
            return;
        }
        PlayerSession player = new PlayerSession(client, nickname);
        players.put(client, player);
        playersByName.put(nickname, player);
        client.send(new Welcome(player.token.toString()));
        logConnection(nickname, "님이 접속했습니다.");
    }

    private void createRoom(PlayerSession player, CreateRoom request) {
        if (player.room != null) {
            fail(player.connection, Protocol.CREATE_ROOM, RoomFailReason.ALREADY_IN_ROOM);
            return;
        }
        if (!Room.validScore(request.targetScore())) {
            fail(player.connection, Protocol.CREATE_ROOM, RoomFailReason.INVALID_SETTINGS);
            return;
        }
        Room room = new Room(newCode(), player, request.targetScore(), request.isPublic());
        rooms.put(room.code, room);
        player.room = room;
        room.broadcast();
        publishRooms();
    }

    private String newCode() {
        String code;
        do {
            StringBuilder builder = new StringBuilder(5);
            for (int i = 0; i < 5; i++) {
                builder.append(CODE_CHARACTERS.charAt(random.nextInt(CODE_CHARACTERS.length())));
            }
            code = builder.toString();
        } while (rooms.containsKey(code));
        return code;
    }

    private void joinRoom(PlayerSession player, String code) {
        if (player.room != null) {
            fail(player.connection, Protocol.JOIN_ROOM, RoomFailReason.ALREADY_IN_ROOM);
            return;
        }
        Room room = rooms.get(code.strip().toUpperCase(Locale.ROOT));
        if (room == null) {
            fail(player.connection, Protocol.JOIN_ROOM, RoomFailReason.NOT_FOUND);
            return;
        }
        if (room.started()) {
            fail(player.connection, Protocol.JOIN_ROOM, RoomFailReason.STARTED);
            return;
        }
        if (room.guest != null) {
            fail(player.connection, Protocol.JOIN_ROOM, RoomFailReason.FULL);
            return;
        }
        // 마지막 자리 검사와 배정이 한 이벤트 안에서 끝난다.
        room.guest = player;
        room.guestReady = false;
        player.room = room;
        room.broadcast();
        publishRooms();
    }

    private Room requireRoom(PlayerSession player, String command) {
        if (player.room == null) {
            fail(player.connection, command, RoomFailReason.NOT_IN_ROOM);
        }
        return player.room;
    }

    private Room requireWaitingRoom(PlayerSession player, String command) {
        Room room = requireRoom(player, command);
        if (room != null && room.started()) {
            fail(player.connection, command, RoomFailReason.STARTED);
            return null;
        }
        return room;
    }

    private void setReady(PlayerSession player, boolean ready) {
        Room room = requireWaitingRoom(player, Protocol.READY);
        if (room == null) {
            return;
        }
        if (room.host == player) {
            fail(player.connection, Protocol.READY, RoomFailReason.GUEST_ONLY);
            return;
        }
        room.guestReady = ready;
        room.broadcast();
    }

    private void changeSettings(PlayerSession player, Settings request) {
        Room room = requireWaitingRoom(player, Protocol.SETTINGS);
        if (room == null) {
            return;
        }
        if (room.host != player) {
            fail(player.connection, Protocol.SETTINGS, RoomFailReason.HOST_ONLY);
            return;
        }
        if (!Room.validScore(request.targetScore())) {
            fail(player.connection, Protocol.SETTINGS, RoomFailReason.INVALID_SETTINGS);
            return;
        }
        if (room.targetScore != request.targetScore() || room.isPublic != request.isPublic()) {
            room.targetScore = request.targetScore();
            room.isPublic = request.isPublic();
            room.guestReady = false;
        }
        room.broadcast();
        publishRooms();
    }

    private void startGame(PlayerSession player) {
        Room room = requireWaitingRoom(player, Protocol.START);
        if (room == null) {
            return;
        }
        if (room.host != player) {
            fail(player.connection, Protocol.START, RoomFailReason.HOST_ONLY);
            return;
        }
        if (room.guest == null || !room.guestReady) {
            fail(player.connection, Protocol.START, RoomFailReason.NOT_READY);
            return;
        }
        room.game = new GameRoom(room, result -> events.add(() -> finishGame(room, result)));
        room.broadcast();
        room.host.connection.send(new GameStart(room.code, 0, room.targetScore));
        room.guest.connection.send(new GameStart(room.code, 1, room.targetScore));
        publishRooms();
        room.game.start();
    }

    private void finishGame(Room room, GameOver result) {
        PlayerSession host = room.host;
        PlayerSession guest = room.guest;
        boolean hostConnected = players.containsKey(host.connection);
        boolean guestConnected = players.containsKey(guest.connection);
        if (hostConnected) {
            host.connection.send(result);
        }
        if (guestConnected) {
            guest.connection.send(result);
        }
        room.game = null;
        room.guestReady = false;
        if (!hostConnected) {
            leaveRoom(host);
        }
        if (!guestConnected) {
            leaveRoom(guest);
        }
        if (hostConnected && guestConnected) {
            room.broadcast();
        }
        publishRooms();
    }

    private void leaveRoom(PlayerSession player) {
        Room room = player.room;
        if (room == null) {
            return;
        }
        player.room = null;
        if (room.host == player) {
            room.host = room.guest;
        }
        room.guest = null;
        room.guestReady = false;
        if (room.host == null) {
            rooms.remove(room.code);
        } else {
            room.broadcast();
        }
    }

    private RoomList roomList() {
        return new RoomList(rooms.values().stream()
                .filter(room -> room.isPublic && !room.started())
                .map(Room::summary).toList());
    }

    private void publishRooms() {
        RoomList list = roomList();
        for (PlayerSession player : players.values()) {
            if (player.watchingRooms && player.room == null) {
                player.connection.send(list);
            }
        }
    }

    private static void fail(ClientConnection client, String command, RoomFailReason reason) {
        client.send(new RoomFailed(command, reason));
    }

    private void unregister(ClientConnection client) {
        PlayerSession player = players.remove(client);
        if (player != null) {
            Room room = player.room;
            if (room != null && room.started()) {
                room.game.forfeit(room.host == player ? 0 : 1);
            } else {
                leaveRoom(player);
            }
            playersByName.remove(player.nickname);
            logConnection(player.nickname, "님이 접속을 종료했습니다.");
            publishRooms();
        }
    }

    private void logConnection(String nickname, String message) {
        String displayName = nickname.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ");
        System.out.println(displayName + message);
    }
}
