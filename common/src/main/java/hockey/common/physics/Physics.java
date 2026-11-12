package hockey.common.physics;

import static hockey.common.physics.PhysicsConstants.*;

/**
 * 서버와 클라이언트가 함께 쓰는 물리. 같은 상태와 같은 입력이면 항상 같은 결과가 나온다
 * (난수·시계·부동소수 외 상태를 쓰지 않는다).
 *
 * 서버는 이 결과로 판정하고, 클라이언트는 패킷 사이를 예측하는 데 쓴다.
 */
public final class Physics {
    private Physics() {}

    // step()이 돌려주는 이벤트 비트
    public static final int EVENT_WALL_HIT = 1;
    public static final int EVENT_MALLET_HIT = 1 << 1;
    /** 플레이어 0 득점(퍽이 위 골대로 들어감). */
    public static final int EVENT_GOAL_P0 = 1 << 2;
    /** 플레이어 1 득점(퍽이 아래 골대로 들어감). */
    public static final int EVENT_GOAL_P1 = 1 << 3;
    public static final int EVENT_GOAL_MASK = EVENT_GOAL_P0 | EVENT_GOAL_P1;

    /**
     * 한 틱(1/60초) 진행한다.
     *
     * @param target0X 플레이어 0의 말렛 목표 위치(마우스). 자기 진영 밖이면 안쪽으로 잘린다.
     * @return 이번 틱에 일어난 이벤트 비트. 골이 나면 퍽은 골라인을 넘은 자리에 멈춘 채로 돌아오므로
     *         호출부가 점수를 올리고 {@link GameState#resetPuck()}을 불러야 한다.
     */
    public static int step(GameState s, double target0X, double target0Y, double target1X, double target1Y) {
        double[] startX = {s.malletX[0], s.malletX[1]};
        double[] startY = {s.malletY[0], s.malletY[1]};
        double[] moveX = new double[2];
        double[] moveY = new double[2];
        planMalletMove(s, 0, target0X, target0Y, moveX, moveY);
        planMalletMove(s, 1, target1X, target1Y, moveX, moveY);

        int events = 0;
        double h = DT / SUBSTEPS;
        for (int k = 1; k <= SUBSTEPS; k++) {
            for (int i = 0; i < 2; i++) {
                s.malletX[i] += moveX[i] / SUBSTEPS;
                s.malletY[i] += moveY[i] / SUBSTEPS;
            }
            s.puckX += s.puckVx * h;
            s.puckY += s.puckVy * h;

            events |= collideMallet(s, 0);
            events |= collideMallet(s, 1);
            events |= collideBounds(s);
            if ((events & EVENT_GOAL_MASK) != 0) {
                s.puckVx = 0;
                s.puckVy = 0;
                break;
            }
            // 벽이 퍽을 되밀어 말렛과 다시 겹쳤으면 말렛이 물러난다.
            unpin(s, 0);
            unpin(s, 1);
        }

        // 말렛 속도는 실제로 움직인 거리 기준(막혀서 못 간 만큼은 빠진다).
        for (int i = 0; i < 2; i++) {
            s.malletVx[i] = (s.malletX[i] - startX[i]) / DT;
            s.malletVy[i] = (s.malletY[i] - startY[i]) / DT;
        }
        if ((events & EVENT_GOAL_MASK) != 0) {
            return events;
        }

        s.puckVx *= PUCK_DAMPING_PER_TICK;
        s.puckVy *= PUCK_DAMPING_PER_TICK;
        clampPuckSpeed(s);
        return events;
    }

    /**
     * 벽에 막힌 퍽을 말렛이 계속 밀면 퍽은 더 갈 곳이 없어 말렛과 겹친다.
     * 이때는 퍽 대신 말렛을 밀어내서, 화면에서 퍽이 말렛 밑으로 파고들지 않게 한다.
     */
    private static void unpin(GameState s, int player) {
        double nx = s.puckX - s.malletX[player];
        double ny = s.puckY - s.malletY[player];
        double minDistance = PUCK_RADIUS + MALLET_RADIUS;
        double distanceSq = nx * nx + ny * ny;
        if (distanceSq >= minDistance * minDistance || distanceSq < 1e-18) {
            return;
        }
        double distance = Math.sqrt(distanceSq);
        s.malletX[player] = s.puckX - nx / distance * minDistance;
        s.malletY[player] = s.puckY - ny / distance * minDistance;
    }

    /** 말렛이 이번 틱에 움직일 벡터를 정한다(진영 제한 + 속도 제한). 말렛 속도도 여기서 갱신한다. */
    private static void planMalletMove(GameState s, int player, double targetX, double targetY,
                                       double[] moveX, double[] moveY) {
        double minY = (player == 0) ? FIELD_HEIGHT / 2 + MALLET_RADIUS : MALLET_RADIUS;
        double maxY = (player == 0) ? FIELD_HEIGHT - MALLET_RADIUS : FIELD_HEIGHT / 2 - MALLET_RADIUS;
        // NaN이 들어와도 상태가 깨지지 않게 현재 위치로 대체
        if (Double.isNaN(targetX) || Double.isNaN(targetY)) {
            targetX = s.malletX[player];
            targetY = s.malletY[player];
        }
        targetX = clamp(targetX, MALLET_RADIUS, FIELD_WIDTH - MALLET_RADIUS);
        targetY = clamp(targetY, minY, maxY);

        double dx = targetX - s.malletX[player];
        double dy = targetY - s.malletY[player];
        double distance = Math.sqrt(dx * dx + dy * dy);
        double maxDistance = MALLET_MAX_SPEED * DT;
        if (distance > maxDistance) {
            double scale = maxDistance / distance;
            dx *= scale;
            dy *= scale;
        }
        moveX[player] = dx;
        moveY[player] = dy;
        s.malletVx[player] = dx / DT;
        s.malletVy[player] = dy / DT;
    }

