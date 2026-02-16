# Phase 11: Kafka Consumer + DLQ + CQRS

## 한 줄 요약

현재 **Producer만 있는 Kafka**에 Consumer Group · Dead Letter Queue · CQRS Read Model을 추가하여, 이벤트 기반 아키텍처의 소비 측을 완성한다.

---

## 현재 상태 (AS-IS)

```
fluxpay-engine
     │
     ├─ Outbox 테이블 (outbox_events)
     ├─ OutboxPublisher (@Scheduled, 100ms 폴링)
     ├─ KafkaEventPublisher (CloudEvents 1.0)
     └─ 발행 토픽:
         ├─ fluxpay.order.events
         ├─ fluxpay.payment.events
         ├─ fluxpay.credit.events
         └─ fluxpay.subscription.events

Consumer: ❌ 없음
DLQ:      ❌ 없음
CQRS:     ❌ 없음
```

**문제**:
1. 이벤트를 발행만 하고 **아무도 소비하지 않음** — 이벤트가 Kafka에 쌓이기만 함
2. 실패한 메시지에 대한 **재처리 전략이 없음**
3. 결제 이벤트를 moalog-server가 소비해서 구독 상태를 업데이트해야 하는데, 현재는 **동기 HTTP 웹훅**에 의존

---

## 목표 상태 (TO-BE)

```
fluxpay-engine (Producer)
     │
     └─ Kafka Topics ──────────────────────────────────────┐
         │                                                  │
         ├─ fluxpay.payment.events ─┐                       │
         ├─ fluxpay.order.events ───┤                       │
         ├─ fluxpay.credit.events ──┤                       │
         └─ fluxpay.subscription.events ┤                   │
                                        │                   │
              ┌─────────────────────────┤                   │
              │                         │                   │
              ▼                         ▼                   │
   ┌──────────────────┐    ┌──────────────────┐            │
   │ Payment Event    │    │ Subscription     │            │
   │ Consumer         │    │ Event Consumer   │            │
   │ (fluxpay-engine) │    │ (fluxpay-engine) │            │
   │                  │    │                  │            │
   │ • 웹훅 발송      │    │ • moalog-server  │            │
   │ • 영수증 생성    │    │   구독 상태 동기  │            │
   │ • 정산 집계      │    │ • 알림 발송      │            │
   └───────┬──────────┘    └───────┬──────────┘            │
           │ 실패 시                │ 실패 시               │
           ▼                       ▼                       │
   ┌──────────────────┐    ┌──────────────────┐            │
   │ fluxpay.dlq.     │    │ fluxpay.dlq.     │            │
   │ payment          │    │ subscription     │            │
   └───────┬──────────┘    └──────────────────┘            │
           │                                                │
           ▼                                                │
   ┌──────────────────┐                                     │
   │  DLQ Consumer    │                                     │
   │  (재처리/알림)    │                                     │
   └──────────────────┘                                     │
                                                            │
   ┌────────────────────────────────────────────────────────┘
   │  CQRS Read Model
   │
   ▼
   ┌──────────────────┐    ┌──────────────────┐
   │ Event Projector  │───▶│ payment_summary  │ (PostgreSQL 뷰 테이블)
   │ (Consumer)       │    │ daily_revenue    │
   │                  │    │ subscription_stats│
   └──────────────────┘    └──────────────────┘
                                    │
                                    ▼
                           ┌──────────────────┐
                           │ Query API        │
                           │ GET /api/v1/     │
                           │   dashboard/     │
                           │   revenue        │
                           └──────────────────┘
```

---

## 구현 범위 (3개 기능)

### 기능 1: Kafka Consumer Group

**목적**: 발행된 이벤트를 실제로 소비하여 비즈니스 로직 실행

**Consumer 목록**:

| Consumer | 소비 토픽 | Group ID | 처리 내용 |
|----------|----------|----------|----------|
| PaymentEventConsumer | `fluxpay.payment.events` | `fluxpay-payment-processor` | 웹훅 발송, 영수증 생성 |
| SubscriptionEventConsumer | `fluxpay.subscription.events` | `fluxpay-subscription-sync` | 구독 상태 업데이트, 알림 |
| OrderEventConsumer | `fluxpay.order.events` | `fluxpay-order-processor` | 주문 완료 후처리 |
| EventProjector | `fluxpay.*.events` (전체) | `fluxpay-projector` | CQRS Read Model 투영 |

**Consumer 구현 패턴**:

