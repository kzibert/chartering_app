-- ---------------------------------------------------------------------------
-- Cargoes and positions read off the open boards, not only out of the mailbox
-- ---------------------------------------------------------------------------
-- The desk's mail is not the only place a circular arrives. ship.gr's Open Cargoes and
-- Open Ships boards are the same circulars, pasted by the same firms, signed with the same
-- blocks - "25,000 MT +/-10% MOLCO Wheat ... Best Regards, CHARTERING DESK, CAMPANA, Mersin,
-- www.campanashipping.com". The parser was trained on exactly that text, so nothing new has
-- to be taught; what was missing was a door for it to come in through.
--
-- The Feed tab already fetches those pages. It reads them to summarise them, which is a
-- different question from "what cargoes are on it", but it is the same fetch of the same
-- page - so a source is marked rather than duplicated, and one copy of each post serves
-- both. A second fetcher would read somebody else's server twice for the same bytes.

ALTER TABLE public.feed_sources
    ADD COLUMN into_intake boolean DEFAULT false NOT NULL;

COMMENT ON COLUMN public.feed_sources.into_intake IS
    'Read this source''s posts as circulars: cargoes, positions and company details, through '
    'the same parser the mailbox goes through. Independent of whether it is summarised.';


-- ---------------------------------------------------------------------------
-- A post is a message the parser has read
-- ---------------------------------------------------------------------------
-- parsed_emails is the dedupe for the sweep ("mail with no row here"), the anchor every
-- review item hangs off, and the only record of what the model actually answered. A board
-- post needs all three, identically - so it becomes a second kind of arrival on this table
-- rather than a parallel table with its own status, its own raw_json, its own log view and
-- its own foreign key from intake_items. Forking that would fork the queue.
--
-- Exactly one of the two columns is set, which the check states rather than leaves to the
-- code. CASCADE on both: a post whose source was deleted is a post nobody kept, the same
-- judgement feed_items already makes about its source and parsed_emails already makes about
-- a message its folder no longer holds. FeedSourceService refuses to delete a source that
-- still has questions waiting, which is where that judgement stops being cheap.
ALTER TABLE public.parsed_emails ALTER COLUMN mail_message_id DROP NOT NULL;

ALTER TABLE public.parsed_emails ADD COLUMN feed_item_id bigint;

ALTER TABLE public.parsed_emails
    ADD CONSTRAINT parsed_emails_feed_item_fkey FOREIGN KEY (feed_item_id)
        REFERENCES public.feed_items(id) ON DELETE CASCADE;

ALTER TABLE public.parsed_emails
    ADD CONSTRAINT parsed_emails_one_arrival
        CHECK ((mail_message_id IS NOT NULL) <> (feed_item_id IS NOT NULL));

-- Postgres counts nulls as distinct, so the existing unique index on mail_message_id already
-- tolerates a table of posts beside it and needs no change.
CREATE UNIQUE INDEX ux_parsed_emails_feed_item ON public.parsed_emails USING btree (feed_item_id);


-- ---------------------------------------------------------------------------
-- Where a cargo came from: typed, mailed, or read off a board
-- ---------------------------------------------------------------------------
-- from_mail answered this when there were two answers. There are three now, and a second
-- boolean beside the first would be two columns for one fact, free to disagree - the shape
-- this schema keeps out of the contacts tables for the same reason. So the boolean becomes
-- the value it always was one of.
--
-- MANUAL - somebody typed it, or saved it off a paste they had just read
-- MAIL   - read out of a synced message by the sweep
-- WEB    - read off a board post by the sweep
--
-- source_feed_item_id is the post itself, so "read the original" works on the Cargoes tab
-- exactly as it does for a mailed cargo. SET NULL, not CASCADE: that this cargo came off a
-- board outlives the copy of the board.
ALTER TABLE public.cargoes ADD COLUMN source_kind varchar(10) DEFAULT 'MANUAL' NOT NULL;

ALTER TABLE public.cargoes ADD COLUMN source_feed_item_id bigint;

ALTER TABLE public.cargoes
    ADD CONSTRAINT cargoes_source_feed_item_fkey FOREIGN KEY (source_feed_item_id)
        REFERENCES public.feed_items(id) ON DELETE SET NULL;

UPDATE public.cargoes SET source_kind = 'MAIL' WHERE from_mail;

ALTER TABLE public.cargoes DROP COLUMN from_mail;


-- ---------------------------------------------------------------------------
-- A position says who reported it; now it can say where that was read
-- ---------------------------------------------------------------------------
-- No source_kind here, deliberately. A position is already one row per report carrying its
-- reporter, and the reporter is what a broker reads - "open Adriatic, per AKANA BULK". Where
-- we were standing when we read it is provenance rather than fact, so it gets a link and not
-- a category. from_mail is left alone and stays false for a post, which is true.
ALTER TABLE public.vessel_positions ADD COLUMN source_feed_item_id bigint;

ALTER TABLE public.vessel_positions
    ADD CONSTRAINT vessel_positions_source_feed_item_fkey FOREIGN KEY (source_feed_item_id)
        REFERENCES public.feed_items(id) ON DELETE SET NULL;


-- The provenance tables get the same second column, for the same reason: a cargo merged out
-- of a board post and an email has one of each, and the drawer offers both to read.
ALTER TABLE public.cargo_sources ADD COLUMN feed_item_id bigint;

ALTER TABLE public.cargo_sources
    ADD CONSTRAINT cargo_sources_feed_item_fkey FOREIGN KEY (feed_item_id)
        REFERENCES public.feed_items(id) ON DELETE SET NULL;

ALTER TABLE public.intake_item_sources ADD COLUMN feed_item_id bigint;

ALTER TABLE public.intake_item_sources
    ADD CONSTRAINT intake_item_sources_feed_item_fkey FOREIGN KEY (feed_item_id)
        REFERENCES public.feed_items(id) ON DELETE SET NULL;


-- ---------------------------------------------------------------------------
-- A fourth question: the firm that signed it
-- ---------------------------------------------------------------------------
-- COMPANY_DETAILS. Every circular ends in a full style - the firm, its address, its site,
-- the people and their numbers - and the desk's own database is the thing that goes stale
-- while the market keeps sending it. The same rule the other three follow decides what is
-- asked and what is not: a firm nobody has heard of, or a record the signature disagrees
-- with, is a question; nothing new is silence.
--
-- The column is what makes one pending question per firm possible, which is what keeps this
-- kind from burying the queue: a broker signs every list he sends, and without it the queue
-- would carry one row per circular per firm. Unmatched firms suppress on subject_label, the
-- way NEW_VESSEL already does on a hull's name.
ALTER TABLE public.intake_items ADD COLUMN company_id bigint;

ALTER TABLE public.intake_items
    ADD CONSTRAINT intake_items_company_fkey FOREIGN KEY (company_id)
        REFERENCES public.companies(id) ON DELETE SET NULL;

CREATE INDEX ix_intake_items_company ON public.intake_items USING btree (company_id)
    WHERE company_id IS NOT NULL;
