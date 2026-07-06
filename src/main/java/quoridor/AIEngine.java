package quoridor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

// クライアントのQuoridorAI.csを移植したサーバー側AI。
// 人間とAIが混在するルームや、切断時のAI交代で使用する。
// Boardを直接いじらず、軽量なStateのコピー上で探索する。
public class AIEngine {

    // 探索用の軽量盤面。配列インデックスは playerId - 1
    public static class State {
        int[] px, py;
        List<int[]> walls = new ArrayList<>();      // {x, y, h(1=水平)}
        List<int[]> miniWalls = new ArrayList<>();  // {x, y, h}
        List<int[]> traps = new ArrayList<>();      // {x, y, owner}
        int[] wallsRemaining;
        boolean characterMode;
        CharacterType[] characters;
        int[] skillRemaining, miniWallsRemaining, trapRemaining;
        boolean[] cannotMove;

        int playerCount() { return px.length; }

        State copy() {
            State s = new State();
            s.px = px.clone();
            s.py = py.clone();
            for (int[] w : walls) s.walls.add(w);
            for (int[] w : miniWalls) s.miniWalls.add(w);
            for (int[] t : traps) s.traps.add(t.clone());
            s.wallsRemaining = wallsRemaining.clone();
            s.characterMode = characterMode;
            s.characters = characters.clone();
            s.skillRemaining = skillRemaining.clone();
            s.miniWallsRemaining = miniWallsRemaining.clone();
            s.trapRemaining = trapRemaining.clone();
            s.cannotMove = cannotMove.clone();
            return s;
        }
    }

    public static State fromBoard(Board board, boolean characterMode) {
        int n = board.getPlayerCount();
        State s = new State();
        s.px = new int[n];
        s.py = new int[n];
        s.wallsRemaining = new int[n];
        s.characters = new CharacterType[n];
        s.skillRemaining = new int[n];
        s.miniWallsRemaining = new int[n];
        s.trapRemaining = new int[n];
        s.cannotMove = new boolean[n];
        s.characterMode = characterMode;
        for (int i = 0; i < n; i++) {
            Player p = board.getPlayer(i + 1);
            s.px[i] = p.getX();
            s.py[i] = p.getY();
            s.wallsRemaining[i] = p.getWallsRemaining();
            s.characters[i] = p.getCharacterType();
            s.skillRemaining[i] = p.getSkillRemaining();
            s.miniWallsRemaining[i] = p.getMiniWallsRemaining();
            s.trapRemaining[i] = p.getTrapRemaining();
            s.cannotMove[i] = p.cannotMoveNextTurn();
        }
        for (Wall w : board.getWalls())
            s.walls.add(new int[]{w.getX(), w.getY(), w.getDirection() == Wall.Direction.HORIZONTAL ? 1 : 0});
        for (MiniWall w : board.getMiniWalls())
            s.miniWalls.add(new int[]{w.getX(), w.getY(), w.getDirection() == MiniWall.Direction.HORIZONTAL ? 1 : 0});
        for (Trap t : board.getTraps())
            if (t.isActive())
                s.traps.add(new int[]{t.getX(), t.getY(), t.getOwnerPlayerId()});
        return s;
    }

    public static class Move {
        public static final int CELL = 0, WALL = 1, SKILL_MOVE = 2, BREAK_WALL = 3, MINI_WALLS = 4, TRAP = 5;
        public int kind;
        public String skill;          // RUNNER_MOVE / ACROBAT_MOVE
        public int x, y;
        public boolean horizontal;
        public List<int[]> miniWalls; // {x, y, h}

        static Move cell(int x, int y) { Move m = new Move(); m.kind = CELL; m.x = x; m.y = y; return m; }
        static Move wall(int x, int y, boolean h) { Move m = new Move(); m.kind = WALL; m.x = x; m.y = y; m.horizontal = h; return m; }
        static Move skillMove(String skill, int x, int y) { Move m = new Move(); m.kind = SKILL_MOVE; m.skill = skill; m.x = x; m.y = y; return m; }
        static Move breakWall(int x, int y, boolean h) { Move m = new Move(); m.kind = BREAK_WALL; m.x = x; m.y = y; m.horizontal = h; return m; }
        static Move miniWalls(List<int[]> walls) { Move m = new Move(); m.kind = MINI_WALLS; m.miniWalls = walls; return m; }
        static Move trap(int x, int y) { Move m = new Move(); m.kind = TRAP; m.x = x; m.y = y; return m; }
    }