```java
@Component
public class PaymentEventConsumer {

    @KafkaListener(
        topics = "fluxpay.payment.events",
        groupId = "fluxpay-payment-processor",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record) {
        CloudEvent event = parseCloudEvent(record.value());

        // 멱등성 보장: processed_events 테이블 체크
        if (isAlreadyProcessed(event.getId())) {
            log.info("Skipping duplicate event: {}", event.getId());
            return;
        }

        switch (event.getType()) {
            case "com.fluxpay.payment.confirmed.v1" -> handlePaymentConfirmed(event);
            case "com.fluxpay.payment.failed.v1"    -> handlePaymentFailed(event);
            case "com.fluxpay.payment.refunded.v1"  -> handlePaymentRefunded(event);
        }

        markAsProcessed(event.getId());
    }
}
```

**Consumer 멱등성**: 기존 `processed_events` 테이블 활용 (이미 스키마 있음)

```sql
-- 이미 존재하는 테이블
CREATE TABLE processed_events (
    id           BIGSERIAL PRIMARY KEY,
    event_id     VARCHAR(50) NOT NULL UNIQUE,
    processed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
```

**변경 파일**:
- `fluxpay-engine/src/.../infrastructure/messaging/consumer/PaymentEventConsumer.java` (신규)
- `fluxpay-engine/src/.../infrastructure/messaging/consumer/SubscriptionEventConsumer.java` (신규)
- `fluxpay-engine/src/.../infrastructure/messaging/consumer/OrderEventConsumer.java` (신규)
- `fluxpay-engine/src/.../infrastructure/messaging/consumer/CloudEventDeserializer.java` (신규)
- `fluxpay-engine/src/.../infrastructure/config/KafkaConsumerConfig.java` (신규)

**트레이드오프: at-least-once vs exactly-once**

| | at-least-once (선택) | exactly-once |
|---|---|---|
| 구현 | Consumer + 멱등성 체크 | Kafka Transactions + Idempotent Producer |
| 복잡도 | 중간 (processed_events 테이블) | 높음 (트랜잭션 코디네이터) |
| 성능 | 좋음 | 트랜잭션 오버헤드 |
| Spring 지원 | 완전 | 추가 설정 필요 |

> **판단**: at-least-once + 애플리케이션 레벨 멱등성이 실무에서 가장 일반적. processed_events 테이블이 이미 있으므로 자연스럽게 적용 가능. exactly-once는 Kafka Streams 같은 스트리밍 처리에서 더 적합.

---

### 기능 2: Dead Letter Queue (DLQ)

**목적**: 처리 실패한 메시지를 격리하고, 수동/자동 재처리 파이프라인 구축

**DLQ 전략**:

```
정상 토픽 → Consumer 처리 시도
    │
    ├─ 성공 → commit offset
    │
    └─ 실패 (3회 재시도 후)
         │
         ├─ DLQ 토픽으로 이동 (fluxpay.dlq.{domain})
         │   ├─ 원본 메시지 + 에러 정보 헤더
         │   │   ├─ X-Original-Topic
         │   │   ├─ X-Error-Message
         │   │   ├─ X-Retry-Count
         │   │   └─ X-Failed-At
         │   └─ DLQ Consumer가 모니터링
         │
         └─ Alertmanager 알림 (DLQ 메시지 발생 시)
```

**재시도 정책**:

```java
@Bean
public DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafkaTemplate) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(kafkaTemplate,
            (record, ex) -> new TopicPartition(
                "fluxpay.dlq." + extractDomain(record.topic()),
                record.partition()
            ));

    // 3회 재시도, 백오프: 1초 → 2초 → 4초
    FixedBackOff backOff = new FixedBackOff(1000L, 2L);

    return new DefaultErrorHandler(recoverer, backOff);
}
```

**DLQ 재처리 API**:

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/v1/admin/dlq/messages` | DLQ 메시지 목록 조회 |
| POST | `/api/v1/admin/dlq/messages/{id}/retry` | 개별 메시지 재처리 |
| POST | `/api/v1/admin/dlq/messages/retry-all` | 전체 재처리 |
| DELETE | `/api/v1/admin/dlq/messages/{id}` | 메시지 폐기 |

**변경 파일**:
- `fluxpay-engine/src/.../infrastructure/messaging/dlq/DlqConsumer.java` (신규)
- `fluxpay-engine/src/.../infrastructure/messaging/dlq/DlqAdminController.java` (신규)
- `fluxpay-engine/src/.../infrastructure/config/KafkaConsumerConfig.java` (DLQ 에러 핸들러 추가)

**Prometheus 메트릭**:

| 메트릭 | 타입 | 설명 |
|--------|------|------|
| `kafka_consumer_messages_total` | Counter | 총 소비 메시지 수 |
| `kafka_consumer_errors_total` | Counter | 처리 실패 수 |
| `kafka_dlq_messages_total` | Counter | DLQ 유입 메시지 수 |
| `kafka_consumer_lag` | Gauge | Consumer lag (지연) |

**Alertmanager 룰 추가**:
```yaml
- alert: KafkaDlqMessages
  expr: increase(kafka_dlq_messages_total[5m]) > 0
  for: 1m
  labels:
    severity: warning
  annotations:
    summary: "Dead Letter Queue에 메시지가 유입됨"
