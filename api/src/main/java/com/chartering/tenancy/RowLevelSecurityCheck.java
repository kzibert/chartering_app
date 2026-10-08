package com.chartering.tenancy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Says at startup whether the database's row-level security (V36) is actually up.
 *
 * <p>It is not, silently, when the application connects as a role Postgres exempts from it: a
 * superuser - the default user of a local postgres container - or a role with BYPASSRLS, which
 * some hosted providers give their owner role. Nothing fails in that case; every desk is still
 * separated by Hibernate. What is missing is the second wall, and the only way to know is to
 * ask, so this asks and says so in the log where a deployment's health is read.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RowLevelSecurityCheck implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Boolean exempt = jdbc.queryForObject(
                    "select rolsuper or rolbypassrls from pg_roles where rolname = current_user", Boolean.class);
            String role = jdbc.queryForObject("select current_user", String.class);
            if (Boolean.TRUE.equals(exempt)) {
                log.warn("Row-level security is NOT enforced: the database role '{}' is a superuser or has "
                        + "BYPASSRLS. Desks are still separated by the application; for the database to "
                        + "enforce it too, connect as a role without either (see V36).", role);
            } else {
                log.info("Row-level security is enforced for the database role '{}'.", role);
            }
        } catch (RuntimeException e) {
            log.warn("Could not tell whether row-level security is enforced: {}", e.getMessage());
        }
    }
}
