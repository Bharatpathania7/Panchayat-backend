package com.panchayat.backend.rooms.service;

import com.panchayat.backend.rooms.dto.RoomStateResponse;
import com.panchayat.backend.rooms.dto.RoomStateResponse.QueueEntry;
import com.panchayat.backend.rooms.dto.RoomStateResponse.RoomEvent;
import com.panchayat.backend.rooms.dto.RoomStateResponse.SeatInvite;
import com.panchayat.backend.rooms.entity.RoomAdmin;
import com.panchayat.backend.rooms.entity.RoomBan;
import com.panchayat.backend.rooms.repository.RoomAdminRepository;
import com.panchayat.backend.rooms.repository.RoomBanRepository;
import com.panchayat.backend.rooms.repository.RoomFollowerRepository;
import com.panchayat.backend.rooms.repository.RoomMemberRepository;
import com.panchayat.backend.voice.entity.VoiceRoom;
import com.panchayat.backend.voice.entity.VoiceRoomParticipant;
import com.panchayat.backend.voice.repository.VoiceRoomParticipantRepository;
import com.panchayat.backend.voice.repository.VoiceRoomRepository;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The live state of each voice room: who sits on which seat, the mic order
 * queue, closed and muted seats, admins, kicks and bans.
 *
 * Rules (the app's UI follows the same ones):
 * - Seat 0 belongs to the owner and only the owner; they're put there on join.
 *   Everyone else joins as a listener and picks any open seat themselves.
 * - The room is "open" only while the owner or an admin is in it. While it's
 *   closed nobody else can take a seat or talk.
 * - Mic order on: people who aren't the owner or an admin ask first (queue).
 * - Admins can act on members only; the owner can act on anyone.
 * - Talking needs: a seat, that seat not muted, and the room open. This is
 *   enforced through LiveKit publish permissions, not only in the UI.
 *
 * Seats and the queue live in memory (this server instance); admins, bans,
 * mic order and closed seats are stored in the database. Every change is
 * broadcast to /topic/voice-rooms/{roomId}/state.
 */
@Service
public class RoomStateService {

    public static final Duration KICK_DURATION = Duration.ofMinutes(10);
    /** How long an invited person has to accept or refuse a seat invite. */
    public static final Duration INVITE_DURATION = Duration.ofSeconds(60);

    private final VoiceRoomRepository roomRepository;
    private final VoiceRoomParticipantRepository participantRepository;
    private final RoomAdminRepository adminRepository;
    private final RoomBanRepository banRepository;
    private final RoomMemberRepository memberRepository;
    private final RoomFollowerRepository followerRepository;
    private final SimpMessagingTemplate messaging;
    private final LiveKitPermissionService permissions;

    private final Map<Long, Live> rooms = new ConcurrentHashMap<>();

    /** In-memory part of one room. Guarded by synchronizing on the instance. */
    private static final class Live {
        Long[] seats;
        final Set<Integer> mutedSeats = new TreeSet<>();
        /** userId -> preferred seat (-1 = any), oldest first. */
        final LinkedHashMap<Long, Integer> queue = new LinkedHashMap<>();
        /** Invited userId -> the invite waiting for their answer. */
        final LinkedHashMap<Long, Invite> invites = new LinkedHashMap<>();
        long version;
    }

    private record Invite(int seat, Long by, Instant expiresAt) {
    }

    /** What the database says about the room right now, loaded per action. */
    private record Ctx(VoiceRoom room, Set<Long> joined, Set<Long> admins) {
        Long ownerId() { return room.getOwnerId(); }
        boolean isOwner(Long id) { return Objects.equals(room.getOwnerId(), id); }
        boolean isAdmin(Long id) { return admins.contains(id); }
        boolean isMod(Long id) { return isOwner(id) || isAdmin(id); }
        boolean open() {
            return joined.contains(room.getOwnerId())
                    || admins.stream().anyMatch(joined::contains);
        }
    }

    public RoomStateService(
            VoiceRoomRepository roomRepository,
            VoiceRoomParticipantRepository participantRepository,
            RoomAdminRepository adminRepository,
            RoomBanRepository banRepository,
            RoomMemberRepository memberRepository,
            RoomFollowerRepository followerRepository,
            SimpMessagingTemplate messaging,
            LiveKitPermissionService permissions
    ) {
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.adminRepository = adminRepository;
        this.banRepository = banRepository;
        this.memberRepository = memberRepository;
        this.followerRepository = followerRepository;
        this.messaging = messaging;
        this.permissions = permissions;
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    public RoomStateResponse getState(Long roomId) {
        Ctx ctx = load(roomId);
        Live live = live(ctx);
        synchronized (live) {
            reconcile(ctx, live);
            return snapshot(ctx, live, null);
        }
    }

    /** Whether someone is blocked from joining (kick not expired, or banned). */
    public Optional<RoomBan> activeBan(Long roomId, Long userId) {
        Instant now = Instant.now();
        return banRepository.findByRoomIdAndUserId(roomId, userId)
                .filter(b -> b.isActive(now));
    }

    /** How many people sit on a seat (on mic) in each live room, from memory (no database). */
    public Map<Long, Integer> seatedCounts() {
        Map<Long, Integer> out = new java.util.HashMap<>();
        rooms.forEach((roomId, live) -> {
            int n = 0;
            synchronized (live) {
                for (Long id : live.seats) if (id != null) n++;
            }
            if (n > 0) out.put(roomId, n);
        });
        return out;
    }

    public boolean isAdmin(Long roomId, Long userId) {
        return adminRepository.existsByRoomIdAndUserId(roomId, userId);
    }

    /** Is this person on a seat right now? */
    public boolean isSeated(Long roomId, Long userId) {
        Live live = rooms.get(roomId);
        if (live == null) return false;
        synchronized (live) {
            return seatOf(live, userId) >= 0;
        }
    }

    /** The seat this person is on, or -1. */
    public int seatIndexOf(Long roomId, Long userId) {
        Live live = rooms.get(roomId);
        if (live == null) return -1;
        synchronized (live) {
            return seatOf(live, userId);
        }
    }

    /** Takes someone off their seat (e.g. they moved to the auction seat). Not the owner. */
    public void vacateSeat(Long roomId, Long userId) {
        Ctx ctx = load(roomId);
        if (ctx.isOwner(userId)) return;
        Live live = live(ctx);
        synchronized (live) {
            int seat = seatOf(live, userId);
            live.queue.remove(userId);
            if (seat < 0) return;
            live.seats[seat] = null;
            publish(ctx, live, event("SEAT_LEFT", userId, userId, seat, null));
        }
    }

    /** The owner or an admin of this room. */
    public boolean isModerator(VoiceRoom room, Long userId) {
        return Objects.equals(room.getOwnerId(), userId) || isAdmin(room.getId(), userId);
    }

    /** May this person talk the moment they join? Only the owner (on seat 0). */
    /**
     * What a new token should allow. Someone already on a seat keeps talking
     * rights: a token made for a reconnect must not take them away, because the
     * correction sent to LiveKit can't land before they have connected.
     */
    public boolean canPublishOnJoin(VoiceRoom room, Long userId) {
        if (Objects.equals(room.getOwnerId(), userId)) return true;
        Live live = rooms.get(room.getId());
        if (live == null) return false;
        synchronized (live) {
            if (seatOf(live, userId) < 0) return false;
        }
        Ctx ctx = load(room.getId());
        synchronized (live) {
            return mayPublish(ctx, live, userId);
        }
    }

    /** Who should be able to talk right now; empty when this room has no live state yet. */
    public Map<Long, Boolean> desiredPublishers(Long roomId) {
        Live live = rooms.get(roomId);
        if (live == null) return Map.of();
        Ctx ctx = load(roomId);
        Map<Long, Boolean> out = new HashMap<>();
        synchronized (live) {
            for (Long userId : ctx.joined()) out.put(userId, mayPublish(ctx, live, userId));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Presence (called by VoiceRoomService)
    // ------------------------------------------------------------------

    public void onJoined(Long roomId, Long userId) {
        Ctx ctx = load(roomId);
        Live live = live(ctx);
        synchronized (live) {
            reconcile(ctx, live);
            if (ctx.isOwner(userId)) {
                unseat(live, userId);
                live.seats[0] = userId;
            }
            publish(ctx, live, event("JOINED", null, userId, null, null));
        }
    }

    public void onLeft(Long roomId, Long userId) {
        Ctx ctx = load(roomId);
        Live live = live(ctx);
        synchronized (live) {
            unseat(live, userId);
            live.queue.remove(userId);
            permissions.forget(roomId, userId);
            reconcile(ctx, live);
            publish(ctx, live, event("LEFT", null, userId, null, null));
        }
    }

    public void onRoomClosed(Long roomId) {
        Live live = rooms.remove(roomId);
        if (live == null) return;
        synchronized (live) {
            for (Long id : live.seats) if (id != null) permissions.forget(roomId, id);
        }
        Object closedNotice = Map.of("roomId", String.valueOf(roomId), "closed", true);
        messaging.convertAndSend(topic(roomId), closedNotice);
    }

    // ------------------------------------------------------------------
    // Your own seat
    // ------------------------------------------------------------------

    /** Take seat `index`, move to it, or (mic order) ask for it. */
    public RoomStateResponse takeSeat(Long roomId, Long actor, int index) {
        Ctx ctx = load(roomId);
        requireInRoom(ctx, actor);
        Live live = live(ctx);
        synchronized (live) {
            reconcile(ctx, live);
            requireSeatIndex(live, index);

            if (index == 0) {
                if (!ctx.isOwner(actor)) throw conflict("This seat belongs to the room owner");
                unseat(live, actor);
                live.seats[0] = actor;
                return publish(ctx, live, event("SEAT_TAKEN", actor, actor, 0, null));
            }
            if (ctx.isOwner(actor)) throw conflict("Your owner seat is fixed at the top");
            if (live.seats[index] != null) throw conflict("This seat is taken");
            if (closedSeats(ctx).contains(index)) throw conflict("This seat is closed");
            if (!ctx.open() && !ctx.isMod(actor)) {
                throw conflict("The room is closed until the owner or an admin joins");
            }

            boolean alreadySeated = seatOf(live, actor) >= 0;
            // Mic order: people not yet on a seat ask first; moving seats needs no request
            if (ctx.room().isMicOrder() && !ctx.isMod(actor) && !alreadySeated) {
                if (live.queue.containsKey(actor)) {
                    throw conflict("Your mic request is already waiting for the owner or an admin");
                }
                live.queue.put(actor, index);
                return publish(ctx, live, event("MIC_REQUESTED", actor, actor, index, null));
            }

            unseat(live, actor);
            live.seats[index] = actor;
            live.queue.remove(actor);
            return publish(ctx, live, event(alreadySeated ? "SEAT_MOVED" : "SEAT_TAKEN", actor, actor, index, null));
        }
    }

    public RoomStateResponse leaveSeat(Long roomId, Long actor) {
        Ctx ctx = load(roomId);
        Live live = live(ctx);
        synchronized (live) {
            reconcile(ctx, live);
            if (ctx.isOwner(actor)) throw conflict("The owner's seat is fixed. Mute your mic instead.");
            int seat = seatOf(live, actor);
            if (seat < 0) return snapshot(ctx, live, null);
            live.seats[seat] = null;
            return publish(ctx, live, event("SEAT_LEFT", actor, actor, seat, null));
        }
    }

    public RoomStateResponse cancelMicRequest(Long roomId, Long actor) {
        Ctx ctx = load(roomId);
        Live live = live(ctx);
        synchronized (live) {
            reconcile(ctx, live);
            if (live.queue.remove(actor) == null) return snapshot(ctx, live, null);
            return publish(ctx, live, event("MIC_REQUEST_CANCELLED", actor, actor, null, null));
        }
    }

    // ------------------------------------------------------------------
    // Owner and admin tools
    // ------------------------------------------------------------------

    public RoomStateResponse approveMicRequest(Long roomId, Long actor, Long target) {
        Ctx ctx = load(roomId);
        requireMod(ctx, actor);
        Live live = live(ctx);
        synchronized (live) {
            reconcile(ctx, live);
            Integer prefer = live.queue.get(target);
            if (prefer == null) throw notFound("That mic request is no longer waiting");
            int at = freeSeat(ctx, live, prefer);
            if (at < 0) throw conflict("No open seat. Open or free a seat first.");
            live.queue.remove(target);
            unseat(live, target);
            live.seats[at] = target;
            return publish(ctx, live, event("MIC_APPROVED", actor, target, at, null));
        }
    }

    public RoomStateResponse declineMicRequest(Long roomId, Long actor, Long target) {
        Ctx ctx = load(roomId);
        requireMod(ctx, actor);
        Live live = live(ctx);
        synchronized (live) {
            reconcile(ctx, live);
            if (live.queue.remove(target) == null) return snapshot(ctx, live, null);
            return publish(ctx, live, event("MIC_DECLINED", actor, target, null, null));
        }
    }

    public RoomStateResponse setMicOrder(Long roomId, Long actor, boolean on) {
        Ctx ctx = load(roomId);
        requireMod(ctx, actor);
        Live live = live(ctx);
        synchronized (live) {
            ctx.room().setMicOrder(on);
            roomRepository.save(ctx.room());
            if (!on) live.queue.clear();
            reconcile(ctx, live);
            return publish(ctx, live, event("MIC_ORDER", actor, null, null, on));
        }
    }

    public RoomStateResponse setSeatClosed(Long roomId, Long actor, int index, boolean closed) {
        Ctx ctx = load(roomId);
        requireMod(ctx, actor);
        Live live = live(ctx);
        synchronized (live) {
            reconcile(ctx, live);
            requireSeatIndex(live, index);
            if (index == 0) throw conflict("The owner's seat can't be closed");
            TreeSet<Integer> set = new TreeSet<>(closedSeats(ctx));
            if (closed) set.add(index); else set.remove(index);
            ctx.room().setClosedSeats(set.stream().map(String::valueOf).collect(Collectors.joining(",")));
            roomRepository.save(ctx.room());
            return publish(ctx, live, event(closed ? "SEAT_CLOSED" : "SEAT_OPENED", actor, null, index, closed));
        }
    }

    public RoomStateResponse setSeatMuted(Long roomId, Long actor, int index, boolean muted) {
        Ctx ctx = load(roomId);
        requireMod(ctx, actor);
        Live live = live(ctx);
        synchronized (live) {
            reconcile(ctx, live);
            requireSeatIndex(live, index);
            if (index == 0) throw conflict("The owner's seat can't be muted");
            Long occupant = live.seats[index];
            if (occupant != null && !occupant.equals(actor) && !canManage(ctx, actor, occupant)) {
                throw forbidden("Admins can't mute the owner or another admin");
            }
            if (muted) live.mutedSeats.add(index); else live.mutedSeats.remove(index);
            return publish(ctx, live, event(muted ? "SEAT_MUTED" : "SEAT_UNMUTED", actor, occupant, index, muted));
        }
    }

    /**
     * The owner or an admin invites someone to a seat. Nothing moves yet: the
     * invited person gets the invite (in the state, event SEAT_INVITED) and
     * accepts or refuses it. Works for listeners and for people already on a
     * seat (an accepted invite moves them). Leaving out index picks the first
     * open seat for a listener.
     */
    public RoomStateResponse inviteToSeat(Long roomId, Long actor, Long target, Integer index) {
        Ctx ctx = load(roomId);
        requireMod(ctx, actor);
        if (Objects.equals(actor, target)) throw conflict("Take the seat yourself instead");
        if (!ctx.joined().contains(target)) throw notFound("That person isn't in the room");
        if (ctx.isOwner(target)) throw conflict("The owner always sits on the owner's seat");
        Live live = live(ctx);
        synchronized (live) {
            reconcile(ctx, live);
            int current = seatOf(live, target);
            int at;
            if (index == null) {
                if (current >= 0) throw conflict("They're already on a seat. Choose the seat to move them to.");
                at = freeSeat(ctx, live, -1);
            } else {
                requireSeatIndex(live, index);
                if (index == current) throw conflict("They're already on that seat");
                at = index > 0 && live.seats[index] == null ? index : -1;
            }
            if (at <= 0) throw conflict("No open seat. Open or free a seat first.");
            live.invites.put(target, new Invite(at, actor, Instant.now().plus(INVITE_DURATION)));
            return publish(ctx, live, event("SEAT_INVITED", actor, target, at, current >= 0));
        }
    }

    /** Yes to a seat invite: takes the seat (or moves you to it). */
    public RoomStateResponse acceptSeatInvite(Long roomId, Long actor) {
        Ctx ctx = load(roomId);
        requireInRoom(ctx, actor);
        Live live = live(ctx);
        synchronized (live) {
            reconcile(ctx, live);
            Invite inv = live.invites.remove(actor);
            if (inv == null) throw notFound("That seat invite has expired");
            if (!ctx.isMod(inv.by()) || !ctx.joined().contains(inv.by())) {
                publish(ctx, live, null);
                throw conflict("The person who invited you is no longer in charge here");
            }
            int at = inv.seat();
            if (at >= live.seats.length || live.seats[at] != null) {
                publish(ctx, live, null);
                throw conflict("Someone else took that seat. Ask for another one.");
            }
            boolean moved = seatOf(live, actor) >= 0;
            // An invite to a closed seat opens it
            Set<Integer> closed = new TreeSet<>(closedSeats(ctx));
            if (closed.remove(at)) {
                ctx.room().setClosedSeats(closed.stream().map(String::valueOf).collect(Collectors.joining(",")));
                roomRepository.save(ctx.room());
            }
            unseat(live, actor);
            live.queue.remove(actor);
            live.seats[at] = actor;
            return publish(ctx, live, event("SEAT_INVITE_ACCEPTED", actor, inv.by(), at, moved));
        }
    }

    /** No to a seat invite. */
    public RoomStateResponse declineSeatInvite(Long roomId, Long actor) {
        Ctx ctx = load(roomId);
        Live live = live(ctx);
        synchronized (live) {
            reconcile(ctx, live);
            Invite inv = live.invites.remove(actor);
            if (inv == null) return snapshot(ctx, live, null);
            return publish(ctx, live, event("SEAT_INVITE_DECLINED", actor, inv.by(), inv.seat(), null));
        }
    }

    /** The owner or an admin takes back an invite that hasn't been answered. */
    public RoomStateResponse cancelSeatInvite(Long roomId, Long actor, Long target) {
        Ctx ctx = load(roomId);
        requireMod(ctx, actor);
        Live live = live(ctx);
        synchronized (live) {
            reconcile(ctx, live);
            Invite inv = live.invites.remove(target);
            if (inv == null) return snapshot(ctx, live, null);
            return publish(ctx, live, event("SEAT_INVITE_CANCELLED", actor, target, inv.seat(), null));
        }
    }

    public RoomStateResponse removeFromMic(Long roomId, Long actor, Long target) {
        Ctx ctx = load(roomId);
        requireMod(ctx, actor);
        if (ctx.isOwner(target) || !canManage(ctx, actor, target)) {
            throw forbidden("You can't remove this person from the mic");
        }
        Live live = live(ctx);
        synchronized (live) {
            reconcile(ctx, live);
            int seat = seatOf(live, target);
            if (seat < 0) return snapshot(ctx, live, null);
            live.seats[seat] = null;
            return publish(ctx, live, event("REMOVED_FROM_MIC", actor, target, seat, null));
        }
    }

    public RoomStateResponse setAdmin(Long roomId, Long actor, Long target, boolean admin) {
        Ctx ctx = load(roomId);
        if (!ctx.isOwner(actor)) throw forbidden("Only the room owner can choose admins");
        if (ctx.isOwner(target)) throw conflict("The owner is already in charge of the room");
        if (admin && !(memberRepository.existsByRoomIdAndUserId(roomId, target)
                && followerRepository.existsByRoomIdAndUserId(roomId, target))) {
            throw conflict("They have to join and follow the room before becoming an admin");
        }

        if (admin && !ctx.isAdmin(target)) {
            adminRepository.save(new RoomAdmin(roomId, target, actor));
        } else if (!admin) {
            adminRepository.deleteByRoomIdAndUserId(roomId, target);
        }
        participantRepository.findByRoomIdAndUserIdAndStatus(roomId, target, "JOINED")
                .ifPresent(p -> {
                    p.setRole(admin ? "MODERATOR" : "MEMBER");
                    participantRepository.save(p);
                });

        Ctx fresh = load(roomId);
        Live live = live(fresh);
        synchronized (live) {
            reconcile(fresh, live);
            return publish(fresh, live, event(admin ? "ADMIN_ADDED" : "ADMIN_REMOVED", actor, target, null, admin));
        }
    }

    /** Admins must stay members and followers: leaving either ends the role. */
    public void dropAdminRole(Long roomId, Long userId) {
        if (!adminRepository.existsByRoomIdAndUserId(roomId, userId)) return;
        adminRepository.deleteByRoomIdAndUserId(roomId, userId);
        participantRepository.findByRoomIdAndUserIdAndStatus(roomId, userId, "JOINED")
                .ifPresent(p -> {
                    p.setRole("MEMBER");
                    participantRepository.save(p);
                });
        Ctx ctx = load(roomId);
        Live live = live(ctx);
        synchronized (live) {
            reconcile(ctx, live);
            publish(ctx, live, event("ADMIN_REMOVED", null, userId, null, false));
        }
    }

    /** Remove from the room; they can come back after KICK_DURATION. */
    public RoomStateResponse kick(Long roomId, Long actor, Long target) {
        return block(roomId, actor, target, Instant.now().plus(KICK_DURATION), "KICKED");
    }

    /** Remove from the room until unbanned. */
    public RoomStateResponse ban(Long roomId, Long actor, Long target) {
        return block(roomId, actor, target, null, "BANNED");
    }

    public RoomStateResponse unban(Long roomId, Long actor, Long target) {
        Ctx ctx = load(roomId);
        requireMod(ctx, actor);
        banRepository.deleteByRoomIdAndUserId(roomId, target);
        Live live = live(ctx);
        synchronized (live) {
            reconcile(ctx, live);
            return publish(ctx, live, event("UNBANNED", actor, target, null, null));
        }
    }

    public List<Map<String, Object>> listBans(Long roomId, Long actor) {
        Ctx ctx = load(roomId);
        requireMod(ctx, actor);
        Instant now = Instant.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (RoomBan b : banRepository.findByRoomIdOrderByCreatedAtDesc(roomId)) {
            if (!b.isActive(now)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", String.valueOf(b.getUserId()));
            row.put("bannedBy", String.valueOf(b.getBannedBy()));
            row.put("expiresAt", b.getExpiresAt());
            row.put("createdAt", b.getCreatedAt());
            out.add(row);
        }
        return out;
    }

    private RoomStateResponse block(Long roomId, Long actor, Long target, Instant until, String type) {
        Ctx ctx = load(roomId);
        requireMod(ctx, actor);
        if (Objects.equals(actor, target)) throw conflict("You can't remove yourself");
        if (ctx.isOwner(target) || !canManage(ctx, actor, target)) {
            throw forbidden("Admins can't remove the owner or another admin");
        }

        RoomBan ban = banRepository.findByRoomIdAndUserId(roomId, target)
                .orElseGet(() -> new RoomBan(roomId, target));
        ban.renew(actor, until);
        banRepository.save(ban);

        // Removing an admin from the room also ends their admin role
        if (ctx.isAdmin(target)) adminRepository.deleteByRoomIdAndUserId(roomId, target);

        participantRepository.findByRoomIdAndUserIdAndStatus(roomId, target, "JOINED")
                .ifPresent(p -> {
                    p.setStatus("LEFT");
                    p.setLeftAt(Instant.now());
                    p.setMuted(false);
                    if ("MODERATOR".equals(p.getRole())) p.setRole("MEMBER");
                    participantRepository.save(p);
                });

        Ctx fresh = load(roomId);
        Live live = live(fresh);
        RoomStateResponse state;
        synchronized (live) {
            unseat(live, target);
            live.queue.remove(target);
            live.invites.remove(target);
            reconcile(fresh, live);
            // Tell the room (and the removed person's app) first, then end their call
            state = publish(fresh, live, event(type, actor, target, null, null));
        }
        permissions.removeFromCall(roomId, target);
        return state;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private Ctx load(Long roomId) {
        if (roomId == null || roomId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid room ID");
        }
        VoiceRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> notFound("Voice room not found"));
        if (!"ACTIVE".equals(room.getStatus())) throw notFound("Voice room is not available");

        Set<Long> joined = participantRepository.findByRoomIdAndStatus(roomId, "JOINED").stream()
                .map(VoiceRoomParticipant::getUserId)
                .collect(Collectors.toCollection(HashSet::new));
        Set<Long> admins = adminRepository.findByRoomId(roomId).stream()
                .map(RoomAdmin::getUserId)
                .collect(Collectors.toCollection(HashSet::new));
        return new Ctx(room, joined, admins);
    }

    private Live live(Ctx ctx) {
        Live l = rooms.computeIfAbsent(ctx.room().getId(), id -> {
            Live fresh = new Live();
            fresh.seats = new Long[seatCount(ctx.room())];
            return fresh;
        });
        // The room changed mode (voice / Kharidari <-> YouTube): the seat count follows
        int want = seatCount(ctx.room());
        synchronized (l) {
            if (l.seats.length != want) l.seats = Arrays.copyOf(l.seats, want);
        }
        return l;
    }

    /** Seats are fixed by the room's mode, whatever its size: 10 in a voice or Kharidari room, 12 in a YouTube room. */
    public static final int SEATS_VOICE = 10;
    public static final int SEATS_WATCH = 12;

    public static int seatCount(VoiceRoom room) {
        return room.getRoomType() == com.panchayat.backend.voice.entity.VoiceRoomType.WATCH ? SEATS_WATCH : SEATS_VOICE;
    }

    /** The owner or an admin changed the room's mode: resize the seats and tell the room. People on seats that no longer exist lose them. */
    public void onModeChanged(Long roomId) {
        Ctx ctx = load(roomId);
        Live live = live(ctx);
        synchronized (live) {
            reconcile(ctx, live);
            publish(ctx, live, null);
        }
    }

    /** Drop people who are no longer in the room; put a present owner on seat 0. */
    private void reconcile(Ctx ctx, Live live) {
        for (int i = 0; i < live.seats.length; i++) {
            Long id = live.seats[i];
            if (id != null && !ctx.joined().contains(id)) live.seats[i] = null;
        }
        live.queue.keySet().removeIf(id -> !ctx.joined().contains(id) || seatOf(live, id) >= 0);
        Instant now = Instant.now();
        live.invites.entrySet().removeIf(e -> !ctx.joined().contains(e.getKey())
                || ctx.isOwner(e.getKey())
                || e.getValue().expiresAt().isBefore(now));
        if (live.seats[0] != null && !ctx.isOwner(live.seats[0])) live.seats[0] = null;
        if (live.seats[0] == null && ctx.joined().contains(ctx.ownerId())) {
            unseat(live, ctx.ownerId());
            live.seats[0] = ctx.ownerId();
        }
    }

    private RoomStateResponse publish(Ctx ctx, Live live, RoomEvent event) {
        live.version++;
        syncMicPermissions(ctx, live);
        RoomStateResponse state = snapshot(ctx, live, event);
        messaging.convertAndSend(topic(ctx.room().getId()), state);
        return state;
    }

    /** Talking needs a seat that isn't muted, in an open room. Everyone else: listen only. */
    private void syncMicPermissions(Ctx ctx, Live live) {
        for (Long userId : ctx.joined()) {
            permissions.setCanPublish(ctx.room().getId(), userId, mayPublish(ctx, live, userId));
        }
    }

    private static boolean mayPublish(Ctx ctx, Live live, Long userId) {
        int seat = seatOf(live, userId);
        return seat >= 0 && !live.mutedSeats.contains(seat) && (ctx.open() || ctx.isMod(userId));
    }

    private RoomStateResponse snapshot(Ctx ctx, Live live, RoomEvent event) {
        List<String> seats = new ArrayList<>(live.seats.length);
        for (Long id : live.seats) seats.add(id == null ? null : String.valueOf(id));
        List<QueueEntry> queue = live.queue.entrySet().stream()
                .map(e -> new QueueEntry(String.valueOf(e.getKey()), e.getValue()))
                .toList();
        return new RoomStateResponse(
                String.valueOf(ctx.room().getId()),
                live.version,
                String.valueOf(ctx.ownerId()),
                ctx.admins().stream().sorted().map(String::valueOf).toList(),
                seats,
                List.copyOf(closedSeats(ctx)),
                List.copyOf(live.mutedSeats),
                ctx.room().isMicOrder(),
                queue,
                ctx.open(),
                live.invites.entrySet().stream()
                        .map(e -> new SeatInvite(String.valueOf(e.getKey()), e.getValue().seat(),
                                String.valueOf(e.getValue().by()), e.getValue().expiresAt().toEpochMilli()))
                        .toList(),
                System.currentTimeMillis(),
                event
        );
    }

    private static RoomEvent event(String type, Long actor, Long target, Integer seat, Boolean on) {
        return new RoomEvent(
                type,
                actor == null ? null : String.valueOf(actor),
                target == null ? null : String.valueOf(target),
                seat,
                on
        );
    }

    private static String topic(Long roomId) {
        return "/topic/voice-rooms/" + roomId + "/state";
    }

    private static Set<Integer> closedSeats(Ctx ctx) {
        Set<Integer> out = new TreeSet<>();
        for (String part : ctx.room().getClosedSeats().split(",")) {
            try {
                if (!part.isBlank()) out.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // skip bad entries
            }
        }
        return out;
    }

    private int freeSeat(Ctx ctx, Live live, int prefer) {
        Set<Integer> closed = closedSeats(ctx);
        if (prefer > 0 && prefer < live.seats.length && live.seats[prefer] == null && !closed.contains(prefer)) {
            return prefer;
        }
        for (int i = 1; i < live.seats.length; i++) {
            if (live.seats[i] == null && !closed.contains(i)) return i;
        }
        return -1;
    }

    private static int seatOf(Live live, Long userId) {
        for (int i = 0; i < live.seats.length; i++) {
            if (Objects.equals(live.seats[i], userId)) return i;
        }
        return -1;
    }

    private static void unseat(Live live, Long userId) {
        for (int i = 0; i < live.seats.length; i++) {
            if (Objects.equals(live.seats[i], userId)) live.seats[i] = null;
        }
    }

    /** The owner can act on anyone else; admins only on people who are neither owner nor admin. */
    private static boolean canManage(Ctx ctx, Long actor, Long target) {
        if (Objects.equals(actor, target)) return false;
        if (ctx.isOwner(actor)) return true;
        return ctx.isAdmin(actor) && !ctx.isOwner(target) && !ctx.isAdmin(target);
    }

    private static void requireInRoom(Ctx ctx, Long actor) {
        if (!ctx.joined().contains(actor)) throw forbidden("Join the room first");
    }

    private static void requireMod(Ctx ctx, Long actor) {
        if (!ctx.isMod(actor)) throw forbidden("Only the owner or an admin can do this");
        if (!ctx.joined().contains(actor)) throw forbidden("Be in the room to do this");
    }

    private static void requireSeatIndex(Live live, int index) {
        if (index < 0 || index >= live.seats.length) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such seat");
        }
    }

    private static ResponseStatusException conflict(String m) {
        return new ResponseStatusException(HttpStatus.CONFLICT, m);
    }

    private static ResponseStatusException forbidden(String m) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, m);
    }

    private static ResponseStatusException notFound(String m) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, m);
    }
}
