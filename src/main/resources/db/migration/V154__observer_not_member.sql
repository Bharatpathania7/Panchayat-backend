-- A silent observer is never a visible member of any room: remove the memberships such accounts already had,
-- and fix those rooms' member counts. (New ones are blocked in the app.)
WITH gone AS (
    DELETE FROM voice_room_members m USING users u
    WHERE u.id = m.user_id AND coalesce(u.silent_observer, false)
    RETURNING m.room_id
)
UPDATE voice_rooms r
SET member_count = (SELECT count(*) FROM voice_room_members x JOIN users u ON u.id = x.user_id
                    WHERE x.room_id = r.id AND NOT coalesce(u.silent_observer, false))
WHERE r.id IN (SELECT DISTINCT room_id FROM gone);
