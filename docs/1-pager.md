# Event-driven Notification Delivery Platform

## 한 줄 요약

Kafka Consumer · DLQ · CQRS를 활용한 **다채널 알림 전송 플랫폼**. 이벤트를 수신하여 Email · Slack · Push 등 적절한 채널로 신뢰성 있게 전달하고, 전송 결과를 추적한다.

---

## 풀고자 하는 문제

현재 moalog 플랫폼에서:

1. FluxPay가 결제 이벤트를 Kafka로 발행하지만 **아무도 소비하지 않음**
2. 구독 결제 완료 시 **사용자에게 알림이 안 감**
3. 실패한 알림에 대한 **재처리 전략이 없음**
4. 알림 전송 이력을 **조회할 수 없음**

```
현재: fluxpay-engine → Kafka (발행만) → 아무도 안 읽음
목표: fluxpay-engine → Kafka → 이 서비스 → Email/Slack/Push + 전송 추적
```

---

## 아키텍처

```
┌─────────────────────────────────────────────────────────────────┐
│              Notification Delivery Platform                       │
│              Java 21 · Spring Boot 3.2 · WebFlux                 │
│                                                                   │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │ Kafka Consumers                                          │    │
│  │                                                          │    │
│  │  PaymentConsumer ──┐                                     │    │
│  │  SubscriptionConsumer ──┤──▶ Notification Router         │    │
│  │  OrderConsumer ────┘       │                             │    │
│  │                            ▼                             │    │
│  │                    ┌───┬───┬───┬───────┐                 │    │
│  │                    │Email│Slack│Push│In-App│               │    │
│  │                    └─┬───┴─┬──┴──┬─┴───┬──┘               │    │
│  │                      │     │     │     │                  │    │
│  │                      └─────┴──┬──┴─────┘                  │    │
│  │                               ▼                           │    │
│  │                     Delivery Tracker (CQRS)               │    │
│  │                     notification_log + delivery_stats     │    │
│  └──────────────────────────────────────────────────────────┘    │
│                                                                   │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │ DLQ Pipeline                                              │    │
│  │ 3회 실패 → DLQ 토픽 → DLQ Consumer → Admin API (재처리)  │    │
│  └──────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

---

## 기술 스택

| 카테고리 | 기술 | 선택 이유 |
|---------|------|----------|
| 언어 | Java 21 | 기존 fluxpay-engine과 일관성, Spring Kafka 성숙도 |
| 프레임워크 | Spring Boot 3.2 + WebFlux | Reactive Kafka Consumer 지원 |
| 메시징 | Apache Kafka 7.5.0 | 기존 인프라 활용 |
| DB | PostgreSQL 16 (R2DBC) | 전송 이력, CQRS Read Model |
| 캐시 | Redis 7 | 사용자 알림 설정 캐시, 중복 방지 |
| 이메일 | Spring Mail (SMTP) | MailHog로 로컬 테스트 |
| 슬랙 | Slack Incoming Webhook | HTTP POST |
| 템플릿 | Thymeleaf | 이메일/슬랙 메시지 템플릿 |

---

## 핵심 기능 (5개)

### 1. Kafka Consumer Group — 이벤트 소비

| Consumer | 토픽 | Group ID | 처리 |
|----------|------|----------|------|
| PaymentNotifConsumer | `fluxpay.payment.events` | `notif-payment` | 결제 완료/실패 알림 |
| SubscriptionNotifConsumer | `fluxpay.subscription.events` | `notif-subscription` | 구독 생성/해지/갱신 |
| OrderNotifConsumer | `fluxpay.order.events` | `notif-order` | 주문 상태 변경 |

**멱등성**: 기존 `processed_events` 테이블로 중복 소비 방지.

**트레이드오프: at-least-once vs exactly-once**

| | at-least-once (선택) | exactly-once |
|---|---|---|
| 구현 | 수동 커밋 + 멱등성 체크 | Kafka Transactions |
| 성능 | 좋음 | 트랜잭션 오버헤드 |

> **판단**: 알림은 "안 보내는 것"이 "2번 보내는 것"보다 나쁨. at-least-once + 멱등성 체크.

---

### 2. 다채널 알림 라우팅

이벤트 타입 + 사용자 설정 → 채널 결정.

| 이벤트 | 기본 채널 |
|--------|----------|
| 결제 완료 | Email + In-App |
| 결제 실패 | Email + In-App + Slack |
| 구독 갱신 | Email |
| 구독 만료 예정 | Email + In-App |

**Strategy 패턴** — 채널 추가가 쉬움:
```java
public interface NotificationSender {
    Mono<DeliveryResult> send(NotificationRequest request);
    String channel();  // EMAIL, SLACK, PUSH, IN_APP
}
```

---

### 3. Dead Letter Queue (DLQ)

```
Consumer → 전송 시도 (3회 재시도, 백오프: 1s→2s→4s)
    ├─ 성공 → offset commit + 이력 저장
    └─ 실패 → DLQ 토픽 (notification.dlq.{domain})
              ├─ X-Error-Message 헤더
              ├─ X-Retry-Count 헤더
              └─ Admin API로 재처리/폐기
