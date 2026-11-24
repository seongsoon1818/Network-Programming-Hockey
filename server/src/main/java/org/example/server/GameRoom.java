package org.example.server;

import hockey.common.protocol.Message.GameOver;
import hockey.common.protocol.Message.GameOverReason;
import hockey.common.protocol.Message.Input;
import hockey.common.protocol.Message.State;
import hockey.common.rules.MatchEngine;
import hockey.common.rules.MatchSnapshot;

import java.util.Random;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import static hockey.common.physics.PhysicsConstants.FIELD_HEIGHT;
import static hockey.common.physics.PhysicsConstants.FIELD_WIDTH;
import static hockey.common.physics.PhysicsConstants.TICK_RATE;

/** 한 경기의 물리와 점수는 이 경기 스레드에서만 변경한다. */
final class GameRoom implements Runnable {
    private static final long TICK_NANOS = 1_000_000_000L / TICK_RATE;
    private final ClientConnection host;
    private final ClientConnection guest;
    private final MatchEngine engine;
    private final Consumer<GameOver> onFinished;
    private final AtomicReferenceArray<Input> inputs = new AtomicReferenceArray<>(2);
    private final Thread thread;
    // 로비 스레드만 이 값을 쓴다. 먼저 들어온 기권 요청을 유지한다.
    private volatile int forfeitingSide = -1;

    GameRoom(Room room, Consumer<GameOver> onFinished) {
        host = room.host.connection;
        guest = room.guest.connection;
        engine = new MatchEngine(room.targetScore, new Random());
        this.onFinished = onFinished;
        thread = new Thread(this, "game-" + room.code);
        thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    void input(int side, Input input) {
        Input previous = inputs.get(side);
        if (input.seq() >= 0 && (previous == null || input.seq() > previous.seq())
                && Double.isFinite(input.x()) && Double.isFinite(input.y())) {
            // 공용 Physics가 각 진영을 제한한다. 먼저 좌표를 경기장 범위에 둔다.
            inputs.set(side, new Input(input.seq(), Math.clamp(input.x(), 0, FIELD_WIDTH),
                    Math.clamp(input.y(), 0, FIELD_HEIGHT)));
        }
    }

    void forfeit(int side) {
        if (forfeitingSide < 0) {
            forfeitingSide = side;
            LockSupport.unpark(thread);
        }
    }

    @Override
    public void run() {
        MatchSnapshot initial = engine.snapshot();
        sendState(initial, null, null);
        long nextTick = System.nanoTime() + TICK_NANOS;
        while (engine.phase() != MatchEngine.Phase.FINISHED) {
            int loser = forfeitingSide;
            if (loser >= 0) {
                engine.forfeit(loser);
                break;
            }
            long remaining = nextTick - System.nanoTime();
            if (remaining > 0) {
                LockSupport.parkNanos(remaining);
                continue;
            }
            Input first = inputs.get(0);
            Input second = inputs.get(1);
            engine.tick(first == null ? initial.mallet0X() : first.x(),
                    first == null ? initial.mallet0Y() : first.y(),
                    second == null ? initial.mallet1X() : second.x(),
                    second == null ? initial.mallet1Y() : second.y());
            sendState(engine.snapshot(), first, second);
            nextTick += TICK_NANOS;
            long now = System.nanoTime();
            if (now - nextTick >= 3 * TICK_NANOS) {
                nextTick = now + TICK_NANOS;
            }
        }
        onFinished.accept(new GameOver(engine.winner(), engine.score(0), engine.score(1),
                engine.forfeited() ? GameOverReason.FORFEIT : GameOverReason.NORMAL));
    }

    private void sendState(MatchSnapshot snapshot, Input first, Input second) {
        host.send(new State(first == null ? -1 : first.seq(), snapshot));
        guest.send(new State(second == null ? -1 : second.seq(), snapshot));
    }
}
