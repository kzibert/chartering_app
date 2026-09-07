package com.chartering.repository;

import com.chartering.model.Port;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PortRepository extends JpaRepository<Port, Long> {

    /**
     * A port by its exact name, for the parser to resolve "SALERNO 1/2 SEPT" onto a row.
     *
     * <p>Exact and case-insensitive, and no fuzzier than that on purpose. Ports carry names
     * that differ by a word from each other's ("Port Said" and "Port Sudan", a dozen
     * Alexandrias), and a near-match here would move a ship to another sea without anything
     * on screen saying it had guessed. When this finds nothing the text is kept in
     * {@code open_port_text} and the <em>area</em> is resolved from it instead, which is the
     * coarser answer Match can actually use — and an honest one.
     *
     * <p>A list because {@code ports} has no unique index on the name; two rows of one name
     * means the caller declines to choose, the same way a duplicated IMO does.
     *
     * <p>The argument is lowercased here and must arrive already trimmed — HQL's
     * {@code trim()} will not take a bare parameter as its operand, so the two sides are
     * normalised in different places. Callers pass {@code Extraction.text()} output,
     * which is stripped.
     *
     * <p>The trade area comes with it: the caller's next question is always which water this
     * berth is on, and fetching it here is one query instead of one per resolved port in a
     * circular carrying eighty of them.
     */
    @EntityGraph(attributePaths = "tradeArea")
    @Query("select p from Port p where lower(trim(p.name)) = lower(?1)")
    List<Port> findByExactName(String name);
}
