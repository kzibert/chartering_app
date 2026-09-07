-- The sea network itself, where the berths are, and what brokers call them.
--
-- Data rather than shape, split from V18 the way V10 was split from V9 and for the same
-- reason: a corrected coordinate, a missing alias or a leg somebody wants to add is then a
-- small migration of its own rather than an edit to the file that built the tables -- which
-- Flyway would refuse anyway.
--
-- Everything is keyed by a natural name and written ON CONFLICT DO NOTHING or as an UPDATE
-- guarded on the column still being null, so this file reads as "make sure these are so"
-- rather than "these were not".
--
-- ON ACCURACY, honestly, because a number on a screen is believed. The waypoint coordinates
-- are read off a chart to about a tenth of a degree, which is six miles; the legs are great
-- circles between them, which is what a ship sails when the water is open.
--
-- Checked against published sailing distances on fifteen routes this desk actually quotes --
-- Odessa/Istanbul, Odessa/Genoa, Constanza/Alexandria, Novorossiysk/Antwerp, Odessa/Jeddah,
-- Antwerp/New York and the rest -- it comes out between 4% short and 9% long, most of them
-- inside 4%. The long side is where a coastal passage is modelled as two straight legs
-- through an offing, and long is the safer direction to be wrong in: it puts a ship on the
-- list a few hours later than she would really arrive rather than a few hours earlier.
--
-- That error is small against the thing it feeds. The miles are turned into days at a speed
-- nobody has measured for the hull in question, to be compared with a laycan quoted as a
-- three-day spread. What this is emphatically not is a distance table to fix on. It is here
-- to stop a Rostov ship being offered for a Constanza laycan four days out, and for that it
-- is an order of magnitude better than the trade area both berths share.


-- ---------------------------------------------------------------------------
-- 1. The waypoints
-- ---------------------------------------------------------------------------
-- Two kinds of row, and the difference is the point of the table.
--
-- Most are open water: a point in the middle of something a ship crosses in a straight
-- line. They carry no delay and exist only so the legs either side of them are legs whose
-- water is genuinely open.
--
-- The rest are the narrows every route through a region has to pass, and they carry
-- delay_hours -- the time a ship loses there over and above sailing through. That figure is
-- what a distance table cannot hold and what this desk lives with daily: a bulker waiting
-- off Kavak for a northbound Bosphorus convoy is not sailing, and a laycan calculation that
-- leaves the wait out is a day optimistic on every Black Sea passage there is.
INSERT INTO public.sea_waypoints (code, name, latitude, longitude, delay_hours, notes) VALUES
    -- Azov, the Kerch Strait and the rivers
    ('AZOV',        'Sea of Azov',                 46.10,  36.60,  0, NULL),
    ('KERCH',       'Kerch Strait',                45.30,  36.62,  6,
        'Pilotage and a queue, and a 8m draft limit that this network does not model - a ship that fits the Azov ports fits the strait.'),
    ('SULINA',      'Sulina, Danube mouth',        45.16,  29.75, 12,
        'The bar and the river pilot. Everything above it is a river passage, which is why the Danube ports carry a gateway_nm override rather than a straight line to here.'),
    ('DNIEPERBUG',  'Dnieper-Bug estuary',         46.55,  31.55,  6, NULL),

    -- The Black Sea proper
    ('BSEANW',      'North-west Black Sea',        45.80,  30.90,  0, NULL),
    ('BSEAW',       'West Black Sea',              43.90,  29.50,  0, NULL),
    ('BSEAC',       'Central Black Sea',           43.30,  33.50,  0, NULL),
    ('BSEAE',       'East Black Sea',              43.60,  38.20,  0, NULL),
    ('BSEASE',      'South-east Black Sea',        41.80,  40.00,  0, NULL),
    ('BSEAS',       'South Black Sea',             41.90,  35.50,  0, NULL),
    ('BSEASW',      'South-west Black Sea',        41.80,  31.50,  0, NULL),

    -- The Turkish Straits
    ('BOSN',        'Bosphorus north',             41.25,  29.13, 10,
        'The convoy wait. Highly variable and often longer; ten hours is the figure a broker would allow for without being asked.'),
    ('BOSS',        'Bosphorus south',             41.00,  28.98,  0, NULL),
    ('MARMARA',     'Sea of Marmara',              40.70,  27.90,  0, NULL),
    ('DARD',        'Dardanelles',                 40.15,  26.40,  4, NULL),

    -- The Aegean
    ('AEGN',        'North Aegean',                39.30,  25.00,  0, NULL),
    ('AEGE',        'East Aegean',                 38.30,  25.80,  0, NULL),
    ('AEGW',        'West Aegean',                 36.40,  23.20,  0, NULL),
    ('AEGS',        'South Aegean',                36.20,  27.00,  0, NULL),

    -- The eastern basin
    ('CRETES',      'South of Crete',              34.30,  24.50,  0, NULL),
    ('TURKSOUTH',   'Off the Turkish south coast', 36.10,  32.20,  0,
        'The Anamur offing. Without it the only way from the Aegean to Mersin is round the west of Cyprus, which is two hundred miles of detour on a range this desk loads from every week.'),
    ('EMEDX',       'East Mediterranean, off Cyprus', 34.50, 32.00, 0, NULL),
    ('LEVANT',      'Levant coast',                33.50,  34.50,  0, NULL),
    ('EGYPTW',      'Off Alexandria',              31.60,  29.30,  0, NULL),
    ('CYRENAICA',   'Off Cyrenaica',               33.30,  20.00,  0,
        'North of the Benghazi bulge, which is the whole reason this node exists: the straight line from Egypt to the Gulf of Sirte runs through Libya.'),
    ('PSAID',       'Port Said, Suez north',       31.30,  32.35, 18,
        'Convoy and canal transit. Charged here rather than split with the south end, so a ship calling at Port Said itself pays nothing for a canal she does not use.'),
    ('SUEZS',       'Suez south',                  29.90,  32.55,  0, NULL),

    -- The central and western basins
    ('CMEDX',       'Central Mediterranean, off Malta', 35.60, 15.00, 0, NULL),
    ('SICILYCH',    'Sicily Channel',              37.20,  11.50,  0, NULL),
    ('GABES',       'Gulf of Gabes',               34.30,  11.50,  0, NULL),
    ('SIRTE',       'Gulf of Sirte',               32.80,  15.50,  0, NULL),
    ('MESSINA',     'Strait of Messina',           38.20,  15.60,  2, NULL),
    ('IONIAN',      'Ionian Sea',                  37.50,  18.50,  0, NULL),
    ('OTRANTO',     'Strait of Otranto',           40.10,  18.90,  0, NULL),
    ('ADRS',        'South Adriatic',              41.80,  17.50,  0, NULL),
    ('ADRC',        'Central Adriatic',            43.20,  15.20,  0, NULL),
    ('ADRN',        'North Adriatic',              44.90,  13.30,  0, NULL),
    ('TYRRS',       'South Tyrrhenian',            39.80,  13.50,  0, NULL),
    ('TYRRN',       'North Tyrrhenian',            42.50,  10.30,  0, NULL),
    ('LIGURE',      'Ligurian Sea',                43.60,   8.60,  0, NULL),
    ('GULFLION',    'Gulf of Lion',                42.60,   4.60,  0, NULL),
    ('SARDW',       'West of Sardinia',            39.80,   7.50,  0, NULL),
    ('BALEAR',      'Balearic Sea',                38.80,   3.50,  0, NULL),
    ('ALBORAN',     'Alboran Sea',                 36.10,  -3.50,  0, NULL),
    ('GIBE',        'Gibraltar east',              36.00,  -5.35,  0, NULL),
    ('GIBW',        'Gibraltar west',              36.10,  -6.30,  0, NULL),

    -- Atlantic Europe
    ('CADIZ',       'Gulf of Cadiz',               36.60,  -7.20,  0, NULL),
    ('SVINCENT',    'Cape St Vincent',             36.90,  -9.30,  0, NULL),
    ('PORTUGAL',    'Off Lisbon',                  38.50,  -9.80,  0, NULL),
    ('FINISTERRE',  'Cape Finisterre',             43.20,  -9.60,  0, NULL),
    ('BISCAY',      'Bay of Biscay',               45.50,  -5.50,  0, NULL),
    ('USHANT',      'Ushant',                      48.45,  -5.60,  0, NULL),
    ('CELTIC',      'Celtic Sea',                  50.50,  -7.50,  0, NULL),
    ('IRISHS',      'Irish Sea',                   53.50,  -5.20,  0, NULL),
    ('CHANNELW',    'Western Channel',             49.80,  -3.00,  0, NULL),
    ('DOVER',       'Dover Strait',                51.00,   1.50,  2, NULL),
    ('NSEAS',       'Southern North Sea',          53.00,   3.50,  0, NULL),
    ('GERMANBIGHT', 'German Bight',                54.00,   8.00,  0, NULL),
    ('NSEAN',       'North Sea, off Jutland',      56.80,   7.00,  0,
        'Here because the straight line from the southern North Sea to the Skaw runs across Jutland. Rounding Denmark is a real leg and this is the point it turns at.'),
    ('NORWAYS',     'Off south-west Norway',       58.50,   4.50,  0, NULL),
    ('SKAW',        'The Skaw',                    57.85,  10.70,  0, NULL),
    ('KATTEGAT',    'Kattegat',                    56.60,  11.50,  0, NULL),
    ('BALTSW',      'South-west Baltic',           55.00,  14.00,  0, NULL),
    ('BALTC',       'Central Baltic',              57.00,  19.00,  0, NULL),
    ('RIGA',        'Gulf of Riga',                57.60,  23.30,  0, NULL),
    ('GULFFIN',     'Gulf of Finland',             59.80,  25.00,  0, NULL),

    -- Africa and the Atlantic
    ('CASA',        'Off Casablanca',              33.40,  -8.30,  0, NULL),
    ('CANARY',      'Off the Canaries',            28.00, -15.50,  0, NULL),
    ('DAKAR',       'Off Dakar',                   14.50, -18.00,  0, NULL),
    ('GUINEA',      'Gulf of Guinea',               3.50,   4.00,  0, NULL),
    ('GOODHOPE',    'Cape of Good Hope',          -35.00,  19.00,  0, NULL),

    -- Red Sea, the Gulf and east of there
    ('REDSEAN',     'North Red Sea',               26.50,  35.00,  0, NULL),
    ('REDSEAS',     'South Red Sea',               16.00,  41.00,  0, NULL),
    ('BABEL',       'Bab el Mandeb',               12.60,  43.40,  0, NULL),
    ('ADEN',        'Gulf of Aden',                12.50,  47.50,  0, NULL),
    ('ARABSEA',     'Arabian Sea',                 17.00,  62.00,  0, NULL),
    ('HORMUZ',      'Strait of Hormuz',            26.55,  56.40,  4, NULL),
    ('PGULFX',      'Persian Gulf',                27.50,  52.00,  0, NULL),
    ('INDIAW',      'Off the west coast of India', 18.50,  71.50,  0, NULL),
    ('INDIAS',      'Off Sri Lanka',                5.50,  80.50,  0, NULL),
    ('BENGAL',      'Bay of Bengal',               16.00,  88.00,  0, NULL),
    ('MALACCA',     'Malacca Strait',               2.50, 100.50,  3, NULL),
    ('SINGAPORE',   'Singapore Strait',             1.20, 104.00,  3, NULL),
    ('SCSEA',       'South China Sea',             14.00, 113.00,  0, NULL),
    ('ECHINA',      'East China Sea',              30.00, 124.00,  0, NULL),
    ('KOREA',       'Yellow Sea',                  35.50, 124.00,  0, NULL),
    ('JAPANS',      'Off Japan',                   34.00, 137.00,  0, NULL),

    -- The Americas
    ('MIDATL',      'Mid North Atlantic',          40.00, -40.00,  0, NULL),
    ('USECN',       'Off New York',                40.30, -73.20,  0, NULL),
    ('HATTERAS',    'Off Cape Hatteras',           34.50, -75.00,  0, NULL),
    ('FLORIDA',     'Florida Straits',             24.50, -80.50,  0, NULL),
    ('USGX',        'Gulf of Mexico',              27.50, -89.00,  0, NULL),
    ('CARIB',       'Caribbean',                   15.00, -66.00,  0, NULL),
    ('RECIFE',      'Off Recife',                  -8.50, -34.00,  0, NULL),
    ('SANTOS',      'Off Santos',                 -24.50, -45.50,  0, NULL),
    ('PLATE',       'River Plate approaches',     -35.50, -55.50,  0, NULL),

    -- And the one that goes nowhere
    ('CASPIAN',     'Caspian Sea',                 41.50,  51.00,  0,
        'Deliberately joined to nothing. A vessel here cannot ballast to a Med cargo in any number of days, and the honest answer to "how far" is that there is no route - which is exactly what a node with no legs produces. The same reasoning left the Caspian out of trade_area_distances.')
