-- The vocabulary itself: the areas, what brokers call them, how far apart they are, and
-- which of the 283 ports on file sit in each.
--
-- Separate from V9 because it is data rather than shape. A correction to a ballast figure
-- or a missing alias is then a small migration of its own, not an edit to the file that
-- built the tables -- which Flyway would refuse anyway.
--
-- Everything below is keyed by code and written ON CONFLICT DO NOTHING, so this file reads
-- as "make sure these exist" rather than "these did not exist".


-- ---------------------------------------------------------------------------
-- The areas
-- ---------------------------------------------------------------------------
-- Two levels, and no more. Deeper nesting invites arguments nobody wins -- whether the
-- Ionian belongs to the Central Med or to the Adriatic approach -- and matching does not
-- need the answer, because the distance table already says those two are a day and a half
-- apart.
--
-- sort_order groups them the way the market quotes them: this desk's home waters first
-- (Black Sea outward through the Med), then Northern Europe, then everything reached by
-- ballasting out of the region.
INSERT INTO public.trade_areas (code, name, sort_order, notes) VALUES
    ('BSEA',   'Black Sea',                  10, NULL),
    ('MRM',    'Sea of Marmara',             20,
        'Top level rather than under either neighbour. Brokers quote Marmara on its own, and she is one day from the Black Sea and one from the Aegean - which the distance table says better than a parent could.'),
    ('MED',    'Mediterranean',              30, NULL),
    ('CONT',   'Continent',                  40, NULL),
    ('BALT',   'Baltic',                     41, NULL),
    ('UKIE',   'UK & Ireland',               42, NULL),
    ('SCAN',   'Scandinavia',                43, NULL),
    ('IBER',   'Iberia Atlantic & Biscay',   44, NULL),
    ('WAFR',   'West Africa',                50, NULL),
    ('RSEA',   'Red Sea',                    60, NULL),
    ('PGULF',  'Persian Gulf',               61, NULL),
    ('CASP',   'Caspian Sea',                62,
        'Landlocked, and deliberately given no distances at all. A vessel open in the Caspian cannot ballast to a Med cargo on any number of days, and an invented figure would say she could.'),
    ('ISC',    'Indian Subcontinent',        63, NULL),
    ('SEASIA', 'South East Asia',            70, NULL),
    ('FEAST',  'Far East',                   71, NULL),
    ('USEC',   'US East Coast',              80, NULL),
    ('USG',    'US Gulf',                    81, NULL),
    ('ECSA',   'East Coast South America',   82, NULL)
ON CONFLICT (code) DO NOTHING;

-- The children, inserted second so the parents have ids to point at.
INSERT INTO public.trade_areas (code, name, parent_id, sort_order, notes)
SELECT v.code, v.name, p.id, v.sort_order, v.notes
FROM (VALUES
    ('AZOV',   'Sea of Azov',                'BSEA', 11, NULL),
    ('DANUBE', 'Danube',                     'BSEA', 12, NULL),
    ('DNPR',   'Dnieper river',              'BSEA', 13, NULL),
    ('AEG',    'Aegean',                     'MED',  31, NULL),
    ('EMED',   'East Mediterranean',         'MED',  32, NULL),
    ('CMED',   'Central Mediterranean',      'MED',  33, NULL),
    ('ADR',    'Adriatic',                   'MED',  34, NULL),
    ('WMED',   'West Mediterranean',         'MED',  35, NULL),
    ('NAFR',   'North Africa',               'MED',  36,
        'Libya and Atlantic Morocco, which no longitude bucket fits: Libya sits between the Central and East Med, and Morocco is on the far side of Gibraltar. Kept as an area because the mail writes "N.Africa" as a position and the parser has to resolve it to something.')
) AS v(code, name, parent_code, sort_order, notes)
JOIN public.trade_areas p ON p.code = v.parent_code
ON CONFLICT (code) DO NOTHING;


