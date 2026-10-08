package com.chartering.service;

import com.chartering.dto.DeskAliasRequest;
import com.chartering.dto.DeskAliasResponse;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.model.Port;
import com.chartering.model.PortAlias;
import com.chartering.model.TradeArea;
import com.chartering.model.TradeAreaAlias;
import com.chartering.repository.PortAliasRepository;
import com.chartering.repository.PortRepository;
import com.chartering.repository.TradeAreaAliasRepository;
import com.chartering.repository.TradeAreaRepository;
import com.chartering.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A desk's own spellings of ports and trade areas, read on top of the market's (V35).
 *
 * <p>The resolver that files a circular's "load ILLICHIVSK" against a berth asks
 * {@link PortDirectory} and {@link TradeAreaGraph}; both lay the desk's aliases over the
 * market's, so an alias added here is how a desk teaches the reading a habit of its own
 * correspondents without changing it for anybody else. The market's own aliases are not
 * edited here - they arrive with migrations, for every desk at once.
 *
 * <p>Refused, each with its reason: an alias that is a port's or area's own name (it would
 * never be read - a name beats an alias), and one the desk already has.
 */
@Service
@RequiredArgsConstructor
public class DeskAliasService {

    private final PortAliasRepository portAliases;
    private final TradeAreaAliasRepository areaAliases;
    private final PortRepository ports;
    private final TradeAreaRepository areas;
    private final PortDirectory portDirectory;
    private final TradeAreaGraph areaGraph;

    @Transactional(readOnly = true)
    public List<DeskAliasResponse> list() {
        Long desk = TenantContext.require();
        List<DeskAliasResponse> out = new ArrayList<>();
        portAliases.findForTenantWithPort(desk).forEach(a ->
                out.add(new DeskAliasResponse("PORT", a.getId(), a.getAlias(), a.getPort().getId(), a.getPort().getName())));
        areaAliases.findForTenantWithArea(desk).forEach(a ->
                out.add(new DeskAliasResponse("AREA", a.getId(), a.getAlias(), a.getTradeArea().getId(), a.getTradeArea().getName())));
        return out;
    }

    @Transactional
    public DeskAliasResponse add(DeskAliasRequest req) {
        Long desk = TenantContext.require();
        String alias = req.getAlias().strip();
        String key = TradeAreaAlias.key(alias);
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("An alias needs at least one letter or digit.");
        }
        DeskAliasResponse added;
        if ("PORT".equals(req.getKind())) {
            Port port = ports.findById(req.getTargetId())
                    .orElseThrow(() -> new ResourceNotFoundException("Port", req.getTargetId()));
            if (key.equals(PortAlias.key(port.getName()))) {
                throw new IllegalArgumentException("That is the port's own name; it is already read as " + port.getName() + ".");
            }
            if (portAliases.findForTenantWithPort(desk).stream().anyMatch(a -> Objects.equals(key, PortAlias.key(a.getAlias())))) {
                throw new IllegalStateException("The desk already reads '" + alias + "' as a port.");
            }
            PortAlias row = new PortAlias();
            row.setPort(port);
            row.setAlias(alias);
            row.setTenantId(desk);
            portAliases.save(row);
            added = new DeskAliasResponse("PORT", row.getId(), alias, port.getId(), port.getName());
        } else {
            TradeArea area = areas.findById(req.getTargetId())
                    .orElseThrow(() -> new ResourceNotFoundException("Trade area", req.getTargetId()));
            if (areaAliases.findForTenantWithArea(desk).stream().anyMatch(a -> Objects.equals(key, TradeAreaAlias.key(a.getAlias())))) {
                throw new IllegalStateException("The desk already reads '" + alias + "' as a trade area.");
            }
            TradeAreaAlias row = new TradeAreaAlias();
            row.setTradeArea(area);
            row.setAlias(alias);
            row.setTenantId(desk);
            areaAliases.save(row);
            added = new DeskAliasResponse("AREA", row.getId(), alias, area.getId(), area.getName());
        }
        refresh(desk);
        return added;
    }

    /** Only the desk's own: a market alias is not this desk's to remove, and answers 404. */
    @Transactional
    public void delete(String kind, Long id) {
        Long desk = TenantContext.require();
        if ("PORT".equals(kind)) {
            PortAlias row = portAliases.findById(id).filter(a -> desk.equals(a.getTenantId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Port alias", id));
            portAliases.delete(row);
        } else {
            TradeAreaAlias row = areaAliases.findById(id).filter(a -> desk.equals(a.getTenantId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Trade area alias", id));
            areaAliases.delete(row);
        }
        refresh(desk);
    }

    /**
     * After the commit, not now: a snapshot rebuilt by another request between this write and
     * its commit would be built without it and kept until the next change.
     */
    private void refresh(Long desk) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                portDirectory.refresh(desk);
                areaGraph.refresh(desk);
            }
        });
    }
}
