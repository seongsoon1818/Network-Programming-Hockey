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

    /** 퍽 가장자리가 벽에서 이 거리 안이면 벽에 붙어 있다고 본다. */
    private static final double PIN_DISTANCE = 1.0;

    /**
     * 한 틱(1/60초) 진행한다.
     *
     * @param target0X 플레이어 0의 말렛 목표 위치(마우스). 자기 진영 밖이면 안쪽으로 잘린다.
     * @return 이번 틱에 일어난 이벤트 비트. 골이 나면 퍽은 골라인을 넘은 자리에 멈춘 채로 돌아오므로
     *         호출부가 점수를 올리고 {@link GameState#placePuckForServe(int)}로 퍽을 다시 놓아야 한다.
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
        double[] fromX = new double[2];
        double[] fromY = new double[2];
        for (int k = 1; k <= SUBSTEPS; k++) {
            // 서브스텝 시작 시점의 퍽 기준 말렛 위치(충돌 순간을 되짚는 데 쓴다)
            for (int i = 0; i < 2; i++) {
                fromX[i] = s.puckX - s.malletX[i];
                fromY[i] = s.puckY - s.malletY[i];
                s.malletX[i] += moveX[i] / SUBSTEPS;
                s.malletY[i] += moveY[i] / SUBSTEPS;
            }
            s.puckX += s.puckVx * h;
            s.puckY += s.puckVy * h;

            events |= collideMallet(s, 0, fromX[0], fromY[0]);
            events |= collideMallet(s, 1, fromX[1], fromY[1]);
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
        // 둥근 구석 밖이면 곡면 안쪽으로. 출발점과 목표가 모두 볼록한 경기장 안이라 직선 경로도 안에 있다.
        double cornerX = cornerCenter(targetX, FIELD_WIDTH);
        double cornerY = cornerCenter(targetY, FIELD_HEIGHT);
        if (CORNER_RADIUS > MALLET_RADIUS && !Double.isNaN(cornerX) && !Double.isNaN(cornerY)) {
            double limit = CORNER_RADIUS - MALLET_RADIUS;
            double ox = targetX - cornerX;
            double oy = targetY - cornerY;
            double d = Math.sqrt(ox * ox + oy * oy);
            if (d > limit) {
                targetX = cornerX + ox / d * limit;
                targetY = cornerY + oy / d * limit;
            }
        }

        double dx = targetX - s.malletX[player];
        double dy = targetY - s.malletY[player];
        double distance = Math.sqrt(dx * dx + dy * dy);
        double maxDistance = MALLET_MAX_SPEED * DT;
        if (distance > maxDistance) {
            double scale = maxDistance / distance;
            dx *= scale;
            dy *= scale;
            distance = maxDistance;
        }
        // 이번 틱에 퍽을 칠 것 같으면 퍽 최고 속도보다 빨리 들어가지 않는다. 말렛이 퍽보다 빠르면
        // 친 퍽을 곧바로 다시 따라잡아 옆으로 밀어내서, 비껴 친 각도보다 훨씬 옆으로 나갔다
        // (6000px/s로 10px 비껴 치면 13도가 아니라 최대 57도). 퍽에서 먼 곳에서는 빨리 움직인다.
        double strikeDistance = STRIKE_MAX_SPEED * DT;
        if (distance > strikeDistance && willTouchPuck(s, player, dx, dy)) {
            double scale = strikeDistance / distance;
            dx *= scale;
            dy *= scale;
        }
        moveX[player] = dx;
        moveY[player] = dy;
        s.malletVx[player] = dx / DT;
        s.malletVy[player] = dy / DT;
    }

    /** 말렛이 (dx, dy)만큼 움직이는 동안 퍽에 닿는가. 퍽도 지금 속도로 움직인다고 본다. */
    private static boolean willTouchPuck(GameState s, int player, double dx, double dy) {
        double rx = s.malletX[player] - s.puckX;
        double ry = s.malletY[player] - s.puckY;
        double mx = dx - s.puckVx * DT;
        double my = dy - s.puckVy * DT;
        double lengthSq = mx * mx + my * my;
        double t = lengthSq < 1e-12 ? 0 : clamp(-(rx * mx + ry * my) / lengthSq, 0, 1);
        double cx = rx + mx * t;
        double cy = ry + my * t;
        double reach = PUCK_RADIUS + MALLET_RADIUS + 1;
        return cx * cx + cy * cy < reach * reach;
    }

    /**
     * 원-원 충돌. 말렛은 퍽에 밀리지 않는다(질량 무한대로 취급).
     *
     * 튕기는 방향은 겹친 것을 발견한 지금이 아니라 실제로 맞닿은 순간의 중심선으로 정한다.
     * 지금 위치로 정하면 서브스텝 동안 파고든 만큼 중심선이 옆으로 기울어, 비껴 친 공이
     * 겨눈 것보다 옆으로 나간다. (fromX, fromY)는 서브스텝 시작 때 말렛에서 본 퍽의 위치.
     */
    private static int collideMallet(GameState s, int player, double fromX, double fromY) {
        double nx = s.puckX - s.malletX[player];
        double ny = s.puckY - s.malletY[player];
        double minDistance = PUCK_RADIUS + MALLET_RADIUS;
        double distanceSq = nx * nx + ny * ny;
        if (distanceSq >= minDistance * minDistance) {
            return 0;
        }
        double distance = Math.sqrt(distanceSq);
        double fromSq = fromX * fromX + fromY * fromY;
        if (fromSq >= minDistance * minDistance) {
            // 서브스텝 시작 때는 떨어져 있었다: 상대 위치가 from → (nx, ny)로 곧게 움직였다고 보고
            // 거리가 처음 minDistance가 되는 순간 t를 구한다(|from + t·d|² = minDistance²의 작은 근).
            double ddx = nx - fromX;
            double ddy = ny - fromY;
            double a = ddx * ddx + ddy * ddy;
            double b = 2 * (fromX * ddx + fromY * ddy);
            double c = fromSq - minDistance * minDistance;
            double disc = b * b - 4 * a * c;
            double t = a < 1e-12 || disc < 0 ? 1 : clamp((-b - Math.sqrt(disc)) / (2 * a), 0, 1);
            nx = fromX + ddx * t;
            ny = fromY + ddy * t;
            distance = Math.sqrt(nx * nx + ny * ny);
        }
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
            // 벽(또는 구석)에 붙은 퍽을 그 벽 쪽으로 치면 튕김 없이 말렛 속도만 받는다.
            // 튕김을 주면 "말렛이 침 → 벽에 튕겨 돌아옴 → 말렛이 또 침"이 서브스텝마다 반복되어
            // 벽에 낀 퍽이 말렛 속도의 몇 배로 빨라진다(200px/s로 밀었는데 1,784px/s).
            // 말렛 속도는 그대로 전달하므로 구석에 낀 퍽도 톡 치면 벽에 튕겨 빠져나온다.
            double restitution = pressedIntoWall(s, nx, ny) ? 0 : MALLET_RESTITUTION;
            double impulse = -(1 + restitution) * approach;
            s.puckVx += impulse * nx;
            s.puckVy += impulse * ny;
            clampPuckSpeed(s);
        }
        return EVENT_MALLET_HIT;
    }

    /** 퍽이 벽에 붙어 있고, 말렛이 미는 방향(nx, ny)이 그 벽 쪽인가. 골대 입구에는 벽이 없다. */
    private static boolean pressedIntoWall(GameState s, double nx, double ny) {
        if ((nx < 0 && s.puckX <= PUCK_RADIUS + PIN_DISTANCE)
                || (nx > 0 && s.puckX >= FIELD_WIDTH - PUCK_RADIUS - PIN_DISTANCE)) {
            return true;
        }
        boolean inGoalMouth = s.puckX > GOAL_LEFT && s.puckX < GOAL_RIGHT;
        return !inGoalMouth && ((ny < 0 && s.puckY <= PUCK_RADIUS + PIN_DISTANCE)
                || (ny > 0 && s.puckY >= FIELD_HEIGHT - PUCK_RADIUS - PIN_DISTANCE));
    }

    /** 벽, 골대 기둥, 골 판정. */
    private static int collideBounds(GameState s) {
        int events = 0;

        // 좌우 벽
        if (s.puckX < PUCK_RADIUS) {
            s.puckX = PUCK_RADIUS;
            if (s.puckVx < 0) {
                bounceOffWall(s, -1, 0);
                events |= EVENT_WALL_HIT;
            }
        } else if (s.puckX > FIELD_WIDTH - PUCK_RADIUS) {
            s.puckX = FIELD_WIDTH - PUCK_RADIUS;
            if (s.puckVx > 0) {
                bounceOffWall(s, 1, 0);
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
            // 위아래 벽(골대 입구가 아닌 곳). 모서리는 아래에서 곡면으로 다시 본다.
            if (s.puckY < PUCK_RADIUS) {
                s.puckY = PUCK_RADIUS;
                if (s.puckVy < 0) {
                    bounceOffWall(s, 0, -1);
                    events |= EVENT_WALL_HIT;
                }
            } else if (s.puckY > FIELD_HEIGHT - PUCK_RADIUS) {
                s.puckY = FIELD_HEIGHT - PUCK_RADIUS;
                if (s.puckVy > 0) {
                    bounceOffWall(s, 0, 1);
                    events |= EVENT_WALL_HIT;
                }
            }
        }
        events |= collideCorner(s);
        return events;
    }

    /** 둥근 모서리. 퍽 중심이 모서리 사분면에 있으면 곡면 안쪽으로 밀고 반사한다. */
    private static int collideCorner(GameState s) {
        double cornerX = cornerCenter(s.puckX, FIELD_WIDTH);
        double cornerY = cornerCenter(s.puckY, FIELD_HEIGHT);
        if (Double.isNaN(cornerX) || Double.isNaN(cornerY)) {
            return 0;
        }
        double limit = CORNER_RADIUS - PUCK_RADIUS;
        double nx = s.puckX - cornerX;
        double ny = s.puckY - cornerY;
        double distanceSq = nx * nx + ny * ny;
        if (distanceSq <= limit * limit) {
            return 0;
        }
        double distance = Math.sqrt(distanceSq);
        nx /= distance; // 곡면 바깥쪽(모서리 쪽) 방향
        ny /= distance;
        s.puckX = cornerX + nx * limit;
        s.puckY = cornerY + ny * limit;
        double outward = s.puckVx * nx + s.puckVy * ny;
        if (outward > 0) {
            bounceOffWall(s, nx, ny);
            return EVENT_WALL_HIT;
        }
        return 0;
    }

    /**
     * 벽에 튕긴다. (nx, ny)는 벽 쪽을 가리키는 단위 벡터이고, 퍽은 그쪽으로 움직이는 중이어야 한다.
     *
     * 들어온 각도 그대로 거울처럼 반사한 뒤 속도 전체를 WALL_RESTITUTION만큼 줄인다. 예전에는
     * 벽에 수직인 성분만 줄여서, 벽에 맞을 때마다 퍽이 벽과 점점 나란해졌고 결국 벽을 타고 다녔다.
     */
    private static void bounceOffWall(GameState s, double nx, double ny) {
        double into = s.puckVx * nx + s.puckVy * ny;
        s.puckVx = (s.puckVx - 2 * into * nx) * WALL_RESTITUTION;
        s.puckVy = (s.puckVy - 2 * into * ny) * WALL_RESTITUTION;
    }

    /** 좌표 v가 모서리 곡면 구간이면 그 곡면 중심 좌표, 아니면 NaN. size는 그 축의 경기장 길이. */
    private static double cornerCenter(double v, double size) {
        if (v < CORNER_RADIUS) {
            return CORNER_RADIUS;
        }
        if (v > size - CORNER_RADIUS) {
            return size - CORNER_RADIUS;
        }
        return Double.NaN;
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
            bounceOffWall(s, -nx, -ny);
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