```

**트레이드오프: DLQ vs 무한 재시도**

| | DLQ (선택) | 무한 재시도 |
|---|---|---|
| 장점 | 실패 격리, 정상 흐름 비차단 | 단순, 결국 처리됨 |
| 단점 | 별도 재처리 로직 필요 | 포이즌 필(poison pill)에 Consumer 블로킹 |
| 적합 | 결제 (실패 원인 파악 필요) | 일시적 장애만 있는 경우 |

> **판단**: 결제 이벤트는 실패 원인이 다양 (잘못된 데이터, 외부 API 장애, 비즈니스 룰 위반). 무한 재시도하면 같은 에러가 반복. DLQ로 격리 후 원인 분석 → 수정 → 재처리가 운영에 적합.

---

### 기능 3: CQRS Read Model (조회 최적화)

**목적**: 결제/구독 이벤트를 소비하여 비정규화된 조회 전용 테이블을 구축. 실시간 대시보드 데이터 제공.

**Read Model 테이블 (PostgreSQL)**:

```sql
-- 일별 매출 집계 (이벤트 소비 시 업데이트)
CREATE TABLE daily_revenue (
    id          BIGSERIAL PRIMARY KEY,
    date        DATE NOT NULL,
    tenant_id   VARCHAR(50) NOT NULL,
    total_amount BIGINT NOT NULL DEFAULT 0,
    order_count  INT NOT NULL DEFAULT 0,
    refund_amount BIGINT NOT NULL DEFAULT 0,
    refund_count  INT NOT NULL DEFAULT 0,
    net_revenue  BIGINT NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE (date, tenant_id)
);

-- 구독 현황 스냅샷
CREATE TABLE subscription_stats (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     VARCHAR(50) NOT NULL,
    plan_name     VARCHAR(20) NOT NULL,
    active_count  INT NOT NULL DEFAULT 0,
    churned_count INT NOT NULL DEFAULT 0,
    mrr           BIGINT NOT NULL DEFAULT 0,  -- Monthly Recurring Revenue
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE (tenant_id, plan_name)
);