-- ---------------------------------------------------------------------------
-- What brokers call them
-- ---------------------------------------------------------------------------
-- Every code is an alias for itself, so nothing below has to repeat one.
INSERT INTO public.trade_area_aliases (trade_area_id, alias)
SELECT id, code FROM public.trade_areas
ON CONFLICT (alias_key) DO NOTHING;

-- The rest, taken from the way this mailbox actually writes them. Only spellings that
-- survive normalisation as something new are worth listing: alias_key strips punctuation
-- and case, so "E.MED", "E MED", "E-MED" and "e.med" are one row, not four.
INSERT INTO public.trade_area_aliases (trade_area_id, alias)
SELECT a.id, v.alias
FROM (VALUES
    ('BSEA',   'Black Sea'), ('BSEA', 'Blk Sea'),
    ('AZOV',   'Sea of Azov'), ('AZOV', 'Azov Sea'),
    ('DANUBE', 'Danub'), ('DANUBE', 'Lower Danube'), ('DANUBE', 'Danube River'),
    ('DNPR',   'Dnepr'), ('DNPR', 'Dnieper'), ('DNPR', 'Dnepr River Ports'),
    ('MRM',    'Marmara'), ('MRM', 'Sea of Marmara'), ('MRM', 'Marmara Sea'),
    ('MED',    'Mediterranean'), ('MED', 'Medit'),
    ('AEG',    'Aegean'), ('AEG', 'Aegean Sea'), ('AEG', 'E.Aegean'), ('AEG', 'W.Aegean'),
    ('EMED',   'E.Med'), ('EMED', 'East Med'), ('EMED', 'Eastern Med'),
               ('EMED', 'East Mediterranean'), ('EMED', 'Levant'),
    ('CMED',   'C.Med'), ('CMED', 'Central Med'), ('CMED', 'Cent Med'),
    ('ADR',    'Adriatic'), ('ADR', 'Adria'), ('ADR', 'Upper Adriatic'),
               ('ADR', 'EC Italy'), ('ADR', 'E.Italy'),
    -- "W.ITALY" is the reason this table exists at all. It is written as a position every
    -- week, it means the Tyrrhenian, and no amount of string comparison would ever have
    -- got from there to the West Med.
    ('WMED',   'W.Med'), ('WMED', 'West Med'), ('WMED', 'Western Med'),
               ('WMED', 'West Mediterranean'), ('WMED', 'Spain Med'),
               ('WMED', 'W.Italy'), ('WMED', 'West Italy'),
    ('NAFR',   'N.Africa'), ('NAFR', 'North Africa'), ('NAFR', 'Nth Africa'),
    ('CONT',   'Continent'), ('CONT', 'N.Cont'), ('CONT', 'North Continent'),
               ('CONT', 'ARA'), ('CONT', 'ARAG'), ('CONT', 'HH Range'),
               ('CONT', 'Le Havre Hamburg'),
    ('BALT',   'Baltic'), ('BALT', 'Baltic Sea'), ('BALT', 'E.Baltic'),
    ('UKIE',   'UK'), ('UKIE', 'Ireland'), ('UKIE', 'UK Ireland'), ('UKIE', 'ECUK'),
               ('UKIE', 'WCUK'), ('UKIE', 'East Coast UK'), ('UKIE', 'Bristol Channel'),
    ('SCAN',   'Scandinavia'), ('SCAN', 'Norway'), ('SCAN', 'Sweden'),
    ('IBER',   'Iberia'), ('IBER', 'Portugal'), ('IBER', 'Biscay'),
               ('IBER', 'Bay of Biscay'), ('IBER', 'N.Spain'),
    ('WAFR',   'West Africa'), ('WAFR', 'W.Africa'), ('WAFR', 'WAF'),
    ('RSEA',   'Red Sea'),
    ('PGULF',  'Persian Gulf'), ('PGULF', 'Arabian Gulf'), ('PGULF', 'AG'), ('PGULF', 'PG'),
    ('CASP',   'Caspian'), ('CASP', 'Caspian Sea'),
    ('ISC',    'India'), ('ISC', 'Indian Subcontinent'), ('ISC', 'WCI'), ('ISC', 'ECI'),
               ('ISC', 'Pakistan'),
    ('SEASIA', 'SE Asia'), ('SEASIA', 'South East Asia'),
    ('FEAST',  'Far East'), ('FEAST', 'China'), ('FEAST', 'Japan'), ('FEAST', 'Korea'),
    ('USEC',   'US East Coast'), ('USEC', 'USAC'), ('USEC', 'USNH'),
    ('USG',    'US Gulf'), ('USG', 'Gulf of Mexico'), ('USG', 'Mississippi'),
    ('ECSA',   'Brazil'), ('ECSA', 'Argentina'), ('ECSA', 'River Plate'),
               ('ECSA', 'East Coast South America')
) AS v(code, alias)
JOIN public.trade_areas a ON a.code = v.code
ON CONFLICT (alias_key) DO NOTHING;