ON CONFLICT (code) DO NOTHING;


-- ---------------------------------------------------------------------------
-- 2. The legs
-- ---------------------------------------------------------------------------
-- Putting a row here asserts that the water between these two points is open, so the great
-- circle between their coordinates is the distance and no number needs writing down. The
-- override column is for the legs that are not straight, and they are exactly the ones a
-- glance at a chart identifies: a strait that winds, a canal, a passage that has to follow
-- a coast.
--
-- Written once per pair; the mirror is inserted from this table at the foot of the section,
-- the same way trade_area_distances does it.
INSERT INTO public.sea_legs (from_waypoint_id, to_waypoint_id, distance_nm, notes)
SELECT f.id, t.id, v.nm, v.note
FROM (VALUES
    -- The Turkish Straits, every one of them an override, because none of them is a
    -- straight line and all of them are on the route out of this desk's home range.
    ('BOSN','BOSS',        17.0, 'The Bosphorus itself: seventeen miles of river bends.'),
    ('BOSS','MARMARA',     45.0, NULL),
    ('MARMARA','DARD',     70.0, 'Across the Marmara and down the Dardanelles.'),

    -- The Black Sea. Two coastal legs are overridden: the Turkish north coast bulges at
    -- Sinop and the great circle would cut across it.
    ('AZOV','KERCH',       NULL, NULL),
    ('KERCH','BSEAE',      NULL, NULL),
    ('KERCH','BSEAC',      NULL, NULL),
    ('BSEAC','BSEANW',     NULL, 'West of Crimea. The direct line from the Kerch Strait to the north-west corner crosses the peninsula, which is why there is no such leg.'),
    ('BSEAC','BSEAW',      NULL, NULL),
    ('BSEAC','BSEAE',      NULL, NULL),
    ('BSEAC','BSEAS',      NULL, NULL),
    ('BSEAC','BSEASW',     NULL, NULL),
    ('BSEAC','BOSN',       NULL, NULL),
    ('BSEANW','BSEAW',     NULL, NULL),
    ('BSEANW','SULINA',    NULL, NULL),
    ('BSEANW','DNIEPERBUG',NULL, NULL),
    ('BSEAW','BOSN',       NULL, NULL),
    ('BSEAW','BSEASW',     NULL, NULL),
    ('BSEAE','BSEASE',     NULL, NULL),
    ('BSEAS','BSEASE',     NULL, NULL),
    ('BSEAS','BOSN',      340.0, 'The Turkish north coast, round Sinop. The straight line runs inland.'),
    ('BSEAS','BSEASW',    200.0, 'Coastal, for the same reason.'),
    ('BSEASW','BOSN',      NULL, NULL),

    -- Aegean and the eastern basin
    ('DARD','AEGN',        NULL, NULL),
    ('AEGN','AEGE',        NULL, NULL),
    ('AEGN','AEGW',        NULL, NULL),
    ('AEGN','AEGS',        NULL, NULL),
    ('AEGE','AEGS',        NULL, NULL),
    ('AEGE','AEGW',        NULL, NULL),
    ('AEGS','AEGW',        NULL, NULL),
    ('AEGS','EMEDX',       NULL, NULL),
    ('AEGS','TURKSOUTH',   NULL, NULL),
    ('AEGS','CRETES',      NULL, NULL),
    ('TURKSOUTH','EMEDX',  NULL, NULL),
    ('AEGW','CRETES',      NULL, NULL),
    ('AEGW','IONIAN',     250.0, 'Round the Peloponnese. The straight line clips it.'),
    ('CRETES','EMEDX',     NULL, NULL),
    ('CRETES','IONIAN',    NULL, NULL),
    ('CRETES','CMEDX',     NULL, NULL),
    ('CRETES','EGYPTW',    NULL, NULL),
    ('CRETES','CYRENAICA', NULL, NULL),
    ('EMEDX','LEVANT',     NULL, NULL),
    ('EMEDX','PSAID',      NULL, NULL),
    ('EMEDX','EGYPTW',     NULL, NULL),
    ('LEVANT','PSAID',     NULL, NULL),
    ('EGYPTW','PSAID',     NULL, NULL),
    ('EGYPTW','CYRENAICA', NULL, NULL),
    ('PSAID','SUEZS',     100.0, 'The canal and its approaches.'),

    -- The central and western basins
    ('CYRENAICA','SIRTE',  NULL, NULL),
    ('CYRENAICA','CMEDX',  NULL, NULL),
    ('SIRTE','CMEDX',      NULL, NULL),
    ('SIRTE','GABES',      NULL, NULL),
    ('GABES','CMEDX',      NULL, NULL),
    ('GABES','SICILYCH',   NULL, NULL),
    ('CMEDX','IONIAN',     NULL, NULL),
    ('CMEDX','MESSINA',    NULL, NULL),
    ('CMEDX','SICILYCH',   NULL, NULL),
    ('IONIAN','OTRANTO',   NULL, NULL),
    ('IONIAN','MESSINA',   NULL, NULL),
    ('OTRANTO','ADRS',     NULL, NULL),
    ('ADRS','ADRC',        NULL, NULL),
    ('ADRC','ADRN',        NULL, NULL),
    ('MESSINA','TYRRS',    NULL, NULL),
    ('TYRRS','TYRRN',      NULL, NULL),
    ('TYRRS','SICILYCH',   NULL, NULL),
    ('TYRRN','LIGURE',     NULL, NULL),
    ('LIGURE','SARDW',     NULL, NULL),
    ('LIGURE','BALEAR',    NULL, NULL),
    ('LIGURE','GULFLION',  NULL, NULL),
    ('GULFLION','SARDW',   NULL, NULL),
    ('GULFLION','BALEAR',  NULL, NULL),
    ('SARDW','BALEAR',     NULL, NULL),
    ('SARDW','SICILYCH',  260.0, 'South of Sardinia, round Cape Teulada.'),
    ('SICILYCH','BALEAR',  NULL, NULL),
    ('SICILYCH','ALBORAN', NULL, 'The Algerian coast lane.'),
    ('BALEAR','ALBORAN',   NULL, NULL),
    ('ALBORAN','GIBE',     NULL, NULL),
    ('GIBE','GIBW',        25.0, 'The strait.'),

    -- Atlantic Europe
    ('GIBW','CADIZ',       NULL, NULL),
    ('GIBW','CASA',        NULL, NULL),
    ('CADIZ','CASA',       NULL, NULL),
    ('CADIZ','SVINCENT',   NULL, NULL),
    ('SVINCENT','PORTUGAL',NULL, NULL),
    ('SVINCENT','CANARY',  NULL, NULL),
    ('PORTUGAL','FINISTERRE', NULL, NULL),
    ('FINISTERRE','BISCAY',NULL, NULL),
    ('FINISTERRE','CELTIC',NULL, NULL),
    ('BISCAY','USHANT',    NULL, NULL),
    ('USHANT','CHANNELW',  NULL, NULL),
    ('USHANT','CELTIC',    NULL, NULL),
    ('USHANT','MIDATL',    NULL, NULL),
    ('CELTIC','CHANNELW',  NULL, NULL),
    ('CELTIC','IRISHS',    NULL, NULL),
    ('CHANNELW','DOVER',  200.0, 'Up the Channel.'),
    ('DOVER','NSEAS',      NULL, NULL),
    ('NSEAS','GERMANBIGHT',NULL, NULL),
    ('NSEAS','NSEAN',      NULL, NULL),
    ('GERMANBIGHT','NSEAN',NULL, NULL),
    ('NSEAN','NORWAYS',    NULL, NULL),
    ('NSEAN','SKAW',      175.0, 'Up the Danish west coast and round the tip.'),
    ('SKAW','KATTEGAT',    NULL, NULL),
    ('KATTEGAT','BALTSW', 130.0, 'Through the Belts.'),
    ('BALTSW','BALTC',     NULL, NULL),
    ('BALTC','RIGA',       NULL, NULL),
    ('BALTC','GULFFIN',    NULL, NULL),

    -- Africa and the South Atlantic
    ('CASA','CANARY',      NULL, NULL),
    ('CANARY','DAKAR',     NULL, NULL),
    ('CANARY','RECIFE',    NULL, NULL),
    ('DAKAR','GUINEA',     NULL, NULL),
    ('DAKAR','RECIFE',     NULL, NULL),
    ('GUINEA','GOODHOPE',  NULL, NULL),

    -- Suez, the Gulf and east
    ('SUEZS','REDSEAN',    NULL, NULL),
    ('REDSEAN','REDSEAS',  NULL, NULL),
    ('REDSEAS','BABEL',    NULL, NULL),
    ('BABEL','ADEN',      120.0, 'Out of the strait and along the Yemeni coast.'),
    ('ADEN','ARABSEA',     NULL, NULL),
    ('ARABSEA','HORMUZ',   NULL, NULL),
    ('ARABSEA','INDIAW',   NULL, NULL),
    ('ARABSEA','INDIAS',   NULL, NULL),
    ('HORMUZ','PGULFX',   220.0, 'Up the Gulf.'),
    ('INDIAW','INDIAS',    NULL, NULL),
    ('INDIAS','BENGAL',    NULL, NULL),
    ('INDIAS','MALACCA',   NULL, NULL),
    ('BENGAL','MALACCA',   NULL, NULL),
    ('MALACCA','SINGAPORE',300.0, 'Down the strait.'),
    ('SINGAPORE','SCSEA',  NULL, NULL),
    ('SCSEA','ECHINA',     NULL, NULL),
    ('ECHINA','KOREA',     NULL, NULL),
    ('ECHINA','JAPANS',    NULL, NULL),
    ('KOREA','JAPANS',     NULL, NULL),

    -- The Americas
    ('MIDATL','USECN',     NULL, NULL),
    ('USECN','HATTERAS',   NULL, NULL),
    ('HATTERAS','FLORIDA', NULL, NULL),
    ('FLORIDA','USGX',    550.0, 'Round Florida and across the Gulf.'),
    ('FLORIDA','CARIB',    NULL, NULL),
    ('CARIB','RECIFE',     NULL, NULL),
    ('RECIFE','SANTOS',    NULL, NULL),
    ('SANTOS','PLATE',     NULL, NULL)
) AS v(from_code, to_code, nm, note)
JOIN public.sea_waypoints f ON f.code = v.from_code
JOIN public.sea_waypoints t ON t.code = v.to_code
ON CONFLICT DO NOTHING;

