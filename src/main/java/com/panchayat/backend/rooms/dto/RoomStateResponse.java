package com.panchayat.backend.rooms.dto;

import java.util.List;

/**
 * The live state of a room, returned by every room-state endpoint and
 * broadcast to /topic/voice-rooms/{roomId}/state after each change.
 *
 * User ids are strings (same as the rest of the app). seats[i] is the user on
 * seat i or null; seat 0 is the owner's. version only goes up, so a client can
 * ignore an older message that arrives late.
 */
public record RoomStateResponse(
        String roomId,
        long version,
        String ownerId,
        List<String> admins,
        List<String> seats,
        List<Integer> closedSeats,
        List<Integer> mutedSeats,
        boolean micOrder,
        List<QueueEntry> queue,
        /* The owner or an admin is in the room. */
        boolean open,
        /* Seat invites waiting for the invited person to accept or refuse. */
        List<SeatInvite> invites,
        /* The server's clock (ms), so apps can time invites with their own clock. */
        long serverTime,
        /* What just changed (null on a plain read). */
        RoomEvent event
) {
    /** userId was invited by byUserId to seat; the invite lapses at expiresAt (epoch ms). */
    public record SeatInvite(String userId, int seat, String byUserId, long expiresAt) {
    }

    /** Someone waiting for a seat; seat = the one they asked for, -1 for any. */
    public record QueueEntry(String userId, int seat) {
    }

    /**
     * type: JOINED, LEFT, SEAT_TAKEN, SEAT_MOVED, SEAT_LEFT, MIC_REQUESTED,
     * MIC_REQUEST_CANCELLED, MIC_APPROVED, MIC_DECLINED, MIC_ORDER, SEAT_CLOSED,
     * SEAT_OPENED, SEAT_MUTED, SEAT_UNMUTED, SEAT_INVITED, SEAT_INVITE_ACCEPTED,
     * SEAT_INVITE_DECLINED, SEAT_INVITE_CANCELLED, REMOVED_FROM_MIC,
     * ADMIN_ADDED, ADMIN_REMOVED, KICKED, BANNED, UNBANNED.
     */
    public record RoomEvent(String type, String actorId, String targetId, Integer seat, Boolean on) {
    }
}