-- ---------------------------------------------------------------------------
-- How far apart they are, in ballast days
-- ---------------------------------------------------------------------------
-- Round numbers a broker would recognise, not distances anyone computed. They exist to
-- answer one question -- can she make the laycan -- and half a day of precision on a figure
-- that moves with weather and speed would be false confidence.
--
-- Written once per pair and inserted in both directions, because the alternative is a
-- lookup that has to try the pair both ways round, and a table where somebody eventually
-- enters BSEA->AEG as 2 and AEG->BSEA as 3.
INSERT INTO public.trade_area_distances (from_area_id, to_area_id, ballast_days)
SELECT f.id, t.id, v.days
FROM (VALUES
    ('BSEA','MRM',1.0),   ('BSEA','AZOV',1.0),  ('BSEA','DANUBE',1.0), ('BSEA','DNPR',1.0),
    ('BSEA','AEG',2.0),   ('BSEA','EMED',3.0),  ('BSEA','CMED',4.0),   ('BSEA','ADR',4.5),
    ('BSEA','WMED',6.0),
    ('AZOV','MRM',2.0),   ('AZOV','DANUBE',2.0),('AZOV','DNPR',1.5),   ('AZOV','AEG',3.0),
    ('AZOV','EMED',4.0),
    ('DANUBE','MRM',2.0), ('DANUBE','DNPR',1.5),('DANUBE','AEG',3.0),  ('DANUBE','EMED',4.0),
    ('DNPR','MRM',2.0),   ('DNPR','AEG',3.0),
    ('MRM','AEG',1.0),    ('MRM','EMED',2.0),   ('MRM','CMED',3.0),    ('MRM','ADR',3.5),
    ('MRM','WMED',5.0),   ('MRM','NAFR',2.5),
    ('AEG','EMED',1.5),   ('AEG','CMED',2.0),   ('AEG','ADR',2.5),     ('AEG','WMED',4.0),
    ('AEG','NAFR',2.0),
    ('EMED','CMED',2.5),  ('EMED','ADR',3.0),   ('EMED','WMED',4.5),   ('EMED','NAFR',1.5),
    ('EMED','RSEA',2.0),
    ('CMED','ADR',1.5),   ('CMED','WMED',2.0),  ('CMED','NAFR',1.0),
    ('ADR','WMED',3.0),   ('ADR','NAFR',2.0),
    ('WMED','NAFR',1.5),  ('WMED','IBER',2.0),  ('WMED','CONT',4.0),   ('WMED','UKIE',4.0),
    ('WMED','WAFR',4.0),
    ('IBER','CONT',2.5),  ('IBER','UKIE',2.0),  ('IBER','NAFR',1.5),   ('IBER','WAFR',3.5),
    ('CONT','UKIE',1.0),  ('CONT','BALT',2.0),  ('CONT','SCAN',1.5),   ('CONT','USEC',9.0),
    ('UKIE','BALT',3.0),  ('UKIE','SCAN',2.0),
    ('BALT','SCAN',1.5),
    ('RSEA','PGULF',4.0), ('RSEA','ISC',7.0),
    ('PGULF','ISC',4.0),
    ('ISC','SEASIA',6.0), ('ISC','FEAST',10.0),
    ('SEASIA','FEAST',4.0),
    ('USG','USEC',4.0),   ('USG','ECSA',12.0),  ('USEC','ECSA',11.0)
) AS v(from_code, to_code, days)
JOIN public.trade_areas f ON f.code = v.from_code
JOIN public.trade_areas t ON t.code = v.to_code
ON CONFLICT DO NOTHING;

