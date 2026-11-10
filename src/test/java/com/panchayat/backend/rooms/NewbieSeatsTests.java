package com.panchayat.backend.rooms;

import com.panchayat.backend.rooms.service.RoomStateService;
import com.panchayat.backend.voice.entity.VoiceRoom;
import com.panchayat.backend.voice.entity.VoiceRoomType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The seat count of a room: 10 for voice, 12 for YouTube, plus 6 only in a welcome room that opened its extra seats. */
class NewbieSeatsTests {

    private static VoiceRoom room(VoiceRoomType type, boolean newbie, boolean extra) {
        VoiceRoom r = new VoiceRoom();
        r.setRoomType(type);
        r.setNewbie(newbie);
        r.setExtraSeats(extra);
        return r;
    }

    @Test
    void regularRoomsKeepTheirSeats() {
        assertEquals(10, RoomStateService.seatCount(room(VoiceRoomType.VOICE, false, false)));
        assertEquals(12, RoomStateService.seatCount(room(VoiceRoomType.WATCH, false, false)));
    }

    @Test
    void extraSeatsOnlyCountInAWelcomeRoom() {
        assertEquals(10, RoomStateService.seatCount(room(VoiceRoomType.VOICE, true, false)));
        assertEquals(16, RoomStateService.seatCount(room(VoiceRoomType.VOICE, true, true)));
        // A flag left on a normal room does nothing
        assertEquals(10, RoomStateService.seatCount(room(VoiceRoomType.VOICE, false, true)));
    }
}
