package com.panchayat.backend.games.ludo;

import com.panchayat.backend.games.ludo.LudoEngine.Move;
import com.panchayat.backend.games.ludo.LudoEngine.Player;
import com.panchayat.backend.games.ludo.LudoEngine.State;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ludo rules (no Spring). */
class LudoEngineTests {

    private static State game() {
        State s = new State();
        for (int seat : new int[]{3, 1}) {
            Player p = new Player();
            p.userId = seat + 10;
            p.name = "P" + seat;
            p.seat = seat;
            s.players.add(p);
        }
        s.turn = 0;
        return s;
    }

    @Test
    void needsASixToLeaveTheBase() {
        State s = game();
        s.dice = 5;
        assertTrue(LudoEngine.legal(s).isEmpty());
        s.dice = 6;
        List<Move> l = LudoEngine.legal(s);
        assertEquals(4, l.size());
        assertTrue(l.get(0).leaveBase());
    }

    @Test
    void sixGivesAnotherRollOtherwiseTurnPasses() {
        State s = game();
        s.dice = 6;
        LudoEngine.apply(s, LudoEngine.legal(s).get(0));
        assertEquals(0, s.turn, "a 6 rolls again");
        assertEquals("ROLL", s.phase);
        s.dice = 3;
        LudoEngine.apply(s, LudoEngine.legal(s).get(0));
        assertEquals(1, s.turn, "no 6: next player");
    }

    @Test
    void captureSendsTheTokenHomeExceptOnSafeSquares() {
        State s = game();
        Player a = s.players.get(0); // seat 3, start 40
        Player b = s.players.get(1); // seat 1, start 14
        // b's token on absolute square 44 (not safe): b pos = 44 - 14 = 30
        b.tokens[0] = 30;
        a.tokens[0] = 0; // abs 40
        s.dice = 4; // a moves to abs 44
        Move m = LudoEngine.legal(s).stream().filter(x -> x.token() == 0).findFirst().orElseThrow();
        assertTrue(m.capture());
        LudoEngine.apply(s, m);
        assertEquals(-1, b.tokens[0], "captured token goes home");
        assertEquals(0, s.turn, "a capture rolls again");

        // On a star (abs 48 = 40 + 8) nothing is captured
        b.tokens[1] = 48 - 14;
        s.dice = 4; // a token 0 from abs 44 to 48
        Move safe = LudoEngine.legal(s).stream().filter(x -> x.token() == 0).findFirst().orElseThrow();
        assertFalse(safe.capture());
    }

    @Test
    void exactRollToFinishAndWin() {
        State s = game();
        Player a = s.players.get(0);
        a.tokens = new int[]{56, 56, 56, 53};
        s.dice = 4;
        assertTrue(LudoEngine.legal(s).isEmpty(), "53 + 4 overshoots home");
        s.dice = 3;
        Move m = LudoEngine.legal(s).get(0);
        assertTrue(m.finish());
        LudoEngine.apply(s, m);
        assertEquals(a.userId, s.winner);
    }

    @Test
    void threeSixesLoseTheTurn() {
        State s = game();
        s.players.get(0).tokens[0] = 10;
        s.sixes = 2;
        // force a 6 by rolling until one comes (random), then check the turn passed
        for (int i = 0; i < 200 && s.turn == 0; i++) {
            s.sixes = 2;
            s.phase = "ROLL";
            LudoEngine.roll(s);
            if (s.dice == 6) break;
            s.turn = 0;
        }
        if (s.dice == 6) assertEquals(1, s.turn);
    }
}
