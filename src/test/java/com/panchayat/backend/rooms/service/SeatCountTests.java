package com.panchayat.backend.rooms.service;

import com.panchayat.backend.voice.entity.VoiceRoom;
import com.panchayat.backend.voice.entity.VoiceRoomType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Seats are fixed by the room's mode, whatever the room's size. */
class SeatCountTests {

    private static VoiceRoom room(VoiceRoomType type, int maxPeople) {
        VoiceRoom r = new VoiceRoom();
        r.setRoomType(type);
        r.setMaxParticipants(maxPeople);
        return r;
    }

    @Test
    void voiceAndKharidariRoomsHaveTenSeats() {
        assertEquals(10, RoomStateService.seatCount(room(VoiceRoomType.VOICE, 20)));
        assertEquals(10, RoomStateService.seatCount(room(VoiceRoomType.KHARIDARI, 20)));
        assertEquals(10, RoomStateService.seatCount(room(VoiceRoomType.VOICE, 4)));
    }

    @Test
    void youtubeRoomsHaveTwelveSeats() {
        assertEquals(12, RoomStateService.seatCount(room(VoiceRoomType.WATCH, 20)));
        assertEquals(12, RoomStateService.seatCount(room(VoiceRoomType.WATCH, 8)));
    }
}
