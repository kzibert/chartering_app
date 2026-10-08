-- Every desk's data is its own.
--
-- A tenant_id on every table that holds a desk's work, filled with 1 - the desk V30 created,
-- which is the desk this database has always been - and then left without a default. No
-- default is the point: a row written without saying whose it is fails on NOT NULL instead
-- of quietly landing on desk 1, so a code path that forgot to name a desk is found by its
-- first insert rather than by a customer reading a competitor's contacts.
--
-- Hibernate writes and filters the column (@TenantId on each entity). The foreign key is to
-- the desk, so a desk with data cannot be deleted - desks are suspended, not removed.
--
-- What stays global, and why:
--   tenants, users              - the login reads them before any desk is known.
--   ports, port_aliases, regions, trade_areas, trade_area_aliases, trade_area_distances,
--   sea_waypoints, sea_legs, tonnage_categories
--                               - the shared vocabulary of the market; a desk's own
--                                 additions come later as overlay rows.
--   feed_sources, feed_items    - copies of public pages, fetched once for everybody.
--   company_ports, company_tonnage_categories, feed_summary_items
--                               - join tables, scoped through the row they hang off.

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'companies', 'people', 'contacts',
        'vessels', 'vessel_company_links', 'vessel_ex_names', 'vessel_positions',
        'vessel_field_reports', 'vessel_lookups',
        'cargoes', 'cargo_sources', 'cargo_vessel_matches',
        'circulation_lists', 'circulation_list_entries', 'circulation_runs', 'circulation_run_recipients',
        'email_footers', 'email_templates',
        'mail_folders', 'mail_rules', 'mail_rule_conditions', 'mail_messages', 'mail_replies',
        'mail_server_folders', 'mail_sync_state',
        'analysis_samples', 'parsed_emails',
        'intake_items', 'intake_item_sources', 'intake_field_decisions', 'intake_vessel_aliases',
        'feed_topics', 'feed_summaries',
        'data_changes'
    ] LOOP
        EXECUTE format('ALTER TABLE public.%I ADD COLUMN tenant_id bigint NOT NULL DEFAULT 1 '
                       'REFERENCES public.tenants (id)', t);
        EXECUTE format('ALTER TABLE public.%I ALTER COLUMN tenant_id DROP DEFAULT', t);
        EXECUTE format('CREATE INDEX %I ON public.%I (tenant_id)', 'ix_' || t || '_tenant', t);
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------------------
-- Uniqueness that was "in the database" and is now "on the desk". Two desks may each have a
-- list called "Black Sea owners", each hold the same hull under her IMO, and each receive the
-- same circular: none of those is a duplicate any more.
-- ---------------------------------------------------------------------------------------

DROP INDEX public.ux_vessels_imo;
CREATE UNIQUE INDEX ux_vessels_imo ON public.vessels (tenant_id, imo_number)
    WHERE imo_number IS NOT NULL;

DROP INDEX public.ux_contacts_main_per_company_kind;
CREATE UNIQUE INDEX ux_contacts_main_per_company_kind ON public.contacts (tenant_id, company_id, contact_kind)
    WHERE is_main AND company_id IS NOT NULL;

DROP INDEX public.ux_circulation_lists_name;
CREATE UNIQUE INDEX ux_circulation_lists_name ON public.circulation_lists (tenant_id, lower((name)::text))
    WHERE name IS NOT NULL;

DROP INDEX public.ux_circulation_lists_single_draft;
CREATE UNIQUE INDEX ux_circulation_lists_single_draft ON public.circulation_lists (tenant_id)
    WHERE is_draft;

DROP INDEX public.ux_email_footers_name;
CREATE UNIQUE INDEX ux_email_footers_name ON public.email_footers (tenant_id, lower((name)::text));

DROP INDEX public.ux_email_footers_single_default;
CREATE UNIQUE INDEX ux_email_footers_single_default ON public.email_footers (tenant_id)
    WHERE is_default;

