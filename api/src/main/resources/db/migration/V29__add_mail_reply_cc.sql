-- Writing to a firm from its own record, not only answering a message.
--
-- The Companies drawer's "Reach out" sends a new message through the same route replies take
-- - the mailbox over SMTP, or Brevo where the host blocks SMTP - and it is recorded in the
-- same table, for the same reasons: it is written the moment the send returns, so the day's
-- count is right before the next poll, and it survives a provider that keeps no Sent copy.
-- Such a row simply has no mail_message_id; the column was nullable from the start, for a
-- message later deleted from the mailbox.
--
-- What it needs that a reply never did is copies. A reply goes to the one address that
-- wrote; a message to a firm goes to one person with the desk address and a colleague on
-- copy, and a record that names only the first recipient is a record of a different email.
-- Comma-joined text rather than a child table: nothing asks "which messages copied this
-- address", and the row is read back whole or not at all.
ALTER TABLE public.mail_replies
    ADD COLUMN cc_addresses text;
