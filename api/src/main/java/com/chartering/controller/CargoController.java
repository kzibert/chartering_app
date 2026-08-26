package com.chartering.controller;

import com.chartering.dto.CargoRequest;
import com.chartering.dto.CargoResponse;
import com.chartering.dto.PageResponse;
import com.chartering.model.CargoStatus;
import com.chartering.service.CargoService;
import com.chartering.service.CargoService.CargoFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/cargoes")
@RequiredArgsConstructor
@Tag(name = "Cargoes", description = "Cargo enquiries in hand, and what became of them")
public class CargoController {

    private final CargoService cargoService;

    @GetMapping
    @Operation(summary = "Search cargoes",
            description = "All filters optional. status accepts repeated values "
                    + "(?status=OPEN&status=QUOTED); left out, every status is returned, "
                    + "which is not what the tab asks for by default. The laycan window "
                    + "matches cargoes whose laycan OVERLAPS it rather than sits inside it, "
                    + "and returns cargoes with no laycan on file whatever the window - "
                    + "\"the charterer has not said\" is not the same as \"not in September\". "
                    + "loadAreaId matches the area entered by hand or the area the load port "
                    + "sits in, exactly, without widening to nested areas.")
    public ResponseEntity<PageResponse<CargoResponse>> search(
            @RequestParam(required = false) String commodity,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) Long loadAreaId,
            @RequestParam(required = false) Long dischargeAreaId,
            @RequestParam(required = false) Long loadPortId,
            @RequestParam(required = false) LocalDate laycanFrom,
            @RequestParam(required = false) LocalDate laycanTo,
            @RequestParam(required = false) BigDecimal minQuantity,
            @RequestParam(required = false) BigDecimal maxQuantity,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Boolean fromMail,
            @PageableDefault(size = 20, sort = "id", direction = org.springframework.data.domain.Sort.Direction.DESC)
            Pageable pageable) {

        List<CargoStatus> statuses = status == null ? null
                : status.stream().map(CargoService::parseStatus).toList();
        CargoFilter filter = new CargoFilter(commodity, statuses, loadAreaId, dischargeAreaId,
                loadPortId, laycanFrom, laycanTo, minQuantity, maxQuantity, companyId, fromMail);
        return ResponseEntity.ok(cargoService.search(filter, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one cargo")
    public ResponseEntity<CargoResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(cargoService.get(id));
    }

    @PostMapping
    @Operation(summary = "Create a cargo",
            description = "Only the commodity is required. Everything else is what the "
                    + "enquiry happened to say, and a first email usually says little of it.")
    public ResponseEntity<CargoResponse> create(@Valid @RequestBody CargoRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cargoService.create(req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a cargo", description = "Replaces the whole record.")
    public ResponseEntity<CargoResponse> update(@PathVariable Long id,
                                                @Valid @RequestBody CargoRequest req) {
        return ResponseEntity.ok(cargoService.update(id, req));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Move a cargo along",
            description = "One fact, its own endpoint - so marking a cargo fixed from a list "
                    + "cannot quietly revert five other fields from a stale form.")
    public ResponseEntity<CargoResponse> setStatus(@PathVariable Long id,
                                                   @RequestParam String status,
                                                   @RequestParam(required = false) String note) {
        return ResponseEntity.ok(
                cargoService.setStatus(id, CargoService.parseStatus(status), note));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a cargo",
            description = "Takes any match decisions recorded against it with it.")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        cargoService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