-- 결제 이벤트 타임라인 (최근 이벤트 빠른 조회)
CREATE TABLE payment_timeline (
    id           BIGSERIAL PRIMARY KEY,
    event_id     VARCHAR(50) NOT NULL UNIQUE,
    tenant_id    VARCHAR(50) NOT NULL,
    event_type   VARCHAR(50) NOT NULL,
    amount       BIGINT,
    currency     VARCHAR(10),
    customer_id  VARCHAR(50),
    description  TEXT,
    occurred_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX idx_timeline_tenant_occurred ON payment_timeline (tenant_id, occurred_at DESC);
```

**Projector (이벤트 → Read Model)**:

```java
@Component
public class EventProjector {

    @KafkaListener(
        topics = {"fluxpay.payment.events", "fluxpay.subscription.events"},
        groupId = "fluxpay-projector"
    )
    public void project(ConsumerRecord<String, String> record) {
        CloudEvent event = parseCloudEvent(record.value());

        switch (event.getType()) {
            case "com.fluxpay.payment.confirmed.v1" -> projectPaymentConfirmed(event);
            case "com.fluxpay.payment.refunded.v1"  -> projectPaymentRefunded(event);
            case "com.fluxpay.subscription.created.v1" -> projectSubscriptionCreated(event);
            case "com.fluxpay.subscription.cancelled.v1" -> projectSubscriptionCancelled(event);
        }
    }

    private void projectPaymentConfirmed(CloudEvent event) {
        // 1. payment_timeline에 INSERT
        // 2. daily_revenue에 UPSERT (ON CONFLICT UPDATE)
    }
}
```

**Query API (새 엔드포인트)**:

| Method | Path | Response | Purpose |
|--------|------|----------|---------|
| GET | `/api/v1/dashboard/revenue?from=&to=` | 일별 매출 차트 데이터 | 매출 대시보드 |
| GET | `/api/v1/dashboard/subscriptions` | 플랜별 활성 구독/MRR | 구독 현황 |
| GET | `/api/v1/dashboard/timeline?limit=50` | 최근 결제 이벤트 | 실시간 피드 |

**변경 파일**:
- `fluxpay-engine/src/.../infrastructure/messaging/projector/EventProjector.java` (신규)
- `fluxpay-engine/src/.../infrastructure/persistence/readmodel/DailyRevenueRepository.java` (신규)
- `fluxpay-engine/src/.../infrastructure/persistence/readmodel/SubscriptionStatsRepository.java` (신규)
- `fluxpay-engine/src/.../infrastructure/persistence/readmodel/PaymentTimelineRepository.java` (신규)
- `fluxpay-engine/src/.../presentation/DashboardController.java` (신규)
- `fluxpay-engine/src/main/resources/db/migration/V10__create_read_model_tables.sql` (신규)
- `fluxpay-engine/init-scripts/01-init-schema.sql` — Read Model 테이블 추가

**트레이드오프: CQRS Read Model 위치**

| | 같은 DB (선택) | 별도 DB (MongoDB/ES) |
|---|---|---|
| 복잡도 | 낮음 (R2DBC 재활용) | 높음 (새 커넥션, 새 드라이버) |
| 일관성 | 같은 트랜잭션 가능 | eventual consistency |
| 조회 성능 | 인덱스로 충분 | 더 유연한 쿼리 |
| 운영 비용 | 추가 인프라 없음 | DB 추가 운영 |

> **판단**: 현재 규모에서 PostgreSQL의 UPSERT + 인덱스면 충분. 별도 MongoDB/ES는 Phase 12(검색)에서 Elasticsearch로 도입. CQRS의 핵심은 "커맨드/쿼리 분리"이지 "DB 분리"가 아님.

**트레이드오프: Eventual Consistency 허용 범위**

```
이벤트 발행 → Kafka → Consumer → Read Model 업데이트
         ~100ms    ~10ms    ~10ms

총 지연: ~120ms (사용자가 인지하기 어려운 수준)
```

> 대시보드 데이터의 ~120ms 지연은 허용 가능. 실시간 잔액 조회 같은 강한 일관성이 필요하면 Command DB 직접 조회.

---

## Docker Compose 변경

```yaml
# 변경 없음 — 기존 Kafka, Zookeeper, PostgreSQL 그대로 사용
# Consumer는 fluxpay-engine 내부에 추가 (별도 서비스 아님)
```

**Kafka 토픽 사전 생성** (선택 — 현재 auto.create.topics.enable=true):
```yaml
# docker-compose.yaml의 fluxpay-kafka에 초기화 스크립트 추가 가능
# 또는 KafkaAdmin Bean으로 앱 시작 시 생성
```

---

## application.yml 변경

```yaml
spring:
  kafka:
    consumer:
      group-id: fluxpay-payment-processor
      auto-offset-reset: earliest
      enable-auto-commit: false          # 수동 커밋 (at-least-once)
      max-poll-records: 100
      properties:
        max.poll.interval.ms: 300000     # 5분 (긴 처리 허용)
        session.timeout.ms: 30000
    listener:
      ack-mode: RECORD                   # 메시지 단위 커밋
      concurrency: 3                     # 파티션 수에 맞춰 조정
```

---

## Grafana 대시보드 추가

**Kafka Consumer 대시보드** (신규):
- Consumer lag per group (중요!)
- 메시지 처리 속도 (msg/sec)
- DLQ 유입률
- CQRS projection 지연 시간

---

## 검증 방법

```bash
# 1. 이벤트 발행 후 Consumer 처리 확인
#    - 결제 API 호출 → Kafka 토픽 확인 → Read Model 테이블 확인

# 2. DLQ 테스트
#    - 의도적으로 잘못된 이벤트 발행 → DLQ 토픽 유입 확인 → 재처리 API 호출

# 3. 멱등성 테스트
#    - 동일 이벤트 2번 발행 → processed_events에 1건만 → Read Model 1번만 업데이트

# 4. Consumer lag 모니터링
#    - k6로 대량 결제 → Consumer lag 증가 → 소비 완료 후 lag 0 확인
```

---

## 예상 작업량

| 기능 | 신규 파일 | 수정 파일 | 난이도 |
|------|----------|----------|--------|
| Consumer Group (3개) | 5 | 2 | ★★☆ |
| DLQ + 재처리 | 3 | 1 | ★★☆ |
| CQRS Read Model | 5 | 2 | ★★★ |
| Dashboard API | 1 | 0 | ★☆☆ |

**의존성**: Consumer → DLQ (순차), Projector/CQRS는 독립

---

## 면접 키워드

- Consumer Group: rebalancing, partition assignment, offset commit 전략
- at-least-once vs exactly-once: 멱등성, processed_events
- DLQ: poison pill, 재처리 파이프라인, backoff 전략
- CQRS: command/query 분리, eventual consistency, projection
- Consumer lag: 모니터링, backpressure, concurrency 튜닝