-- The mirror image of every leg just written.
INSERT INTO public.sea_legs (from_waypoint_id, to_waypoint_id, distance_nm, notes)
SELECT l.to_waypoint_id, l.from_waypoint_id, l.distance_nm, l.notes
FROM public.sea_legs l
ON CONFLICT DO NOTHING;


-- ---------------------------------------------------------------------------
-- 3. Where the berths are
-- ---------------------------------------------------------------------------
-- Matched on the name exactly as the ports table spells it, case-insensitively, the same
-- way V10 placed them in their trade areas -- and guarded on latitude still being null, so
-- a coordinate somebody has since corrected by hand is not overwritten.
--
-- gateway_nm is filled only where the straight line from the berth to its waypoint is not
-- the distance. That is every river port and nothing else: Rostov is 250 miles up the Don
-- and across the Azov, Izmail is 50 miles up the Danube, Kiev is most of the way across
-- Ukraine. A coordinate pair cannot say any of that.
--
-- Ports left out are left out on purpose. Kelheim is on the Bavarian Danube and no seagoing
-- ship reaches it; Bontang and Belitung are in Indonesian waters this network does not
-- model; a handful of rows ("Sicily port", "Drepano", "Kalamaki") name something too vaguely
-- to place. All of them keep working exactly as they do today, on the area table.
UPDATE public.ports p
SET latitude = v.lat,
    longitude = v.lon,
    country = v.country,
    gateway_waypoint_id = w.id,
    gateway_nm = v.gateway_nm
