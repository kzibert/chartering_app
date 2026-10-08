-- Circulars per person.
--
-- A circular goes out through the sender's own mailbox (V32), so who sent it is part of the
-- record: the desk reads the whole history, and only the sender can resume a run, because the
-- rest of it would go out through their mailbox and nobody else's. Restarting - the same
-- circular again, as a new run - stays open to anybody, from their own mailbox.
--
-- The current list is per person too. It is a scratch pad filled from the Vessels, Companies
-- and People tabs, and two people collecting into one pad would each send the other's picks.
-- Saved lists stay the desk's: they are prepared documents, and sharing them is their point,
-- so their owner stays null.
--
-- Both columns stay nullable for the rows written before accounts; MailOwnership hands those
-- to the server mailbox's owner at startup, as it does the mail.

ALTER TABLE public.circulation_runs ADD COLUMN sent_by_user_id bigint REFERENCES public.users (id);
CREATE INDEX ix_circulation_runs_sent_by ON public.circulation_runs (sent_by_user_id);

ALTER TABLE public.circulation_lists ADD COLUMN owner_user_id bigint REFERENCES public.users (id);

DROP INDEX public.ux_circulation_lists_single_draft;
CREATE UNIQUE INDEX ux_circulation_lists_single_draft
    ON public.circulation_lists (tenant_id, coalesce(owner_user_id, 0))
    WHERE is_draft;
