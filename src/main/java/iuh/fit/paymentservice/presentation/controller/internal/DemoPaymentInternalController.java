package iuh.fit.paymentservice.presentation.controller.internal;

import iuh.fit.paymentservice.infrastructure.DemoPaymentSeeder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Nhận dữ liệu mô phỏng từ venue-service (lượt đặt sân) và club-service (lệ phí giải) để dựng dòng tiền demo.
 * Chỉ tồn tại khi bật {@code app.demo-data.simulation.enabled=true}; gateway chặn mọi đường {@code /internal/**}.
 */
@RestController
@RequestMapping("/internal/demo")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.demo-data.simulation", name = "enabled", havingValue = "true")
public class DemoPaymentInternalController {
    private final DemoPaymentSeeder seeder;

    @PostMapping("/booking-payments")
    public ResponseEntity<DemoPaymentSeeder.Result> bookingPayments(@RequestBody List<DemoPaymentSeeder.BookingPayment> rows) {
        return ResponseEntity.ok(seeder.importBookings(rows));
    }

    @PostMapping("/tournament-fees")
    public ResponseEntity<DemoPaymentSeeder.Result> tournamentFees(@RequestBody List<DemoPaymentSeeder.TournamentFee> rows) {
        return ResponseEntity.ok(seeder.importTournamentFees(rows));
    }
}
