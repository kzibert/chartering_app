package com.chartering.service.lookup;

import com.chartering.config.VesselLookupProperties;
import com.chartering.model.Vessel;
import com.chartering.model.VesselLookup;
import com.chartering.repository.IntakeItemRepository;
import com.chartering.repository.VesselLookupRepository;
import com.chartering.repository.VesselRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Looking a hull up from her own record rather than from a review item.
 *
 * <p>The facts come from one place — her record — so there is nothing to prefer between
 * sources, which makes these tests mostly about what is searched for and what is refused. The
 * one thing worth stating twice is that this path never decides it has nothing to gain: the
 * {@code alreadyIdentified} flag exists to hold the unattended pass back, and nothing here is
 * unattended.
 */
class VesselLookupFromRecordTest {

    private VesselRepository vessels;
    private VesselLookupRepository lookups;
    private VesselLookupProvider provider;
    private VesselLookupProperties props;
    private VesselLookupService service;

    @BeforeEach
    void setUp() {
        vessels = mock(VesselRepository.class);
        lookups = mock(VesselLookupRepository.class);
        provider = mock(VesselLookupProvider.class);
        props = new VesselLookupProperties();
        props.setEnabled(true);
        props.setMinRequestIntervalMs(0);
        props.setMaxCandidates(5);

        service = new VesselLookupService(props, provider, lookups,
                mock(IntakeItemRepository.class), vessels, new ObjectMapper());

        when(provider.name()).thenReturn("test");
        when(lookups.save(any(VesselLookup.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Vessel hull(String name, String imo) {
        Vessel v = new Vessel();
        v.setId(3L);
        v.setName(name);
        v.setImoNumber(imo);
        when(vessels.findById(3L)).thenReturn(Optional.of(v));
        return v;
    }

    // ------------------------------------------------------------------ what is known

    @Test
    void readsTheFactsOffHerRecord() {
        Vessel v = hull("HACI HILMI-II", "9133513");
        v.setYearBuilt(1996);
        v.setDeadweightTonnage(new BigDecimal("6750"));
        v.setFlag("Turkey");

        VesselLookupService.Known known = service.knownFactsForVessel(v);

        assertThat(known).isNotNull();
        assertThat(known.facts().imo()).isEqualTo("9133513");
        assertThat(known.facts().yearBuilt()).isEqualTo(1996);
        assertThat(known.facts().deadweight()).isEqualByComparingTo("6750");
        assertThat(known.facts().flag()).isEqualTo("Turkey");
    }

    /**
     * The same cleaning the review path does, and for the same reason: {@code LookupMatcher}
     * squashes punctuation but knows nothing of "MV", so scoring "MV ELEMENTS" against the
     * "ELEMENTS" a search returned would read as a disagreement — a search that worked,
     * reported as the wrong ship.
     */
    @Test
    void cleansTheNameBeforeSearchingForIt() {
        VesselLookupService.Known known = service.knownFactsForVessel(hull("MV ELEMENTS", null));

        assertThat(known.searchName()).isEqualTo("ELEMENTS");
        assertThat(known.facts().name()).isEqualTo("ELEMENTS");
    }

    /**
     * A stored 0 is how the older rows here say "not on file". Read as a figure it would be
     * scored against a candidate's real deadweight and count as a disagreement.
     */
    @Test
    void treatsAZeroDeadweightAsNotOnFile() {
        Vessel v = hull("ELEMENTS", null);
        v.setDeadweightTonnage(BigDecimal.ZERO);

        assertThat(service.knownFactsForVessel(v).facts().deadweight()).isNull();
    }

    /**
     * Her number is the question with one answer, so a hull carrying one is worth asking about
     * even where the name column is useless.
     */
    @Test
    void searchesByTheNumberWhenThereIsNoUsableName() {
        VesselLookupService.Known known = service.knownFactsForVessel(hull("   ", "9014561"));

        assertThat(known).isNotNull();
        assertThat(known.searchName()).isEqualTo("9014561");
        assertThat(known.facts().imo()).isEqualTo("9014561");
    }

    @Test
    void knowsNothingAboutAHullWithNeitherNameNorNumber() {
        assertThat(service.knownFactsForVessel(hull(null, null))).isNull();
    }

    /**
     * Never "nothing to gain". That flag holds the unattended pass back from spending somebody
     * else's bandwidth on its own initiative; a person on her record pressing the button has
     * already decided the request is worth making, and a hull with an IMO on file is the case
     * most likely to come back certain.
     */
    @Test
    void neverTreatsHerAsAlreadyIdentified() {
        assertThat(service.knownFactsForVessel(hull("ELEMENTS", "9014561")).alreadyIdentified())
                .isFalse();
    }

    // ------------------------------------------------------------------ running one

    @Test
    void searchesByTheNumberFirstAndRecordsTheMatch() {
        hull("ELEMENTS", "9014561");
        when(provider.searchByImo("9014561")).thenReturn(List.of(
                new VesselParticulars("9014561", "ELEMENTS", "General Cargo", "Panama", 1991,
                        null, new BigDecimal("6977"), null, null, "http://example/9014561")));

        VesselLookup row = service.lookUpVesselNow(3L);

        assertThat(row.getStatus()).isEqualTo(VesselLookup.STATUS_OK);
        assertThat(row.getMatchedImo()).isEqualTo("9014561");
        // The row belongs to her, not to a review item — which is what keeps the two kinds of
        // lookup on the two screens they belong to.
        assertThat(row.getVesselId()).isEqualTo(3L);
        assertThat(row.getIntakeItem()).isNull();
        verify(provider, never()).searchByName(any());
    }

    /** Falls back to the name, which is the ordinary case: the IMO is what we do not have. */
    @Test
    void fallsBackToTheNameWhenThereIsNoNumber() {
        hull("ELEMENTS", null);
        when(provider.searchByName("ELEMENTS")).thenReturn(List.of(
                new VesselParticulars("9014561", "ELEMENTS", null, null, null, null, null,
                        null, null, "http://example/9014561")));

        VesselLookup row = service.lookUpVesselNow(3L);

        assertThat(row.getQuery()).isEqualTo("ELEMENTS");
        assertThat(row.getMatchedImo()).isEqualTo("9014561");
    }

    /**
     * One answer on the screen, so the previous search for her is replaced. Rows raised by
     * review items are left alone — hence the narrower finder.
     */
    @Test
    void replacesHerPreviousSearch() {
        hull("ELEMENTS", null);
        VesselLookup old = new VesselLookup();
        old.setId(99L);
        when(lookups.findTopByVesselIdAndIntakeItemIsNullOrderByFetchedAtDesc(3L))
                .thenReturn(Optional.of(old));
        when(provider.searchByName("ELEMENTS")).thenReturn(List.of());

        service.lookUpVesselNow(3L);

        verify(lookups).delete(old);
    }

    /** Nothing came back is a result and is recorded, not an error. */
    @Test
    void recordsThatNothingCameBack() {
        hull("ELEMENTS", null);
        when(provider.searchByName("ELEMENTS")).thenReturn(List.of());

        assertThat(service.lookUpVesselNow(3L).getStatus())
                .isEqualTo(VesselLookup.STATUS_NO_MATCH);
    }

    @Test
    void refusesAHullWithNothingToSearchFor() {
        hull(null, null);

        assertThatThrownBy(() -> service.lookUpVesselNow(3L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nothing to search for");
        verify(lookups, never()).save(any());
    }

    @Test
    void refusesWhenTheFeatureIsOff() {
        props.setEnabled(false);

        assertThatThrownBy(() -> service.lookUpVesselNow(3L))
                .isInstanceOf(com.chartering.exception.FeatureDisabledException.class);
        verify(vessels, never()).findById(anyLong());
    }

    @Test
    void describesNothingWhenTheFeatureIsOff() {
        props.setEnabled(false);

        assertThat(service.describeForVessel(3L)).isNull();
    }

    @Test
    void applyingRefusesBeforeAnythingHasBeenSearched() {
        when(lookups.findTopByVesselIdAndIntakeItemIsNullOrderByFetchedAtDesc(3L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.applyVesselLookup(3L, List.of("imoNumber")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nothing has been looked up");
    }
}
