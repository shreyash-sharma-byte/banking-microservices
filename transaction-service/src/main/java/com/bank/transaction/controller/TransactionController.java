package com.bank.transaction.controller;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    @PersistenceContext
    private EntityManager em;

    @GetMapping
    public ResponseEntity<?> listTransactions(
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return buildResult(accountId, type, minAmount, maxAmount, startDate, endDate, page, size);
    }

    // ── Secure: POST with filters in body (no IDs in URL) ──
    @PostMapping("/search")
    public ResponseEntity<?> searchTransactions(@RequestBody Map<String, Object> body) {
        UUID accountId = body.containsKey("accountId") ? UUID.fromString((String) body.get("accountId")) : null;
        String type = (String) body.get("type");
        BigDecimal minAmount = body.containsKey("minAmount") ? new BigDecimal(body.get("minAmount").toString()) : null;
        BigDecimal maxAmount = body.containsKey("maxAmount") ? new BigDecimal(body.get("maxAmount").toString()) : null;
        LocalDate startDate = body.containsKey("startDate") ? LocalDate.parse((String) body.get("startDate")) : null;
        LocalDate endDate = body.containsKey("endDate") ? LocalDate.parse((String) body.get("endDate")) : null;
        int page = body.containsKey("page") ? ((Number) body.get("page")).intValue() : 0;
        int size = body.containsKey("size") ? ((Number) body.get("size")).intValue() : 20;
        return buildResult(accountId, type, minAmount, maxAmount, startDate, endDate, page, size);
    }

    private ResponseEntity<?> buildResult(UUID accountId, String type, BigDecimal minAmount, BigDecimal maxAmount,
                                           LocalDate startDate, LocalDate endDate, int page, int size) {

        StringBuilder sql = new StringBuilder("SELECT * FROM unified_ledger WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (accountId != null) { sql.append(" AND account_id = ?"); params.add(accountId); }
        if (type != null) { sql.append(" AND entry_type = ?"); params.add(type); }
        if (minAmount != null) { sql.append(" AND amount >= ?"); params.add(minAmount); }
        if (maxAmount != null) { sql.append(" AND amount <= ?"); params.add(maxAmount); }
        if (startDate != null) { sql.append(" AND occurred_at >= ?"); params.add(startDate.atStartOfDay()); }
        if (endDate != null) { sql.append(" AND occurred_at < ?"); params.add(endDate.plusDays(1).atStartOfDay()); }

        sql.append(" ORDER BY occurred_at DESC LIMIT ? OFFSET ?");
        params.add(size); params.add(page * size);

        var query = em.createNativeQuery(sql.toString());
        for (int i = 0; i < params.size(); i++) query.setParameter(i + 1, params.get(i));

        // Convert Object[] rows to Map<String, Object> for frontend compatibility
        List<?> raw = query.getResultList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : raw) {
            Object[] cols = (Object[]) row;
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", cols[0]);
            map.put("eventId", cols[1]);
            map.put("sourceService", cols[2]);
            map.put("accountId", cols[3]);
            map.put("entryType", cols[4]);
            map.put("amount", cols[5]);
            map.put("reference", cols[6]);
            map.put("occurredAt", cols[7]);
            map.put("ingestedAt", cols[8]);
            map.put("correlationId", cols[9]);
            result.add(map);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{entryId}")
    public ResponseEntity<?> getEntry(@PathVariable Long entryId) {
        List<?> raw = em.createNativeQuery("SELECT * FROM unified_ledger WHERE id = ?")
            .setParameter(1, entryId).getResultList();
        if (raw.isEmpty()) return ResponseEntity.notFound().build();
        Object[] cols = (Object[]) raw.get(0);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", cols[0]);
        map.put("eventId", cols[1]);
        map.put("sourceService", cols[2]);
        map.put("accountId", cols[3]);
        map.put("entryType", cols[4]);
        map.put("amount", cols[5]);
        map.put("reference", cols[6]);
        map.put("occurredAt", cols[7]);
        map.put("ingestedAt", cols[8]);
        map.put("correlationId", cols[9]);
        return ResponseEntity.ok(map);
    }

    @GetMapping("/daily-summary")
    public ResponseEntity<?> dailySummary(@RequestParam LocalDate date) {
        var result = em.createNativeQuery(
            "SELECT entry_type, COUNT(*) as txn_count, SUM(amount) as total_volume " +
            "FROM unified_ledger WHERE occurred_at::date = ? GROUP BY entry_type")
            .setParameter(1, date).getResultList();

        long totalCount = 0;
        BigDecimal totalVolume = BigDecimal.ZERO;
        List<Map<String, Object>> breakdown = new ArrayList<>();
        for (Object row : result) {
            Object[] cols = (Object[]) row;
            long count = ((Number) cols[1]).longValue();
            BigDecimal volume = (BigDecimal) cols[2];
            totalCount += count;
            totalVolume = totalVolume.add(volume);
            breakdown.add(Map.of("entryType", cols[0], "count", count, "volume", volume));
        }
        return ResponseEntity.ok(Map.of("date", date, "totalCount", totalCount, "totalVolume", totalVolume, "breakdown", breakdown));
    }
}
