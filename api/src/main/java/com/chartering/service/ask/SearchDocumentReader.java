package com.chartering.service.ask;

import com.chartering.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads the text of the sources the indexer has decided to index, and renders each as the
 * {@link SearchDocument} its chunks are made from.
 *
 * <p>Called only with ids the indexer found pending, in batches of the indexer's own size, so the
 * text crosses the wire once per source and only for the ones that need it. Mail and post bodies are
 * cut in SQL at {@link #BODY_CHARS}: what runs past it is a quoted chain or a disclaimer, and a
 * hundred-kilobyte thread would otherwise cost a hundred chunks to index and send across for nothing.
 *
 * <p>Cargoes and positions are rendered here, from their columns, as the labelled lines a person
 * would write. Rendering them from the stored columns (rather than from a free-text field) is what
 * lets a question about "Chornomorsk" find the cargo whose load port is that berth, through the port
 * table, and not only the cargo whose notes happen to say so.
 */
@Component
@RequiredArgsConstructor
public class SearchDocumentReader {

    /** The most body text indexed from one mail or post. */
    static final int BODY_CHARS = 20_000;

    private static final DateTimeFormatter DAY_AND_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JdbcTemplate jdbc;

    /** The documents for these ids of one kind, in id order. Ids no longer on the desk are simply absent. */
    @Transactional(readOnly = true)
    public List<SearchDocument> load(SourceKind kind, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        Long tenant = TenantContext.require();
        String in = placeholders(ids.size());
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        args.addAll(ids);

        return switch (kind) {
            case MAIL -> jdbc.query("""
                    select m.id, m.owner_user_id, m.subject, m.from_name, m.from_address,
                           coalesce(m.sent_at, m.received_at) as shown_at,
                           left(m.body_text, %d) as body
                    from mail_messages m
                    where m.tenant_id = ? and m.id in (%s)
                    order by m.id
                    """.formatted(BODY_CHARS, in), (rs, i) -> mail(rs), args.toArray());
            case POST -> jdbc.query("""
                    select i.id, i.title, s.name as board,
                           coalesce(i.published_at, i.fetched_at) as shown_at,
                           left(i.text, %d) as body
                    from feed_items i
                    join feed_sources s on s.id = i.source_id
                    where i.id in (%s)
                    order by i.id
                    """.formatted(BODY_CHARS, in), (rs, i) -> post(rs), ids.toArray());
            case CARGO -> jdbc.query("""
                    select c.id, c.status, c.status_note, c.commodity, c.quantity, c.quantity_unit,
                           c.quantity_tolerance, c.quantity_min, c.quantity_max,
                           c.load_port_text, lp.name as load_port, la.name as load_area,
                           c.discharge_port_text, dp.name as discharge_port, da.name as discharge_area,
                           c.laycan_text, c.laycan_from, c.laycan_to,
                           c.freight_idea, c.commission, c.terms, c.load_rate, c.discharge_rate,
                           c.min_dwt, c.max_dwt, c.max_draft, c.max_ballast_days,
                           c.requires_geared, c.notes,
                           ch.name as charterer, bc.name as broker_company, bp.full_name as broker_person,
                           coalesce(c.received_at, c.created_at) as shown_at,
                           coalesce(c.updated_at, c.created_at) as changed_at
                    from cargoes c
                    left join ports lp on lp.id = c.load_port_id
                    left join trade_areas la on la.id = c.load_area_id
                    left join ports dp on dp.id = c.discharge_port_id
                    left join trade_areas da on da.id = c.discharge_area_id
                    left join companies ch on ch.id = c.charterer_company_id
                    left join companies bc on bc.id = c.broker_company_id
                    left join people bp on bp.id = c.broker_person_id
                    where c.tenant_id = ? and c.id in (%s)
                    order by c.id
                    """.formatted(in), (rs, i) -> cargo(rs), args.toArray());
            case POSITION -> jdbc.query("""
                    select p.id, p.status, p.open_port_text, op.name as open_port, oa.name as open_area,
                           p.open_from, p.open_to, p.open_text, p.last_cargo, p.cargo_preferences, p.notes,
                           p.reported_at, coalesce(p.updated_at, p.created_at, p.reported_at) as changed_at,
                           v.name as vessel, v.imo_number, v.vessel_type, v.deadweight_tonnage,
                           v.deadweight_cargo_capacity, v.year_built, v.flag, v.geared,
                           rc.name as reported_by_company, rp.full_name as reported_by_person
                    from vessel_positions p
                    join vessels v on v.id = p.vessel_id
                    left join ports op on op.id = p.open_port_id
                    left join trade_areas oa on oa.id = p.open_area_id
                    left join companies rc on rc.id = p.reported_by_company_id
                    left join people rp on rp.id = p.reported_by_person_id
                    where p.tenant_id = ? and p.id in (%s)
                    order by p.id
                    """.formatted(in), (rs, i) -> position(rs), args.toArray());
        };
    }

    // ----------------------------------------------------------------- per kind

    private static SearchDocument mail(ResultSet rs) throws SQLException {
        String subject = rs.getString("subject");
        String name = rs.getString("from_name");
        String address = rs.getString("from_address");
        LocalDateTime shown = localDateTime(rs, "shown_at");
        String header = lines(
                line("Subject", subject),
                line("From", name == null || name.isBlank() ? address : name + " <" + address + ">"),
                line("Date", shown == null ? null : shown.format(DAY_AND_TIME)));
        return new SearchDocument(SourceKind.MAIL, rs.getLong("id"),
                rs.getObject("owner_user_id") == null ? null : rs.getLong("owner_user_id"),
                blankTo(subject, "(no subject)"), shown, null, header, rs.getString("body"));
    }

    private static SearchDocument post(ResultSet rs) throws SQLException {
        String title = rs.getString("title");
        String board = rs.getString("board");
        LocalDateTime shown = localDateTime(rs, "shown_at");
        String header = lines(
                line("Board", board),
                line("Date", shown == null ? null : shown.format(DAY_AND_TIME)),
                line("Title", title));
        return new SearchDocument(SourceKind.POST, rs.getLong("id"), null,
                blankTo(title, board), shown, null, header, rs.getString("body"));
    }

    private static SearchDocument cargo(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String status = rs.getString("status");
        String commodity = rs.getString("commodity");
        String load = place(rs.getString("load_port"), rs.getString("load_port_text"), rs.getString("load_area"));
        String discharge = place(rs.getString("discharge_port"), rs.getString("discharge_port_text"),
                rs.getString("discharge_area"));
        String title = blankTo(commodity, "Cargo") + (load == null ? "" : ", " + load)
                + (discharge == null ? "" : " to " + discharge);
        LocalDate from = rs.getObject("laycan_from", LocalDate.class);
        LocalDate to = rs.getObject("laycan_to", LocalDate.class);
        String laycan = rs.getString("laycan_text") != null ? rs.getString("laycan_text")
                : (from == null ? null : from + (to == null || to.equals(from) ? "" : " to " + to));

        String header = "Cargo #" + id + " (" + (status == null ? "OPEN" : status) + ")";
        String body = lines(
                line("Status note", rs.getString("status_note")),
                line("Commodity", commodity),
                line("Quantity", quantity(rs)),
                line("Load", load),
                line("Discharge", discharge),
                line("Laycan", laycan),
                line("Vessel", restrictions(rs)),
                line("Freight", rs.getString("freight_idea")),
                line("Commission", rs.getString("commission")),
                line("Terms", rs.getString("terms")),
                line("Load rate", rs.getString("load_rate")),
                line("Discharge rate", rs.getString("discharge_rate")),
                line("Charterer", rs.getString("charterer")),
                line("Broker", broker(rs)),
                line("Notes", rs.getString("notes")));
        return new SearchDocument(SourceKind.CARGO, id, null, title,
                localDateTime(rs, "shown_at"), rs.getObject("changed_at", OffsetDateTime.class),
                header, body);
    }

    private static SearchDocument position(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String vessel = rs.getString("vessel");
        String where = place(rs.getString("open_port"), rs.getString("open_port_text"), rs.getString("open_area"));
        LocalDate from = rs.getObject("open_from", LocalDate.class);
        LocalDate to = rs.getObject("open_to", LocalDate.class);
        String dates = rs.getString("open_text") != null ? rs.getString("open_text")
                : (from == null ? null : from + (to == null || to.equals(from) ? "" : " to " + to));

        String title = blankTo(vessel, "Vessel") + (where == null ? "" : " open " + where);
        String header = "Vessel position #" + id + " (" + rs.getString("status") + ")";
        String body = lines(
                line("Vessel", vesselLine(rs)),
                line("Open", where),
                line("Open dates", dates),
                line("Last cargo", rs.getString("last_cargo")),
                line("Wants", rs.getString("cargo_preferences")),
                line("Reported by", reporter(rs)),
                line("Notes", rs.getString("notes")));
        return new SearchDocument(SourceKind.POSITION, id, null, title,
                localDateTime(rs, "reported_at"), rs.getObject("changed_at", OffsetDateTime.class),
                header, body);
    }

    // ----------------------------------------------------------------- rendering

    private static String quantity(ResultSet rs) throws SQLException {
        BigDecimal q = rs.getBigDecimal("quantity");
        if (q == null) return null;
        String unit = rs.getString("quantity_unit");
        String tolerance = rs.getString("quantity_tolerance");
        BigDecimal min = rs.getBigDecimal("quantity_min");
        BigDecimal max = rs.getBigDecimal("quantity_max");
        StringBuilder s = new StringBuilder(q.toPlainString());
        if (unit != null) s.append(' ').append(unit);
        if (tolerance != null) s.append(" ").append(tolerance);
        if (min != null && max != null && !min.equals(max)) {
            s.append(" (").append(min.toPlainString()).append(" to ").append(max.toPlainString()).append(')');
        }
        return s.toString();
    }

    private static String restrictions(ResultSet rs) throws SQLException {
        List<String> parts = new ArrayList<>();
        addRange(parts, "DWT", rs.getBigDecimal("min_dwt"), rs.getBigDecimal("max_dwt"));
        BigDecimal draft = rs.getBigDecimal("max_draft");
        if (draft != null) parts.add("max draft " + draft.toPlainString() + " m");
        Object geared = rs.getObject("requires_geared");
        if (Boolean.TRUE.equals(geared)) parts.add("geared");
        BigDecimal ballast = rs.getBigDecimal("max_ballast_days");
        if (ballast != null) parts.add("max ballast " + ballast.toPlainString() + " days");
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    private static void addRange(List<String> parts, String label, BigDecimal min, BigDecimal max) {
        if (min == null && max == null) return;
        String range = (min == null ? "" : min.toPlainString()) + "-" + (max == null ? "" : max.toPlainString());
        parts.add(label + " " + range);
    }

    private static String broker(ResultSet rs) throws SQLException {
        String company = rs.getString("broker_company");
        String person = rs.getString("broker_person");
        if (company == null) return person;
        return person == null ? company : company + " (" + person + ")";
    }

    /** The vessel's name first, then what identifies and describes her. The name is what a question asks for. */
    private static String vesselLine(ResultSet rs) throws SQLException {
        List<String> parts = new ArrayList<>();
        String name = rs.getString("vessel");
        if (name != null) parts.add(name);
        String imo = rs.getString("imo_number");
        if (imo != null) parts.add("IMO " + imo);
        String type = rs.getString("vessel_type");
        if (type != null) parts.add(type);
        BigDecimal dwt = rs.getBigDecimal("deadweight_tonnage");
        if (dwt != null) parts.add("DWT " + dwt.toPlainString());
        BigDecimal dwcc = rs.getBigDecimal("deadweight_cargo_capacity");
        if (dwcc != null) parts.add("DWCC " + dwcc.toPlainString());
        Object built = rs.getObject("year_built");
        if (built != null) parts.add("built " + built);
        String flag = rs.getString("flag");
        if (flag != null) parts.add(flag);
        Object geared = rs.getObject("geared");
        if (Boolean.TRUE.equals(geared)) parts.add("geared");
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static String reporter(ResultSet rs) throws SQLException {
        String company = rs.getString("reported_by_company");
        String person = rs.getString("reported_by_person");
        if (company == null) return person;
        return person == null ? company : company + " (" + person + ")";
    }

    /** A port by name when the desk's text was matched to one, else as the desk wrote it, with its area. */
    private static String place(String port, String written, String area) {
        String name = port != null ? port : written;
        if (name == null) return area;
        return area == null || area.equalsIgnoreCase(name) ? name : name + " (" + area + ")";
    }

    private static String line(String label, String value) {
        if (value == null || value.isBlank()) return null;
        return label + ": " + value.strip();
    }

    private static String lines(String... parts) {
        List<String> kept = new ArrayList<>();
        for (String p : parts) {
            if (p != null) kept.add(p);
        }
        return String.join("\n", kept);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * A {@code timestamp} or {@code timestamptz} as the JVM-local {@link LocalDateTime} the store
     * writes it back from, so a date read here and written there are the same instant.
     */
    private static LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }
}
