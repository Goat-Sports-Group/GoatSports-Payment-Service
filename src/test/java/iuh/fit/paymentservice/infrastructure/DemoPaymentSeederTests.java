package iuh.fit.paymentservice.infrastructure;

import iuh.fit.paymentservice.application.service.OwnerPayoutService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dòng tiền mô phỏng: đẩy lại không sinh trùng, số dư chủ sân (tính bằng OwnerPayoutService thật) không âm, có lịch sử
 * rút tiền theo tháng, lượt hủy gần đây đang chờ hoàn.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:demo_payment_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.demo-data.simulation.enabled=true"
})
@ActiveProfiles("test")
class DemoPaymentSeederTests {
    @Autowired
    private DemoPaymentSeeder seeder;
    @Autowired
    private OwnerPayoutService ownerPayoutService;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void importIsIdempotentAndKeepsOwnerBalanceConsistent() {
        UUID owner = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        List<DemoPaymentSeeder.BookingPayment> rows = new ArrayList<>();
        for (int index = 0; index < 120; index++) {
            LocalDateTime playStartsAt = now.minusDays(120 - index).withHour(19).withMinute(0).withSecond(0).withNano(0);
            LocalDateTime createdAt = playStartsAt.minusDays(2);
            boolean cancelled = index % 10 == 0;
            LocalDateTime cancelledAt = cancelled ? createdAt.plusHours(3) : null;
            rows.add(new DemoPaymentSeeder.BookingPayment(UUID.randomUUID(), player, owner, "Nguyễn Văn Đức",
                    150_000, 350_000, cancelled ? "CANCELLED" : "COMPLETED", createdAt, playStartsAt,
                    cancelled ? null : playStartsAt, cancelledAt, cancelled ? 150_000 : 0,
                    cancelled ? "Hủy trước giờ chơi từ 24 giờ: hoàn 100% tiền cọc" : null));
        }
        // Hủy cách đây 1 giờ: tiền hoàn còn chờ người chơi thêm tài khoản nhận.
        rows.add(new DemoPaymentSeeder.BookingPayment(UUID.randomUUID(), player, owner, "Nguyễn Văn Đức", 150_000,
                350_000, "CANCELLED", now.minusDays(1), now.plusDays(1), null, now.minusHours(1), 150_000,
                "Hủy trước giờ chơi từ 24 giờ: hoàn 100% tiền cọc"));

        DemoPaymentSeeder.Result first = seeder.importBookings(rows);
        DemoPaymentSeeder.Result second = seeder.importBookings(rows);

        assertThat(first.payments()).isGreaterThan(200);
        assertThat(first.withdrawals()).isGreaterThan(1);
        assertThat(first.bankAccounts()).isEqualTo(1);
        assertThat(second.payments()).isZero();
        assertThat(second.refunds()).isZero();
        assertThat(second.withdrawals()).isZero();
        assertThat(second.bankAccounts()).isZero();

        OwnerPayoutService.Balance balance = ownerPayoutService.getBalance(owner);
        assertThat(balance.withdrawn()).isPositive();
        assertThat(balance.available()).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject("select count(*) from refunds where status = 'AWAITING_BANK_ACCOUNT'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select account_name from bank_accounts where payee_id = ?", String.class, owner))
                .isEqualTo("NGUYEN VAN DUC");
    }
}
