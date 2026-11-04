package com.panchayat.backend.games.jackaroo;

import java.security.SecureRandom;
import java.util.*;

/**
 * Jackaroo rules, with no I/O: the service loads a {@link State}, asks for
 * legal moves, applies one, and saves it.
 *
 * Board: a ring of 76 cells; seat s starts at cell s*19. Each player has 4
 * marbles: -1 = in base, 0..75 = on the ring (absolute cell), 100..103 = in
 * their home lane. A marble enters its home lane after a full lap.
 *
 * Cards (one per turn):
 *   A  bring a marble out, or move 1 or 11      K  bring out, or move 13
 *   Q  move 12      J  swap one of your marbles with someone else's on the ring
 *   10 move 10      7 move 7      5 move 5      4 move 4 backwards
 *   2 3 6 8 9  move that many
 * Landing on a marble sends it back to its base (a teammate's too). You can't
 * land on your own marble. With no legal move you must burn (discard) a card.
 * Teams: in a 4-player game seats 0+2 and 1+3; in 2 players each plays alone.
 * A team wins when all its marbles are home.
 */
public final class JackarooEngine {

    public static final int CELLS = 76;
    public static final int STRIDE = 19;
    public static final int HOME = 100;
    public static final int HAND = 4;
    private static final SecureRandom RNG = new SecureRandom();

    private JackarooEngine() {
    }

    /* ---------------- state ---------------- */

    public static final class Player {
        public long userId;
        public String name;
        public String avatar;
        public int seat;
        public int team;
        public int[] marbles = {-1, -1, -1, -1};
        public List<String> hand = new ArrayList<>();
        public boolean left;
    }

    public static final class State {
        public List<Player> players = new ArrayList<>();
        public List<String> deck = new ArrayList<>();
        public List<String> discard = new ArrayList<>();
        public int turn;          // index into players
        public int round;
        public Integer winnerTeam;
        public String last;       // what just happened, for the table
        public String lastCard;
        public long lastBy;
        public Map<String, Object> lastMove; // {seat, marble, from, to, captured:[seat,marble]}
    }

    /** One legal move for the player whose turn it is. */
    public record Move(int card, String code, int marble, String action, Integer targetSeat, Integer targetMarble, String label) {
    }

    /* ---------------- setup ---------------- */

    public static void start(State s) {
        int n = s.players.size();
        if (n != 2 && n != 4) throw new IllegalStateException("Jackaroo needs 2 or 4 players.");
        s.players.sort(Comparator.comparingInt(p -> p.seat));
        for (int i = 0; i < n; i++) {
            Player p = s.players.get(i);
            p.team = n == 4 ? p.seat % 2 : i;
            p.marbles = new int[]{-1, -1, -1, -1};
            p.hand = new ArrayList<>();
        }
        s.deck = newDeck();
        s.discard = new ArrayList<>();
        s.turn = 0;
        s.round = 0;
        s.winnerTeam = null;
        s.last = "The game started. Good luck!";
        deal(s);
    }

    static List<String> newDeck() {
        List<String> d = new ArrayList<>();
        for (String suit : List.of("S", "H", "D", "C"))
            for (String r : List.of("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")) d.add(r + suit);
        Collections.shuffle(d, RNG);
        return d;
    }

    static void deal(State s) {
        s.round++;
        for (Player p : s.players) {
            while (p.hand.size() < HAND) {
                if (s.deck.isEmpty()) {
                    s.deck.addAll(s.discard);
                    s.discard.clear();
                    if (s.deck.isEmpty()) s.deck = newDeck();
                    Collections.shuffle(s.deck, RNG);
                }
                p.hand.add(s.deck.remove(s.deck.size() - 1));
            }
        }
    }

    public static String rank(String card) {
        return card.substring(0, card.length() - 1);
    }

    /* ---------------- moves ---------------- */

    public static Player current(State s) {
        return s.players.get(s.turn);
    }

    /** Every legal move for the player whose turn it is. Empty: they must burn a card. */
    public static List<Move> legal(State s) {
        Player me = current(s);
        List<Move> out = new ArrayList<>();
        for (int c = 0; c < me.hand.size(); c++) {
            String card = me.hand.get(c);
            String r = rank(card);
            for (int m = 0; m < 4; m++) {
                int pos = me.marbles[m];
                if (pos < 0) {
                    if ((r.equals("A") || r.equals("K")) && canLand(s, me, start(me))) out.add(new Move(c, card, m, "OUT", null, null, "Bring out"));
                    continue;
                }
                switch (r) {
                    case "A" -> {
                        addForward(s, out, c, card, me, m, 1);
                        addForward(s, out, c, card, me, m, 11);
                    }
                    case "K" -> addForward(s, out, c, card, me, m, 13);
                    case "Q" -> addForward(s, out, c, card, me, m, 12);
                    case "4" -> {
                        if (pos < HOME) {
                            int to = Math.floorMod(pos - 4, CELLS);
                            if (canLand(s, me, to)) out.add(new Move(c, card, m, "BACK", null, null, "Back 4"));
                        }
                    }
                    case "J" -> {
                        if (pos < HOME)
                            for (Player o : s.players)
                                if (o != me && !o.left)
                                    for (int k = 0; k < 4; k++) {
                                        int op = o.marbles[k];
                                        // A marble sitting on its own start is safe from swaps
                                        if (op >= 0 && op < HOME && op != start(o))
                                            out.add(new Move(c, card, m, "SWAP", o.seat, k, "Swap with " + o.name));
                                    }
                    }
                    default -> addForward(s, out, c, card, me, m, Integer.parseInt(r));
                }
            }
        }
        return out;
    }

