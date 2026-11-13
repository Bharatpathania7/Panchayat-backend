package com.panchayat.backend.wallet.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The Diamond wallet. Every change is a ledger row; a ref makes a credit happen only once. */
@Service
public class DiamondService {

    private final JdbcTemplate jdbc;

    public DiamondService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long balance(Long userId) {
        Long b = jdbc.query("select balance from diamond_wallets where user_id = ?", rs -> rs.next() ? rs.getLong(1) : 0L, userId);
        return b == null ? 0 : b;
    }

    /** Adds diamonds. Returns false when this ref was already credited (nothing changes). */
    @Transactional
    public boolean credit(Long userId, long amount, String reason, String ref) {
        if (amount <= 0) return false;
        if (ref != null && jdbc.queryForObject("select count(*) from diamond_transactions where ref = ?", Long.class, ref) > 0) return false;
        Long after = jdbc.queryForObject("insert into diamond_wallets (user_id, balance) values (?, ?) on conflict (user_id) do update set balance = diamond_wallets.balance + excluded.balance, updated_at = now() returning balance",
                Long.class, userId, amount);
        jdbc.update("insert into diamond_transactions (user_id, amount, balance_after, reason, ref) values (?, ?, ?, ?, ?)", userId, amount, after, reason, ref);
        return true;
    }
}
