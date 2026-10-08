package com.chartering.model;

/** Whether a desk's users may log in. Suspending keeps every row and refuses every login. */
public enum TenantStatus {
    ACTIVE,
    SUSPENDED
}
