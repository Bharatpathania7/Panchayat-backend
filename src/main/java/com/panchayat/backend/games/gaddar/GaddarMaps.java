package com.panchayat.backend.games.gaddar;

import java.util.*;

/**
 * The maps of Gaon ka Gaddaar. Each is plain data: walls the players can't walk through, things only drawn (decor),
 * kaam (tasks), vents the gaddaars use, the sabotage fix points and the place of the baithak. The same data goes to the
 * phones, so drawing and walking match the server. The game picks a map at the start.
 */
final class GaddarMaps {

    private GaddarMaps() {
    }

    record Rect(double x, double y, double w, double h, String kind, String label) {
    }

    record Pt(double x, double y) {
    }

    /** t: rect | circle (w is the radius) | text. */
    record Decor(String t, double x, double y, double w, double h, String c, String text) {
    }

    /** kind: hold (stand there and hold Kaam), seq (tap the diyas in order) or wires (join the matching wires). */
    record TaskDef(int id, String name, String kind, double x, double y) {
    }

    /** Vents with the same group are linked: a gaddaar in one can come out of any other of its group. */
    record VentDef(int id, double x, double y, int group) {
    }

    record MapDef(String id, String name, String blurb, double w, double h, String ground, Pt meeting, List<Rect> walls, List<Decor> decor,
                  List<TaskDef> tasks, List<VentDef> vents, Pt lightFix, Pt reactorA, Pt reactorB) {
    }

    static final double RADIUS = 18;

    static final Map<String, MapDef> ALL = new LinkedHashMap<>();

    static {
        ALL.put("gaon", gaon());
        ALL.put("mela", mela());
        ALL.put("haveli", haveli());
    }

    static MapDef get(String id) {
        return ALL.getOrDefault(id, ALL.get("gaon"));
    }

    static boolean blocked(MapDef m, double x, double y) {
        if (x < RADIUS || y < RADIUS || x > m.w() - RADIUS || y > m.h() - RADIUS) return true;
        for (Rect r : m.walls()) {
            double cx = Math.max(r.x(), Math.min(x, r.x() + r.w()));
            double cy = Math.max(r.y(), Math.min(y, r.y() + r.h()));
            if (Math.hypot(x - cx, y - cy) < RADIUS) return true;
        }
        return false;
    }

