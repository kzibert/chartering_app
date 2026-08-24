-- Three columns the contact imports kept arriving with and having nowhere to put.
--
-- A contacts export names a company's country, its website, and — for every phone —
-- whether the number is a switchboard, a mobile or a fax. All three were dropped on the
-- floor, because nothing here could hold them.


-- The company's country. Sits beside city_name, which has always been a plain string
-- rather than a lookup: cities in this data arrive spelled however the source spelled
-- them ("ATASEHIR/ Istanbul"), and a foreign key would mean inventing a canonical row for
-- each variant before the import could store anything at all. Country has the same
-- problem in milder form - "Turkey", "Türkiye", "TR" - so it is stored the same way.
--
-- Not an address. There is deliberately still no street or postcode: nothing in this app
-- posts anything, and a half-filled address block invites the reader to trust it as one.
-- City and country are what a chartering desk actually uses - which port range a company
-- sits in, and whether they are somewhere sanctions or a time zone make awkward.
ALTER TABLE public.companies ADD COLUMN country character varying(100);


-- The company's own site. 255 to match name; long enough for a path nobody needed.
--
-- Stored bare ("fednav.com"), not as a URL, because that is how it arrives and because
-- the scheme is the one part of it that is never informative. The UI adds https:// when
-- it makes a link.
ALTER TABLE public.companies ADD COLUMN website character varying(255);


-- What kind of line this is: Work, Mobile, Direct, Fax, Home, Other.
--
-- Free text rather than a check constraint or an enum. The vocabulary belongs to whoever
-- exported the file - one source says Mobile, the next says Cell, a third says Business -
-- and a constraint would turn every unfamiliar word into a failed import rather than a
-- label somebody can read. The importer normalises the ones it recognises and passes
-- anything else through.
--
-- Worth having despite that looseness, because two of the flags already here depend on
-- knowing it. has_whatsapp is only ever true of a mobile, and a Fax flagged working is a
-- number nobody will ever answer. Until now the distinction lived only in the exporter's
-- own formatting - "Work,+32.3.821.13.35,Mobile,+32.475.89.02.67" is one cell, and
-- splitting it threw the labels away with the commas.
--
-- Applies to phones. An email has no equivalent - "work email" is not a property of the
-- address, it is a guess about the person - so email rows leave it null rather than
-- carrying a label that means nothing.
ALTER TABLE public.contacts ADD COLUMN label character varying(20);