    private static void addForward(State s, List<Move> out, int c, String card, Player me, int m, int n) {
        Integer to = forward(me, me.marbles[m], n);
        if (to == null) return;
        if (to >= HOME) {
            if (homeTaken(me, to, m)) return;
        } else if (!canLand(s, me, to)) return;
        out.add(new Move(c, card, m, n == 11 ? "MOVE11" : "MOVE", null, null, "Move " + n));
    }

    /** Where a marble ends after n steps forward, or null if it can't (overshooting home). */
    static Integer forward(Player me, int pos, int n) {
        if (pos >= HOME) {
            int h = pos - HOME + n;
            return h <= 3 ? HOME + h : null;
        }
        int rel = Math.floorMod(pos - start(me), CELLS);
        int nr = rel + n;
        if (nr < CELLS) return Math.floorMod(start(me) + nr, CELLS);
        int h = nr - CELLS;
        return h <= 3 ? HOME + h : null;
    }

    static int start(Player p) {
        return p.seat * STRIDE;
    }

    private static boolean homeTaken(Player me, int to, int self) {
        for (int k = 0; k < 4; k++) if (k != self && me.marbles[k] == to) return true;
        return false;
    }

    private static boolean canLand(State s, Player me, int cell) {
        for (int k = 0; k < 4; k++) if (me.marbles[k] == cell) return false;
        return true;
    }

    /** Applies a legal move (the service picks it from legal()). Returns the next player's index. */
    public static void apply(State s, Move mv) {
        Player me = current(s);
        String card = me.hand.remove(mv.card());
        s.discard.add(card);
        int from = me.marbles[mv.marble()];
        int to;
        List<Integer> captured = null;
        switch (mv.action()) {
            case "OUT" -> to = start(me);
            case "BACK" -> to = Math.floorMod(from - 4, CELLS);
            case "SWAP" -> {
                Player o = bySeat(s, mv.targetSeat());
                int theirs = o.marbles[mv.targetMarble()];
                o.marbles[mv.targetMarble()] = from;
                to = theirs;
            }
            default -> {
                int n = mv.action().equals("MOVE11") ? 11 : switch (rank(card)) {
                    case "A" -> 1;
                    case "K" -> 13;
                    case "Q" -> 12;
                    default -> Integer.parseInt(rank(card));
                };
                to = forward(me, from, n);
            }
        }
        if (!mv.action().equals("SWAP") && to < HOME) {
            for (Player o : s.players)
                for (int k = 0; k < 4; k++)
                    if (o.marbles[k] == to && !(o == me && k == mv.marble())) {
                        o.marbles[k] = -1;
                        captured = List.of(o.seat, k);
                    }
        }
        me.marbles[mv.marble()] = to;
        Map<String, Object> lm = new LinkedHashMap<>();
        lm.put("seat", me.seat);
        lm.put("marble", mv.marble());
        lm.put("from", from);
        lm.put("to", to);
        lm.put("action", mv.action());
        if (captured != null) lm.put("captured", captured);
        s.lastMove = lm;
        s.lastCard = card;
        s.lastBy = me.userId;
        s.last = me.name + " played " + pretty(card) + (mv.action().equals("OUT") ? " and brought a marble out"
                : mv.action().equals("SWAP") ? " and swapped marbles" : mv.action().equals("BACK") ? " and moved back 4" : "")
                + (captured != null ? " and knocked " + bySeat(s, captured.get(0)).name + " back to base!" : "");
        finishTurn(s);
    }

    /** No legal move: burn a card. */
    public static void burn(State s, int cardIndex) {
        Player me = current(s);
        if (cardIndex < 0 || cardIndex >= me.hand.size()) cardIndex = 0;
        String card = me.hand.remove(cardIndex);
        s.discard.add(card);
        s.lastCard = card;
        s.lastBy = me.userId;
        s.lastMove = null;
        s.last = me.name + " had no move and burned " + pretty(card);
        finishTurn(s);
    }

    private static void finishTurn(State s) {
        // A team wins when all its players' marbles are home
        Map<Integer, Boolean> done = new HashMap<>();
        for (Player p : s.players) {
            boolean home = Arrays.stream(p.marbles).allMatch(x -> x >= HOME);
            done.merge(p.team, home, Boolean::logicalAnd);
        }
        for (var e : done.entrySet())
            if (e.getValue()) {
                s.winnerTeam = e.getKey();
                s.last = s.last + ". " + teamName(s, e.getKey()) + " won!";
                return;
            }
        // Next player still in the game
        int n = s.players.size();
        for (int i = 1; i <= n; i++) {
            int t = (s.turn + i) % n;
            if (!s.players.get(t).left) {
                s.turn = t;
                break;
            }
        }
        if (s.players.stream().filter(p -> !p.left).allMatch(p -> p.hand.isEmpty())) deal(s);
        // A player with an empty hand (left players) is skipped
        int guard = 0;
        while (current(s).hand.isEmpty() && guard++ < n) s.turn = (s.turn + 1) % n;
    }

    public static String teamName(State s, int team) {
        List<String> names = s.players.stream().filter(p -> p.team == team).map(p -> p.name).toList();
        return String.join(" & ", names);
    }

    static Player bySeat(State s, int seat) {
        return s.players.stream().filter(p -> p.seat == seat).findFirst().orElseThrow();
    }

    static String pretty(String card) {
        String r = rank(card);
        String suit = switch (card.charAt(card.length() - 1)) {
            case 'S' -> "♠";
            case 'H' -> "♥";
            case 'D' -> "♦";
            default -> "♣";
        };
        return r + suit;
    }
}
