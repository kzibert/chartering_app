-- The duplicates already in the queue, collapsed the way new arrivals now are.
--
-- V20 made a pending VESSEL_FIELDS item one question per hull however many emails raise it,
-- but only for parses from that point on. What was already waiting stayed as it was - FOX
-- twice, from one broker two days apart, over a reworded vessel type - and a reviewer opening
-- the tab today would still see the thing the change was made to stop.
--
-- Keeps the newest item per vessel and moves the others' arrivals onto it, which is exactly
-- the runtime rule: the later list is the later statement, and every email stays readable from
-- the one that survives. Only PENDING items are touched. An answered question is history and
-- nothing here rewrites history.
--
-- One thing is genuinely lost, and it is worth naming rather than glossing: where an older
-- item disagreed about a field the newest one says nothing about, that disagreement goes with
-- it. The runtime merge takes the union and keeps it; this cannot, because reconciling two
-- JSON payloads in SQL would be a worse idea than the problem it solves. The next list
-- mentioning her raises it again, and until then her record holds what a person put there,
-- which is the safe direction to be wrong in.

-- Move every source row from the losers onto the survivor. Guarded against the unique index:
-- if one parse somehow raised both items, its row is already on the survivor and the loser's
-- copy is dropped below rather than colliding.
UPDATE public.intake_item_sources s
SET intake_item_id = keep.keep_id
FROM (
    SELECT i.id AS loser_id,
           max(i2.id) OVER (PARTITION BY i.vessel_id) AS keep_id
    FROM public.intake_items i
    JOIN public.intake_items i2
      ON i2.vessel_id = i.vessel_id
     AND i2.kind = 'VESSEL_FIELDS'
     AND i2.status = 'PENDING'
    WHERE i.kind = 'VESSEL_FIELDS'
      AND i.status = 'PENDING'
      AND i.vessel_id IS NOT NULL
) keep
WHERE s.intake_item_id = keep.loser_id
  AND keep.loser_id <> keep.keep_id
  AND NOT EXISTS (
      SELECT 1 FROM public.intake_item_sources other
      WHERE other.intake_item_id = keep.keep_id
        AND other.parsed_email_id = s.parsed_email_id
  );

-- Anything left pointing at a loser is a parse the survivor already lists. Removed explicitly
-- rather than left to the cascade below, so the intent is on the page.
DELETE FROM public.intake_item_sources s
USING public.intake_items i
WHERE s.intake_item_id = i.id
  AND i.kind = 'VESSEL_FIELDS'
  AND i.status = 'PENDING'
  AND i.vessel_id IS NOT NULL
  AND i.id < (SELECT max(i2.id) FROM public.intake_items i2
              WHERE i2.vessel_id = i.vessel_id
                AND i2.kind = 'VESSEL_FIELDS'
                AND i2.status = 'PENDING');

-- And the duplicate questions themselves.
DELETE FROM public.intake_items i
WHERE i.kind = 'VESSEL_FIELDS'
  AND i.status = 'PENDING'
  AND i.vessel_id IS NOT NULL
  AND i.id < (SELECT max(i2.id) FROM public.intake_items i2
              WHERE i2.vessel_id = i.vessel_id
                AND i2.kind = 'VESSEL_FIELDS'
                AND i2.status = 'PENDING');