-- The mirror image of every row just written.
INSERT INTO public.trade_area_distances (from_area_id, to_area_id, ballast_days)
SELECT d.to_area_id, d.from_area_id, d.ballast_days
FROM public.trade_area_distances d
ON CONFLICT DO NOTHING;


-- ---------------------------------------------------------------------------
-- Which port is where
-- ---------------------------------------------------------------------------
-- Matched on the name exactly as the ports table spells it, case-insensitively. Only ports
-- this file is sure about: a port left without an area still works everywhere it worked
-- before, whereas a Turkish port filed in the wrong sea sends a vessel the wrong way and
-- looks authoritative doing it.
--
-- The Turkish coast is the part worth checking if this ever reads oddly. Eregli and
-- Erdemir are Black Sea, not Marmara; Canakkale is the Dardanelles and files under
-- Marmara; Toros is Mersin and files under the East Med; Igsas, Yilport, Poliport,
-- Kroman and Martas are all Izmit bay under names that do not say so.
UPDATE public.ports p
SET trade_area_id = a.id
FROM (VALUES
    -- Sea of Azov and the Don
    ('Rostov','AZOV'), ('Rostov BB','AZOV'), ('Azov','AZOV'), ('Mariupol','AZOV'),
    ('Yeisk','AZOV'), ('Temryuk','AZOV'), ('Taganrog','AZOV'), ('Semikarakorsk','AZOV'),
    ('Bagaevskaya','AZOV'), ('Starocherkask','AZOV'), ('Berdiyansk','AZOV'),
    ('Kalach-na-Donu','AZOV'),

    -- Danube
    ('Izmail','DANUBE'), ('Reni','DANUBE'), ('Kilya','DANUBE'), ('Braila','DANUBE'),
    ('Galati','DANUBE'), ('Giurgiulesti','DANUBE'), ('Sulina channel','DANUBE'),
    ('Bystroye channel','DANUBE'), ('Medgidia','DANUBE'), ('Ruse','DANUBE'),
    ('Kelheim','DANUBE'),

    -- Dnieper
    ('Kiev','DNPR'), ('Zaporozhye','DNPR'), ('Dnepropetrovsk','DNPR'), ('Dnipro','DNPR'),
    ('Zolotaya Balka','DNPR'), ('Demos Kamenske, Dnipro river','DNPR'),
    ('Svitlovodskyi River Terminal','DNPR'), ('Novaya Kakhovka','DNPR'),

    -- Black Sea
    ('Novorossiysk','BSEA'), ('Tuapse','BSEA'), ('Sochi','BSEA'), ('Constanza','BSEA'),
    ('Varna','BSEA'), ('Burgas','BSEA'), ('Balchik','BSEA'), ('Poti','BSEA'),
    ('Batumi','BSEA'), ('Samsun','BSEA'), ('Trabzon','BSEA'), ('Rize','BSEA'),
    ('Hopa','BSEA'), ('Giresun','BSEA'), ('Unye','BSEA'), ('Fatsa','BSEA'),
    ('Zonguldak','BSEA'), ('Eregli','BSEA'), ('Erdemir','BSEA'), ('Bartin','BSEA'),
    ('Karasu','BSEA'), ('Eren-port','BSEA'), ('Odessa','BSEA'), ('Chornomorsk','BSEA'),
    ('Illyichevsk','BSEA'), ('Yuzhny','BSEA'), ('Nikolaev','BSEA'),
    ('Nikolaev Ocean','BSEA'), ('Nikolaev River port','BSEA'), ('Nika Tera','BSEA'),
    ('Olvia','BSEA'), ('Kherson','BSEA'), ('Ochakov','BSEA'), ('Scadovsk','BSEA'),
    ('Belgorod-Dnestrovskiy','BSEA'), ('Oktyabrsk','BSEA'), ('Dnepro-Bugsky','BSEA'),
    ('Kerch','BSEA'), ('Sevastopol','BSEA'), ('Theodosia','BSEA'), ('Kavkaz','BSEA'),
    ('Kavkaz IPL','BSEA'), ('Taman','BSEA'), ('Bosporus','BSEA'),

    -- Marmara
    ('Istanbul','MRM'), ('Ambarli','MRM'), ('Haydarpasa','MRM'), ('Diliskelesi','MRM'),
    ('Izmit','MRM'), ('Derince','MRM'), ('Gemlik','MRM'), ('Bandirma','MRM'),
    ('Tekirdag','MRM'), ('Marmara','MRM'), ('Karabiga','MRM'), ('Hereke','MRM'),
    ('Gebze','MRM'), ('Yarimca','MRM'), ('Dilovasi','MRM'), ('Tavsancil','MRM'),
    ('Anadolu','MRM'), ('Yilport','MRM'), ('Kroman','MRM'), ('Poliport','MRM'),
    ('Icdas','MRM'), ('Martas','MRM'), ('Yesilyurt','MRM'), ('Igsas','MRM'),
    ('Zeyport','MRM'), ('Canakkale','MRM'),

    -- Aegean
    ('Izmir','AEG'), ('Aliaga','AEG'), ('Nemrut','AEG'), ('Gulluk','AEG'),
    ('Dikili','AEG'), ('Piraeus','AEG'), ('Eleusis','AEG'), ('Thessaloniki','AEG'),
    ('Volos','AEG'), ('Alexandroupolis','AEG'), ('Nea Karvali','AEG'), ('Rodos','AEG'),
    ('Achladi','AEG'), ('Psachna','AEG'), ('Drepano','AEG'), ('Kalamaki','AEG'),

    -- East Mediterranean
    ('Mersin','EMED'), ('Iskenderun','EMED'), ('Isdemir','EMED'), ('Antalya','EMED'),
    ('Toros','EMED'), ('Alexandria','EMED'), ('El Dekheila','EMED'), ('Damietta','EMED'),
    ('Port Said','EMED'), ('Abu Qir','EMED'), ('Beirut','EMED'), ('Selaata','EMED'),
    ('Tripoli (Lebanon)','EMED'), ('Lattakia','EMED'), ('Tartus','EMED'),
    ('Ashdod','EMED'), ('Haifa','EMED'), ('Limassol','EMED'), ('Larnaca','EMED'),
    ('Vassiliko','EMED'), ('Famagusta','EMED'),

    -- Central Mediterranean
    ('Malta','CMED'), ('Augusta','CMED'), ('Catania','CMED'), ('Termini Imerese','CMED'),
    ('Sicily port','CMED'), ('Crotone','CMED'), ('Patras','CMED'), ('Sfax','CMED'),
    ('Sousse','CMED'), ('Gabes','CMED'), ('Bizerte','CMED'),

    -- Adriatic
    ('Venice','ADR'), ('Marghera','ADR'), ('Chioggia','ADR'), ('Ravenna','ADR'),
    ('Monfalcone','ADR'), ('Trieste','ADR'), ('Koper','ADR'), ('Rjieka','ADR'),
    ('Split','ADR'), ('Ploce','ADR'), ('Bar','ADR'), ('Durres','ADR'), ('Ortona','ADR'),
    ('Vasto','ADR'), ('Manfredonia','ADR'), ('Barletta','ADR'),

    -- West Mediterranean
    ('Genoa','WMED'), ('Savona','WMED'), ('Naples','WMED'), ('Salerno','WMED'),
    ('Piombino','WMED'), ('Sardinia','WMED'), ('Oristano','WMED'), ('Barselona','WMED'),
    ('Tarragona','WMED'), ('Valencia','WMED'), ('Sagunto','WMED'), ('Castellon','WMED'),
    ('Cartagena','WMED'), ('Fos Sur Mer','WMED'), ('Sete','WMED'), ('Algiers','WMED'),
    ('Oran','WMED'), ('Arzew','WMED'), ('Mostaganem','WMED'), ('Annaba','WMED'),
    ('Skikda','WMED'), ('Nador','WMED'),

    -- North Africa: Libya, and Morocco's Atlantic coast
    ('Misurata','NAFR'), ('Tripoli (Libya)','NAFR'), ('Benghazi','NAFR'),
    ('Tobruk','NAFR'), ('Derna','NAFR'), ('Al Khums','NAFR'), ('Az Zawiyah','NAFR'),
    ('Mellitah','NAFR'), ('Abu Kammash','NAFR'), ('Marsa el Braga','NAFR'),
    ('Casablanca','NAFR'), ('Jorf Lasfar','NAFR'), ('Safi','NAFR'), ('Laayoune','NAFR'),

    -- Iberia Atlantic and Biscay
    ('Lisboa','IBER'), ('Setubal','IBER'), ('Aveiro','IBER'), ('Figueira da Foz','IBER'),
    ('Huelva','IBER'), ('Cadiz','IBER'), ('Santander','IBER'), ('Lorient','IBER'),

    -- Continent, UK, Baltic
    ('Antwerp','CONT'), ('Hamburg','CONT'), ('GHENT','CONT'), ('Dunkirk','CONT'),
    ('Willebroek','CONT'), ('Cork','UKIE'),
    ('St.Petersburg','BALT'), ('Visotskiy','BALT'), ('Tallinn','BALT'), ('Riga','BALT'),
    ('Klaipeda','BALT'), ('Gdynia','BALT'),

    -- Caspian
    ('Aktau','CASP'), ('Baku','CASP'), ('Turkmenbashi','CASP'), ('Astrakhan','CASP'),
    ('Makhachkala','CASP'), ('Bandar Anzali','CASP'), ('Amirabad','CASP'),
    ('Bekdash','CASP'), ('Hovsan','CASP'), ('Solianka','CASP'),

    -- Red Sea, Gulf, Indian subcontinent, east of there
    ('Aqaba','RSEA'), ('Agabah','RSEA'), ('Jeddah','RSEA'), ('Sokhna','RSEA'),
    ('Suez channel','RSEA'), ('Sudan','RSEA'), ('Djibouti','RSEA'),
    ('Bandar Abbas','PGULF'), ('Bandar Imam Khomeini (BIK)','PGULF'), ('Bushehr','PGULF'),
    ('Dubai','PGULF'), ('Mina Saqr','PGULF'), ('Chabahar','PGULF'),
    ('Vizag','ISC'), ('Chennai','ISC'), ('Kakinada','ISC'), ('Mumbai','ISC'),
    ('Mangalore','ISC'), ('Cochin','ISC'), ('Vizhinjam','ISC'), ('Beypore','ISC'),
    ('Azhikal','ISC'), ('Porbandar','ISC'), ('Karachi','ISC'), ('Chittagong','ISC'),
    ('Mongla','ISC'), ('Payra','ISC'), ('Male, Maldives','ISC'),
    ('Bontang','SEASIA'), ('Belitung','SEASIA'),
    ('Ningbo','FEAST'), ('Shanghai','FEAST'), ('Qingdao','FEAST'), ('Shenzhen','FEAST'),
    ('Hong Kong','FEAST'), ('Vostochny','FEAST'), ('Vladivostok','FEAST'),
    ('Peyongtaek','FEAST'),
    ('New York','USEC'), ('Buchanan','WAFR')
) AS v(port_name, area_code)
JOIN public.trade_areas a ON a.code = v.area_code
WHERE lower(p.name) = lower(v.port_name)
  AND p.trade_area_id IS NULL;
