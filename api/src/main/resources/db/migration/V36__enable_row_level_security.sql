-- The database refuses a desk another desk's rows, whatever the application asks.
--
-- Hibernate already scopes every query to the desk on the thread (@TenantId). This is the
-- second wall, for what Hibernate does not write or cannot see: a native query, a JDBC
-- statement, a bulk update somebody adds next year without the predicate. Each connection the
-- application takes is told its desk (TenantAwareDataSource sets app.tenant_id on checkout,
-- empty when there is none), and every table holding a desk's data shows and accepts only that
-- desk's rows. With no desk set, nothing - the same fail-closed answer the application gives.
--
-- FORCE, because the application connects as the role that owns these tables (Neon's owner
-- role, a local compose user), and an owner is otherwise exempt. A superuser is exempt
-- regardless: the local chartering-db container's default user is one, so there this wall is
-- not up, and the Testcontainers suite connects as an ordinary role precisely to test it.
--
-- Migrations run as the same role. One that reads or writes a desk's rows must say so first,
-- inside its own transaction:
--
--     SET LOCAL app.rls_bypass = 'on';
--
-- LOCAL ends with the migration's transaction, so a pooled connection never carries it into
-- the application. The application never sets it. It is a guard against our own mistakes,
-- not against somebody holding a SQL session on this database - such a session can set
-- app.tenant_id to anything it likes as well.

DO $$
DECLARE
    t text;
BEGIN
    -- Every row belongs to exactly one desk.
    FOREACH t IN ARRAY ARRAY[
        'companies', 'people', 'contacts',
        'vessels', 'vessel_company_links', 'vessel_ex_names', 'vessel_positions',
        'vessel_field_reports', 'vessel_lookups',
        'cargoes', 'cargo_sources', 'cargo_vessel_matches',
        'circulation_lists', 'circulation_list_entries', 'circulation_runs', 'circulation_run_recipients',
        'email_footers', 'email_templates',
        'mail_folders', 'mail_rules', 'mail_rule_conditions', 'mail_messages', 'mail_replies',
        'mail_server_folders', 'mail_sync_state', 'mail_accounts',
        'analysis_samples', 'parsed_emails',
        'intake_items', 'intake_item_sources', 'intake_field_decisions', 'intake_vessel_aliases',
        'feed_topics', 'feed_summaries', 'feed_intake_subscriptions',
        'data_changes'
    ] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY desk_rows ON public.%I
            USING (current_setting('app.rls_bypass', true) = 'on'
                   OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::bigint)
            WITH CHECK (current_setting('app.rls_bypass', true) = 'on'
                   OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::bigint)$p$, t);
    END LOOP;

    -- A null desk is everybody's: the installation's settings, the market's spellings.
    FOREACH t IN ARRAY ARRAY['app_settings', 'port_aliases', 'trade_area_aliases'] LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY desk_rows ON public.%I
            USING (current_setting('app.rls_bypass', true) = 'on'
                   OR tenant_id IS NULL
                   OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::bigint)
            WITH CHECK (current_setting('app.rls_bypass', true) = 'on'
                   OR tenant_id IS NULL
                   OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::bigint)$p$, t);
    END LOOP;
END $$;
