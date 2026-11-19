package iuh.fit.paymentservice.infrastructure;

import iuh.fit.paymentservice.application.port.security.BankAccountProtector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dòng tiền mô phỏng (dev / demo) cho các lượt đặt sân và lệ phí giải mà venue-service / club-service seed thẳng vào
 * DB của chúng (không đi qua payOS). Hai service đó đẩy danh sách sang qua {@code /internal/demo/*}; ở đây dựng:
 * <ul>
 *   <li>khoản cọc đã thanh toán qua payOS cho mỗi lượt đặt qua app, khoản tiền còn lại thu tiền mặt khi check-in;</li>
 *   <li>lượt hủy: hoàn 100% nếu hủy trước giờ chơi từ 24 giờ, 50% nếu trễ hơn; hủy trong 2 ngày gần đây thì hoàn tiền
 *       còn chờ người chơi thêm tài khoản nhận (để demo hàng đợi hoàn tiền), quá 2 ngày thì đã hoàn xong;</li>
 *   <li>lệ phí giải đấu đã thanh toán, đúng {@code payment_id} mà club-service ghi ở {@code tournament_payments};</li>
 *   <li>mỗi chủ sân một tài khoản ngân hàng đã xác minh, và mỗi tháng đã qua một lần rút 90% doanh thu ròng của tháng
 *       (tháng hiện tại giữ số dư để demo thao tác rút).</li>
 * </ul>
 * Id tất định theo booking / đăng ký, mọi lệnh ghi {@code on conflict do nothing}: đẩy lại bao nhiêu lần cũng được.
 * Bật bằng {@code app.demo-data.simulation.enabled=true}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.demo-data.simulation", name = "enabled", havingValue = "true")
public class DemoPaymentSeeder {
    static final String CREATED_BY = "demo-sim";
    /** BIN ngân hàng thật (VietQR) để giao diện hiện đúng tên ngân hàng. */
    private static final String[] BANK_BINS = {"970436", "970407", "970422", "970416", "970418", "970415", "970432", "970423"};
    private static final double WITHDRAW_SHARE = 0.9;
    private static final double MINIMUM_WITHDRAWAL = 10_000D;
    private static final int PENDING_REFUND_DAYS = 2;

    private final JdbcTemplate jdbc;
    private final BankAccountProtector protector;

    /**
     * Một lượt đặt sân qua app (có cọc) do venue-service gửi sang. Lượt đã hủy có {@code cancelledAt}, số tiền hoàn và
     * lý do do venue-service tính (cùng con số với bảng booking_cancellations bên đó).
     */
    public record BookingPayment(UUID bookingId, UUID playerId, UUID ownerId, String ownerAccountName,
                                 double depositAmount, double remainingAmount, String status,
                                 LocalDateTime createdAt, LocalDateTime playStartsAt, LocalDateTime checkedInAt,
                                 LocalDateTime cancelledAt, double refundAmount, String refundReason) { }

    /** Một lệ phí giải đã thanh toán do club-service gửi sang. */
    public record TournamentFee(UUID paymentId, UUID registrationId, UUID payerId, UUID organizerId,
                                String organizerAccountName, double amount, LocalDateTime paidAt) { }

    public record Result(int payments, int refunds, int bankAccounts, int withdrawals) { }

    @Transactional
    public Result importBookings(List<BookingPayment> rows) {
        LocalDateTime now = LocalDateTime.now();
        List<Object[]> payments = new ArrayList<>();
        List<Object[]> attempts = new ArrayList<>();
        List<Object[]> refunds = new ArrayList<>();
        Map<UUID, String> payees = new LinkedHashMap<>();
        for (BookingPayment row : rows) {
            if (row.depositAmount() <= 0 || row.playerId() == null || row.ownerId() == null) continue;
            UUID bookingId = row.bookingId();
            long salt = bookingId.getLeastSignificantBits();
            LocalDateTime paidAt = row.createdAt().plusMinutes(2 + Math.floorMod(salt, 9));
            // Lượt đặt "sắp tạo" (tạo trước ngày chơi vài hôm, có thể sau hôm nay): đợi lần đẩy sau.
            if (paidAt.isAfter(now)) continue;
            payees.putIfAbsent(row.ownerId(), row.ownerAccountName());
            UUID paymentId = id("payment-deposit", bookingId.toString());
            String status = "SUCCEEDED";
            LocalDateTime cancelledAt = row.cancelledAt();
            if (cancelledAt != null && !cancelledAt.isAfter(now) && row.refundAmount() > 0) {
                boolean pending = cancelledAt.isAfter(now.minusDays(PENDING_REFUND_DAYS));
                if (!pending) status = row.refundAmount() >= row.depositAmount() ? "REFUNDED" : "PARTIALLY_REFUNDED";
                refunds.add(new Object[]{id("refund", bookingId.toString()), paymentId, row.playerId(), row.refundReason(),
                        row.refundAmount(), "refund:booking:" + bookingId, pending ? "AWAITING_BANK_ACCOUNT" : "SUCCEEDED",
                        pending ? null : ts(cancelledAt.plusMinutes(5)), ts(cancelledAt), ts(cancelledAt)});
            }
            payments.add(payment(paymentId, row.playerId(), row.ownerId(), "BOOKING_DEPOSIT", "BOOKING", bookingId,
                    row.depositAmount(), "Đặt cọc đặt sân", "booking-deposit:" + bookingId, status, paidAt, row.createdAt()));
            attempts.add(attempt(paymentId, "PAYOS", "BANK_TRANSFER", transactionCode(paymentId), row.createdAt(), paidAt));
            // Tiền còn lại: chủ sân thu tiền mặt tại quầy khi check-in (không vào số dư nền tảng, nên không có payee).
            boolean collected = "COMPLETED".equals(row.status()) && row.remainingAmount() > 0
                    && row.checkedInAt() != null && !row.checkedInAt().isAfter(now);
            if (collected) {
                UUID remainingId = id("payment-remaining", bookingId.toString());
                payments.add(payment(remainingId, row.playerId(), null, "BOOKING_REMAINING", "BOOKING", bookingId,
                        row.remainingAmount(), "Thanh toán số tiền còn lại của booking", "booking-remaining:" + bookingId,
                        "SUCCEEDED", row.checkedInAt(), row.checkedInAt()));
                attempts.add(attempt(remainingId, "MANUAL", "CASH", null, row.checkedInAt(), row.checkedInAt()));
            }
        }
        int insertedPayments = insertPayments(payments);
        insertAttempts(attempts);
        int insertedRefunds = insertRefunds(refunds);
        settleAgedRefunds(now);
        int accounts = ensureBankAccounts(payees, now);
        int withdrawals = withdrawMonthly(now);
        log.info("Dòng tiền mô phỏng: {} khoản thu, {} hoàn tiền, {} tài khoản ngân hàng, {} lần rút (từ {} lượt đặt)",
                insertedPayments, insertedRefunds, accounts, withdrawals, rows.size());
        return new Result(insertedPayments, insertedRefunds, accounts, withdrawals);
    }

    @Transactional
    public Result importTournamentFees(List<TournamentFee> rows) {
        LocalDateTime now = LocalDateTime.now();
        List<Object[]> payments = new ArrayList<>();
        List<Object[]> attempts = new ArrayList<>();
        Map<UUID, String> payees = new LinkedHashMap<>();
        for (TournamentFee row : rows) {
            if (row.paymentId() == null || row.paidAt() == null || row.paidAt().isAfter(now) || row.amount() <= 0) continue;
            if (row.organizerId() != null) payees.putIfAbsent(row.organizerId(), row.organizerAccountName());
            LocalDateTime createdAt = row.paidAt().minusMinutes(3);
            payments.add(payment(row.paymentId(), row.payerId(), row.organizerId(), "TOURNAMENT_FEE",
                    "TOURNAMENT_REGISTRATION", row.registrationId(), row.amount(), "Lệ phí đăng ký giải đấu",
                    "tournament-fee:" + row.registrationId(), "SUCCEEDED", row.paidAt(), createdAt));
            attempts.add(attempt(row.paymentId(), "PAYOS", "BANK_TRANSFER", transactionCode(row.paymentId()), createdAt,
                    row.paidAt()));
        }
        int inserted = insertPayments(payments);
        insertAttempts(attempts);
        int accounts = ensureBankAccounts(payees, now);
        int withdrawals = withdrawMonthly(now);
        log.info("Lệ phí giải mô phỏng: {} khoản thu, {} tài khoản ngân hàng, {} lần rút", inserted, accounts, withdrawals);
        return new Result(inserted, 0, accounts, withdrawals);
    }

    // ---- Hoàn tiền tới hạn ----

    /** Hoàn tiền đã chờ quá 2 ngày coi như người chơi đã thêm tài khoản và nhận tiền: chốt hoàn và trạng thái khoản cọc. */
    private void settleAgedRefunds(LocalDateTime now) {
        Timestamp cutoff = ts(now.minusDays(PENDING_REFUND_DAYS));
        jdbc.update("""
                update payments p set status = case when r.amount >= p.amount then 'REFUNDED' else 'PARTIALLY_REFUNDED' end,
                       updated_at = now(), updated_by = ?
                from refunds r
                where r.payment_id = p.payment_id and r.created_by = ? and r.status = 'AWAITING_BANK_ACCOUNT'
                  and r.created_at < ?
                """, CREATED_BY, CREATED_BY, cutoff);
        // Ngày nhận hoàn = một ngày sau khi hủy (tính ở đây cho câu SQL chạy được trên mọi CSDL).
        List<Object[]> settled = jdbc.query("""
                select refund_id, created_at from refunds
                where created_by = ? and status = 'AWAITING_BANK_ACCOUNT' and created_at < ?
                """, (rs, index) -> new Object[]{ts(rs.getTimestamp(2).toLocalDateTime().plusDays(1)),
                rs.getObject(1, UUID.class)}, CREATED_BY, cutoff);
        jdbc.batchUpdate("""
                update refunds set status = 'SUCCEEDED', refunded_at = ?, updated_at = now(), updated_by = 'demo-sim'
                where refund_id = ?
                """, settled);
    }

    // ---- Tài khoản ngân hàng + rút tiền ----

    private int ensureBankAccounts(Map<UUID, String> payees, LocalDateTime now) {
        if (payees.isEmpty()) return 0;
        // Bảng tài khoản ngân hàng nhỏ (mỗi chủ sân vài dòng): đọc hết rồi lọc, khỏi phụ thuộc cách driver truyền mảng.
        Set<UUID> existing = new HashSet<>(jdbc.queryForList("select distinct payee_id from bank_accounts", UUID.class));
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<UUID, String> payee : payees.entrySet()) {
            if (existing.contains(payee.getKey())) continue;
            UUID payeeId = payee.getKey();
            String bin = BANK_BINS[Math.floorMod(payeeId.hashCode(), BANK_BINS.length)];
            String number = String.format("%010d", Math.floorMod(payeeId.getLeastSignificantBits(), 10_000_000_000L));
            Timestamp verifiedAt = ts(now.minusDays(150 + Math.floorMod(payeeId.getMostSignificantBits(), 30)));
            rows.add(new Object[]{id("bank-account", payeeId.toString()), payeeId, bin, protector.encrypt(number),
                    protector.hash(bin, number), number.substring(number.length() - 4), accountName(payee.getValue()),
                    verifiedAt, verifiedAt, verifiedAt});
        }
        return sum(jdbc.batchUpdate("""
                insert into bank_accounts (bank_account_id, payee_id, bank_bin, account_number_encrypted, account_number_hash,
                                           account_number_last4, account_name, status, is_default, verified_at,
                                           created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, 'VERIFIED', true, ?, ?, ?, 'demo-sim', 'demo-sim') on conflict do nothing
                """, rows));
    }

    /**
     * Mỗi chủ sân, mỗi tháng đã qua: một lần rút 90% (doanh thu ròng của tháng = tiền thu vào số dư − tiền hoàn), làm
     * tròn nghìn, vào ngày 5 tháng sau. Luôn ≤ số dư khả dụng lúc đó nên số dư không bao giờ âm.
     */
    private int withdrawMonthly(LocalDateTime now) {
        Map<String, Double> net = new HashMap<>();
        jdbc.query("""
                select payee_id, to_char(paid_at, 'YYYY-MM'), sum(payee_amount) from payments
                where payee_id is not null and paid_at is not null and created_by = ?
                  and status in ('SUCCEEDED', 'PARTIALLY_REFUNDED', 'REFUNDED')
                group by 1, 2
                """, row -> {
            net.merge(row.getObject(1, UUID.class) + "|" + row.getString(2), row.getDouble(3), Double::sum);
        }, CREATED_BY);
        jdbc.query("""
                select p.payee_id, to_char(r.refunded_at, 'YYYY-MM'), sum(r.amount) from refunds r
                join payments p on p.payment_id = r.payment_id
                where p.payee_id is not null and r.status = 'SUCCEEDED' and r.refunded_at is not null and r.created_by = ?
                group by 1, 2
                """, row -> {
            net.merge(row.getObject(1, UUID.class) + "|" + row.getString(2), -row.getDouble(3), Double::sum);
        }, CREATED_BY);
        Map<UUID, Object[]> accounts = new HashMap<>();
        jdbc.query("""
                select payee_id, bank_account_id, bank_bin, account_number_last4, account_name from bank_accounts
                where is_default = true and status = 'VERIFIED'
                """, row -> {
            accounts.put(row.getObject(1, UUID.class), new Object[]{row.getObject(2, UUID.class), row.getString(3),
                    row.getString(4), row.getString(5)});
        });
        YearMonth current = YearMonth.from(now);
        List<Object[]> settlements = new ArrayList<>();
        List<Object[]> payouts = new ArrayList<>();
        for (Map.Entry<String, Double> entry : net.entrySet()) {
            String[] key = entry.getKey().split("\\|");
            UUID payeeId = UUID.fromString(key[0]);
            YearMonth month = YearMonth.parse(key[1]);
            Object[] account = accounts.get(payeeId);
            double amount = Math.floor(entry.getValue() * WITHDRAW_SHARE / 1000d) * 1000d;
            LocalDateTime requestedAt = month.plusMonths(1).atDay(5).atTime(9, 30);
            if (!month.isBefore(current) || account == null || amount < MINIMUM_WITHDRAWAL || requestedAt.isAfter(now)) continue;
            UUID settlementId = id("settlement", payeeId + "|" + month);
            LocalDateTime completedAt = requestedAt.plusMinutes(3);
            settlements.add(new Object[]{settlementId, payeeId, amount, amount, ts(requestedAt), ts(completedAt),
                    ts(requestedAt), ts(completedAt)});
            payouts.add(new Object[]{id("payout", settlementId.toString()), settlementId, account[0],
                    "PO" + transactionCode(settlementId), "settlement-" + settlementId, "settlement-payout-" + settlementId,
                    amount, account[1], "****" + account[2], account[3], ts(requestedAt), ts(completedAt),
                    ts(requestedAt), ts(completedAt)});
        }
        int inserted = sum(jdbc.batchUpdate("""
                insert into settlements (settlement_id, payee_id, currency, gross_amount, refund_amount, platform_fee_amount,
                                         payout_amount, available_at, status, settled_at,
                                         created_at, updated_at, created_by, updated_by)
                values (?, ?, 'VND', ?, 0, 0, ?, ?, 'PAID', ?, ?, ?, 'demo-sim', 'demo-sim') on conflict do nothing
                """, settlements));
        jdbc.batchUpdate("""
                insert into payouts (payout_id, settlement_id, bank_account_id, provider, provider_payout_id, reference_id,
                                     idempotency_key, amount, destination_bank_bin, destination_account_masked,
                                     destination_account_name, status, requested_at, completed_at,
                                     created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, 'PAYOS', ?, ?, ?, ?, ?, ?, ?, 'SUCCEEDED', ?, ?, ?, ?, 'demo-sim', 'demo-sim')
                on conflict do nothing
                """, payouts);
        return inserted;
    }

    // ---- Ghi DB ----

    private static Object[] payment(UUID paymentId, UUID payerId, UUID payeeId, String purpose, String referenceType,
                                    UUID referenceId, double amount, String description, String idempotencyKey,
                                    String status, LocalDateTime paidAt, LocalDateTime createdAt) {
        return new Object[]{paymentId, payerId, payeeId, purpose, referenceType, referenceId, amount, amount, description,
                idempotencyKey, status, ts(paidAt), ts(createdAt), ts(paidAt)};
    }

    private static Object[] attempt(UUID paymentId, String provider, String method, String transactionId,
                                    LocalDateTime initiatedAt, LocalDateTime completedAt) {
        return new Object[]{id("payment-attempt", paymentId.toString()), paymentId, provider, method, transactionId,
                ts(initiatedAt), ts(completedAt), ts(initiatedAt), ts(completedAt)};
    }

    private int insertPayments(List<Object[]> rows) {
        return sum(jdbc.batchUpdate("""
                insert into payments (payment_id, payer_id, payee_id, purpose, reference_type, reference_id, amount,
                                      platform_fee_amount, payee_amount, currency, description, idempotency_key, status,
                                      paid_at, expires_at, created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, 0, ?, 'VND', ?, ?, ?, ?, null, ?, ?, 'demo-sim', 'demo-sim')
                on conflict do nothing
                """, rows));
    }

    private void insertAttempts(List<Object[]> rows) {
        // Chỉ gắn lần thanh toán cho khoản thu có thật (khoản trùng khóa với giao dịch thật đã bị bỏ qua ở trên).
        jdbc.batchUpdate("""
                insert into payment_attempts (attempt_id, payment_id, provider, method, provider_transaction_id, status,
                                              initiated_at, completed_at, created_at, updated_at, created_by, updated_by)
                select ?, ?, ?, ?, ?, 'SUCCEEDED', ?, ?, ?, ?, 'demo-sim', 'demo-sim'
                where exists (select 1 from payments where payment_id = ?)
                on conflict do nothing
                """, rows.stream().map(row -> append(row, row[1])).toList());
    }

    private int insertRefunds(List<Object[]> rows) {
        return sum(jdbc.batchUpdate("""
                insert into refunds (refund_id, payment_id, requested_by, reason, amount, idempotency_key, auto_disburse,
                                     status, refunded_at, retry_count, created_at, updated_at, created_by, updated_by)
                select ?, ?, ?, ?, ?, ?, true, ?, ?, 0, ?, ?, 'demo-sim', 'demo-sim'
                where exists (select 1 from payments where payment_id = ?)
                on conflict do nothing
                """, rows.stream().map(row -> append(row, row[1])).toList()));
    }

    // ---- Tiện ích ----

    static UUID id(String kind, String key) {
        return UUID.nameUUIDFromBytes(("goatsports:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    /** Tên chủ tài khoản kiểu ngân hàng Việt Nam: chữ in hoa, không dấu. */
    static String accountName(String name) {
        String source = name == null || name.isBlank() ? "CHU SAN GOAT SPORTS" : name;
        String ascii = Normalizer.normalize(source.replace('Đ', 'D').replace('đ', 'd'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return ascii.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", " ").replaceAll(" +", " ").trim();
    }

    private static String transactionCode(UUID id) {
        return String.format("%012d", Math.floorMod(id.getMostSignificantBits(), 1_000_000_000_000L));
    }

    private static Object[] append(Object[] row, Object value) {
        Object[] copy = new Object[row.length + 1];
        System.arraycopy(row, 0, copy, 0, row.length);
        copy[row.length] = value;
        return copy;
    }

    private static Timestamp ts(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static int sum(int[] counts) {
        int total = 0;
        for (int count : counts) total += Math.max(count, 0);
        return total;
    }
}