    /** 원-원 충돌. 말렛은 퍽에 밀리지 않는다(질량 무한대로 취급). */
    private static int collideMallet(GameState s, int player) {
        double nx = s.puckX - s.malletX[player];
        double ny = s.puckY - s.malletY[player];
        double minDistance = PUCK_RADIUS + MALLET_RADIUS;
        double distanceSq = nx * nx + ny * ny;
        if (distanceSq >= minDistance * minDistance) {
            return 0;
        }
        double distance = Math.sqrt(distanceSq);
        if (distance < 1e-9) {
            // 완전히 겹쳤으면 경기장 중앙 쪽으로 밀어낸다.
            nx = 0;
            ny = (player == 0) ? -1 : 1;
        } else {
            nx /= distance;
            ny /= distance;
        }
        s.puckX = s.malletX[player] + nx * minDistance;
        s.puckY = s.malletY[player] + ny * minDistance;

        // 말렛 기준 상대 속도의 법선 성분이 다가오는 방향일 때만 튕긴다.
        double relVx = s.puckVx - s.malletVx[player];
        double relVy = s.puckVy - s.malletVy[player];
        double approach = relVx * nx + relVy * ny;
        if (approach < 0) {
            double impulse = -(1 + MALLET_RESTITUTION) * approach;
            s.puckVx += impulse * nx;
            s.puckVy += impulse * ny;
            clampPuckSpeed(s);
        }
        return EVENT_MALLET_HIT;
    }

    /** 벽, 골대 기둥, 골 판정. */
    private static int collideBounds(GameState s) {
        int events = 0;

        // 좌우 벽
        if (s.puckX < PUCK_RADIUS) {
            s.puckX = PUCK_RADIUS;
            if (s.puckVx < 0) {
                s.puckVx = -s.puckVx * WALL_RESTITUTION;
                events |= EVENT_WALL_HIT;
            }
        } else if (s.puckX > FIELD_WIDTH - PUCK_RADIUS) {
            s.puckX = FIELD_WIDTH - PUCK_RADIUS;
            if (s.puckVx > 0) {
                s.puckVx = -s.puckVx * WALL_RESTITUTION;
                events |= EVENT_WALL_HIT;
            }
        }

        boolean inGoalMouth = s.puckX > GOAL_LEFT && s.puckX < GOAL_RIGHT;
        if (inGoalMouth) {
            // 퍽 중심이 골라인을 넘으면 골
            if (s.puckY < 0) {
                return events | EVENT_GOAL_P0;
            }
            if (s.puckY > FIELD_HEIGHT) {
                return events | EVENT_GOAL_P1;
            }
            // 골대 입구에서는 벽 대신 양쪽 기둥(점)과 충돌
            if (s.puckY < PUCK_RADIUS) {
                events |= collidePost(s, GOAL_LEFT, 0);
                events |= collidePost(s, GOAL_RIGHT, 0);
            } else if (s.puckY > FIELD_HEIGHT - PUCK_RADIUS) {
                events |= collidePost(s, GOAL_LEFT, FIELD_HEIGHT);
                events |= collidePost(s, GOAL_RIGHT, FIELD_HEIGHT);
            }
        } else {
            // 위아래 벽
            if (s.puckY < PUCK_RADIUS) {
                s.puckY = PUCK_RADIUS;
                if (s.puckVy < 0) {
                    s.puckVy = -s.puckVy * WALL_RESTITUTION;
                    events |= EVENT_WALL_HIT;
                }
            } else if (s.puckY > FIELD_HEIGHT - PUCK_RADIUS) {
                s.puckY = FIELD_HEIGHT - PUCK_RADIUS;
                if (s.puckVy > 0) {
                    s.puckVy = -s.puckVy * WALL_RESTITUTION;
                    events |= EVENT_WALL_HIT;
                }
            }
        }
        return events;
    }

    private static int collidePost(GameState s, double postX, double postY) {
        double nx = s.puckX - postX;
        double ny = s.puckY - postY;
        double distanceSq = nx * nx + ny * ny;
        if (distanceSq >= PUCK_RADIUS * PUCK_RADIUS || distanceSq < 1e-18) {
            return 0;
        }
        double distance = Math.sqrt(distanceSq);
        nx /= distance;
        ny /= distance;
        s.puckX = postX + nx * PUCK_RADIUS;
        s.puckY = postY + ny * PUCK_RADIUS;
        double approach = s.puckVx * nx + s.puckVy * ny;
        if (approach < 0) {
            double impulse = -(1 + WALL_RESTITUTION) * approach;
            s.puckVx += impulse * nx;
            s.puckVy += impulse * ny;
            return EVENT_WALL_HIT;
        }
        return 0;
    }

    private static void clampPuckSpeed(GameState s) {
        double speedSq = s.puckVx * s.puckVx + s.puckVy * s.puckVy;
        if (speedSq > PUCK_MAX_SPEED * PUCK_MAX_SPEED) {
            double scale = PUCK_MAX_SPEED / Math.sqrt(speedSq);
            s.puckVx *= scale;
            s.puckVy *= scale;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
