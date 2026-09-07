-- The spellings this mailbox actually uses that the vocabulary could not answer to.
--
-- Found by labelling 167 emails into analysis_samples and putting every area the brokers
-- wrote through the same normalisation alias_key applies. Of 46 distinct spellings, 29 -
-- ninety of the hundred and ninety-seven mentions - resolved to nothing. That is not a
-- cosmetic gap: TradeAreaGraph.resolve() is what the timing check runs on, timing carries
-- the joint-heaviest weight in MatchScorer, and an area it cannot place makes the check
-- UNKNOWN. Half the positions on this desk were arriving un-timeable because nobody had
-- told the table that a Turk quoting "TURK MED" means the East Med.
--
-- Data, not shape, and additive: keyed on alias_key with ON CONFLICT DO NOTHING, so this
-- reads as "make sure these exist". alias_key strips case and punctuation, which is why
-- "TURK MED" and "TURKMED" are one row here and "W.C. GREECE" would have been one with
-- "WC GREECE".
--
-- Only spellings whose area is not in doubt are below. The ones left out are left out on
-- purpose and are listed at the foot of this file, because a wrong alias is worse than a
-- missing one: a missing alias makes a check UNKNOWN and costs points, an alias pointing at
-- the wrong water makes it PASS or FAIL and is believed.

INSERT INTO public.trade_area_aliases (trade_area_id, alias)
SELECT a.id, v.alias
FROM (VALUES
    -- The southern Turkish coast, and by far the biggest miss in the corpus - seventeen
    -- mentions of "TURK MED" alone, from the operators who send this desk most of its
    -- tonnage.
    ('EMED', 'Turk Med'),
    ('EMED', 'Turkish Med'),
    ('EMED', 'Turkish Mediterranean'),
    -- Alexandria and Damietta. "Egypt" unqualified is here too: this desk's Egyptian
    -- business is Med-side, and the Red Sea ports are written as such when they are meant.
    ('EMED', 'Egypt Med'),
    ('EMED', 'Egypt'),

    -- The Turkish Black Sea ports. Written TBS by the Turkish brokers and by nobody else,
    -- so there is no competing reading to lose to.
    ('BSEA', 'TBS'),
    ('BSEA', 'Turkish B.Sea'),
    ('BSEA', 'Turkish Black Sea'),
    ('BSEA', 'Bulgaria'),

    -- North African coast. All four are quoted as load or discharge ranges rather than as
    -- countries; the area is the same water in every case.
    ('NAFR', 'Tunisia'),
    ('NAFR', 'Morocco'),
    ('NAFR', 'Algeria'),
    ('NAFR', 'Libya'),

    -- Spain's Mediterranean coast, which the market writes four ways and V10 already knew
    -- as "SPAIN MED".
    ('WMED', 'Spain Mediterranean'),
    ('WMED', 'Spanish Med'),

    -- Italy's Tyrrhenian side. V10 has E.Italy and EC Italy pointing at the Adriatic; this
    -- is the other coast, and it is the Central Med rather than the West.
    ('CMED', 'WC Italy'),
    ('CMED', 'West Coast Italy'),

    ('ADR', 'Adriatic Sea'),
    ('ADR', 'North Adriatic'),

    ('AEG', 'Agean Sea'),          -- the spelling as it arrives, missing an 'e'
    ('AEG', 'Aegean Sea East'),
    ('AEG', 'EC Greece'),          -- Greece's east coast is the Aegean

    ('UKIE', 'South UK'),
    ('UKIE', 'S.UK'),

    ('IBER', 'North Spain'),       -- V10 has "N.Spain"; this is the same water spelled out

    ('SCAN', 'Denmark'),
    ('BALT', 'Finland')
) AS v(code, alias)
JOIN public.trade_areas a ON a.code = v.code
ON CONFLICT (alias_key) DO NOTHING;

-- Deliberately not added, and why:
--
--   NORTH EUROPE (3)  - reads as the Continent to one broker and as the whole of
--                       Continent + Baltic + Scandinavia to another. Three mentions is not
--                       enough to settle it, and the wrong answer would put ships a
--                       thousand miles off.
--   W.C. GREECE (1)   - the Ionian, which V10 deliberately does not model: "whether the
--                       Ionian belongs to the Central Med or to the Adriatic approach" is
--                       named there as an argument nobody wins.
--   MOZAMBIQUE (3)    - East Africa is not in the vocabulary at all. An alias needs an area
--                       to point at, and inventing one for three mentions is the wrong way
--                       round; add the area first if this trade continues.
--   MALTA (5)         - a port, not an area, and it is already in the ports table. It
--                       appears in the corpus's openArea because a circular wrote it where
--                       an area belongs, which is a fact about the email rather than about
--                       the vocabulary.
--   SPAIN (1)         - Atlantic Spain and Med Spain are different tonnage. Unqualified, it
--                       cannot be placed.
