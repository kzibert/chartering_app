package com.chartering.tenancy;

import jakarta.persistence.Entity;
import org.hibernate.annotations.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every entity is a desk's unless this file says otherwise.
 *
 * <p>The failure this guards against is quiet: an entity added next year without
 * {@code @TenantId} works perfectly on a one-desk installation, passes every other test, and on
 * a shared one shows every desk every desk's rows. So the default is the safe one - a new
 * entity fails here until it either carries the field or is named below with the reason it is
 * global, which is a decision somebody then has to make on purpose.
 */
class TenantScopeTest {

    /** Global on purpose. Each needs a reason, written where the class or V31 explains it. */
    private static final Set<String> GLOBAL = Set.of(
            // The login reads these before any desk is known.
            "Tenant", "AppUser",
            // One table, two scopes, chosen per key by SettingsStore.
            "AppSetting",
            // Copies of public pages, fetched once for every desk.
            "FeedSource", "FeedItem",
            // The market's shared vocabulary.
            "Port", "PortAlias", "Region", "TonnageCategory",
            "TradeArea", "TradeAreaAlias", "TradeAreaDistance", "SeaWaypoint", "SeaLeg");

    @Test
    void everyEntityIsScopedToADeskOrDeclaredGlobal() throws Exception {
        List<String> unscoped = new ArrayList<>();
        for (Class<?> entity : entities()) {
            boolean scoped = Arrays.stream(entity.getDeclaredFields()).anyMatch(f -> f.isAnnotationPresent(TenantId.class));
            if (!scoped && !GLOBAL.contains(entity.getSimpleName())) unscoped.add(entity.getSimpleName());
            if (scoped) assertThat(GLOBAL).as(entity.getSimpleName() + " is scoped and listed as global").doesNotContain(entity.getSimpleName());
        }
        assertThat(unscoped)
                .as("Entities with no @TenantId field. Add one (and a tenant_id column), or list the entity "
                        + "in GLOBAL with the reason it belongs to no desk.")
                .isEmpty();
    }

    @Test
    void theTenantFieldIsNeverWrittenByTheApplication() throws Exception {
        for (Class<?> entity : entities()) {
            for (Field f : entity.getDeclaredFields()) {
                if (!f.isAnnotationPresent(TenantId.class)) continue;
                jakarta.persistence.Column column = f.getAnnotation(jakarta.persistence.Column.class);
                assertThat(column).as(entity.getSimpleName() + ".tenantId has a @Column").isNotNull();
                assertThat(column.updatable()).as(entity.getSimpleName() + ": a row never changes desk").isFalse();
            }
        }
    }

    /**
     * {@code getReferenceById} on an id a caller sent skips the load that would have found the
     * row belongs to another desk - and the foreign key check that follows does not look at
     * desks at all. Services resolve ids with a finder instead; the few internal uses that
     * pass an id this same code just loaded are listed here.
     */
    @Test
    void servicesDoNotTakeReferencesToIdsTheyHaveNotLoaded() throws Exception {
        Set<String> allowed = Set.of("FeedSummaryRecorder.java", "VesselLookupService.java",
                "IntakePasteService.java", "UserBootstrap.java");
        Path root = Path.of("src/main/java/com/chartering");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (Files.readString(p).contains("getReferenceById(") && !allowed.contains(p.getFileName().toString())) {
                    offenders.add(p.getFileName().toString());
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    private static List<Class<?>> entities() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.chartering.model")) {
            found.add(Class.forName(bd.getBeanClassName()));
        }
        assertThat(found).isNotEmpty();
        return found;
    }
}
