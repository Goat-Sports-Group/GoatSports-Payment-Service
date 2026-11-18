# GoatSports Payment Service

Thanh toán qua payOS (VietQR): đặt cọc sân, phí giải đấu, hoàn tiền và chi trả cho chủ sân.

## Chức năng chính

- **Tạo thanh toán**: sinh link / mã VietQR qua payOS. Phí giải đấu do server tính giá
  (`/internal/payments/tournament-fee-checkouts`), client không tự tạo được.
- **Webhook payOS** (`/api/v1/payments/providers/payos`): kiểm tra chữ ký checksum, xử lý lũy đẳng
  (một thông báo gửi lại nhiều lần chỉ ghi nhận một lần).
- **Đối soát**: `PaymentReconciliationJob` hỏi lại payOS các giao dịch treo quá lâu.
- **Phát sự kiện tin cậy**: ghi sự kiện vào outbox rồi `PaymentEventOutboxRelay` đẩy lên Kafka `payment.events`,
  để venue, club, notification, audit, workflow cập nhật theo.
- **Hoàn tiền** (`/api/v1/refunds`) và chi trả cho chủ sân (`/api/v1/payouts`), có job thử lại khi lỗi.
  Số tài khoản ngân hàng được mã hóa (`BANK_ACCOUNT_ENCRYPTION_KEY`).
- **Báo cáo tài chính** cho admin (`/api/v1/admin/finance`).

## API chính

| Đường dẫn | Mô tả |
|---|---|
| `/api/v1/payments`, `/api/v1/payments/{paymentId}/attempts` | Tạo, tra cứu thanh toán, các lần thử |
| `/api/v1/payments/providers/payos` | Webhook payOS |
| `/api/v1/refunds`, `/api/v1/payouts` | Hoàn tiền, chi trả |
| `/api/v1/admin/finance` | Tài chính nền tảng |
| `/internal/payments`, `/internal/finance`, `/internal/users` | API nội bộ |

## Cấu trúc

```
src/main/java/iuh/fit/paymentservice/
├── domain/          # entity, enum
├── application/     # service, port, DTO, mapper
├── infrastructure/  # JPA, payOS client, Kafka (outbox), job định kỳ
└── presentation/    # REST controller
shared-kernel/       # @AuditLog, Feign interceptor dùng chung
```

## Tích hợp

- **PostgreSQL** (Liquibase), **payOS** (thanh toán và payout, cấu hình `PAYOS_*`).
- **Kafka phát**: `payment.events`, `user-log`. **Kafka nhận**: `user-deleted`.

## Chạy và kiểm thử

```bash
mvn -f shared-kernel/pom.xml clean install -DskipTests
mvn spring-boot:run -Dspring-boot.run.profiles=dev     # cần application-dev.properties (bị .gitignore)
mvn test
```

Để payOS gọi được webhook khi chạy local cần URL công khai (`PAYOS_WEBHOOK_URL`).

## CI/CD

`.gitlab-ci.yml`: test → `jib:build` lên Docker Hub → gọi Jenkins job `GoatSports-CD`.
