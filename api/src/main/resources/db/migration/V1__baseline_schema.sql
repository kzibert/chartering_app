--
-- V1 - baseline schema
--
-- The complete structure of the chartering database: 23 tables, 3 views, 49
-- indexes, 11 sequences, the foreign keys between them, and the pg_trgm
-- extension the name searches need. No data - that is V2.
--
-- This file is where the schema lives. Before Flyway it lived in a 1.9 MB
-- pg_dump that Postgres ran on first init, with a folder of hand-applied
-- patches beside it that had to be remembered and kept in step; the last one
-- was not, and a fresh volume built a database the API refused to validate
-- against. Generated from the live database on 2026-08-22 (PostgreSQL 16.13),
-- which is why it already carries every one of those patches inline.
--
-- Never edit this file once it has run anywhere: Flyway records its checksum,
-- and changing a byte makes that database fail validation at startup. Schema
-- changes are new V-numbered files beside it - see the README.
--
-- Rewritten out of pg_dump --schema-only rather than used raw: the psql
-- \restrict / \unrestrict meta-commands it emits are not SQL and Flyway runs
-- this over JDBC, the session SET block is pg_dump's own and not ours to
-- impose, and COMMENT ON EXTENSION needs an ownership this app should not
-- assume it has on a managed Postgres.
--

