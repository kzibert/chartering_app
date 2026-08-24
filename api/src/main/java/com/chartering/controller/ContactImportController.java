package com.chartering.controller;

import com.chartering.dto.ContactImportPreview;
import com.chartering.dto.ContactImportRequest;
import com.chartering.dto.ContactImportResult;
import com.chartering.service.ContactImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;

@RestController
@RequestMapping("/api/v1/contacts/import")
@RequiredArgsConstructor
@Tag(name = "Contact import", description = "Load a contacts export as companies, people and addresses")
public class ContactImportController {

    /**
     * Refused above this size without reading a byte of it. A contacts export is a text
     * file of a few hundred kilobytes; anything at this scale is a different kind of file
     * that happens to end in .csv, and the parse would be the expensive way to find out.
     */
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final ContactImportService importService;

    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Read a contacts file and report what importing it would do",
            description = "Writes nothing. Returns the companies, people and addresses the file "
                    + "describes, each matched against what is already stored and flagged with "
                    + "anything worth a second look — a name that reads as a slogan, an address "
                    + "claimed by two people, a company that nearly matches an existing one. "
                    + "The caller edits the result and posts it back to the import endpoint.")
    public ResponseEntity<ContactImportPreview> preview(
            @Parameter(description = "The contacts export, .csv, up to 5 MB")
            @RequestPart("file") MultipartFile file) {

        if (file.isEmpty()) {
            throw new IllegalArgumentException("The file is empty.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "The file is larger than 5 MB, which no contacts export should be.");
        }
        String name = file.getOriginalFilename();
        if (name != null && !name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            // A warning would be friendlier, but a spreadsheet renamed to .csv parses as one
            // enormous unreadable row and the preview it produces is nonsense. Better to say
            // what is wrong than to show that.
            throw new IllegalArgumentException(
                    "Only .csv files can be imported. Export the sheet as CSV first — an .xlsx "
                            + "renamed to .csv is still a spreadsheet and will not read.");
        }
        return ResponseEntity.ok(importService.preview(file));
    }

    @PostMapping
    @Operation(summary = "Write a reviewed import",
            description = "Takes the preview as the user left it: rows they removed are simply "
                    + "absent. One transaction for the whole file, so a failure part-way leaves "
                    + "nothing behind to review the next attempt against. Addresses the company "
                    + "already holds are skipped and counted, not duplicated.")
    public ResponseEntity<ContactImportResult> commit(@Valid @RequestBody ContactImportRequest request) {
        // 200 rather than 201: the request creates rows across three tables and there is no
        // one resource to name in a Location header.
        return ResponseEntity.ok(importService.commit(request));
    }
}
