package com.chartering.service;

import com.chartering.dto.VesselRequest;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.Vessel;
import com.chartering.model.VesselExName;
import com.chartering.repository.CompanyRepository;
import com.chartering.repository.ContactRepository;
import com.chartering.repository.VesselCompanyLinkRepository;
import com.chartering.repository.VesselExNameRepository;
import com.chartering.repository.VesselPositionRepository;
import com.chartering.repository.VesselRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Renaming a hull on her own edit form keeps the name she had.
 *
 * <p>The point of the rule is the circular that arrives next week still calling her by the old
 * name. So the tests are about what is kept and, more importantly, what is not: a typo being
 * corrected must not leave a former name that was never a name, and a hull renamed back to
 * something she already lists must not accumulate a second copy of it.
 */
class VesselRenameExNameTest {

    private VesselRepository vessels;
    private VesselExNameRepository exNames;
    private VesselService service;

    @BeforeEach
    void setUp() {
        vessels = mock(VesselRepository.class);
        exNames = mock(VesselExNameRepository.class);
        DtoMapper mapper = mock(DtoMapper.class);
        service = new VesselService(vessels, mock(CompanyRepository.class),
                mock(ContactRepository.class), mock(VesselCompanyLinkRepository.class),
                exNames, mock(VesselPositionRepository.class),
                mock(RecipientSelectionService.class),
                mock(com.chartering.service.lookup.VesselLookupService.class), mapper);

        when(vessels.save(any(Vessel.class))).thenAnswer(i -> i.getArgument(0));
        when(exNames.findByVesselIdOrderByNameAsc(anyLong())).thenReturn(List.of());
    }

    private Vessel onFile(String name) {
        Vessel v = new Vessel();
        v.setId(7L);
        v.setName(name);
        when(vessels.findById(7L)).thenReturn(Optional.of(v));
        // update() returns the former names with the record, and that read checks the hull
        // exists on its own account.
        when(vessels.existsById(7L)).thenReturn(true);
        return v;
    }

    private VesselRequest renameTo(String name) {
        VesselRequest req = new VesselRequest();
        req.setName(name);
        return req;
    }

    @Test
    void keepsThePreviousNameWhenTheNameChanges() {
        onFile("GUBERNATOR KAMCHATKI");

        service.update(7L, renameTo("ELEMENTS"));

        ArgumentCaptor<VesselExName> saved = ArgumentCaptor.forClass(VesselExName.class);
        verify(exNames).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("GUBERNATOR KAMCHATKI");
        // Says where the row came from: nobody entered this, a save produced it. The Edit form
        // can delete it, which is the answer to a typo corrected twice.
        assertThat(saved.getValue().getSource()).isEqualTo(VesselExName.SOURCE_RENAME);
    }

    @Test
    void recordsNothingWhenTheNameIsUnchanged() {
        onFile("ELEMENTS");

        service.update(7L, renameTo("ELEMENTS"));

        verify(exNames, never()).save(any());
    }

    /**
     * The same ship spelled differently is not a rename. This is the common edit to a name
     * field and it must not leave a row behind, or every hull tidied up carries a former name
     * identical to her current one.
     */
    @Test
    void treatsACaseOrSpacingFixAsTheSameName() {
        onFile("NORD  STAR ");

        service.update(7L, renameTo("Nord  Star"));

        verify(exNames, never()).save(any());
    }

    @Test
    void doesNotAddAFormerNameSheAlreadyLists() {
        onFile("ELEMENTS");
        when(exNames.existsByVesselIdAndNameIgnoreCase(7L, "ELEMENTS")).thenReturn(true);

        service.update(7L, renameTo("KATERINA"));

        verify(exNames, never()).save(any());
    }

    /**
     * A record whose name column is empty has nothing worth keeping, and a blank former name
     * would fail the NOT NULL on the column rather than telling anybody why.
     */
    @Test
    void ignoresAnEmptyPreviousName() {
        onFile("   ");

        service.update(7L, renameTo("ELEMENTS"));

        verify(exNames, never()).save(any());
    }

    /**
     * Clearing the name is a rejected edit elsewhere, not a rename — there is no new name for
     * the old one to be former to, so nothing is filed.
     */
    @Test
    void ignoresAClearedNewName() {
        onFile("ELEMENTS");

        service.update(7L, renameTo("  "));

        verify(exNames, never()).save(any());
    }

    @Test
    void doesNotTouchFormerNamesOnCreate() {
        service.create(renameTo("ELEMENTS"));

        verify(exNames, never()).save(any());
        verify(exNames, never()).existsByVesselIdAndNameIgnoreCase(anyLong(), anyString());
    }
}