-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: app_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_settings (
    key character varying(100) NOT NULL,
    value text NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE app_settings; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.app_settings IS 'Overrides for configured defaults, keyed by setting name. Absent key = use the default.';


--
-- Name: circulation_list_entries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.circulation_list_entries (
    id bigint NOT NULL,
    list_id bigint NOT NULL,
    contact_id bigint,
    email character varying(320) NOT NULL,
    person_id bigint,
    person_name character varying(255),
    greeting_name character varying(255),
    title character varying(50),
    company_id bigint,
    company_name character varying(255),
    sort_order integer DEFAULT 0 NOT NULL
);


--
-- Name: circulation_list_entries_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.circulation_list_entries_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: circulation_list_entries_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.circulation_list_entries_id_seq OWNED BY public.circulation_list_entries.id;


--
-- Name: circulation_lists; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.circulation_lists (
    id bigint NOT NULL,
    name character varying(150),
    is_draft boolean DEFAULT false NOT NULL,
    notes text,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: circulation_lists_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.circulation_lists_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: circulation_lists_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.circulation_lists_id_seq OWNED BY public.circulation_lists.id;


--
-- Name: circulation_run_recipients; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.circulation_run_recipients (
    id bigint NOT NULL,
    run_id bigint NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    email character varying(320) NOT NULL,
    contact_id bigint,
    person_id bigint,
    person_name character varying(255),
    greeting_name character varying(255),
    title character varying(50),
    company_id bigint,
    company_name character varying(255),
    status character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    error text,
    sent_at timestamp without time zone,
    provider character varying(10) DEFAULT 'SMTP'::character varying NOT NULL
);


--
-- Name: circulation_run_recipients_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.circulation_run_recipients_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: circulation_run_recipients_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.circulation_run_recipients_id_seq OWNED BY public.circulation_run_recipients.id;


--
-- Name: circulation_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.circulation_runs (
    id bigint NOT NULL,
    subject_template character varying(300) NOT NULL,
    composed_html text NOT NULL,
    footer_id bigint,
    footer_name character varying(150),
    list_id bigint,
    list_name character varying(150),
    from_address character varying(320),
    from_name character varying(255),
    reply_to character varying(320),
    state character varying(30) NOT NULL,
    total integer DEFAULT 0 NOT NULL,
    sent integer DEFAULT 0 NOT NULL,
    failed integer DEFAULT 0 NOT NULL,
    skipped integer DEFAULT 0 NOT NULL,
    started_at timestamp without time zone DEFAULT now() NOT NULL,
    finished_at timestamp without time zone,
    last_error text,
    message text
);


--
-- Name: circulation_runs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.circulation_runs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: circulation_runs_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.circulation_runs_id_seq OWNED BY public.circulation_runs.id;


--
-- Name: companies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.companies (
    id bigint NOT NULL,
    name character varying(255) NOT NULL,
    is_shipowner boolean DEFAULT false NOT NULL,
    is_charterer boolean DEFAULT false NOT NULL,
    is_broker boolean DEFAULT false NOT NULL,
    is_agent boolean DEFAULT false NOT NULL,
    city_name character varying(255),
    notes text,
    legacy_id bigint,
    is_confirmed boolean DEFAULT false NOT NULL,
    confirmed_at timestamp with time zone,
    confirmed_by character varying(255),
    confirm_notes text,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone DEFAULT now(),
    banned boolean DEFAULT false NOT NULL,
    is_legacy boolean DEFAULT false NOT NULL,
    is_solo boolean DEFAULT false NOT NULL
);


--
-- Name: companies_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.companies ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.companies_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: company_ports; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.company_ports (
    company_id bigint NOT NULL,
    port_id bigint NOT NULL
);


--
-- Name: company_tonnage_categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.company_tonnage_categories (
    company_id bigint NOT NULL,
    tonnage_category_id bigint NOT NULL
);


--
-- Name: contacts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.contacts (
    id bigint NOT NULL,
    person_id bigint,
    company_id bigint,
    contact_kind character varying(10) NOT NULL,
    contact_value character varying(255) NOT NULL,
    notes text,
    legacy_id bigint,
    is_confirmed boolean DEFAULT false NOT NULL,
    confirmed_at timestamp with time zone,
    confirmed_by character varying(255),
    confirm_notes text,
    banned boolean DEFAULT false NOT NULL,
    is_legacy boolean DEFAULT false NOT NULL,
    is_main boolean DEFAULT false NOT NULL,
    is_working boolean DEFAULT true NOT NULL,
    is_circ boolean DEFAULT false NOT NULL,
    is_no_circ boolean DEFAULT false NOT NULL,
    has_whatsapp boolean DEFAULT false NOT NULL,
    greeting_name character varying(120),
    CONSTRAINT contacts_contact_kind_check CHECK (((contact_kind)::text = ANY (ARRAY[('email'::character varying)::text, ('phone'::character varying)::text])))
);


--
-- Name: contacts_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.contacts ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.contacts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: email_footers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.email_footers (
    id bigint NOT NULL,
    name character varying(150) NOT NULL,
    html text NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: email_footers_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.email_footers_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: email_footers_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.email_footers_id_seq OWNED BY public.email_footers.id;


--
-- Name: email_templates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.email_templates (
    id bigint NOT NULL,
    name character varying(150) NOT NULL,
    subject character varying(300),
    body_html text NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: email_templates_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.email_templates_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: email_templates_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.email_templates_id_seq OWNED BY public.email_templates.id;


--
-- Name: mail_folders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mail_folders (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    notes text,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE mail_folders; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mail_folders IS 'App-side folders for synced mail. Not IMAP folders — filing never touches the server.';


--
-- Name: mail_folders_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mail_folders_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mail_folders_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mail_folders_id_seq OWNED BY public.mail_folders.id;


--
-- Name: mail_messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mail_messages (
    id bigint NOT NULL,
    message_id character varying(998),
    imap_uid bigint,
    imap_validity bigint,
    imap_folder character varying(255) DEFAULT 'INBOX'::character varying NOT NULL,
    from_address character varying(320) NOT NULL,
    from_name character varying(255),
    to_addresses text,
    cc_addresses text,
    subject text,
    sent_at timestamp without time zone,
    received_at timestamp without time zone DEFAULT now() NOT NULL,
    body_text text,
    body_html text,
    snippet character varying(300),
    has_attachments boolean DEFAULT false NOT NULL,
    attachment_names text,
    size_bytes integer,
    is_read boolean DEFAULT false NOT NULL,
    folder_id bigint,
    filed_by_rule_id bigint,
    filed_at timestamp without time zone,
    company_id bigint,
    contact_id bigint,
    person_id bigint,
    link_manual boolean DEFAULT false NOT NULL,
    synced_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE mail_messages; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mail_messages IS 'Incoming mail synced from IMAP. Read state, folder and company link are the app''s own.';


--
-- Name: mail_messages_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mail_messages_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mail_messages_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mail_messages_id_seq OWNED BY public.mail_messages.id;


--
-- Name: mail_rule_conditions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mail_rule_conditions (
    id bigint NOT NULL,
    rule_id bigint NOT NULL,
    field character varying(20) NOT NULL,
    operator character varying(20) DEFAULT 'CONTAINS'::character varying NOT NULL,
    value text NOT NULL,
    CONSTRAINT ck_mail_rule_cond_field CHECK (((field)::text = ANY ((ARRAY['FROM'::character varying, 'FROM_DOMAIN'::character varying, 'TO'::character varying, 'SUBJECT'::character varying, 'BODY'::character varying, 'ANY'::character varying])::text[]))),
    CONSTRAINT ck_mail_rule_cond_operator CHECK (((operator)::text = ANY ((ARRAY['CONTAINS'::character varying, 'NOT_CONTAINS'::character varying, 'EQUALS'::character varying, 'STARTS_WITH'::character varying, 'ENDS_WITH'::character varying])::text[])))
);


--
-- Name: mail_rule_conditions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mail_rule_conditions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mail_rule_conditions_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mail_rule_conditions_id_seq OWNED BY public.mail_rule_conditions.id;


--
-- Name: mail_rules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mail_rules (
    id bigint NOT NULL,
    name character varying(150) NOT NULL,
    folder_id bigint NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    match_type character varying(10) DEFAULT 'ALL'::character varying NOT NULL,
    mark_read boolean DEFAULT false NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_mail_rules_match_type CHECK (((match_type)::text = ANY ((ARRAY['ALL'::character varying, 'ANY'::character varying])::text[])))
);


--
-- Name: mail_rules_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mail_rules_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mail_rules_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mail_rules_id_seq OWNED BY public.mail_rules.id;


--
-- Name: mail_server_folders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mail_server_folders (
    full_name character varying(255) NOT NULL,
    display_name character varying(255) NOT NULL,
    parent_name character varying(255),
    separator character varying(4),
    special_use character varying(20),
    selectable boolean DEFAULT true NOT NULL,
    server_total integer,
    server_unseen integer,
    sort_order integer DEFAULT 0 NOT NULL,
    last_seen_at timestamp without time zone DEFAULT now() NOT NULL,
    present boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE mail_server_folders; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mail_server_folders IS 'The mail server''s folder tree as last listed. A read-only mirror; the app never writes folders back.';


--
-- Name: mail_sync_state; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mail_sync_state (
    imap_folder character varying(255) NOT NULL,
    imap_validity bigint,
    last_uid bigint,
    last_sync_at timestamp without time zone,
    last_status character varying(20),
    last_error text,
    last_fetched integer DEFAULT 0 NOT NULL,
    last_stored integer DEFAULT 0 NOT NULL
);


--
-- Name: TABLE mail_sync_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.mail_sync_state IS 'Where the IMAP reader got to, per mailbox folder, plus the outcome of the last attempt.';


--
-- Name: people; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.people (
    id bigint NOT NULL,
    full_name character varying(255) NOT NULL,
    company_id bigint,
    notes text,
    legacy_id bigint,
    greeting_name character varying(120),
    title character varying(20),
    is_legacy boolean DEFAULT false NOT NULL,
    has_left boolean DEFAULT false NOT NULL
);


--
-- Name: people_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.people ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.people_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ports; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ports (
    id bigint NOT NULL,
    name character varying(255) NOT NULL,
    code character varying(50),
    region_id bigint,
    legacy_id bigint
);


--
-- Name: ports_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.ports ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.ports_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: regions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.regions (
    id bigint NOT NULL,
    name character varying(255) NOT NULL,
    code character varying(50),
    legacy_id bigint
);


--
-- Name: regions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.regions ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.regions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: tonnage_categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tonnage_categories (
    id bigint NOT NULL,
    name character varying(255) NOT NULL,
    legacy_id bigint
);


--
-- Name: tonnage_categories_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.tonnage_categories ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.tonnage_categories_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: vessel_company_links; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.vessel_company_links (
    id bigint NOT NULL,
    vessel_id bigint NOT NULL,
    company_id bigint NOT NULL,
    role character varying(20) NOT NULL,
    notes text,
    CONSTRAINT vessel_company_links_role_check CHECK (((role)::text = ANY ((ARRAY['exclusive_broker'::character varying, 'broker'::character varying])::text[])))
);


--
-- Name: vessel_company_links_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.vessel_company_links_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: vessel_company_links_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.vessel_company_links_id_seq OWNED BY public.vessel_company_links.id;


--
-- Name: vessels; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.vessels (
    id bigint NOT NULL,
    name character varying(255) NOT NULL,
    imo_number character varying(20),
    deadweight_tonnage numeric,
    deadweight_cargo_capacity numeric,
    grain_capacity_m3 numeric,
    bale_capacity_m3 numeric,
    maximum_draft numeric,
    year_built integer,
    vessel_type character varying(255),
    flag character varying(255),
    owner_id bigint,
    notes text,
    legacy_id bigint,
    is_confirmed boolean DEFAULT false NOT NULL,
    confirmed_at timestamp with time zone,
    confirmed_by character varying(255),
    confirm_notes text,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone DEFAULT now(),
    banned boolean DEFAULT false NOT NULL,
    is_legacy boolean DEFAULT false NOT NULL
);


--
-- Name: vessels_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.vessels ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.vessels_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: vw_company_contacts; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.vw_company_contacts AS
 SELECT co.id AS company_id,
    co.name AS company_name,
    p.id AS person_id,
    p.full_name,
    ct.id AS contact_id,
    ct.contact_kind,
    ct.contact_value,
    ct.is_confirmed,
    ct.confirmed_at,
    ct.confirmed_by
   FROM ((public.companies co
     JOIN public.contacts ct ON ((ct.company_id = co.id)))
     LEFT JOIN public.people p ON ((p.id = ct.person_id)));


--
-- Name: vw_stale_confirmations; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.vw_stale_confirmations AS
 SELECT 'vessel'::text AS entity,
    vessels.id,
    vessels.name AS label,
    vessels.is_confirmed,
    vessels.confirmed_at
   FROM public.vessels
  WHERE ((vessels.is_confirmed = false) OR (vessels.confirmed_at < (now() - '6 mons'::interval)))
UNION ALL
 SELECT 'company'::text AS entity,
    companies.id,
    companies.name AS label,
    companies.is_confirmed,
    companies.confirmed_at
   FROM public.companies
  WHERE ((companies.is_confirmed = false) OR (companies.confirmed_at < (now() - '6 mons'::interval)))
UNION ALL
 SELECT 'contact'::text AS entity,
    contacts.id,
    contacts.contact_value AS label,
    contacts.is_confirmed,
    contacts.confirmed_at
   FROM public.contacts
  WHERE ((contacts.is_confirmed = false) OR (contacts.confirmed_at < (now() - '6 mons'::interval)));


--
-- Name: vw_vessels_full; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.vw_vessels_full AS
 SELECT v.id,
    v.name,
    v.imo_number,
    v.deadweight_tonnage,
    v.deadweight_cargo_capacity,
    v.grain_capacity_m3,
    v.bale_capacity_m3,
    v.maximum_draft,
    v.year_built,
    v.vessel_type,
    v.flag,
    v.is_confirmed,
    v.confirmed_at,
    v.confirmed_by,
    c.id AS owner_id,
    c.name AS owner_name,
    c.city_name AS owner_city,
    c.is_shipowner,
    c.is_charterer,
    c.is_broker,
    c.is_agent,
    c.is_confirmed AS owner_confirmed
   FROM (public.vessels v
     LEFT JOIN public.companies c ON ((c.id = v.owner_id)));


--
-- Name: circulation_list_entries id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.circulation_list_entries ALTER COLUMN id SET DEFAULT nextval('public.circulation_list_entries_id_seq'::regclass);


--
-- Name: circulation_lists id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.circulation_lists ALTER COLUMN id SET DEFAULT nextval('public.circulation_lists_id_seq'::regclass);


--
-- Name: circulation_run_recipients id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.circulation_run_recipients ALTER COLUMN id SET DEFAULT nextval('public.circulation_run_recipients_id_seq'::regclass);


--
-- Name: circulation_runs id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.circulation_runs ALTER COLUMN id SET DEFAULT nextval('public.circulation_runs_id_seq'::regclass);


--
-- Name: email_footers id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_footers ALTER COLUMN id SET DEFAULT nextval('public.email_footers_id_seq'::regclass);


--
-- Name: email_templates id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_templates ALTER COLUMN id SET DEFAULT nextval('public.email_templates_id_seq'::regclass);


--
-- Name: mail_folders id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mail_folders ALTER COLUMN id SET DEFAULT nextval('public.mail_folders_id_seq'::regclass);


--
-- Name: mail_messages id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mail_messages ALTER COLUMN id SET DEFAULT nextval('public.mail_messages_id_seq'::regclass);


--
-- Name: mail_rule_conditions id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mail_rule_conditions ALTER COLUMN id SET DEFAULT nextval('public.mail_rule_conditions_id_seq'::regclass);


--
-- Name: mail_rules id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mail_rules ALTER COLUMN id SET DEFAULT nextval('public.mail_rules_id_seq'::regclass);


--
-- Name: vessel_company_links id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vessel_company_links ALTER COLUMN id SET DEFAULT nextval('public.vessel_company_links_id_seq'::regclass);


--
-- Name: app_settings app_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_settings
    ADD CONSTRAINT app_settings_pkey PRIMARY KEY (key);


--
-- Name: circulation_list_entries circulation_list_entries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.circulation_list_entries
    ADD CONSTRAINT circulation_list_entries_pkey PRIMARY KEY (id);


--
-- Name: circulation_lists circulation_lists_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.circulation_lists
    ADD CONSTRAINT circulation_lists_pkey PRIMARY KEY (id);


--
-- Name: circulation_run_recipients circulation_run_recipients_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.circulation_run_recipients
    ADD CONSTRAINT circulation_run_recipients_pkey PRIMARY KEY (id);


--
-- Name: circulation_runs circulation_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.circulation_runs
    ADD CONSTRAINT circulation_runs_pkey PRIMARY KEY (id);


--
-- Name: companies companies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.companies
    ADD CONSTRAINT companies_pkey PRIMARY KEY (id);


--
-- Name: company_ports company_ports_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_ports
    ADD CONSTRAINT company_ports_pkey PRIMARY KEY (company_id, port_id);


--
-- Name: company_tonnage_categories company_tonnage_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_tonnage_categories
    ADD CONSTRAINT company_tonnage_categories_pkey PRIMARY KEY (company_id, tonnage_category_id);


--
-- Name: contacts contacts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contacts
    ADD CONSTRAINT contacts_pkey PRIMARY KEY (id);


--
-- Name: email_footers email_footers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_footers
    ADD CONSTRAINT email_footers_pkey PRIMARY KEY (id);


--
-- Name: email_templates email_templates_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_templates
    ADD CONSTRAINT email_templates_pkey PRIMARY KEY (id);


--
-- Name: mail_folders mail_folders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mail_folders
    ADD CONSTRAINT mail_folders_pkey PRIMARY KEY (id);


--
-- Name: mail_messages mail_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mail_messages
    ADD CONSTRAINT mail_messages_pkey PRIMARY KEY (id);


--
-- Name: mail_rule_conditions mail_rule_conditions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mail_rule_conditions
    ADD CONSTRAINT mail_rule_conditions_pkey PRIMARY KEY (id);


--
-- Name: mail_rules mail_rules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mail_rules
    ADD CONSTRAINT mail_rules_pkey PRIMARY KEY (id);


--
-- Name: mail_server_folders mail_server_folders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mail_server_folders
    ADD CONSTRAINT mail_server_folders_pkey PRIMARY KEY (full_name);


--
-- Name: mail_sync_state mail_sync_state_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mail_sync_state
    ADD CONSTRAINT mail_sync_state_pkey PRIMARY KEY (imap_folder);


--
-- Name: people people_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.people
    ADD CONSTRAINT people_pkey PRIMARY KEY (id);


--
-- Name: ports ports_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ports
    ADD CONSTRAINT ports_pkey PRIMARY KEY (id);


--
-- Name: regions regions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.regions
    ADD CONSTRAINT regions_pkey PRIMARY KEY (id);


--
-- Name: tonnage_categories tonnage_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tonnage_categories
    ADD CONSTRAINT tonnage_categories_pkey PRIMARY KEY (id);


--
-- Name: vessel_company_links vessel_company_links_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vessel_company_links
    ADD CONSTRAINT vessel_company_links_pkey PRIMARY KEY (id);


--
-- Name: vessels vessels_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vessels
    ADD CONSTRAINT vessels_pkey PRIMARY KEY (id);


--
-- Name: ix_circulation_list_entries_list; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_circulation_list_entries_list ON public.circulation_list_entries USING btree (list_id, sort_order, id);


--
-- Name: ix_circulation_run_recipients_email; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_circulation_run_recipients_email ON public.circulation_run_recipients USING btree (lower((email)::text));


--
-- Name: ix_circulation_run_recipients_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_circulation_run_recipients_pending ON public.circulation_run_recipients USING btree (run_id) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: ix_circulation_run_recipients_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_circulation_run_recipients_run ON public.circulation_run_recipients USING btree (run_id, sort_order, id);


--
-- Name: ix_circulation_run_recipients_sent_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_circulation_run_recipients_sent_at ON public.circulation_run_recipients USING btree (sent_at, provider) WHERE ((status)::text = 'SENT'::text);


--
-- Name: ix_circulation_runs_started; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_circulation_runs_started ON public.circulation_runs USING btree (started_at DESC);


--
-- Name: ix_companies_name_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_companies_name_trgm ON public.companies USING gin (lower((name)::text) public.gin_trgm_ops);


--
-- Name: ix_contacts_circ; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_contacts_circ ON public.contacts USING btree (company_id, person_id) WHERE is_circ;


--
-- Name: ix_contacts_company; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_contacts_company ON public.contacts USING btree (company_id);


--
-- Name: ix_contacts_email; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_contacts_email ON public.contacts USING btree (company_id) WHERE ((contact_kind)::text = 'email'::text);


--
-- Name: ix_contacts_no_circ; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_contacts_no_circ ON public.contacts USING btree (company_id) WHERE is_no_circ;


--
-- Name: ix_contacts_not_working; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_contacts_not_working ON public.contacts USING btree (company_id) WHERE (NOT is_working);


--
-- Name: ix_contacts_person; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_contacts_person ON public.contacts USING btree (person_id);


--
-- Name: ix_contacts_value; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_contacts_value ON public.contacts USING btree (lower((contact_value)::text));


--
-- Name: ix_contacts_whatsapp; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_contacts_whatsapp ON public.contacts USING btree (company_id) WHERE has_whatsapp;


--
-- Name: ix_mail_messages_company; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mail_messages_company ON public.mail_messages USING btree (company_id);


--
-- Name: ix_mail_messages_folder; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mail_messages_folder ON public.mail_messages USING btree (folder_id, received_at DESC);


--
-- Name: ix_mail_messages_from; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mail_messages_from ON public.mail_messages USING btree (lower((from_address)::text));


--
-- Name: ix_mail_messages_from_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mail_messages_from_trgm ON public.mail_messages USING gin (lower((from_address)::text) public.gin_trgm_ops);


--
-- Name: ix_mail_messages_imap_folder; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mail_messages_imap_folder ON public.mail_messages USING btree (imap_folder, received_at DESC);


--
-- Name: ix_mail_messages_imap_folder_unread; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mail_messages_imap_folder_unread ON public.mail_messages USING btree (imap_folder) WHERE (NOT is_read);


--
-- Name: ix_mail_messages_received; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mail_messages_received ON public.mail_messages USING btree (received_at DESC, id DESC);


--
-- Name: ix_mail_messages_subject_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mail_messages_subject_trgm ON public.mail_messages USING gin (lower(subject) public.gin_trgm_ops);


--
-- Name: ix_mail_messages_unread; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mail_messages_unread ON public.mail_messages USING btree (folder_id) WHERE (NOT is_read);


--
-- Name: ix_mail_rule_conditions_rule; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mail_rule_conditions_rule ON public.mail_rule_conditions USING btree (rule_id);


--
-- Name: ix_mail_rules_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mail_rules_order ON public.mail_rules USING btree (sort_order, id);


--
-- Name: ix_mail_server_folders_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_mail_server_folders_order ON public.mail_server_folders USING btree (sort_order, full_name);


--
-- Name: ix_people_has_left; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_people_has_left ON public.people USING btree (id) WHERE has_left;


--
-- Name: ix_vessel_company_links_company; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_vessel_company_links_company ON public.vessel_company_links USING btree (company_id);


--
-- Name: ix_vessels_dwcc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_vessels_dwcc ON public.vessels USING btree (deadweight_cargo_capacity);


--
-- Name: ix_vessels_dwt; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_vessels_dwt ON public.vessels USING btree (deadweight_tonnage);


--
-- Name: ix_vessels_grain; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_vessels_grain ON public.vessels USING btree (grain_capacity_m3);


--
-- Name: ix_vessels_name_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_vessels_name_trgm ON public.vessels USING gin (lower((name)::text) public.gin_trgm_ops);


--
-- Name: ix_vessels_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_vessels_owner ON public.vessels USING btree (owner_id);


--
-- Name: ix_vessels_year; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_vessels_year ON public.vessels USING btree (year_built);


--
-- Name: ux_circulation_list_entries_email; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_circulation_list_entries_email ON public.circulation_list_entries USING btree (list_id, lower((email)::text));


--
-- Name: ux_circulation_lists_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_circulation_lists_name ON public.circulation_lists USING btree (lower((name)::text)) WHERE (name IS NOT NULL);


--
-- Name: ux_circulation_lists_single_draft; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_circulation_lists_single_draft ON public.circulation_lists USING btree (is_draft) WHERE is_draft;


--
-- Name: ux_contacts_main_per_company_kind; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_contacts_main_per_company_kind ON public.contacts USING btree (company_id, contact_kind) WHERE (is_main AND (company_id IS NOT NULL));


--
-- Name: ux_email_footers_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_email_footers_name ON public.email_footers USING btree (lower((name)::text));


--
-- Name: ux_email_footers_single_default; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_email_footers_single_default ON public.email_footers USING btree (is_default) WHERE is_default;


--
-- Name: ux_email_templates_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_email_templates_name ON public.email_templates USING btree (lower((name)::text));


--
-- Name: ux_mail_folders_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_mail_folders_name ON public.mail_folders USING btree (lower((name)::text));


--
-- Name: ux_mail_messages_message_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_mail_messages_message_id ON public.mail_messages USING btree (message_id) WHERE (message_id IS NOT NULL);


--
-- Name: ux_mail_messages_uid; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_mail_messages_uid ON public.mail_messages USING btree (imap_folder, imap_validity, imap_uid) WHERE (imap_uid IS NOT NULL);


--
-- Name: ux_mail_rules_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_mail_rules_name ON public.mail_rules USING btree (lower((name)::text));


--
-- Name: ux_vessel_company_links_one_exclusive; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_vessel_company_links_one_exclusive ON public.vessel_company_links USING btree (vessel_id) WHERE ((role)::text = 'exclusive_broker'::text);


--
-- Name: ux_vessel_company_links_pair; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_vessel_company_links_pair ON public.vessel_company_links USING btree (vessel_id, company_id);


--
-- Name: ux_vessels_imo; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_vessels_imo ON public.vessels USING btree (imo_number) WHERE (imo_number IS NOT NULL);


--
-- Name: circulation_list_entries circulation_list_entries_contact_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.circulation_list_entries
    ADD CONSTRAINT circulation_list_entries_contact_id_fkey FOREIGN KEY (contact_id) REFERENCES public.contacts(id) ON DELETE SET NULL;


--
-- Name: circulation_list_entries circulation_list_entries_list_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.circulation_list_entries
    ADD CONSTRAINT circulation_list_entries_list_id_fkey FOREIGN KEY (list_id) REFERENCES public.circulation_lists(id) ON DELETE CASCADE;


--
-- Name: circulation_run_recipients circulation_run_recipients_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.circulation_run_recipients
    ADD CONSTRAINT circulation_run_recipients_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.circulation_runs(id) ON DELETE CASCADE;


--
-- Name: company_ports company_ports_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_ports
    ADD CONSTRAINT company_ports_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;


--
-- Name: company_ports company_ports_port_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_ports
    ADD CONSTRAINT company_ports_port_id_fkey FOREIGN KEY (port_id) REFERENCES public.ports(id) ON DELETE CASCADE;


--
-- Name: company_tonnage_categories company_tonnage_categories_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_tonnage_categories
    ADD CONSTRAINT company_tonnage_categories_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;


--
-- Name: company_tonnage_categories company_tonnage_categories_tonnage_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_tonnage_categories
    ADD CONSTRAINT company_tonnage_categories_tonnage_category_id_fkey FOREIGN KEY (tonnage_category_id) REFERENCES public.tonnage_categories(id) ON DELETE CASCADE;


--
-- Name: contacts contacts_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contacts
    ADD CONSTRAINT contacts_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;


--
-- Name: contacts contacts_person_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contacts
    ADD CONSTRAINT contacts_person_id_fkey FOREIGN KEY (person_id) REFERENCES public.people(id) ON DELETE CASCADE;


--
-- Name: mail_messages mail_messages_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mail_messages
    ADD CONSTRAINT mail_messages_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE SET NULL;


--
-- Name: mail_messages mail_messages_contact_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mail_messages
    ADD CONSTRAINT mail_messages_contact_id_fkey FOREIGN KEY (contact_id) REFERENCES public.contacts(id) ON DELETE SET NULL;


--
-- Name: mail_messages mail_messages_folder_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mail_messages
    ADD CONSTRAINT mail_messages_folder_id_fkey FOREIGN KEY (folder_id) REFERENCES public.mail_folders(id) ON DELETE SET NULL;


--
-- Name: mail_messages mail_messages_person_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mail_messages
    ADD CONSTRAINT mail_messages_person_id_fkey FOREIGN KEY (person_id) REFERENCES public.people(id) ON DELETE SET NULL;


--
-- Name: mail_rule_conditions mail_rule_conditions_rule_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mail_rule_conditions
    ADD CONSTRAINT mail_rule_conditions_rule_id_fkey FOREIGN KEY (rule_id) REFERENCES public.mail_rules(id) ON DELETE CASCADE;


--
-- Name: mail_rules mail_rules_folder_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mail_rules
    ADD CONSTRAINT mail_rules_folder_id_fkey FOREIGN KEY (folder_id) REFERENCES public.mail_folders(id) ON DELETE CASCADE;


--
-- Name: people people_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.people
    ADD CONSTRAINT people_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;


--
-- Name: ports ports_region_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ports
    ADD CONSTRAINT ports_region_id_fkey FOREIGN KEY (region_id) REFERENCES public.regions(id) ON DELETE SET NULL;


--
-- Name: vessel_company_links vessel_company_links_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vessel_company_links
    ADD CONSTRAINT vessel_company_links_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON DELETE CASCADE;


--
-- Name: vessel_company_links vessel_company_links_vessel_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vessel_company_links
    ADD CONSTRAINT vessel_company_links_vessel_id_fkey FOREIGN KEY (vessel_id) REFERENCES public.vessels(id) ON DELETE CASCADE;


--
-- Name: vessels vessels_owner_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vessels
    ADD CONSTRAINT vessels_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES public.companies(id) ON DELETE SET NULL;