    // difficulty: 1=弱(greedy), 2=中(1手読み+壁), 3=強(2手読み+壁)
    public static Move getBestMove(State state, int aiPlayer, int difficulty) {
        if (difficulty <= 1) return pickBest(state, aiPlayer, candidates(state, aiPlayer, 4));

        int maxWalls = difficulty == 2 ? 6 : 12;

        // 3人以上では2人用minimaxが使えないため、壁候補込みの1手読みで選ぶ
        if (state.playerCount() > 2)
            return pickBest(state, aiPlayer, candidates(state, aiPlayer, maxWalls));

        int depth = difficulty == 2 ? 1 : 2;
        List<Move> moves = candidates(state, aiPlayer, maxWalls);
        if (moves.isEmpty())
            return Move.cell(state.px[aiPlayer - 1], state.py[aiPlayer - 1]);

        Move best = moves.get(0);
        int bestScore = Integer.MIN_VALUE;
        for (Move m : moves) {
            State applied = apply(state.copy(), aiPlayer, m);
            int cur = m.kind == Move.TRAP ? aiPlayer : nextPlayer(state, aiPlayer);
            int s = minimax(applied, cur, aiPlayer, depth - 1, Integer.MIN_VALUE + 1, Integer.MAX_VALUE);
            if (s > bestScore) { bestScore = s; best = m; }
        }
        return best;
    }

    private static int nextPlayer(State s, int p) { return p % s.playerCount() + 1; }

    private static int minimax(State s, int cur, int ai, int depth, int alpha, int beta) {
        if (depth <= 0) return eval(s, ai);
        List<Move> moves = candidates(s, cur, 4);
        if (moves.isEmpty()) return eval(s, ai);

        if (cur == ai) {
            int best = Integer.MIN_VALUE;
            for (Move m : moves) {
                State applied = apply(s.copy(), cur, m);
                int next = m.kind == Move.TRAP ? cur : nextPlayer(s, cur);
                int v = minimax(applied, next, ai, depth - 1, alpha, beta);
                if (v > best) best = v;
                if (best > alpha) alpha = best;
                if (beta <= alpha) break;
            }
            return best;
        } else {
            int best = Integer.MAX_VALUE;
            for (Move m : moves) {
                State applied = apply(s.copy(), cur, m);
                int next = m.kind == Move.TRAP ? cur : nextPlayer(s, cur);
                int v = minimax(applied, next, ai, depth - 1, alpha, beta);
                if (v < best) best = v;
                if (best < beta) beta = best;
                if (beta <= alpha) break;
            }
            return best;
        }
    }

    private static Move pickBest(State s, int player, List<Move> moves) {
        if (moves.isEmpty()) return Move.cell(s.px[player - 1], s.py[player - 1]);
        Move best = moves.get(0);
        int bestScore = Integer.MIN_VALUE;
        for (Move m : moves) {
            int sc = eval(apply(s.copy(), player, m), player);
            if (sc > bestScore) { bestScore = sc; best = m; }
        }
        return best;
    }

    // 評価値: 最も近い相手のゴール距離 - 自分のゴール距離
    private static int eval(State s, int ai) {
        int myD = bfs(s, ai);
        if (myD == 0) return 10000;
        int minOpp = minOpponentDist(s, ai);
        if (minOpp == 0) return -10000;
        return minOpp - myD + trapScore(s, ai);
    }

    private static int trapScore(State s, int ai) {
        int score = 0;
        for (int[] trap : s.traps)
            score += trap[2] == ai ? 2 : -2;
        return score;
    }

    private static int minOpponentDist(State s, int player) {
        int min = Integer.MAX_VALUE;
        for (int p = 1; p <= s.playerCount(); p++)
            if (p != player) {
                int d = bfs(s, p);
                if (d < min) min = d;
            }
        return min;
    }

