package com.panchayat.backend.games.ludo;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Classic Ludo rules, no I/O. The service loads a {@link State}, asks for the
 * legal moves, applies one and saves it.
 *
 * Board: a 52-square loop. Seat s starts on square START[s] (= 1 + 13s); a
 * token's position is counted from its own start:
 *   -1        at home base
 *   0..50     on the loop (absolute square = (START[s] + pos) % 52)
 *   51..55    in its own home lane
 *   56        finished
 * Squares START[s] and START[s] + 8 are safe (no captures).
 *
 * Turn: roll, then move one token by the dice. A 6 is needed to leave the base.
 * Rolling a 6, capturing, or bringing a token home gives another roll; three 6s
 * in a row lose the turn. An exact roll is needed to finish. The first player
 * with all four tokens home wins.
 */
public final class LudoEngine {

    public static final int LOOP = 52;
    public static final int LANE_START = 51;
    public static final int FINISH = 56;
    public static final int[] START = {1, 14, 27, 40};
    private static final SecureRandom RNG = new SecureRandom();

    private LudoEngine() {
    }

    public static final class Player {
        public long userId;
        public String name;
        public String avatar;
        public int seat;
        public int[] tokens = {-1, -1, -1, -1};
        public boolean left;
        public boolean paid;
    }

    public static final class LastMove {
        public int seat;
        public int token;
        public int from;
        public int to;
        public Integer capturedSeat;
        public Integer capturedToken;
    }

    public static final class State {
        public List<Player> players = new ArrayList<>();
        public int turn;
        /** ROLL or MOVE */
        public String phase = "ROLL";
        public int dice;
        public int sixes;
        public long rolls;
        public String last = "";
        public LastMove lastMove;
        public Long winner;
        public long stake;
        public long pot;
    }

    public record Move(int token, int from, int to, boolean capture, boolean finish, boolean leaveBase) {
    }

    public static Player current(State s) {
        return s.players.get(s.turn);
    }

    public static boolean isSafe(int abs) {
        for (int st : START) if (abs == st || abs == (st + 8) % LOOP) return true;
        return false;
    }

    public static int abs(int seat, int pos) {
        return (START[seat] + pos) % LOOP;
    }

    /** Roll the dice for the current player. Returns false when the turn ends without a move (three 6s, or nothing to move). */
    public static void roll(State s) {
        Player p = current(s);
        s.dice = 1 + RNG.nextInt(6);
        s.rolls++;
        s.sixes = s.dice == 6 ? s.sixes + 1 : 0;
        s.lastMove = null;
        if (s.sixes >= 3) {
            s.last = p.name + " rolled three 6s and lost the turn";
            nextTurn(s);
            return;
        }
        if (legal(s).isEmpty()) {
            s.last = p.name + " rolled a " + s.dice + " (no move)";
            nextTurn(s);
            return;
        }
        s.phase = "MOVE";
        s.last = p.name + " rolled a " + s.dice;
    }

    public static List<Move> legal(State s) {
        List<Move> out = new ArrayList<>();
        Player p = current(s);
        int d = s.dice;
        if (d < 1) return out;
        for (int t = 0; t < 4; t++) {
            int pos = p.tokens[t];
            if (pos == FINISH) continue;
            int to;
            if (pos == -1) {
                if (d != 6) continue;
                to = 0;
            } else {
                to = pos + d;
                if (to > FINISH) continue;
            }
            // Two of your own tokens may share a square; a block isn't used in this version
            boolean capture = false;
            if (to <= 50) {
                int a = abs(p.seat, to);
                if (!isSafe(a)) {
                    for (Player o : s.players) {
                        if (o == p || o.left) continue;
                        for (int ot : o.tokens) if (ot >= 0 && ot <= 50 && abs(o.seat, ot) == a) capture = true;
                    }
                }
            }
            out.add(new Move(t, pos, to, capture, to == FINISH, pos == -1));
        }
        return out;
    }

    /** Applies a legal move; gives another roll on a 6, a capture or a finish. */
    public static void apply(State s, Move m) {
        Player p = current(s);
        p.tokens[m.token()] = m.to();
        LastMove lm = new LastMove();
        lm.seat = p.seat;
        lm.token = m.token();
        lm.from = m.from();
        lm.to = m.to();
        StringBuilder text = new StringBuilder(p.name);
        if (m.leaveBase()) text.append(" brought a token out");
        else if (m.finish()) text.append(" got a token home 🏠");
        else text.append(" moved ").append(s.dice);
        boolean captured = false;
        if (m.to() <= 50) {
            int a = abs(p.seat, m.to());
            if (!isSafe(a)) {
                for (Player o : s.players) {
                    if (o == p || o.left) continue;
                    for (int i = 0; i < 4; i++) {
                        if (o.tokens[i] >= 0 && o.tokens[i] <= 50 && abs(o.seat, o.tokens[i]) == a) {
                            o.tokens[i] = -1;
                            lm.capturedSeat = o.seat;
                            lm.capturedToken = i;
                            captured = true;
                            text.append(" and cut ").append(o.name).append("'s token ✂️");
                        }
                    }
                }
            }
        }
        s.lastMove = lm;
        s.last = text.toString();
        if (allHome(p)) {
            s.winner = p.userId;
            s.last = p.name + " won the game! 🏆";
            return;
        }
        if (s.dice == 6 || captured || m.finish()) {
            s.phase = "ROLL"; // same player again
        } else {
            nextTurn(s);
        }
    }

    public static boolean allHome(Player p) {
        for (int t : p.tokens) if (t != FINISH) return false;
        return true;
    }

    public static void nextTurn(State s) {
        s.phase = "ROLL";
        s.sixes = 0;
        int n = s.players.size();
        for (int i = 1; i <= n; i++) {
            int t = (s.turn + i) % n;
            if (!s.players.get(t).left) {
                s.turn = t;
                return;
            }
        }
    }

    /** The move a timed-out (or auto) player makes: finish, then capture, then leave base, then the furthest token. */
    public static Move bestMove(List<Move> legal) {
        Move best = legal.get(0);
        for (Move m : legal) {
            if (score(m) > score(best)) best = m;
        }
        return best;
    }

    private static int score(Move m) {
        if (m.finish()) return 1000;
        if (m.capture()) return 900;
        if (m.leaveBase()) return 800;
        return m.from();
    }
}
