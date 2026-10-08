-- A desk's own spellings, on top of the market's.
--
-- port_aliases and trade_area_aliases are the vocabulary the resolver reads circulars with -
-- ILLICHIVSK for Chornomorsk, W.MED for the West Mediterranean - and they are global: every
-- desk reads the same market. But each desk also has correspondents with habits of their own,
-- and a spelling one desk's brokers use daily is noise to another desk's. So a desk may add
-- aliases that only it reads with: tenant_id set means "this desk's", null means everybody's.
--
-- The global rows stay as they are; only migrations write them, as before. A desk's alias for
-- a key that is already a global alias overrides it for that desk (PortDirectory,
-- TradeAreaGraph), which is what lets a desk correct a spelling it reads differently. A port's
-- or area's own name still beats any alias, global or not.
ALTER TABLE public.port_aliases ADD COLUMN tenant_id bigint REFERENCES public.tenants (id);
ALTER TABLE public.trade_area_aliases ADD COLUMN tenant_id bigint REFERENCES public.tenants (id);

DROP INDEX public.ux_port_aliases_key;
CREATE UNIQUE INDEX ux_port_aliases_key ON public.port_aliases (coalesce(tenant_id, 0), alias_key);
CREATE INDEX ix_port_aliases_tenant ON public.port_aliases (tenant_id);

DROP INDEX public.ux_trade_area_aliases_key;
CREATE UNIQUE INDEX ux_trade_area_aliases_key ON public.trade_area_aliases (coalesce(tenant_id, 0), alias_key);
CREATE INDEX ix_trade_area_aliases_tenant ON public.trade_area_aliases (tenant_id);