    private static List<Move> candidates(State s, int player, int maxWalls) {
        List<Move> result = new ArrayList<>(cellMoves(s, player));
        if (s.characterMode)
            result.addAll(characterCandidates(s, player, maxWalls));

        if (s.wallsRemaining[player - 1] <= 0 || maxWalls <= 0) return result;

        int oppBefore = minOpponentDist(s, player);
        List<int[]> wc = new ArrayList<>(); // {x, y, h, gain}

        for (int wx = 0; wx <= 7; wx++)
            for (int wy = 0; wy <= 7; wy++)
                for (int h = 1; h >= 0; h--) {
                    if (!isValidWall(s, player, wx, wy, h == 1)) continue;
                    State tmp = s.copy();
                    tmp.walls.add(new int[]{wx, wy, h});
                    int gain = minOpponentDist(tmp, player) - oppBefore;
                    if (gain > 0) wc.add(new int[]{wx, wy, h, gain});
                }
        wc.sort((a, b) -> b[3] - a[3]);
        for (int i = 0; i < Math.min(maxWalls, wc.size()); i++) {
            int[] w = wc.get(i);
            result.add(Move.wall(w[0], w[1], w[2] == 1));
        }
        return result;
    }

    private static List<Move> characterCandidates(State s, int player, int maxCount) {
        List<Move> result = new ArrayList<>();
        CharacterType character = s.characters[player - 1];
        int idx = player - 1;
        boolean cannotMove = s.cannotMove[idx];

        if (character == CharacterType.RUNNER && s.skillRemaining[idx] > 0 && !cannotMove)
            for (int[] m : runnerMoves(s, player)) result.add(Move.skillMove("RUNNER_MOVE", m[0], m[1]));

        if (character == CharacterType.ACROBAT && s.skillRemaining[idx] > 0 && !cannotMove)
            for (int[] m : acrobatMoves(s, player)) result.add(Move.skillMove("ACROBAT_MOVE", m[0], m[1]));

        if (character == CharacterType.BREAKER && s.skillRemaining[idx] > 0)
            result.addAll(breakWallCandidates(s, player, maxCount));

        if (character == CharacterType.BUILDER && s.skillRemaining[idx] > 0 && s.miniWallsRemaining[idx] > 0)
            result.addAll(miniWallCandidates(s, player, maxCount));

        if (character == CharacterType.TRAPPER && s.trapRemaining[idx] > 0)
            result.addAll(trapCandidates(s, player));

        return result;
    }

    private static List<Move> breakWallCandidates(State s, int player, int maxCount) {
        List<Move> result = new ArrayList<>();
        int before = bfs(s, player);
        List<int[]> scored = new ArrayList<>(); // {x, y, h, gain}
        for (int[] w : s.walls) {
            State tmp = s.copy();
            tmp.walls.removeIf(x -> x[0] == w[0] && x[1] == w[1] && x[2] == w[2]);
            int gain = before - bfs(tmp, player);
            scored.add(new int[]{w[0], w[1], w[2], gain});
        }
        scored.sort((a, b) -> b[3] - a[3]);
        for (int i = 0; i < Math.min(maxCount, scored.size()); i++) {
            int[] w = scored.get(i);
            result.add(Move.breakWall(w[0], w[1], w[2] == 1));
        }
        return result;
    }

    private static List<Move> miniWallCandidates(State s, int player, int maxCount) {
        List<Move> result = new ArrayList<>();
        int oppBefore = minOpponentDist(s, player);
        List<int[]> scored = new ArrayList<>(); // {x, y, h, gain}
        for (int x = 0; x <= 8; x++)
            for (int y = 0; y <= 8; y++)
                for (int h = 1; h >= 0; h--) {
                    int[] wall = {x, y, h};
                    List<int[]> walls = new ArrayList<>();
                    walls.add(wall);
                    if (!isValidMiniWallPlacements(s, player, walls)) continue;
                    State tmp = s.copy();
                    tmp.miniWalls.add(wall);
                    int gain = minOpponentDist(tmp, player) - oppBefore;
                    if (gain > 0) scored.add(new int[]{x, y, h, gain});
                }
        scored.sort((a, b) -> b[3] - a[3]);
        for (int i = 0; i < Math.min(maxCount, scored.size()); i++) {
            int[] w = scored.get(i);
            List<int[]> walls = new ArrayList<>();
            walls.add(new int[]{w[0], w[1], w[2]});
            result.add(Move.miniWalls(walls));
        }
        return result;
    }

