package com.chartering.tenancy;

import com.chartering.model.TenantStatus;
import com.chartering.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Consumer;

/**
 * The desks background work runs for. A timer has no logged-in desk, so it asks here and
 * does its work once per desk, each inside {@link TenantContext#runAs}.
 *
 * <p>Suspended desks are left out: suspending a desk is meant to stop it, and a sweep still
 * reading its mail would be a desk that stopped only at the login screen.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantDirectory {

    private final TenantRepository tenants;

    @Transactional(readOnly = true)
    public List<Long> activeIds() {
        return tenants.findIdsByStatus(TenantStatus.ACTIVE);
    }

    /**
     * {@code work} once per active desk. One desk failing is logged and does not stop the
     * others: a desk whose data trips a bug must not halt every other desk's work with it.
     */
    public void forEachActive(String what, Consumer<Long> work) {
        for (Long id : activeIds()) {
            try {
                TenantContext.runAs(id, () -> work.accept(id));
            } catch (RuntimeException e) {
                log.error("{} failed for tenant {}", what, id, e);
            }
        }
    }
}
