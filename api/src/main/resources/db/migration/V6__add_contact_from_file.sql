-- Where an address came from, third answer.
--
-- is_legacy tells a row carried over from the old database apart from one entered since,
-- and that used to be the whole question. It cannot tell a row somebody typed apart from
-- one that arrived in a contacts file, because the importer sets is_legacy false on
-- purpose: an address met at a trade fair last month is new data whichever door it came
-- in through. Reviewing what an import brought in needs that distinction, so it is
-- recorded here rather than guessed at afterwards.
--
-- A second boolean rather than one source column replacing is_legacy: the two facts are
-- independent - nothing in the app writes a legacy row, so a file import can never be one
-- - and folding them together would rewrite the vessel and company searches, which ask
-- the same question about tables this import never touches.

ALTER TABLE public.contacts
    ADD COLUMN from_file boolean DEFAULT false NOT NULL;

-- Imports that already ran, recovered from the change log while it still holds them: the
-- importer names its change set (ChangeContext.describe("Contact import")), so every row
-- it created says so. This is the one moment that will ever be true - a log is a log and
-- may be pruned - and from here on the column is the record.
UPDATE public.contacts c
SET from_file = true
WHERE EXISTS (
    SELECT 1 FROM public.data_changes d
    WHERE d.entity_type = 'contact'
      AND d.entity_id = c.id
      AND d.operation = 'create'
      AND d.context = 'Contact import');