```

**DLQ Admin API**:

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/v1/admin/dlq` | DLQ 메시지 목록 |
| POST | `/api/v1/admin/dlq/{id}/retry` | 개별 재처리 |
| POST | `/api/v1/admin/dlq/retry-all` | 전체 재처리 |
| DELETE | `/api/v1/admin/dlq/{id}` | 메시지 폐기 |

**트레이드오프: DLQ vs 무한 재시도** — 전송 실패 원인이 다양 (SMTP 장애, 잘못된 이메일). DLQ로 격리 → 원인 분석 → 수정 후 재처리.

---

### 4. CQRS Read Model — 전송 이력 + 대시보드

```sql
CREATE TABLE notification_log (
    id              BIGSERIAL PRIMARY KEY,
    notification_id VARCHAR(50) NOT NULL UNIQUE,
    event_id        VARCHAR(50) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    user_id         BIGINT NOT NULL,
    channel         VARCHAR(20) NOT NULL,
    status          VARCHAR(20) NOT NULL,       -- SENT, FAILED, PENDING
    recipient       VARCHAR(255),
    error_message   TEXT,
    sent_at         TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE delivery_stats (
    id             BIGSERIAL PRIMARY KEY,
    date           DATE NOT NULL,
    channel        VARCHAR(20) NOT NULL,
    sent_count     INT DEFAULT 0,
    failed_count   INT DEFAULT 0,
    success_rate   DECIMAL(5,2),
    UNIQUE (date, channel)
);
```

**Dashboard API**:

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/v1/dashboard/delivery` | 일별 전송 통계 |
| GET | `/api/v1/dashboard/delivery/channels` | 채널별 성공률 |
| GET | `/api/v1/dashboard/delivery/failures` | 최근 실패 목록 |

---

### 5. 채널별 전송 구현

**Email**: Spring Mail + Thymeleaf 템플릿 → MailHog(로컬)
**Slack**: WebClient → Incoming Webhook URL
**In-App**: Redis Pub/Sub → Notification Hub(Phase 10)로 전달

---

## API 전체

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/v1/notifications?userId={id}` | 유저별 알림 이력 |
| PUT | `/api/v1/notifications/settings` | 알림 설정 변경 |
| GET | `/api/v1/notifications/settings` | 알림 설정 조회 |
| GET | `/api/v1/dashboard/delivery` | 전송 통계 |
| GET | `/api/v1/admin/dlq` | DLQ 메시지 목록 |
| POST | `/api/v1/admin/dlq/{id}/retry` | DLQ 재처리 |

---

## Docker Compose 추가

```yaml
notification-platform:
  build: ../kafka-cqrs
  ports:
    - "8084:8080"
  environment:
    KAFKA_SERVERS: fluxpay-kafka:9092
    SPRING_R2DBC_URL: r2dbc:postgresql://fluxpay-postgres:5432/fluxpay
    SPRING_DATA_REDIS_HOST: redis
    SPRING_MAIL_HOST: mailhog
    SPRING_MAIL_PORT: 1025
  depends_on: [fluxpay-kafka, fluxpay-postgres, redis]

mailhog:
  image: mailhog/mailhog:latest
  ports:
    - "8025:8025"   # Web UI (이메일 확인)
    - "1025:1025"   # SMTP
```

---

## 면접 키워드

- Consumer Group: rebalancing, partition assignment, offset commit
- at-least-once + 멱등성: processed_events, idempotency
- DLQ: poison pill 격리, 재처리 파이프라인, backoff
- CQRS: command/query 분리, eventual consistency, read model
- Strategy 패턴: 채널 확장성, OCP
- Consumer lag: 모니터링, backpressure, concurrency 튜닝
