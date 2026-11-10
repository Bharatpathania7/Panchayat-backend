-- Lucky coins fired on every gift (chance 100). Make them a real random trigger: about 1 gift in 4.
alter table gift_settings alter column lucky_chance_percent set default 25;
update gift_settings set lucky_chance_percent = 25 where lucky_chance_percent = 100;
