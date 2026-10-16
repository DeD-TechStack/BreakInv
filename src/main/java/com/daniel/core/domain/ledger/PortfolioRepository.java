package com.daniel.core.domain.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence contract for the ledger. Implementations are bound to one connection. */
public interface PortfolioRepository {

    Optional<Movement> findByIntentKey(String intentKey);

    /** Payload hash stored with the intent key, if the key exists. */
    Optional<String> payloadHashOf(String intentKey);

    Optional<Movement> findById(long id);

    /** All movements ordered by (date, id). */
    List<Movement> listAll();

    /** Appends; {@code payloadHash} is persisted beside the key. Returns the row with its id. */
    Movement insert(Movement movement, String payloadHash);

    Optional<InvestmentLedgerInfo> investmentInfo(long investmentTypeId);

    List<InvestmentLedgerInfo> listInvestments(boolean includeArchived);

    void setPositionMode(long investmentTypeId, PositionMode mode);

    /** {@code null} un-archives. */
    void setArchivedAt(long investmentTypeId, Instant archivedAt);

    void setNote(long investmentTypeId, String note);

    /** True when any history (ledger, legacy transactions, snapshots, flows) references the id. */
    boolean hasHistory(long investmentTypeId);

    /** Physical delete, allowed only when {@link #hasHistory(long)} is false (DB trigger enforces it too). */
    void deleteRegistration(long investmentTypeId);

    /** Latest legacy cash snapshot, if any. */
    Optional<Long> latestLegacyCashCents();
}