    static Map<String, Object> json(MapDef m) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", m.id());
        o.put("name", m.name());
        o.put("blurb", m.blurb());
        o.put("w", m.w());
        o.put("h", m.h());
        o.put("radius", RADIUS);
        o.put("speed", GaddarService.SPEED);
        o.put("ground", m.ground());
        o.put("meeting", List.of(m.meeting().x(), m.meeting().y()));
        List<Map<String, Object>> walls = new ArrayList<>();
        for (Rect r : m.walls()) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("x", r.x());
            w.put("y", r.y());
            w.put("w", r.w());
            w.put("h", r.h());
            w.put("kind", r.kind());
            if (r.label() != null) w.put("label", r.label());
            walls.add(w);
        }
        o.put("walls", walls);
        List<Map<String, Object>> decor = new ArrayList<>();
        for (Decor d : m.decor()) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("t", d.t());
            w.put("x", d.x());
            w.put("y", d.y());
            w.put("w", d.w());
            w.put("h", d.h());
            w.put("c", d.c());
            if (d.text() != null) w.put("text", d.text());
            decor.add(w);
        }
        o.put("decor", decor);
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (TaskDef t : m.tasks()) tasks.add(Map.of("id", t.id(), "name", t.name(), "kind", t.kind(), "x", t.x(), "y", t.y()));
        o.put("tasks", tasks);
        List<Map<String, Object>> vents = new ArrayList<>();
        for (VentDef v : m.vents()) vents.add(Map.of("id", v.id(), "x", v.x(), "y", v.y(), "group", v.group()));
        o.put("vents", vents);
        o.put("lightFix", List.of(m.lightFix().x(), m.lightFix().y()));
        o.put("reactor", List.of(List.of(m.reactorA().x(), m.reactorA().y()), List.of(m.reactorB().x(), m.reactorB().y())));
        return o;
    }

    /** Short list for the lobby's map picker. */
    static List<Map<String, Object>> catalogue() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MapDef m : ALL.values()) out.add(Map.of("id", m.id(), "name", m.name(), "blurb", m.blurb()));
        return out;
    }

    private static Rect r(double x, double y, double w, double h, String kind) {
        return new Rect(x, y, w, h, kind, null);
    }

    private static Rect r(double x, double y, double w, double h, String kind, String label) {
        return new Rect(x, y, w, h, kind, label);
    }

    private static Decor rect(double x, double y, double w, double h, String c) {
        return new Decor("rect", x, y, w, h, c, null);
    }

    private static Decor text(double x, double y, String text, String c) {
        return new Decor("text", x, y, 0, 0, c, text);
    }

    /* ---------------- 1. Gaon (the village) ---------------- */

    private static MapDef gaon() {
        List<Rect> walls = List.of(
                r(90, 90, 310, 190, "haveli", "Haveli"), r(1200, 90, 310, 190, "mandir", "Mandir"),
                r(90, 720, 310, 190, "mandi", "Mandi"), r(1200, 720, 310, 190, "ghar", "Panchayat Ghar"),
                r(765, 120, 70, 70, "kuan", "Kuan"),
                r(700, 430, 24, 24, "pillar"), r(876, 430, 24, 24, "pillar"), r(700, 566, 24, 24, "pillar"), r(876, 566, 24, 24, "pillar"),
                r(520, 260, 60, 60, "tree"), r(1020, 260, 60, 60, "tree"), r(520, 680, 60, 60, "tree"), r(1020, 680, 60, 60, "tree"),
                r(440, 480, 60, 60, "tree"), r(1100, 480, 60, 60, "tree"));
        List<Decor> decor = List.of(
                rect(0, 466, 1600, 64, "#b98a58"), rect(768, 0, 64, 1000, "#b98a58"),
                new Decor("circle", 800, 498, 150, 0, "#d3ab72", null),
                rect(900, 770, 220, 14, "#6b4a2b"), rect(900, 796, 220, 14, "#6b4a2b"), rect(900, 822, 220, 14, "#6b4a2b"), rect(900, 848, 220, 14, "#6b4a2b"),
                rect(900, 874, 220, 14, "#6b4a2b"));
        List<TaskDef> tasks = List.of(
                new TaskDef(1, "Kuen se paani bharo", "hold", 800, 215), new TaskDef(2, "Anaaj chhanto", "shield", 245, 690),
                new TaskDef(3, "Diya jalao", "seq", 1355, 310), new TaskDef(4, "Doodh ikattha karo", "hold", 245, 310),
                new TaskDef(5, "Khet ki sinchai", "hold", 1000, 930), new TaskDef(6, "Tractor ke taar jodo", "wires", 1355, 690),
                new TaskDef(7, "Gaay ko chaara do", "hold", 560, 870), new TaskDef(8, "Panchayat register bharo", "swipe", 1000, 380));
        List<VentDef> vents = List.of(new VentDef(1, 430, 200, 1), new VentDef(2, 1170, 200, 1), new VentDef(3, 800, 880, 1),
                new VentDef(4, 430, 800, 2), new VentDef(5, 1170, 800, 2));
        return new MapDef("gaon", "Gaon", "Chaupal, haveli, mandir aur khet. Khula gaon, saaf nazar.", 1600, 1000, "#3f7d3a", new Pt(800, 498), walls, decor, tasks, vents,
                new Pt(560, 520), new Pt(640, 215), new Pt(1010, 960));
    }

    /* ---------------- 2. Mela (the fair) ---------------- */

    private static MapDef mela() {
        List<Rect> walls = List.of(
                r(80, 80, 380, 220, "tent", "Tambu"), r(1340, 80, 380, 200, "halwai", "Halwai"),
                r(1340, 800, 380, 220, "circus", "Maut ka Kuan"), r(80, 820, 320, 180, "chai", "Chai"),
                r(800, 500, 200, 200, "wheel", "Jhoola"),
                r(560, 100, 110, 70, "stall"), r(1100, 100, 110, 70, "stall"),
                r(300, 480, 110, 70, "stall"), r(300, 620, 110, 70, "stall"), r(1400, 480, 110, 70, "stall"), r(1400, 620, 110, 70, "stall"),
                r(600, 860, 110, 70, "stall"), r(900, 880, 110, 70, "stall"), r(1180, 860, 110, 70, "stall"),
                r(500, 420, 60, 60, "tree"), r(1240, 420, 60, 60, "tree"), r(560, 650, 60, 60, "tree"), r(1180, 700, 60, 60, "tree"));
        List<Decor> decor = List.of(
                rect(0, 330, 1800, 90, "#d9c48f"), rect(0, 740, 1800, 90, "#d9c48f"), rect(740, 0, 90, 1100, "#d9c48f"), rect(1000, 0, 90, 1100, "#d9c48f"),
                new Decor("circle", 900, 300, 110, 0, "#e6d6a2", null),
                new Decor("circle", 900, 600, 190, 0, "#d9c48f", null));
        List<TaskDef> tasks = List.of(
                new TaskDef(1, "Jhoola ki rassi kaso", "hold", 760, 600), new TaskDef(2, "Gubbare phulao", "align", 900, 770),
                new TaskDef(3, "Jalebi talo", "hold", 1530, 330), new TaskDef(4, "Chai banao", "hold", 240, 780),
                new TaskDef(5, "Roshni ke taar jodo", "wires", 270, 340), new TaskDef(6, "Ticket kaato", "swipe", 1530, 760),
                new TaskDef(7, "Dhol bajao", "hold", 680, 300), new TaskDef(8, "Kulhad dhoyo", "hold", 600, 520));
        List<VentDef> vents = List.of(new VentDef(1, 500, 280, 1), new VentDef(2, 1300, 760, 1), new VentDef(3, 1300, 300, 2), new VentDef(4, 480, 780, 2));
        return new MapDef("mela", "Mela", "Jhoola, halwai aur maut ka kuan. Bheed aur stalls ke peeche chhupne ki jagah.", 1800, 1100, "#c9b27a", new Pt(900, 300), walls, decor, tasks, vents,
                new Pt(700, 990), new Pt(355, 585), new Pt(1450, 585));
    }

    /* ---------------- 3. Haveli (rooms and corridors) ---------------- */

    private static MapDef haveli() {
        final double cw = 560, ch = 360, t = 26, gap = 130;
        List<Rect> walls = new ArrayList<>();
        // The walls between rooms, each with a doorway in the middle
        for (int x : new int[]{1, 2}) {
            for (int row = 0; row < 3; row++) {
                double y0 = row * ch, mid = y0 + ch / 2;
                walls.add(r(x * cw - t / 2, y0, t, mid - gap / 2 - y0, "wall"));
                walls.add(r(x * cw - t / 2, mid + gap / 2, t, y0 + ch - (mid + gap / 2), "wall"));
            }
        }
        for (int y : new int[]{1, 2}) {
            for (int col = 0; col < 3; col++) {
                double x0 = col * cw, mid = x0 + cw / 2;
                walls.add(r(x0, y * ch - t / 2, mid - gap / 2 - x0, t, "wall"));
                walls.add(r(mid + gap / 2, y * ch - t / 2, x0 + cw - (mid + gap / 2), t, "wall"));
            }
        }
        // Furniture
        walls.addAll(List.of(
                r(120, 110, 160, 60, "table"), r(380, 200, 80, 80, "chulha"),
                r(690, 200, 30, 30, "pillar"), r(950, 200, 30, 30, "pillar"),
                r(1300, 100, 80, 80, "box"), r(1450, 100, 80, 80, "box"), r(1300, 250, 80, 60, "box"),
                r(80, 420, 180, 80, "bed"), r(360, 600, 100, 70, "table"),
                r(800, 560, 80, 80, "fountain"),
                r(1300, 400, 160, 70, "sofa"), r(1400, 600, 100, 80, "table"),
                r(80, 780, 90, 70, "chest"), r(80, 900, 90, 70, "chest"), r(400, 800, 100, 100, "chest"),
                r(780, 800, 120, 60, "altar"), r(680, 960, 40, 40, "pillar"), r(960, 960, 40, 40, "pillar"),
                r(1250, 780, 140, 70, "hay"), r(1450, 960, 100, 60, "hay")));
        String[] names = {"Rasoi", "Diwan-e-khas", "Bhandar", "Zenana", "Aangan", "Baithak", "Toshakhana", "Pooja Ghar", "Ghodisal"};
        String[] floors = {"#c9a36a", "#b78a52", "#a8845a", "#c48f8f", "#bfae84", "#9aa0b8", "#8c8a7a", "#d6b57a", "#a69a5e"};
        List<Decor> decor = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            int col = i % 3, row = i / 3;
            decor.add(rect(col * cw, row * ch, cw, ch, floors[i]));
            decor.add(text(col * cw + cw / 2, row * ch + 52, names[i], "rgba(0,0,0,0.38)"));
        }
        decor.add(new Decor("circle", 840, 540, 170, 0, "#d9c48f", null));
        List<TaskDef> tasks = List.of(
                new TaskDef(1, "Chulha jalao", "hold", 230, 300), new TaskDef(2, "Khazana gino", "shield", 260, 870),
                new TaskDef(3, "Diya jalao", "seq", 700, 880), new TaskDef(4, "Bhandar ke taar jodo", "wires", 1450, 330),
                new TaskDef(5, "Ghode ko chaara do", "hold", 1540, 850), new TaskDef(6, "Tulsi ko paani do", "hold", 700, 640),
                new TaskDef(7, "Hukka saaf karo", "hold", 1250, 520), new TaskDef(8, "Palang ki chaadar badlo", "align", 150, 640),
                new TaskDef(9, "Haveli ka register bharo", "swipe", 840, 250));
        List<VentDef> vents = List.of(new VentDef(1, 470, 90, 1), new VentDef(2, 1600, 960, 1), new VentDef(3, 1600, 120, 2), new VentDef(4, 470, 990, 2),
                new VentDef(5, 480, 400, 3), new VentDef(6, 1620, 440, 3));
        return new MapDef("haveli", "Haveli", "Kamre aur galiyare. Darwaze se darwaze, andhere kone: gaddaar ke liye jannat.", 1680, 1080, "#6d5a45", new Pt(840, 470), walls, decor, tasks, vents,
                new Pt(1000, 900), new Pt(100, 60), new Pt(1580, 1010));
    }
}
