-- How far this desk will ballast a ship for one cargo.
--
-- Match already refuses a pairing nothing on file connects - an absent row in
-- trade_area_distances means "too far to consider", and the Caspian has no distances at
-- all. That answers the question of whether a ship *can* get there. It says nothing about
-- whether she is worth sending, and those are different questions: a hull nine days away
-- can make a laycan three weeks out and still be tonnage nobody would work.
--
-- The desk-wide answer to that lives in app_settings (match.maxBallastDays), where the
-- utilisation floor already lives and for the same reason - it is a figure a broker argues
-- with the screen about. This column is the per-enquiry override, because the desk-wide
-- figure is a default and never a rule: a full cargo worth crossing an ocean for and a
-- part cargo nobody would cross the Med for are both an ordinary week, and there is no one
-- number that is right for both.
--
-- NULL MEANS "USE THE SETTING", which is what almost every row will say. It does not mean
-- "no limit" - a cargo that really is worth any ballast says so with a large number, and
-- the difference matters because the two would otherwise be the same blank cell.


ALTER TABLE public.cargoes
    ADD COLUMN max_ballast_days smallint;