DROP INDEX public.ux_email_footers_single_reply_default;
CREATE UNIQUE INDEX ux_email_footers_single_reply_default ON public.email_footers (tenant_id)
    WHERE is_reply_default;

DROP INDEX public.ux_email_templates_name;
CREATE UNIQUE INDEX ux_email_templates_name ON public.email_templates (tenant_id, lower((name)::text));

DROP INDEX public.ux_mail_folders_name;
CREATE UNIQUE INDEX ux_mail_folders_name ON public.mail_folders (tenant_id, lower((name)::text));

DROP INDEX public.ux_mail_rules_name;
CREATE UNIQUE INDEX ux_mail_rules_name ON public.mail_rules (tenant_id, lower((name)::text));

DROP INDEX public.ux_feed_topics_name;
CREATE UNIQUE INDEX ux_feed_topics_name ON public.feed_topics (tenant_id, lower(name));

DROP INDEX public.ux_mail_messages_message_id;
CREATE UNIQUE INDEX ux_mail_messages_message_id ON public.mail_messages (tenant_id, message_id)
    WHERE message_id IS NOT NULL;

DROP INDEX public.ux_mail_messages_uid;
CREATE UNIQUE INDEX ux_mail_messages_uid ON public.mail_messages (tenant_id, imap_folder, imap_validity, imap_uid)
    WHERE imap_uid IS NOT NULL;

DROP INDEX public.ux_analysis_samples_message_id;
CREATE UNIQUE INDEX ux_analysis_samples_message_id ON public.analysis_samples (tenant_id, message_id)
    WHERE message_id IS NOT NULL;

-- A board post is global and every desk that reads boards parses it for itself: the
-- reading is the same, but what it files - and against which hull - is the desk's.
DROP INDEX public.ux_parsed_emails_feed_item;
CREATE UNIQUE INDEX ux_parsed_emails_feed_item ON public.parsed_emails (tenant_id, feed_item_id);

-- The two tables whose key is a folder name: two desks each have an INBOX.
ALTER TABLE public.mail_server_folders DROP CONSTRAINT mail_server_folders_pkey;
ALTER TABLE public.mail_server_folders ADD PRIMARY KEY (tenant_id, full_name);
ALTER TABLE public.mail_sync_state DROP CONSTRAINT mail_sync_state_pkey;
ALTER TABLE public.mail_sync_state ADD PRIMARY KEY (tenant_id, imap_folder);

-- ---------------------------------------------------------------------------------------
-- Settings: per desk, except the few that describe the installation itself.
--
-- The model servers, how often the sweep and the board fetch run, and how large the served
-- context window is are facts about the one machine and the one GPU every desk shares; a
-- desk cannot have its own sweep interval when there is one sweep. Those rows keep a NULL
-- tenant_id and only a platform administrator changes them (SettingsStore decides which keys
-- they are). Everything else - match weights, mail pacing, own addresses, prompts - is how a
-- desk works, and is copied to desk 1 here.
-- ---------------------------------------------------------------------------------------

ALTER TABLE public.app_settings DROP CONSTRAINT app_settings_pkey;
ALTER TABLE public.app_settings ADD COLUMN id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY;
ALTER TABLE public.app_settings ADD COLUMN tenant_id bigint REFERENCES public.tenants (id);

UPDATE public.app_settings SET tenant_id = 1
WHERE key NOT LIKE 'parser.%'
  AND key NOT IN ('feed.modelUrl', 'feed.modelName', 'feed.fetchIntervalMinutes', 'feed.contextWindowTokens');

CREATE UNIQUE INDEX ux_app_settings_tenant_key ON public.app_settings (tenant_id, key)
    WHERE tenant_id IS NOT NULL;
CREATE UNIQUE INDEX ux_app_settings_platform_key ON public.app_settings (key)
    WHERE tenant_id IS NULL;