    private static List<Move> trapCandidates(State s, int player) {
        List<Move> result = new ArrayList<>();
        int opponent = closestOpponent(s, player);
        for (int[] m : cellMoves2(s, opponent)) {
            if (isValidTrapPlacement(s, player, m[0], m[1]))
                result.add(Move.trap(m[0], m[1]));
            if (result.size() >= 4) break;
        }
        return result;
    }

    private static int closestOpponent(State s, int player) {
        int bestPlayer = player == 1 ? 2 : 1;
        int bestDist = Integer.MAX_VALUE;
        for (int p = 1; p <= s.playerCount(); p++) {
            if (p == player) continue;
            int d = bfs(s, p);
            if (d < bestDist) { bestDist = d; bestPlayer = p; }
        }
        return bestPlayer;
    }

    private static boolean anyPlayerTrapped(State s) {
        for (int p = 1; p <= s.playerCount(); p++)
            if (bfs(s, p) >= 999) return true;
        return false;
    }

    private static final int[][] DIRS = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};

    private static List<Move> cellMoves(State s, int player) {
        List<Move> result = new ArrayList<>();
        for (int[] m : cellMoves2(s, player)) result.add(Move.cell(m[0], m[1]));
        return result;
    }

    private static List<int[]> cellMoves2(State s, int player) {
        List<int[]> result = new ArrayList<>();
        if (s.cannotMove[player - 1]) return result;

        int mx = s.px[player - 1], my = s.py[player - 1];
        for (int[] d : DIRS) {
            int nx = mx + d[0], ny = my + d[1];
            if (!in(nx, ny) || isBlocked(s, mx, my, nx, ny)) continue;

            if (pawnAt(s, nx, ny) != 0) {
                // 隣がポーンなら跳び越え。着地点が塞がっていれば斜めへ
                int jx = nx + d[0], jy = ny + d[1];
                if (in(jx, jy) && !isBlocked(s, nx, ny, jx, jy) && pawnAt(s, jx, jy) == 0)
                    result.add(new int[]{jx, jy});
                else {
                    int[][] perps = d[0] == 0 ? new int[][]{{1, 0}, {-1, 0}} : new int[][]{{0, 1}, {0, -1}};
                    for (int[] p : perps) {
                        int dx = nx + p[0], dy = ny + p[1];
                        if (in(dx, dy) && !isBlocked(s, nx, ny, dx, dy) && pawnAt(s, dx, dy) == 0)
                            result.add(new int[]{dx, dy});
                    }
                }
            } else result.add(new int[]{nx, ny});
        }
        return result;
    }

    private static List<int[]> runnerMoves(State s, int player) {
        List<int[]> result = new ArrayList<>();
        int px = s.px[player - 1], py = s.py[player - 1];
        for (int[] d : DIRS) {
            int x1 = px + d[0], y1 = py + d[1];
            int x2 = px + d[0] * 2, y2 = py + d[1] * 2;
            if (!in(x2, y2)) continue;
            if (isBlocked(s, px, py, x1, y1) || isBlocked(s, x1, y1, x2, y2)) continue;
            if (pawnAt(s, x1, y1) != 0 || pawnAt(s, x2, y2) != 0) continue;
            result.add(new int[]{x2, y2});
        }
        return result;
    }

    private static List<int[]> acrobatMoves(State s, int player) {
        List<int[]> result = new ArrayList<>();
        int px = s.px[player - 1], py = s.py[player - 1];
        int[][] dirs = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] d : dirs) {
            int tx = px + d[0], ty = py + d[1];
            if (!in(tx, ty) || pawnAt(s, tx, ty) != 0) continue;
            boolean route1 = !isBlocked(s, px, py, px + d[0], py)
                    && !isBlocked(s, px + d[0], py, tx, ty);
            boolean route2 = !isBlocked(s, px, py, px, py + d[1])
                    && !isBlocked(s, px, py + d[1], tx, ty);
            if (route1 || route2) result.add(new int[]{tx, ty});
        }
        return result;
    }

    private static int pawnAt(State s, int x, int y) {
        for (int i = 0; i < s.playerCount(); i++)
            if (s.px[i] == x && s.py[i] == y) return i + 1;
        return 0;
    }

    private static boolean isBlocked(State s, int x1, int y1, int x2, int y2) {
        int dx = x2 - x1, dy = y2 - y1;
        for (int[] w : s.walls) {
            boolean h = w[2] == 1;
            if (dy == 1 && h && w[1] == y1 && (w[0] == x1 || w[0] == x1 - 1)) return true;
            if (dy == -1 && h && w[1] == y1 - 1 && (w[0] == x1 || w[0] == x1 - 1)) return true;
            if (dx == 1 && !h && w[0] == x1 && (w[1] == y1 || w[1] == y1 - 1)) return true;
            if (dx == -1 && !h && w[0] == x1 - 1 && (w[1] == y1 || w[1] == y1 - 1)) return true;
        }
        for (int[] w : s.miniWalls) {
            boolean h = w[2] == 1;
            if (dy == 1 && h && w[0] == x1 && w[1] == y1) return true;
            if (dy == -1 && h && w[0] == x1 && w[1] == y1 - 1) return true;
            if (dx == 1 && !h && w[0] == x1 && w[1] == y1) return true;
            if (dx == -1 && !h && w[0] == x1 - 1 && w[1] == y1) return true;
        }
        return false;
    }

    private static boolean isValidWall(State s, int player, int x, int y, boolean h) {
        if (s.wallsRemaining[player - 1] <= 0 || x < 0 || x > 7 || y < 0 || y > 7) return false;
        int[] w = {x, y, h ? 1 : 0};
        if (wallConflicts(s, w)) return false;
        if (normalWallConflictsWithMiniWalls(s, w)) return false;
        State tmp = s.copy();
        tmp.walls.add(w);
        return !anyPlayerTrapped(tmp);
    }

    private static boolean wallConflicts(State s, int[] nw) {
        boolean nh = nw[2] == 1;
        for (int[] w : s.walls) {
            boolean wh = w[2] == 1;
            if (nh == wh) {
                if (nh && w[1] == nw[1] && Math.abs(w[0] - nw[0]) <= 1) return true;
                if (!nh && w[0] == nw[0] && Math.abs(w[1] - nw[1]) <= 1) return true;
            } else if (w[0] == nw[0] && w[1] == nw[1]) return true;
        }
        return false;
    }

    private static boolean isValidMiniWallPlacements(State s, int player, List<int[]> walls) {
        if (walls == null || walls.isEmpty() || walls.size() > 2) return false;
        if (s.miniWallsRemaining[player - 1] < walls.size()) return false;
        if (s.characters[player - 1] != CharacterType.BUILDER || s.skillRemaining[player - 1] <= 0) return false;

        for (int i = 0; i < walls.size(); i++) {
            if (!isMiniWallInBounds(walls.get(i))) return false;
            if (miniWallConflicts(s, walls.get(i))) return false;
            for (int j = 0; j < i; j++)
                if (sameMiniWall(walls.get(i), walls.get(j))) return false;
        }

        State tmp = s.copy();
        tmp.miniWalls.addAll(walls);
        return !anyPlayerTrapped(tmp);
    }

    private static boolean isMiniWallInBounds(int[] w) {
        if (w[2] == 1) return w[0] >= 0 && w[0] <= 8 && w[1] >= 0 && w[1] <= 7;
        return w[0] >= 0 && w[0] <= 7 && w[1] >= 0 && w[1] <= 8;
    }

    private static boolean miniWallConflicts(State s, int[] nw) {
        for (int[] w : s.miniWalls)
            if (sameMiniWall(w, nw)) return true;
        for (int[] w : s.walls)
            if (normalWallCoversMiniWall(w, nw)) return true;
        return false;
    }

    private static boolean normalWallConflictsWithMiniWalls(State s, int[] wall) {
        for (int[] w : s.miniWalls)
            if (normalWallCoversMiniWall(wall, w)) return true;
        return false;
    }

    private static boolean normalWallCoversMiniWall(int[] wall, int[] mini) {
        if (wall[2] == 1 && mini[2] == 1)
            return wall[1] == mini[1] && (mini[0] == wall[0] || mini[0] == wall[0] + 1);
        if (wall[2] == 0 && mini[2] == 0)
            return wall[0] == mini[0] && (mini[1] == wall[1] || mini[1] == wall[1] + 1);
        return false;
    }

    private static boolean sameMiniWall(int[] a, int[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    private static boolean isValidTrapPlacement(State s, int player, int x, int y) {
        if (s.characters[player - 1] != CharacterType.TRAPPER || s.trapRemaining[player - 1] <= 0) return false;
        if (!in(x, y) || y == 0 || y == 8) return false;
        if (s.playerCount() == 4 && (x == 0 || x == 8)) return false;
        if (pawnAt(s, x, y) != 0) return false;
        for (int[] trap : s.traps)
            if (trap[0] == x && trap[1] == y) return false;
        return true;
    }

    private static int bfs(State s, int player) {
        int px = s.px[player - 1], py = s.py[player - 1];
        if (GameLogic.isGoal(player, px, py)) return 0;

        boolean[][] vis = new boolean[9][9];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{px, py, 0});
        vis[px][py] = true;

        while (!q.isEmpty()) {
            int[] n = q.poll();
            for (int[] dir : DIRS) {
                int nx = n[0] + dir[0], ny = n[1] + dir[1];
                if (!in(nx, ny) || vis[nx][ny] || isBlocked(s, n[0], n[1], nx, ny)) continue;
                if (GameLogic.isGoal(player, nx, ny)) return n[2] + 1;
                vis[nx][ny] = true;
                q.add(new int[]{nx, ny, n[2] + 1});
            }
        }
        return 999;
    }

    // 探索用にStateへ手を適用する(実盤面への適用はGameRoom側のハンドラが行う)
    private static State apply(State s, int player, Move m) {
        int idx = player - 1;
        switch (m.kind) {
            case Move.TRAP:
                if (isValidTrapPlacement(s, player, m.x, m.y)) {
                    s.traps.add(new int[]{m.x, m.y, player});
                    s.trapRemaining[idx]--;
                }
                return s; // トラップ設置では手番が回らない
            case Move.WALL:
                s.walls.add(new int[]{m.x, m.y, m.horizontal ? 1 : 0});
                s.wallsRemaining[idx]--;
                s.cannotMove[idx] = false;
                return s;
            case Move.BREAK_WALL: {
                int h = m.horizontal ? 1 : 0;
                s.walls.removeIf(w -> w[0] == m.x && w[1] == m.y && w[2] == h);
                s.skillRemaining[idx]--;
                s.cannotMove[idx] = false;
                return s;
            }
            case Move.MINI_WALLS:
                s.miniWalls.addAll(m.miniWalls);
                s.miniWallsRemaining[idx] -= m.miniWalls.size();
                s.skillRemaining[idx]--;
                s.cannotMove[idx] = false;
                return s;
            case Move.SKILL_MOVE:
                if ("RUNNER_MOVE".equals(m.skill)) {
                    applyRunnerMove(s, player, m.x, m.y);
                } else {
                    moveWithTrap(s, player, m.x, m.y);
                }
                s.skillRemaining[idx]--;
                return s;
            default:
                moveWithTrap(s, player, m.x, m.y);
                return s;
        }
    }

    private static void applyRunnerMove(State s, int player, int toX, int toY) {
        int idx = player - 1;
        int stepX = Integer.compare(toX - s.px[idx], 0);
        int stepY = Integer.compare(toY - s.py[idx], 0);
        int x1 = s.px[idx] + stepX;
        int y1 = s.py[idx] + stepY;
        if (!moveWithTrap(s, player, x1, y1))
            moveWithTrap(s, player, toX, toY);
    }

    private static boolean moveWithTrap(State s, int player, int x, int y) {
        int idx = player - 1;
        s.px[idx] = x;
        s.py[idx] = y;

        for (int i = 0; i < s.traps.size(); i++) {
            int[] trap = s.traps.get(i);
            if (trap[2] != player && trap[0] == x && trap[1] == y) {
                s.traps.remove(i);
                s.cannotMove[idx] = true;
                return true;
            }
        }
        return false;
    }

    private static boolean in(int x, int y) {
        return x >= 0 && x <= 8 && y >= 0 && y <= 8;
    }
}
