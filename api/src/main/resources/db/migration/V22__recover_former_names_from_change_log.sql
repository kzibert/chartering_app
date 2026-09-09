-- The former names a rename should have filed and did not.
--
-- Accepting a rename on the Intake tab was supposed to keep the name the ship was losing: it is
-- the name this database has been finding her under, and a circular arriving next week still
-- uses it. It never did. The guard read "skip when the name being filed is her current name",
-- and the caller passed exactly her current name, so it compared the value against itself and
-- returned every single time. FWN SOLIDE became LADY VIOLETTA with the rename in the change log
-- and no former name anywhere; twenty-two hulls went through that, plus three renamed by hand on
-- the vessel form before that path learned to file one at all.
--
-- Recoverable only because data_changes exists. Every one of those renames is a row there with
-- the before and after values, which is the whole argument for the change log - a field that was
-- overwritten by mistake is still answerable months later. This reads them back.
--
-- The source says which path should have written it, so the recovered rows are
-- indistinguishable from the ones the fixed code will write: 'mail' where a circular revealed
-- the new name and somebody accepted it on the Intake tab, 'rename' where a person retyped the
-- name on her own form. Neither is 'backfill' - that means a machine's reading of a free-text
-- field and is the first thing to suspect about a vessel, and these are not guesses. The log
-- recorded them.

INSERT INTO public.vessel_ex_names (vessel_id, name, source, notes)
SELECT DISTINCT ON (c.entity_id, upper(btrim(c.old_value)))
       c.entity_id,
       btrim(c.old_value),
       CASE WHEN c.context LIKE 'Intake:%' THEN 'mail' ELSE 'rename' END,
       'Recovered from the change log: renamed to ' || btrim(c.new_value)
           || ' on ' || to_char(c.changed_at, 'DD Mon YYYY')
           || ', when the former name was not filed.'
FROM public.data_changes c
JOIN public.vessels v ON v.id = c.entity_id
WHERE c.entity_type = 'vessel'
  AND c.field_name = 'name'
  AND c.operation = 'update'
  AND c.old_value IS NOT NULL
  AND btrim(c.old_value) <> ''
  -- Not a rename at all: a correction to the spelling of the name she still carries. Filing
  -- that would leave a former name nobody ever called her, matching the vessel search for ever.
  AND upper(btrim(c.old_value)) <> upper(btrim(coalesce(c.new_value, '')))
  -- Nor the name she reads as now. A hull renamed and renamed back is not a former name of
  -- herself.
  AND upper(btrim(c.old_value)) <> upper(btrim(v.name))
  -- And nothing already on her, whoever filed it. Matches ux_vessel_ex_names_vessel_name, so
  -- this cannot fail on the index it is written to respect.
  AND NOT EXISTS (
      SELECT 1 FROM public.vessel_ex_names e
      WHERE e.vessel_id = c.entity_id
        AND upper(e.name) = upper(btrim(c.old_value))
  )
-- DISTINCT ON needs the key leading, and the newest rename of a pair wins the note.
ORDER BY c.entity_id, upper(btrim(c.old_value)), c.changed_at DESC;