FROM (VALUES
    -- Azov, the Don, and the Kerch Strait
    ('Rostov',                     'RU', 47.22,  39.72, 'KERCH',      250.0),
    ('Rostov BB',                  'RU', 47.22,  39.72, 'KERCH',      250.0),
    ('Azov',                       'RU', 47.11,  39.42, 'KERCH',      235.0),
    ('Semikarakorsk',              'RU', 47.52,  40.81, 'KERCH',      300.0),
    ('Bagaevskaya',                'RU', 47.32,  40.39, 'KERCH',      280.0),
    ('Starocherkask',              'RU', 47.24,  40.06, 'KERCH',      265.0),
    ('Kalach-na-Donu',             'RU', 48.68,  43.53, 'KERCH',      480.0),
    ('Taganrog',                   'RU', 47.21,  38.93, 'AZOV',       105.0),
    ('Yeisk',                      'RU', 46.71,  38.28, 'AZOV',        80.0),
    ('Temryuk',                    'RU', 45.30,  37.38, 'AZOV',        35.0),
    ('Mariupol',                   'UA', 47.09,  37.55, 'AZOV',        60.0),
    ('Berdiyansk',                 'UA', 46.75,  36.80, 'AZOV',        55.0),
    ('Kerch',                      'UA', 45.35,  36.47, 'KERCH',       NULL),
    ('Kavkaz',                     'RU', 45.35,  36.68, 'KERCH',       NULL),
    ('Kavkaz IPL',                 'RU', 45.35,  36.68, 'KERCH',       NULL),
    ('Taman',                      'RU', 45.20,  36.70, 'KERCH',       NULL),

    -- The Danube
    ('Sulina channel',             'RO', 45.16,  29.66, 'SULINA',       5.0),
    ('Bystroye channel',           'UA', 45.30,  29.60, 'SULINA',      15.0),
    ('Kilya',                      'UA', 45.45,  29.27, 'SULINA',      30.0),
    ('Izmail',                     'UA', 45.35,  28.84, 'SULINA',      50.0),
    ('Reni',                       'UA', 45.45,  28.28, 'SULINA',      70.0),
    ('Giurgiulesti',               'MD', 45.47,  28.20, 'SULINA',      80.0),
    ('Galati',                     'RO', 45.43,  28.03, 'SULINA',      85.0),
    ('Braila',                     'RO', 45.27,  27.96, 'SULINA',      95.0),
    ('Ruse',                       'BG', 43.85,  25.97, 'SULINA',     260.0),
    ('Medgidia',                   'RO', 44.25,  28.28, 'BSEAW',       40.0),

    -- The Dnieper and the Bug
    ('Ochakov',                    'UA', 46.61,  31.55, 'DNIEPERBUG',  10.0),
    ('Dnepro-Bugsky',              'UA', 46.70,  31.85, 'DNIEPERBUG',  30.0),
    ('Kherson',                    'UA', 46.63,  32.62, 'DNIEPERBUG',  40.0),
    ('Olvia',                      'UA', 46.86,  31.94, 'DNIEPERBUG',  40.0),
    ('Oktyabrsk',                  'UA', 46.86,  31.94, 'DNIEPERBUG',  40.0),
    ('Nikolaev',                   'UA', 46.97,  31.99, 'DNIEPERBUG',  45.0),
    ('Nikolaev Ocean',             'UA', 46.94,  31.98, 'DNIEPERBUG',  45.0),
    ('Nikolaev River port',        'UA', 46.97,  32.00, 'DNIEPERBUG',  45.0),
    ('Nika Tera',                  'UA', 46.94,  31.96, 'DNIEPERBUG',  45.0),
    ('Novaya Kakhovka',            'UA', 46.75,  33.37, 'DNIEPERBUG',  85.0),
    ('Zolotaya Balka',             'UA', 47.13,  33.53, 'DNIEPERBUG', 110.0),
    ('Zaporozhye',                 'UA', 47.84,  35.14, 'DNIEPERBUG', 230.0),
    ('Dnepropetrovsk',             'UA', 48.46,  35.05, 'DNIEPERBUG', 280.0),
    ('Dnipro',                     'UA', 48.46,  35.05, 'DNIEPERBUG', 280.0),
    ('Demos Kamenske, Dnipro river','UA',48.52,  34.62, 'DNIEPERBUG', 295.0),
    ('Svitlovodskyi River Terminal','UA',49.06,  33.24, 'DNIEPERBUG', 360.0),
    ('Kiev',                       'UA', 50.45,  30.52, 'DNIEPERBUG', 480.0),

    -- The Black Sea
    ('Odessa',                     'UA', 46.48,  30.73, 'BSEANW',      NULL),
    ('Chornomorsk',                'UA', 46.30,  30.66, 'BSEANW',      NULL),
    ('Illyichevsk',                'UA', 46.30,  30.66, 'BSEANW',      NULL),
    ('Yuzhny',                     'UA', 46.62,  31.02, 'BSEANW',      NULL),
    ('Belgorod-Dnestrovskiy',      'UA', 46.19,  30.35, 'BSEANW',      NULL),
    ('Scadovsk',                   'UA', 46.12,  32.92, 'BSEANW',      NULL),
    ('Sevastopol',                 'UA', 44.62,  33.53, 'BSEAC',       NULL),
    ('Theodosia',                  'UA', 45.03,  35.38, 'BSEAC',       NULL),
    ('Constanza',                  'RO', 44.17,  28.65, 'BSEAW',       NULL),
    ('Varna',                      'BG', 43.19,  27.92, 'BSEAW',       NULL),
    ('Burgas',                     'BG', 42.49,  27.48, 'BSEAW',       NULL),
    ('Balchik',                    'BG', 43.40,  28.17, 'BSEAW',       NULL),
    ('Novorossiysk',               'RU', 44.72,  37.78, 'BSEAE',       NULL),
    ('Tuapse',                     'RU', 44.10,  39.07, 'BSEAE',       NULL),
    ('Sochi',                      'RU', 43.58,  39.72, 'BSEASE',      NULL),
    ('Poti',                       'GE', 42.15,  41.67, 'BSEASE',      NULL),
    ('Batumi',                     'GE', 41.64,  41.63, 'BSEASE',      NULL),
    ('Hopa',                       'TR', 41.42,  41.43, 'BSEASE',      NULL),
    ('Rize',                       'TR', 41.03,  40.52, 'BSEASE',      NULL),
    ('Trabzon',                    'TR', 41.00,  39.73, 'BSEASE',      NULL),
    ('Giresun',                    'TR', 40.92,  38.39, 'BSEASE',      NULL),
    ('Fatsa',                      'TR', 41.03,  37.50, 'BSEAS',       NULL),
    ('Unye',                       'TR', 41.13,  37.28, 'BSEAS',       NULL),
    ('Samsun',                     'TR', 41.30,  36.33, 'BSEAS',       NULL),
    ('Bartin',                     'TR', 41.70,  32.25, 'BSEASW',      NULL),
    ('Eren-port',                  'TR', 41.30,  32.20, 'BSEASW',      NULL),
    ('Eregli',                     'TR', 41.28,  31.42, 'BSEASW',      NULL),
    ('Erdemir',                    'TR', 41.28,  31.44, 'BSEASW',      NULL),
    ('Zonguldak',                  'TR', 41.45,  31.79, 'BSEASW',      NULL),
    ('Karasu',                     'TR', 41.11,  30.69, 'BSEASW',      NULL),
    ('Bosporus',                   'TR', 41.12,  29.06, 'BOSS',        NULL),

    -- The Marmara
    ('Istanbul',                   'TR', 41.02,  28.98, 'BOSS',        NULL),
    ('Haydarpasa',                 'TR', 40.99,  29.02, 'BOSS',        NULL),
    ('Ambarli',                    'TR', 40.97,  28.69, 'BOSS',        18.0),
    ('Tekirdag',                   'TR', 40.98,  27.51, 'MARMARA',     NULL),
    ('Marmara',                    'TR', 40.60,  27.57, 'MARMARA',     NULL),
    ('Bandirma',                   'TR', 40.35,  27.97, 'MARMARA',     NULL),
    ('Karabiga',                   'TR', 40.41,  27.30, 'MARMARA',     NULL),
    ('Gemlik',                     'TR', 40.43,  29.13, 'MARMARA',     NULL),
    ('Gebze',                      'TR', 40.79,  29.44, 'MARMARA',     NULL),
    ('Diliskelesi',                'TR', 40.77,  29.52, 'MARMARA',     NULL),
    ('Zeyport',                    'TR', 40.77,  29.52, 'MARMARA',     NULL),
    ('Dilovasi',                   'TR', 40.78,  29.53, 'MARMARA',     NULL),
    ('Anadolu',                    'TR', 40.77,  29.55, 'MARMARA',     NULL),
    ('Tavsancil',                  'TR', 40.77,  29.57, 'MARMARA',     NULL),
    ('Hereke',                     'TR', 40.79,  29.61, 'MARMARA',     NULL),
    ('Poliport',                   'TR', 40.77,  29.63, 'MARMARA',     NULL),
    ('Kroman',                     'TR', 40.77,  29.68, 'MARMARA',     NULL),
    ('Martas',                     'TR', 40.75,  29.70, 'MARMARA',     NULL),
    ('Yilport',                    'TR', 40.76,  29.75, 'MARMARA',     NULL),
    ('Yarimca',                    'TR', 40.76,  29.79, 'MARMARA',     NULL),
    ('Derince',                    'TR', 40.76,  29.83, 'MARMARA',     NULL),
    ('Igsas',                      'TR', 40.76,  29.88, 'MARMARA',     NULL),
    ('Izmit',                      'TR', 40.76,  29.92, 'MARMARA',     NULL),
    ('Canakkale',                  'TR', 40.15,  26.41, 'DARD',        NULL),
    ('Icdas',                      'TR', 40.35,  26.68, 'DARD',        20.0),
    ('Yesilyurt',                  'TR', 40.35,  26.70, 'DARD',        20.0),

    -- The Aegean
    ('Alexandroupolis',            'GR', 40.84,  25.88, 'AEGN',        NULL),
    ('Nea Karvali',                'GR', 40.95,  24.60, 'AEGN',        NULL),
    ('Thessaloniki',               'GR', 40.63,  22.94, 'AEGN',        NULL),
    ('Volos',                      'GR', 39.35,  22.94, 'AEGN',        NULL),
    ('Achladi',                    'GR', 38.90,  22.62, 'AEGN',        NULL),
    ('Psachna',                    'GR', 38.58,  23.63, 'AEGN',        NULL),
    ('Dikili',                     'TR', 39.07,  26.89, 'AEGE',        NULL),
    ('Aliaga',                     'TR', 38.80,  26.97, 'AEGE',        NULL),
    ('Nemrut',                     'TR', 38.77,  26.93, 'AEGE',        NULL),
    ('Izmir',                      'TR', 38.43,  27.14, 'AEGE',        NULL),
    ('Piraeus',                    'GR', 37.94,  23.63, 'AEGW',        NULL),
    ('Eleusis',                    'GR', 38.04,  23.53, 'AEGW',        NULL),
    ('Gulluk',                     'TR', 37.24,  27.60, 'AEGS',        NULL),
    ('Rodos',                      'GR', 36.45,  28.22, 'AEGS',        NULL),

    -- The eastern Mediterranean
    ('Antalya',                    'TR', 36.83,  30.61, 'TURKSOUTH',   NULL),
    ('Mersin',                     'TR', 36.80,  34.63, 'TURKSOUTH',   NULL),
    ('Toros',                      'TR', 36.78,  34.72, 'TURKSOUTH',   NULL),
    ('Iskenderun',                 'TR', 36.60,  36.17, 'EMEDX',       NULL),
    ('Isdemir',                    'TR', 36.68,  36.20, 'EMEDX',       NULL),
    ('Limassol',                   'CY', 34.65,  33.02, 'EMEDX',       NULL),
    ('Vassiliko',                  'CY', 34.71,  33.31, 'EMEDX',       NULL),
    ('Larnaca',                    'CY', 34.91,  33.65, 'EMEDX',       NULL),
    ('Famagusta',                  'CY', 35.12,  33.94, 'EMEDX',       NULL),
    ('Lattakia',                   'SY', 35.52,  35.78, 'LEVANT',      NULL),
    ('Tartus',                     'SY', 34.90,  35.87, 'LEVANT',      NULL),
    ('Tripoli (Lebanon)',          'LB', 34.45,  35.82, 'LEVANT',      NULL),
    ('Selaata',                    'LB', 34.28,  35.65, 'LEVANT',      NULL),
    ('Beirut',                     'LB', 33.90,  35.52, 'LEVANT',      NULL),
    ('Haifa',                      'IL', 32.82,  35.00, 'LEVANT',      NULL),
    ('Ashdod',                     'IL', 31.82,  34.63, 'LEVANT',      NULL),
    ('Port Said',                  'EG', 31.26,  32.30, 'PSAID',       NULL),
    ('Suez channel',               'EG', 30.50,  32.35, 'PSAID',       50.0),
    ('Damietta',                   'EG', 31.47,  31.76, 'PSAID',       40.0),
    ('Abu Qir',                    'EG', 31.32,  30.07, 'EGYPTW',      NULL),
    ('Alexandria',                 'EG', 31.19,  29.87, 'EGYPTW',      NULL),
    ('El Dekheila',                'EG', 31.13,  29.82, 'EGYPTW',      NULL),

    -- North Africa
    ('Tobruk',                     'LY', 32.08,  24.00, 'CYRENAICA',   NULL),
    ('Derna',                      'LY', 32.77,  22.64, 'CYRENAICA',   NULL),
    ('Benghazi',                   'LY', 32.12,  20.06, 'CYRENAICA',   NULL),
    ('Marsa el Braga',             'LY', 30.42,  19.58, 'SIRTE',       NULL),
    ('Misurata',                   'LY', 32.37,  15.22, 'SIRTE',       NULL),
    ('Al Khums',                   'LY', 32.65,  14.26, 'SIRTE',       NULL),
    ('Tripoli (Libya)',            'LY', 32.90,  13.19, 'SIRTE',       NULL),
    ('Az Zawiyah',                 'LY', 32.79,  12.72, 'SIRTE',       NULL),
    ('Mellitah',                   'LY', 33.25,  11.75, 'SIRTE',       NULL),
    ('Abu Kammash',                'LY', 33.07,  11.80, 'SIRTE',       NULL),
    ('Gabes',                      'TN', 33.89,  10.10, 'GABES',       NULL),
    ('Sfax',                       'TN', 34.73,  10.77, 'GABES',       NULL),
    ('Sousse',                     'TN', 35.83,  10.64, 'SICILYCH',    NULL),
    ('Bizerte',                    'TN', 37.27,   9.87, 'SICILYCH',    NULL),
    ('Casablanca',                 'MA', 33.60,  -7.62, 'CASA',        NULL),
    ('Jorf Lasfar',                'MA', 33.13,  -8.62, 'CASA',        NULL),
    ('Safi',                       'MA', 32.30,  -9.24, 'CASA',        NULL),
    ('Laayoune',                   'MA', 27.10, -13.42, 'CANARY',      NULL),

    -- The central Mediterranean and the Adriatic
    ('Malta',                      'MT', 35.89,  14.51, 'CMEDX',       NULL),
    ('Augusta',                    'IT', 37.20,  15.22, 'CMEDX',       NULL),
    ('Catania',                    'IT', 37.50,  15.10, 'CMEDX',       NULL),
    ('Termini Imerese',            'IT', 37.99,  13.70, 'MESSINA',     NULL),
    ('Crotone',                    'IT', 39.08,  17.13, 'IONIAN',      NULL),
    ('Patras',                     'GR', 38.24,  21.73, 'IONIAN',      NULL),
    ('Durres',                     'AL', 41.31,  19.45, 'ADRS',        NULL),
    ('Bar',                        'ME', 42.09,  19.09, 'ADRS',        NULL),
    ('Manfredonia',                'IT', 41.62,  15.92, 'ADRS',        NULL),
    ('Barletta',                   'IT', 41.32,  16.29, 'ADRS',        NULL),
    ('Ploce',                      'HR', 43.05,  17.43, 'ADRC',        NULL),
    ('Split',                      'HR', 43.50,  16.44, 'ADRC',        NULL),
    ('Vasto',                      'IT', 42.12,  14.71, 'ADRC',        NULL),
    ('Ortona',                     'IT', 42.35,  14.41, 'ADRC',        NULL),
    ('Ravenna',                    'IT', 44.48,  12.28, 'ADRN',        NULL),
    ('Chioggia',                   'IT', 45.22,  12.29, 'ADRN',        NULL),
    ('Venice',                     'IT', 45.43,  12.33, 'ADRN',        NULL),
    ('Marghera',                   'IT', 45.47,  12.25, 'ADRN',        NULL),
    ('Monfalcone',                 'IT', 45.79,  13.55, 'ADRN',        NULL),
    ('Trieste',                    'IT', 45.65,  13.75, 'ADRN',        NULL),
    ('Koper',                      'SI', 45.55,  13.73, 'ADRN',        NULL),
    ('Rjieka',                     'HR', 45.33,  14.44, 'ADRN',        65.0),

    -- The western Mediterranean
    ('Salerno',                    'IT', 40.67,  14.75, 'TYRRS',       NULL),
    ('Naples',                     'IT', 40.83,  14.27, 'TYRRS',       NULL),
    ('Piombino',                   'IT', 42.93,  10.53, 'TYRRN',       NULL),
    ('Genoa',                      'IT', 44.40,   8.90, 'LIGURE',      NULL),
    ('Savona',                     'IT', 44.30,   8.49, 'LIGURE',      NULL),
    ('Sardinia',                   'IT', 39.20,   9.12, 'SARDW',       NULL),
    ('Oristano',                   'IT', 39.89,   8.50, 'SARDW',       NULL),
    ('Fos Sur Mer',                'FR', 43.40,   4.87, 'GULFLION',    NULL),
    ('Sete',                       'FR', 43.40,   3.70, 'GULFLION',    NULL),
    ('Barselona',                  'ES', 41.35,   2.17, 'BALEAR',      NULL),
    ('Tarragona',                  'ES', 41.10,   1.22, 'BALEAR',      NULL),
    ('Castellon',                  'ES', 39.96,   0.02, 'BALEAR',      NULL),
    ('Sagunto',                    'ES', 39.63,  -0.21, 'BALEAR',      NULL),
    ('Valencia',                   'ES', 39.44,  -0.31, 'BALEAR',      NULL),
    ('Cartagena',                  'ES', 37.58,  -0.98, 'ALBORAN',     NULL),
    ('Annaba',                     'DZ', 36.90,   7.77, 'SICILYCH',    NULL),
    ('Skikda',                     'DZ', 36.89,   6.91, 'SICILYCH',    NULL),
    ('Algiers',                    'DZ', 36.77,   3.07, 'ALBORAN',     NULL),
    ('Mostaganem',                 'DZ', 35.94,   0.09, 'ALBORAN',     NULL),
    ('Arzew',                      'DZ', 35.85,  -0.30, 'ALBORAN',     NULL),
    ('Oran',                       'DZ', 35.71,  -0.63, 'ALBORAN',     NULL),
    ('Nador',                      'MA', 35.28,  -2.93, 'ALBORAN',     NULL),

    -- Atlantic Iberia and Biscay
    ('Huelva',                     'ES', 37.13,  -6.87, 'CADIZ',       NULL),
    ('Cadiz',                      'ES', 36.53,  -6.28, 'CADIZ',       NULL),
    ('Setubal',                    'PT', 38.50,  -8.90, 'PORTUGAL',    NULL),
    ('Lisboa',                     'PT', 38.70,  -9.15, 'PORTUGAL',    NULL),
    ('Figueira da Foz',            'PT', 40.15,  -8.87, 'PORTUGAL',    NULL),
    ('Aveiro',                     'PT', 40.64,  -8.75, 'PORTUGAL',    NULL),
    ('Santander',                  'ES', 43.45,  -3.79, 'BISCAY',      NULL),
    ('Lorient',                    'FR', 47.73,  -3.37, 'BISCAY',      NULL),

    -- The Continent, the UK and the Baltic
    ('Dunkirk',                    'FR', 51.05,   2.37, 'DOVER',       NULL),
    ('Antwerp',                    'BE', 51.25,   4.35, 'DOVER',       75.0),
    ('GHENT',                      'BE', 51.10,   3.75, 'DOVER',       85.0),
    ('Willebroek',                 'BE', 51.06,   4.36, 'DOVER',       85.0),
    ('Hamburg',                    'DE', 53.53,   9.95, 'GERMANBIGHT', 70.0),
    ('Cork',                       'IE', 51.85,  -8.30, 'CELTIC',      NULL),
    ('Gdynia',                     'PL', 54.53,  18.55, 'BALTC',       NULL),
    ('Klaipeda',                   'LT', 55.70,  21.13, 'BALTC',       NULL),
    ('Riga',                       'LV', 57.00,  24.05, 'RIGA',        60.0),
    ('Tallinn',                    'EE', 59.45,  24.75, 'GULFFIN',     NULL),
    ('Visotskiy',                  'RU', 60.63,  28.57, 'GULFFIN',    115.0),
    ('St.Petersburg',              'RU', 59.90,  30.25, 'GULFFIN',    160.0),

    -- The Red Sea, the Gulf and the subcontinent
    ('Sokhna',                     'EG', 29.60,  32.35, 'SUEZS',       NULL),
    ('Aqaba',                      'JO', 29.52,  35.00, 'REDSEAN',    130.0),
    ('Agabah',                     'JO', 29.52,  35.00, 'REDSEAN',    130.0),
    ('Jeddah',                     'SA', 21.48,  39.18, 'REDSEAS',     NULL),
    ('Sudan',                      'SD', 19.62,  37.22, 'REDSEAS',     NULL),
    ('Djibouti',                   'DJ', 11.60,  43.13, 'BABEL',       NULL),
    ('Bandar Abbas',               'IR', 27.15,  56.21, 'HORMUZ',      NULL),
    ('Mina Saqr',                  'AE', 25.98,  56.05, 'HORMUZ',      NULL),
    ('Dubai',                      'AE', 25.27,  55.28, 'HORMUZ',      75.0),
    ('Bushehr',                    'IR', 28.98,  50.83, 'PGULFX',      NULL),
    ('Bandar Imam Khomeini (BIK)', 'IR', 30.42,  49.08, 'PGULFX',     220.0),
    ('Chabahar',                   'IR', 25.28,  60.62, 'ARABSEA',     NULL),
    ('Karachi',                    'PK', 24.82,  66.97, 'ARABSEA',     NULL),
    ('Porbandar',                  'IN', 21.63,  69.60, 'INDIAW',      NULL),
    ('Mumbai',                     'IN', 18.94,  72.84, 'INDIAW',      NULL),
    ('Azhikal',                    'IN', 11.95,  75.32, 'INDIAW',      NULL),
    ('Mangalore',                  'IN', 12.92,  74.80, 'INDIAW',      NULL),
    ('Beypore',                    'IN', 11.17,  75.80, 'INDIAW',      NULL),
    ('Cochin',                     'IN',  9.97,  76.25, 'INDIAS',      NULL),
    ('Vizhinjam',                  'IN',  8.38,  77.00, 'INDIAS',      NULL),
    ('Chennai',                    'IN', 13.10,  80.30, 'INDIAS',      NULL),
    ('Male, Maldives',             'MV',  4.18,  73.51, 'INDIAS',      NULL),
    ('Vizag',                      'IN', 17.69,  83.28, 'BENGAL',      NULL),
    ('Kakinada',                   'IN', 16.95,  82.25, 'BENGAL',      NULL),
    ('Mongla',                     'BD', 22.48,  89.60, 'BENGAL',      NULL),
    ('Payra',                      'BD', 21.98,  90.30, 'BENGAL',      NULL),
    ('Chittagong',                 'BD', 22.30,  91.80, 'BENGAL',      NULL),

    -- The Far East, West Africa and the Americas
    ('Shenzhen',                   'CN', 22.55, 114.10, 'SCSEA',       NULL),
    ('Hong Kong',                  'HK', 22.30, 114.17, 'SCSEA',       NULL),
    ('Ningbo',                     'CN', 29.87, 121.55, 'ECHINA',      NULL),
    ('Shanghai',                   'CN', 31.23, 121.50, 'ECHINA',      NULL),
    ('Qingdao',                    'CN', 36.07, 120.32, 'KOREA',       NULL),
    ('Peyongtaek',                 'KR', 36.97, 126.82, 'KOREA',       NULL),
    ('Vladivostok',                'RU', 43.10, 131.90, 'JAPANS',      NULL),
    ('Vostochny',                  'RU', 42.75, 132.75, 'JAPANS',      NULL),
    ('Buchanan',                   'LR',  5.88, -10.05, 'GUINEA',      NULL),
    ('New York',                   'US', 40.70, -74.02, 'USECN',       NULL),

    -- The Caspian, whose gateway leads nowhere and is meant to
    ('Aktau',                      'KZ', 43.64,  51.20, 'CASPIAN',     NULL),
    ('Baku',                       'AZ', 40.38,  49.87, 'CASPIAN',     NULL),
    ('Hovsan',                     'AZ', 40.35,  50.07, 'CASPIAN',     NULL),
    ('Turkmenbashi',               'TM', 40.03,  52.97, 'CASPIAN',     NULL),
    ('Bekdash',                    'TM', 41.55,  52.58, 'CASPIAN',     NULL),
    ('Astrakhan',                  'RU', 46.35,  48.05, 'CASPIAN',     NULL),
    ('Solianka',                   'RU', 46.30,  48.00, 'CASPIAN',     NULL),
    ('Makhachkala',                'RU', 42.98,  47.51, 'CASPIAN',     NULL),
    ('Bandar Anzali',              'IR', 37.47,  49.46, 'CASPIAN',     NULL),
    ('Amirabad',                   'IR', 36.86,  53.38, 'CASPIAN',     NULL)
) AS v(port_name, country, lat, lon, gateway_code, gateway_nm)
JOIN public.sea_waypoints w ON w.code = v.gateway_code
WHERE lower(trim(p.name)) = lower(v.port_name)
  AND p.latitude IS NULL;


