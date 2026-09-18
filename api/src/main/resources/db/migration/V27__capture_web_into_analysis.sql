-- ---------------------------------------------------------------------------
-- The corpus takes from the boards as well as from the mailbox
-- ---------------------------------------------------------------------------
-- analysis_samples was built when synced mail was the only text arriving. It is not any
-- more: ship.gr's boards carry the same circulars from the same firms, and the parser
-- already reads them. A corpus that left them out would be trained on one half of what the
-- model is asked to read at inference - and the half it left out is the one with no
-- relationship behind it, which is where the awkward formats live.
--
-- The column mirrors mail_message_id exactly, including why it is nullable and SET NULL: a
-- sample carries its own copy of the text, and this is provenance - "this came off that
-- post, if you still want to look at it". A board rolls its entries off the bottom and a
-- source can be deleted; neither may take an annotation with it, because the annotation is
-- the expensive half of a sample and the email is the cheap one.
--
-- source already distinguishes MAILBOX from PASTED and is varchar(20), so WEB needs no
-- widening - only a third value.

ALTER TABLE public.analysis_samples ADD COLUMN feed_item_id bigint;

ALTER TABLE public.analysis_samples
    ADD CONSTRAINT analysis_samples_feed_item_fkey FOREIGN KEY (feed_item_id)
        REFERENCES public.feed_items(id) ON DELETE SET NULL;

COMMENT ON COLUMN public.analysis_samples.feed_item_id IS
    'The board post this sample was captured from, while the fetcher still holds a copy. '
    'Provenance, not storage: body_text is the snapshot that is trained on.';

-- The dedupe asks "which of these posts are already in the corpus" once per capture run,
-- against a table that only grows. Partial, because the mail half of the corpus never has
-- one and there is no reason to carry them in the index.
CREATE INDEX ix_analysis_samples_feed_item ON public.analysis_samples USING btree (feed_item_id)
    WHERE feed_item_id IS NOT NULL;
