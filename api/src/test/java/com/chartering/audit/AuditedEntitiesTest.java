package com.chartering.audit;

import com.chartering.model.Company;
import com.chartering.model.Contact;
import com.chartering.model.MailMessage;
import com.chartering.model.Person;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scope of the log. The exclusions matter as much as the inclusions — see the class
 * comment on {@link AuditedEntities} for why "log everything" is not the safe default it
 * sounds like.
 */
class AuditedEntitiesTest {

    @Test
    void logsTheRecordsSomebodyEdits() {
        assertThat(AuditedEntities.typeOf(new Company())).isEqualTo("company");
        assertThat(AuditedEntities.typeOf(new Person())).isEqualTo("person");
        assertThat(AuditedEntities.typeOf(new Contact())).isEqualTo("contact");
    }

    @Test
    void doesNotLogMailSyncedFromTheServer() {
        // Auditing this would write more history rows per sync than the sync writes
        // messages, to record that a machine copied a mailbox to itself.
        assertThat(AuditedEntities.typeOf(new MailMessage())).isNull();
    }

    @Test
    void describesARecordByWhateverNamesIt() {
        Company company = new Company();
        company.setName("FEDNAV");
        assertThat(AuditedEntities.labelOf(company)).isEqualTo("FEDNAV");

        Contact contact = new Contact();
        contact.setContactValue("ops@fednav.com");
        assertThat(AuditedEntities.labelOf(contact)).isEqualTo("ops@fednav.com");
    }

    @Test
    void trimsALabelRatherThanLettingItOverflowTheColumn() {
        Company company = new Company();
        company.setName("x".repeat(400));
        assertThat(AuditedEntities.labelOf(company)).hasSize(255);
    }

    @Test
    void survivesARecordWithNothingToCallItself() {
        assertThat(AuditedEntities.labelOf(new Company())).isNull();
        assertThat(AuditedEntities.labelOf(null)).isNull();
    }

    @Test
    void mapsATypeNameBackToItsClassForTheRevert() {
        assertThat(AuditedEntities.classOf("contact")).isEqualTo(Contact.class);
        assertThat(AuditedEntities.classOf("nonsense")).isNull();
    }

    @Test
    void doesNotLogTheTimestampsHibernateMaintainsOnEverySave() {
        // Logging these would put an "updatedAt changed" row beside every real change, and
        // one on its own beside every save that changed nothing else.
        assertThat(AuditedEntities.IGNORED_FIELDS).contains("createdAt", "updatedAt");
    }
}