-- ---------------------------------------------------------------------------
-- 4. What brokers call them
-- ---------------------------------------------------------------------------
-- The same idea as the trade-area aliases and the same test for inclusion: a spelling earns
-- a row when it is one this mailbox actually receives and it does not resolve to a different
-- berth. Renames dominate, because the market keeps writing the old name for years -- a
-- circular arriving this week still says ILLICHIVSK for a port renamed in 2016, and
-- BOMBAY for one renamed in 1995.
--
-- What is deliberately absent is anything ambiguous. "Tripoli" is left out because two
-- Tripolis are on file, "Bar" and "Reni" because they are words, "Cartagena" because the
-- other one is in Colombia. In every case the resolver finds nothing, the words stay in
-- open_port_text, and the area is read out of them -- which is the coarse answer, and the
-- honest one.
INSERT INTO public.port_aliases (port_id, alias)
SELECT p.id, v.alias
FROM (VALUES
    -- The Black Sea and the rivers. The renames here are the whole reason this table exists.
    ('Chornomorsk', 'Illichivsk'), ('Chornomorsk', 'Ilyichevsk'), ('Chornomorsk', 'Ilichevsk'),
        ('Chornomorsk', 'Chernomorsk'), ('Chornomorsk', 'Ilyichyovsk'),
    ('Odessa', 'Odesa'),
    ('Yuzhny', 'Pivdennyi'), ('Yuzhny', 'Yuzhnyy'), ('Yuzhny', 'Port Yuzhny'),
    ('Nikolaev', 'Mykolaiv'), ('Nikolaev', 'Nikolayev'),
    ('Nikolaev Ocean', 'Mykolaiv Ocean'),
    ('Kherson', 'Cherson'),
    ('Theodosia', 'Feodosia'), ('Theodosia', 'Feodosiya'),
    ('Zaporozhye', 'Zaporizhzhia'),
    ('Dnepropetrovsk', 'Dnipropetrovsk'),
    ('Novorossiysk', 'Novorossiisk'), ('Novorossiysk', 'Novorossisk'),
    ('Constanza', 'Constantza'), ('Constanza', 'Constanta'), ('Constanza', 'Konstanza'),
    ('Rostov', 'Rostov-on-Don'), ('Rostov', 'Rostov na Donu'),
    ('Taganrog', 'Taganroag'),
    ('Yeisk', 'Yeysk'), ('Yeisk', 'Eysk'),
    ('Berdiyansk', 'Berdyansk'), ('Berdiyansk', 'Berdiansk'),
    ('Mariupol', 'Marioupol'),
    ('Izmail', 'Ismail'), ('Izmail', 'Izmayil'),
    ('Kilya', 'Kiliya'),
    ('Galati', 'Galatz'),
    ('Sulina channel', 'Sulina'),
    ('Giurgiulesti', 'Giurgiulesti Free Port'),

    -- Turkey
    ('Ambarli', 'Ambarli Port'),
    ('Izmit', 'Kocaeli'), ('Izmit', 'Izmit Bay'),
    ('Diliskelesi', 'Dil Iskelesi'), ('Diliskelesi', 'Dilskelesi'),
    ('Nemrut', 'Nemrut Bay'),
    ('Eregli', 'Karadeniz Eregli'), ('Eregli', 'K.Eregli'),
    ('Canakkale', 'Chanakkale'),
    ('Iskenderun', 'Iskenderoon'),
    ('Mersin', 'Icel'),
    ('Toros', 'Toros Gubre'), ('Toros', 'Toros Terminal'),
    ('Bandirma', 'Bandirma Port'),

    -- Greece and Cyprus
    ('Piraeus', 'Pireas'), ('Piraeus', 'Pireus'),
    ('Eleusis', 'Elefsina'), ('Eleusis', 'Elefsis'),
    ('Thessaloniki', 'Salonica'), ('Thessaloniki', 'Saloniki'), ('Thessaloniki', 'Thessalonica'),
    ('Alexandroupolis', 'Alexandroupoli'), ('Alexandroupolis', 'Dedeagac'),
    ('Nea Karvali', 'N.Karvali'),
    ('Rodos', 'Rhodes'),
    ('Limassol', 'Lemesos'),
    ('Vassiliko', 'Vasiliko'), ('Vassiliko', 'Vassilikos'),

    -- Egypt and the Levant
    ('El Dekheila', 'Dekheila'), ('El Dekheila', 'El Dekhelia'),
    ('Damietta', 'Damiette'), ('Damietta', 'Dumyat'),
    ('Port Said', 'P.Said'),
    ('Abu Qir', 'Abu Kir'), ('Abu Qir', 'Abukir'),
    ('Beirut', 'Beyrouth'),
    ('Lattakia', 'Latakia'), ('Lattakia', 'Lattakiya'),

    -- Italy, the Adriatic and the west
    ('Genoa', 'Genova'),
    ('Savona', 'Vado Ligure'), ('Savona', 'Savona Vado'),
    ('Naples', 'Napoli'),
    ('Venice', 'Venezia'),
    ('Marghera', 'Porto Marghera'),
    ('Rjieka', 'Rijeka'), ('Rjieka', 'Fiume'),
    ('Durres', 'Durazzo'),
    ('Termini Imerese', 'Termini'),
    -- The ports table spells this one wrong, and the market spells it right.
    ('Barselona', 'Barcelona'),
    ('Castellon', 'Castellon de la Plana'),
    ('Fos Sur Mer', 'Fos'),
    ('Algiers', 'Alger'), ('Algiers', 'Algier'),
    ('Nador', 'Beni Ansar'),
    ('Casablanca', 'Casa'),
    ('Jorf Lasfar', 'Jorf'),
    ('Misurata', 'Misrata'), ('Misurata', 'Misratah'),
    ('Tripoli (Libya)', 'Tripoli Libya'),
    ('Tripoli (Lebanon)', 'Tripoli Lebanon'),
    ('Benghazi', 'Bengasi'),
    ('Al Khums', 'Khoms'), ('Al Khums', 'Al Khoms'),
    ('Az Zawiyah', 'Zawia'), ('Az Zawiyah', 'Zawiya'),
    ('Marsa el Braga', 'Brega'), ('Marsa el Braga', 'Marsa Brega'),
    ('Bizerte', 'Bizerta'),

    -- Iberia, the Continent and the Baltic
    ('Lisboa', 'Lisbon'),
    ('Figueira da Foz', 'Figueira'),
    ('Antwerp', 'Antwerpen'), ('Antwerp', 'Anvers'),
    ('GHENT', 'Gent'), ('GHENT', 'Gand'),
    ('Dunkirk', 'Dunkerque'), ('Dunkirk', 'Dunquerque'),
    ('St.Petersburg', 'Saint Petersburg'), ('St.Petersburg', 'Petersburg'),
    ('Visotskiy', 'Vysotsk'), ('Visotskiy', 'Vysotskiy'),
    ('Tallinn', 'Muuga'),

    -- The Gulf, the subcontinent and the Far East
    ('Aqaba', 'Akaba'),
    ('Jeddah', 'Jiddah'), ('Jeddah', 'Jedda'),
    ('Sokhna', 'Ain Sokhna'), ('Sokhna', 'Ain Sukhna'),
    ('Bandar Abbas', 'Bandar Abbass'),
    ('Bandar Imam Khomeini (BIK)', 'BIK'), ('Bandar Imam Khomeini (BIK)', 'Bandar Imam'),
        ('Bandar Imam Khomeini (BIK)', 'Bandar Khomeini'),
    ('Dubai', 'Jebel Ali'),
    ('Mina Saqr', 'Ras Al Khaimah'),
    ('Vizag', 'Visakhapatnam'),
    ('Chennai', 'Madras'),
    ('Mumbai', 'Bombay'),
    ('Cochin', 'Kochi'),
    ('Chittagong', 'Chattogram'),
    ('Male, Maldives', 'Male'),
    ('Qingdao', 'Tsingtao'),
    ('Ningbo', 'Ningbo Zhoushan'),
    ('Shenzhen', 'Yantian'), ('Shenzhen', 'Shekou'),
    ('Vostochny', 'Vostochniy'),
    ('Peyongtaek', 'Pyeongtaek'), ('Peyongtaek', 'Pyongtaek'),
    ('Turkmenbashi', 'Krasnovodsk'),
    ('Bandar Anzali', 'Anzali'), ('Bandar Anzali', 'Bandar Enzeli')
) AS v(port_name, alias)
JOIN public.ports p ON lower(trim(p.name)) = lower(v.port_name)
ON CONFLICT (alias_key) DO NOTHING;
