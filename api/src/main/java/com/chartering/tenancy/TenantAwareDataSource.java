package com.chartering.tenancy;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Tells every connection the application takes which desk it is working for, so the
 * database's row-level security (V36) can hold the line Hibernate's {@code @TenantId} draws.
 *
 * <p>Set on every checkout rather than reset on return: a pooled connection's previous desk is
 * overwritten before anything runs on it, so there is no path - an exception, a forgotten
 * close - by which one desk's setting reaches the next borrower. No desk on the thread sets
 * it empty, which the policies read as "none", and which shows nothing.
 *
 * <p>Session-level ({@code set_config(..., false)}), not transaction-local: Spring checks a
 * connection out before it begins the transaction, and a transaction-local setting made then
 * would be gone by the first query.
 *
 * <p>The cost is one round trip per checkout, which Spring makes once per transaction.
 */
@Component
public class TenantAwareDataSource implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource ds && !(bean instanceof DeskBound)) {
            return new DeskBound(ds);
        }
        return bean;
    }

    static final class DeskBound extends DelegatingDataSource {

        DeskBound(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return bind(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return bind(super.getConnection(username, password));
        }

        private static Connection bind(Connection connection) throws SQLException {
            String tenant = TenantContext.current().map(String::valueOf).orElse("");
            try (PreparedStatement ps = connection.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
                ps.setString(1, tenant);
                ps.execute();
            } catch (SQLException e) {
                connection.close();
                throw e;
            }
            return connection;
        }
    }
}
