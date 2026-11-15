-- A group chat can have a picture (set by the owner or an admin).
alter table chat_conversations add column if not exists image_path varchar(500);
