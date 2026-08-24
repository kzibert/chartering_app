package com.chartering.service.imports;

import java.util.ArrayList;
import java.util.List;

/**
 * One line of a contacts file, after the columns have been made sense of and before
 * anything has been looked up in the database.
 *
 * <p>Mutable and plain on purpose. The parser fills it in several passes — the address
 * block is chosen after the name is read, shared addresses are hoisted after every row is
 * known — and a record would mean rebuilding it at each step for no gain.
 */
public class ParsedRow {

    /** 1-based line in the file, for a warning that has to say where. */
    public int lineNumber;

    /** true when the row describes an organisation rather than a human. */
    public boolean organisation;

    /** The organisation this row names — its own name if it is one, its employer if not. */
    public String organisationName;

    public String fullName;
    public String firstName;
    public String title;
    public String jobTitle;

    public String cityName;
    public String country;
    public String website;

    /** Free-text provenance from the file's Tags column — folded into notes. */
    public String tags;

    public final List<ParsedContact> contacts = new ArrayList<>();

    /** Trouble with this row that the user should see before importing it. */
    public final List<String> warnings = new ArrayList<>();

    /** One address or number, with whatever the file said about what kind it is. */
    public record ParsedContact(String kind, String value, String label) {
    }
}
