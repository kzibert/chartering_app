package com.chartering.service.parser;

import com.chartering.config.MailboxProperties;
import com.chartering.config.ParserProperties;
import com.chartering.dto.IntakeItemResponse;
import com.chartering.dto.IntakeSuggestionResponse;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.Company;
import com.chartering.model.IntakeItem;
import com.chartering.model.IntakeItemKind;
import com.chartering.model.ParsedEmail;
import com.chartering.model.Vessel;
import com.chartering.model.VesselExName;
import com.chartering.repository.CargoSourceRepository;
import com.chartering.repository.IntakeItemRepository;
import com.chartering.repository.IntakeItemSourceRepository;
import com.chartering.repository.ParsedEmailRepository;
import com.chartering.repository.VesselExNameRepository;
import com.chartering.repository.VesselRepository;
import com.chartering.service.ParserSettings;
import com.chartering.service.lookup.VesselLookupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The shortlist a {@code NEW_VESSEL} item offers, as the drawer receives it.
 *
 * <p>What is under test is one decision: the identity half of a suggestion is read back out
 * of the stored payload, because it is a record of the search that ran, and the particulars
 * half is fetched now. An item can wait in this queue for weeks while somebody fills in a
 * deadweight or attaches an owner, and a card quoting the fleet as it stood when the email
 * arrived would disagree with the vessel screen open in the next tab — over exactly the
 * figures a reader is comparing.
 */
class IntakeSuggestionsTest {

    private IntakeItemRepository items;
    private VesselRepository vessels;
    private VesselExNameRepository exNames;
    private IntakeQueryService queries;

    @BeforeEach
    void setUp() {
        ParserProperties props = new ParserProperties();
        props.setEnabled(true);

        items = mock(IntakeItemRepository.class);
        vessels = mock(VesselRepository.class);
        exNames = mock(VesselExNameRepository.class);
        IntakeItemSourceRepository itemSources = mock(IntakeItemSourceRepository.class);
        VesselLookupService lookups = mock(VesselLookupService.class);

        when(itemSources.forItem(anyLong())).thenReturn(List.of());
        // The web card is a separate feature answering a separate question; off here so the
        // assembly under test is the only thing the response is carrying.
        when(lookups.isEnabled()).thenReturn(false);

        queries = new IntakeQueryService(
                props,
                mock(MailboxProperties.class),
                mock(ParserSettings.class),
                mock(ParserSweepService.class),
                mock(EmailParserClient.class),
                items,
                mock(ParsedEmailRepository.class),
                mock(CargoSourceRepository.class),
                mock(IntakeService.class),
                lookups,
                vessels,
                exNames,
                itemSources,
                new DtoMapper(),
                new ObjectMapper());
    }

    /** An item whose payload offers one hull, by the id the search recorded. */
    private void queueItemSuggesting(long vesselId, String nameWhenSearched) {
        IntakeItem item = new IntakeItem();
        item.setId(1L);
        item.setKind(IntakeItemKind.NEW_VESSEL);
        item.setSubjectLabel("LIUDMILA");
        item.setParsedEmail(new ParsedEmail());
        item.setPayload(("{\"searchedBy\":\"name LIUDMILA\",\"vessel\":{\"name\":\"LIUDMILA\"},"
                + "\"suggestions\":[{\"vesselId\":%d,\"name\":\"%s\","
                + "\"imoNumber\":\"9014561\",\"reason\":\"DWT 6,950 against 6,977\"}]}")
                .formatted(vesselId, nameWhenSearched));
        when(items.findWithEmailById(1L)).thenReturn(Optional.of(item));
    }

    private static Vessel celia() {
        Company owner = new Company();
        owner.setId(3L);
        owner.setName("Interscan");

        Vessel v = new Vessel();
        v.setId(42L);
        v.setName("CELIA");
        v.setImoNumber("9014561");
        v.setDeadweightTonnage(new BigDecimal("6977"));
        v.setYearBuilt(2003);
        v.setOwner(owner);
        return v;
    }

    @Test
    void carriesTheHullsRecordAsItStandsNow() {
        queueItemSuggesting(42L, "CELIA");
        when(vessels.findWithOwnerByIdIn(any())).thenReturn(List.of(celia()));
        when(exNames.findByVesselIds(any())).thenReturn(List.of());

        IntakeItemResponse response = queries.get(1L);

        assertThat(response.suggestions()).hasSize(1);
        IntakeSuggestionResponse s = response.suggestions().get(0);
        assertThat(s.vesselId()).isEqualTo(42L);
        // The evidence is the search's, not the fleet's: these are the figures the scorer
        // weighed on the day it weighed them, and rewriting them from the record would
        // credit the comparison with facts it never saw.
        assertThat(s.reason()).isEqualTo("DWT 6,950 against 6,977");
        // Her particulars are the fleet's, so an owner attached last week is on the card.
        assertThat(s.vessel()).isNotNull();
        assertThat(s.vessel().ownerName()).isEqualTo("Interscan");
        assertThat(s.vessel().deadweightTonnage()).isEqualByComparingTo("6977");
        assertThat(s.vessel().yearBuilt()).isEqualTo(2003);
    }

    /**
     * The most valuable thing this shortlist does: she is on file under the name she carried
     * three owners ago, and the card has to say so or the evidence for the whole suggestion
     * is on another screen.
     */
    @Test
    void printsTheNamesSheUsedToCarry() {
        queueItemSuggesting(42L, "CELIA");
        Vessel celia = celia();
        VesselExName former = new VesselExName();
        former.setId(7L);
        former.setVessel(celia);
        former.setName("LIUDMILA");
        when(vessels.findWithOwnerByIdIn(any())).thenReturn(List.of(celia));
        when(exNames.findByVesselIds(any())).thenReturn(List.of(former));

        IntakeItemResponse response = queries.get(1L);

        assertThat(response.suggestions().get(0).vessel().exNames())
                .extracting(e -> e.name())
                .containsExactly("LIUDMILA");
    }

    /**
     * Deleted since the item was raised. The row stays without a record behind it: the queue
     * counted this shortlist from the payload, and quietly returning a shorter one would make
     * the list row and the drawer disagree about how many hulls were offered.
     */
    @Test
    void keepsARowForAHullThatHasSinceBeenDeleted() {
        queueItemSuggesting(42L, "CELIA");
        when(vessels.findWithOwnerByIdIn(any())).thenReturn(List.of());
        when(exNames.findByVesselIds(any())).thenReturn(List.of());

        IntakeItemResponse response = queries.get(1L);

        assertThat(response.suggestions()).hasSize(1);
        assertThat(response.suggestions().get(0).name()).isEqualTo("CELIA");
        assertThat(response.suggestions().get(0).vessel()).isNull();
    }

    /** An item of another kind has no shortlist, and the field is absent rather than empty. */
    @Test
    void isAbsentOnAnItemThatOffersNoShortlist() {
        IntakeItem item = new IntakeItem();
        item.setId(1L);
        item.setKind(IntakeItemKind.VESSEL_FIELDS);
        item.setParsedEmail(new ParsedEmail());
        item.setPayload("{\"vesselId\":42,\"diffs\":[],\"filled\":[]}");
        when(items.findWithEmailById(1L)).thenReturn(Optional.of(item));

        assertThat(queries.get(1L).suggestions()).isNull();
    }
}
