package com.chartering.tenancy;

import java.util.function.Supplier;

/**
 * The value of the {@link Owned#FILTER} filter's {@code viewer} parameter: the person on the
 * thread, or 0 for work bound to nobody in particular, which the filter reads as "the whole
 * desk". Instantiated by Hibernate, so it has a no-argument constructor and asks
 * {@link TenantContext} rather than being given anything.
 */
public class ViewerResolver implements Supplier<Long> {

    /** "No particular person": what a desk-wide sweep sees through the filter. */
    public static final Long WHOLE_DESK = 0L;

    @Override
    public Long get() {
        return TenantContext.currentUser().orElse(WHOLE_DESK);
    }
}
