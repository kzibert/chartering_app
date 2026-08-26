-- The details a charterer asks about before anything else, and that this schema had no
-- room for.
--
-- Every one of these is taken from the position lists this mailbox already receives. A
-- typical line reads
--
--     MV LOIRE RIVER  DWCC 6100 MTS AT 5,06M DRAFT
--     PANAMA FLAG, SID, BOX, BLT'07 ... 3HO/3HA  GR/BL 9467,8 CBM
--
-- and another
--
--     PAIVI  3200 cc  177,000 cbft = MOROCCO 7/9   imo-timber-grain ftd
--
-- Draft, deadweight, capacity and build year already have columns. Gear, holds, hatches
-- and the three fittings did not, and they are precisely the ones that decide whether a
-- cargo can be offered at all: a gearless ship cannot work a berth with no shore cranes,
-- and a hull that is not grain-fitted cannot lift grain however well the tonnage fits.
--
-- ALL OF THEM ARE NULLABLE, AND NULL MEANS "NOT ON FILE". This is the same distinction the
-- existing figures make by storing 0 for an unknown capacity, and it matters more here
-- because these are booleans: false would say "she is not geared", and there is no honest
-- way to say that about four thousand rows nobody has checked. Match reads a null as a
-- question to raise, never as a no.


ALTER TABLE public.vessels
    -- The queryable form. Filled by hand or by the parser when a list actually says so.
    ADD COLUMN geared boolean,

    -- What the list said, kept verbatim. "2x30T CRANES", "3 x 12,5 t derricks", "GEARLESS",
    -- "cranes fitted grabs 2x6cbm" - a column of enumerated crane types would have to
    -- discard most of that, and the discarded half is what a charterer reads.
    ADD COLUMN gear_description character varying(160),

    ADD COLUMN holds smallint,
    ADD COLUMN hatches smallint,

    -- The three fittings, and they are three separate facts rather than one list because
    -- circulars negate them one at a time: "imo-timber-not grain ftd" is a real line, and
    -- it is the "not grain" that decides whether a wheat cargo can be offered.
    ADD COLUMN grain_fitted boolean,
    ADD COLUMN timber_fitted boolean,
    ADD COLUMN imo_fitted boolean,

    -- Free text, because the class societies do not agree on one scale: 1A, 1A Super, E3,
    -- Ice Class II and "ice class - no" all appear in this trade's mail.
    ADD COLUMN ice_class character varying(20);


-- Only the two that a search actually narrows on. Gear is the first question asked of any
-- fleet list; the fittings are read once a candidate is already in hand, and indexing a
-- mostly-null boolean to save that is an index that costs more to keep than it returns.
CREATE INDEX ix_vessels_geared ON public.vessels USING btree (geared) WHERE geared IS NOT NULL;
CREATE INDEX ix_vessels_holds ON public.vessels USING btree (holds) WHERE holds IS NOT NULL;
