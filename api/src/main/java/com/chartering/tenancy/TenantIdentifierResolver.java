package com.chartering.tenancy;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Tells Hibernate which desk a session belongs to, which is what makes {@code @TenantId}
 * work: Hibernate stamps that value on every insert of a tenant-scoped entity and adds
 * {@code tenant_id = ?} to every query over one - JPQL, criteria, Specifications and loads by
 * id alike. The 94 repository queries and ten Specifications in this application did not
 * change to become desk-scoped; this class is why.
 *
 * <p>With no desk on the thread the answer is {@link #NO_TENANT}, an id no desk has, rather
 * than an exception. Hibernate asks at the start of <em>every</em> session, including the ones
 * that only read accounts at login or reference data at startup, and those are legitimate.
 * What the sentinel does to tenant-scoped data is the point: reads find nothing, and an insert
 * fails on the foreign key to {@code tenants} - see {@link TenantContext}.
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<Long>, HibernatePropertiesCustomizer {

    /** No desk has id 0 (identity columns start at 1); used when nothing is bound. */
    public static final Long NO_TENANT = 0L;

    @Override
    public Long resolveCurrentTenantIdentifier() {
        return TenantContext.current().orElse(NO_TENANT);
    }

    /**
     * False: Spring opens a session per transaction and never reuses one across desks, so
     * there is no "current session" for Hibernate to compare against.
     */
    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
