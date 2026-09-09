package com.chartering.service;

import com.chartering.model.Port;
import com.chartering.model.PortAlias;
import com.chartering.model.TradeArea;
import com.chartering.repository.PortAliasRepository;
import com.chartering.repository.PortRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What one written place name means.
 *
 * <p>The tests that matter are the refusals. This vocabulary is read by a parser that writes
 * to Open Fleet without being watched, so a spelling it resolves wrongly puts one owner's
 * ship on another owner's berth in a row that looks correct forever after. Finding nothing
 * costs a position its miles and leaves it with its water, which is what the feature did
 * before any of this existed.
 */
class PortDirectoryTest {

    private final TradeArea bsea = area(1L, "BSEA");

    private PortDirectory directory(List<Port> ports, List<PortAlias> aliases) {
        PortRepository portRepository = mock(PortRepository.class);
        PortAliasRepository aliasRepository = mock(PortAliasRepository.class);
        when(portRepository.findAllWithArea()).thenReturn(ports);
        when(aliasRepository.findAllWithPort()).thenReturn(aliases);
        PortDirectory d = new PortDirectory(portRepository, aliasRepository);
        d.refresh();
        return d;
    }

    @Test
    void resolvesAPortWhateverPunctuationAndCaseItArrivesIn() {
        PortDirectory d = directory(List.of(port(1L, "St.Petersburg")), List.of());

        assertThat(d.resolve("st petersburg").orElseThrow().id()).isEqualTo(1L);
        assertThat(d.resolve("ST-PETERSBURG").orElseThrow().id()).isEqualTo(1L);
    }

    @Test
    void findsAPortUnderAnOldName() {
        // The reason the table exists. The port was renamed in 2016 and a circular arriving
        // this week still writes the old spelling.
        Port chornomorsk = port(1L, "Chornomorsk");
        PortDirectory d = directory(List.of(chornomorsk),
                List.of(alias(chornomorsk, "Illichivsk"), alias(chornomorsk, "Ilyichevsk")));

        assertThat(d.resolve("ILYICHEVSK").orElseThrow().name()).isEqualTo("Chornomorsk");
    }

    @Test
    void letsAPortsOwnNameBeatSomebodyElsesAlias() {
        // The two tables cannot share an index, so the rule lives in the directory - and it
        // is what makes an alias safe to add: the worst a wrong one can do is answer a
        // question no port name already answered.
        Port real = port(1L, "Marmara");
        Port other = port(2L, "Bandirma");
        PortDirectory d = directory(List.of(real, other), List.of(alias(other, "Marmara")));

        assertThat(d.resolve("Marmara").orElseThrow().id()).isEqualTo(1L);
    }

    @Test
    void refusesANameTwoBerthsShare() {
        // The ports table has no unique index on the name and never has. Picking whichever
        // came first would file a ship in whichever sea the row order happened to give.
        PortDirectory d = directory(List.of(port(1L, "Tripoli"), port(2L, "Tripoli")), List.of());

        assertThat(d.resolve("Tripoli")).isEmpty();
    }

    @Test
    void readsThePortOutOfAPositionLine() {
        // "SALERNO 1/2 SEPT" arrives in the port column as often as "SALERNO" does, because
        // a position list writes the berth and the dates in one breath.
        PortDirectory d = directory(List.of(port(1L, "Salerno"), port(2L, "Port Said")), List.of());

        assertThat(d.findIn("SALERNO 1/2 SEPT").orElseThrow().id()).isEqualTo(1L);
        assertThat(d.findIn("spot at port said ppt").orElseThrow().id()).isEqualTo(2L);
    }

    @Test
    void willNotFindAShortPortNameInsideASentence() {
        // "Bar" is a berth in Montenegro and also an ordinary word. Scanning prose for it
        // would file a ship there because somebody wrote "grain in bulk, bar none".
        PortDirectory d = directory(List.of(port(1L, "Bar")), List.of());

        assertThat(d.findIn("grain in bulk, bar none")).isEmpty();
        // Asked as the whole field, it is a berth: a person or a model put it there as one.
        assertThat(d.resolve("Bar").orElseThrow().id()).isEqualTo(1L);
    }

    @Test
    void carriesTheWaterOutWithTheBerth() {
        // The point of resolving a port at all. A placed berth is what lets Match measure a
        // leg in miles instead of in a broker's round days between two seas.
        PortDirectory d = directory(List.of(port(1L, "Odessa")), List.of());

        PortDirectory.Berth berth = d.resolve("Odessa").orElseThrow();
        assertThat(berth.tradeAreaId()).isEqualTo(1L);
        assertThat(berth.tradeAreaCode()).isEqualTo("BSEA");
    }

    // ------------------------------------------------------------- fixtures

    private Port port(Long id, String name) {
        Port p = new Port();
        p.setId(id);
        p.setName(name);
        p.setTradeArea(bsea);
        return p;
    }

    private static PortAlias alias(Port port, String text) {
        PortAlias a = new PortAlias();
        a.setPort(port);
        a.setAlias(text);
        return a;
    }

    private static TradeArea area(Long id, String code) {
        TradeArea a = new TradeArea();
        a.setId(id);
        a.setCode(code);
        a.setName(code);
        return a;
    }
}
