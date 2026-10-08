package com.chartering.model;

/**
 * What an account may do beyond working its desk's data.
 *
 * <p>Three levels, each including the one below it. {@link #PLATFORM_ADMIN} runs the
 * installation - it creates desks and their first administrators - and is still a member of
 * one desk like anybody else, so it works that desk's data and only that desk's. Reaching into
 * another desk's records is not part of any role: managing accounts is one thing, reading the
 * contacts a competitor keeps is another, and no screen here mixes them.
 */
public enum UserRole {
    /** Works the desk's data. */
    USER,
    /** Also creates, disables and resets the accounts of their own desk. */
    TENANT_ADMIN,
    /** Also creates and suspends desks, and manages accounts on any of them. */
    PLATFORM_ADMIN;

    public boolean atLeast(UserRole other) {
        return ordinal() >= other.ordinal();
    }
}
