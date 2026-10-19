package com.chartering.service.parser;

import com.chartering.repository.CompanyRepository;
import com.chartering.repository.ContactRepository;
import com.chartering.repository.DataChangeRepository;
import com.chartering.repository.PersonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A cheap fingerprint of the desk's companies, people and addresses: it moves whenever one of
 * them is created, edited or deleted, and is the same when none of them has.
 *
 * <p><b>Why a fingerprint.</b> A board post's signature can only start naming a firm when this
 * set changes, so the reconcile pass need not read the posts again on every tick. Comparing the rows
 * themselves would cost the very reads being avoided, so the answer is read from what the log keeps.
 *
 * <p><b>Why the change log and the counts.</b> Every create, edit and delete made through
 * Hibernate writes a {@code data_changes} row in the same transaction ({@code audit/}), so the
 * log's count and newest id move with them. A row removed by {@code ON DELETE CASCADE}, or by a
 * bulk SQL fix, writes no log row, so the three counts are read as well. The known gap is a bulk
 * SQL {@code UPDATE} that changes a name or a website: nothing here sees it until the next audited
 * write to the desk's companies, people or addresses. The reconcile pass is a convenience, so a
 * late name is acceptable; a missed one would be a row that stays unnamed until that write.
 *
 * <p>Read per desk, under the caller's {@code TenantContext}: every repository here is
 * tenant-scoped, so the fingerprint is the desk's own.
 */
@Component
@RequiredArgsConstructor
public class CompanyDirectoryVersion {

    private static final List<String> TYPES = List.of("company", "person", "contact");

    /** Equal versions mean the directory has not changed; a record gets equals for free. */
    public record Version(long auditRows, long latestAuditId, long companies, long people, long contacts) {
    }

    private final DataChangeRepository changes;
    private final CompanyRepository companies;
    private final PersonRepository people;
    private final ContactRepository contacts;

    public Version current() {
        return new Version(changes.countForEntityTypes(TYPES), changes.latestIdForEntityTypes(TYPES),
                companies.count(), people.count(), contacts.count());
    }
}
