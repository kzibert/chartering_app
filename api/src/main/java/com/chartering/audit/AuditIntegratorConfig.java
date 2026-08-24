package com.chartering.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Hangs {@link AuditEventListener} on Hibernate's flush events.
 *
 * <p>Through an {@code IntegratorProvider} rather than the {@code META-INF/services} file
 * Hibernate would otherwise discover an {@link Integrator} by. The difference is that this
 * one is constructed here, by Spring, holding the beans it needs — the service-loader route
 * builds the integrator itself and would leave the listener reaching for a static handle on
 * the application context to find its writer.
 */
@Configuration
@RequiredArgsConstructor
public class AuditIntegratorConfig implements HibernatePropertiesCustomizer {

    private final DataChangeWriter writer;
    private final ObjectMapper objectMapper;

    @Override
    public void customize(Map<String, Object> properties) {
        AuditEventListener listener = new AuditEventListener(writer, objectMapper);
        properties.put("hibernate.integrator_provider",
                (IntegratorProvider) () -> List.of(new AuditIntegrator(listener)));
    }

    @RequiredArgsConstructor
    private static final class AuditIntegrator implements Integrator {

        private final AuditEventListener listener;

        @Override
        public void integrate(Metadata metadata, BootstrapContext bootstrapContext,
                              SessionFactoryImplementor sessionFactory) {
            EventListenerRegistry registry =
                    sessionFactory.getServiceRegistry().requireService(EventListenerRegistry.class);
            // Appended, not prepended: Hibernate's own post-* listeners do the work that
            // makes the state arrays meaningful, and running ahead of them would mean
            // reading an id that has not been assigned yet on an insert.
            registry.appendListeners(EventType.POST_INSERT, listener);
            registry.appendListeners(EventType.POST_UPDATE, listener);
            registry.appendListeners(EventType.POST_DELETE, listener);
        }

        @Override
        public void disintegrate(SessionFactoryImplementor sessionFactory,
                                 org.hibernate.service.spi.SessionFactoryServiceRegistry serviceRegistry) {
            // Nothing to undo: the registry goes with the session factory.
        }
    }
}
