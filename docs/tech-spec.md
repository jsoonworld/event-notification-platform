# Event-driven Notification Platform - 기술 명세서

> **문서 버전**: 1.0.0
> **최종 수정**: 2026-02-16
> **기반 문서**: [1-pager.md](./1-pager.md)

---

## 목차

1. [개요](#1-개요)
2. [기술 스택 상세](#2-기술-스택-상세)
3. [프로젝트 구조](#3-프로젝트-구조)
4. [핵심 모듈 설계](#4-핵심-모듈-설계)
5. [데이터 모델](#5-데이터-모델)
6. [API 상세 설계](#6-api-상세-설계)
7. [Kafka Consumer 설계](#7-kafka-consumer-설계)
8. [DLQ 파이프라인](#8-dlq-파이프라인)
9. [에러 처리](#9-에러-처리)
10. [설정 관리](#10-설정-관리)
11. [모니터링](#11-모니터링)
12. [Docker](#12-docker)
13. [테스트 전략](#13-테스트-전략)
14. [구현 페이즈](#14-구현-페이즈)
15. [Open Questions / Future Work](#15-open-questions--future-work)

---

## 1. 개요

### 프로젝트 요약

Event-driven Notification Delivery Platform은 moalog-platform 생태계에서 fluxpay-engine이 Kafka로 발행하는 도메인 이벤트(결제, 구독, 주문)를 소비하여 Email, Slack, Push, In-App 채널로 알림을 전달하고, 전송 결과를 CQRS Read Model로 추적하는 리액티브 마이크로서비스이다.

fluxpay-engine은 Transactional Outbox 패턴으로 `fluxpay.{aggregateType}.events` 토픽에 도메인 이벤트를 발행하지만, 현재 이를 소비하는 서비스가 없다. 이 플랫폼이 해당 이벤트를 소비하여 사용자에게 실시간 알림을 전달하고, 실패 시 DLQ로 격리하여 운영자가 재처리할 수 있는 파이프라인을 구축한다.

### Goals

| # | 목표 | 측정 기준 |
|---|------|----------|
| G1 | Kafka 이벤트 소비 및 다채널 알림 전송 | 3개 토픽(payment, subscription, order) Consumer 정상 동작 |
| G2 | at-least-once 보장 + 멱등성 | processed_events 중복 체크, 동일 event_id 재소비 시 skip 확인 |
| G3 | DLQ 기반 실패 격리 및 재처리 | 3회 재시도 후 DLQ 적재, Admin API로 재처리/폐기 가능 |
| G4 | CQRS Read Model 기반 전송 추적 | notification_log 전수 기록, delivery_stats 일별 집계, Dashboard API 응답 |
| G5 | 채널 확장성 | Strategy 패턴으로 새 채널 추가 시 기존 코드 변경 없음 (OCP) |
| G6 | 운영 가시성 | Prometheus 메트릭 + Grafana 대시보드로 전송 현황 실시간 모니터링 |

### Non-Goals

| 항목 | 제외 이유 |
|------|----------|
| Push Notification (FCM/APNs) | Phase 1에서는 Email, Slack, In-App만 구현. Push는 모바일 클라이언트 준비 후 Phase 2에서 추가 |
| 템플릿 관리 UI | 초기에는 Thymeleaf 파일 기반. 관리 UI는 프론트엔드 리소스 확보 후 별도 구현 |
| 사용자 알림 설정 동기화 (moalog-server) | Notification Platform 자체 설정 테이블로 관리. moalog-server 연동은 API Gateway 도입 후 |
| 실시간 WebSocket 전달 | In-App은 Redis Pub/Sub로 발행까지만. WebSocket Hub는 별도 서비스로 분리 |
| Multi-tenant 지원 | fluxpay-engine의 tenant_id 필드는 수신하되, 알림 라우팅에는 미반영 (단일 테넌트 우선) |

---

## 2. 기술 스택 상세

### 핵심 의존성

| groupId:artifactId | 버전 | 용도 | 선택 이유 |
|---------------------|------|------|----------|
| `org.springframework.boot:spring-boot-starter-webflux` | 3.2.2 | Reactive REST API | fluxpay-engine과 동일 버전, Netty 기반 non-blocking I/O |
| `org.springframework.kafka:spring-kafka` | 3.1.x (BOM) | Kafka Consumer/Producer | Spring Boot 3.2 BOM 관리, `@KafkaListener` + manual ack 지원 |
| `org.springframework.boot:spring-boot-starter-data-r2dbc` | 3.2.2 | Reactive DB 접근 | PostgreSQL R2DBC로 non-blocking 쿼리, fluxpay-engine과 동일 DB 접근 방식 |
| `org.postgresql:r2dbc-postgresql` | 1.0.x | R2DBC PostgreSQL 드라이버 | Spring Data R2DBC 공식 지원 드라이버 |
| `org.springframework.boot:spring-boot-starter-mail` | 3.2.2 | SMTP 이메일 전송 | JavaMailSender 기반, MailHog와 호환 |
| `org.springframework.boot:spring-boot-starter-thymeleaf` | 3.2.2 | 이메일/슬랙 메시지 템플릿 | HTML 이메일 템플릿 렌더링, Spring 생태계 표준 |
| `org.springframework.boot:spring-boot-starter-data-redis-reactive` | 3.2.2 | Reactive Redis (Lettuce) | 알림 설정 캐시, In-App Pub/Sub, 중복 방지 |
| `org.springframework.boot:spring-boot-starter-actuator` | 3.2.2 | Health check, 메트릭 노출 | /actuator/health, /actuator/prometheus |
| `io.micrometer:micrometer-registry-prometheus` | 1.12.x (BOM) | Prometheus 메트릭 수집 | 기존 moalog-platform Prometheus 스택과 통합 |
| `org.springframework.boot:spring-boot-starter-validation` | 3.2.2 | Jakarta Bean Validation | API 요청 검증 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.16.x (BOM) | JSON 직렬화/역직렬화 | Kafka 메시지 역직렬화, API 응답 직렬화 |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.16.x (BOM) | Java 8+ 날짜/시간 Jackson 지원 | Instant, LocalDate 등 직렬화 |
| `org.projectlombok:lombok` | 1.18.x | 보일러플레이트 제거 | fluxpay-engine과 동일 관례 |

### 테스트 의존성

| groupId:artifactId | 버전 | 용도 |
|---------------------|------|------|
| `org.springframework.boot:spring-boot-starter-test` | 3.2.2 | JUnit 5, Mockito, WebTestClient |
| `io.projectreactor:reactor-test` | 3.6.x | StepVerifier 기반 리액티브 테스트 |
| `org.springframework.kafka:spring-kafka-test` | 3.1.x | EmbeddedKafka 테스트 |
| `org.testcontainers:testcontainers` | 1.19.x | Docker 기반 통합 테스트 |
| `org.testcontainers:postgresql` | 1.19.x | PostgreSQL Testcontainer |
| `org.testcontainers:kafka` | 1.19.x | Kafka Testcontainer |
| `org.testcontainers:r2dbc` | 1.19.x | R2DBC Testcontainer |
| `org.testcontainers:junit-jupiter` | 1.19.x | Testcontainers JUnit 5 통합 |

### 빌드 도구

- **Gradle 8.x** (Groovy DSL) -- fluxpay-engine과 동일
- **Java 21** -- Virtual Threads 활용 가능, `record` 패턴 적극 사용
- **Spring Boot 3.2.2** -- fluxpay-engine과 정확히 동일 버전

---

## 3. 프로젝트 구조

### Hexagonal Architecture

fluxpay-engine과 동일한 Hexagonal (Ports & Adapters) 아키텍처를 따른다. 의존성 방향은 항상 안쪽(domain)을 향한다.

```
┌─────────────────────────────────────────────────────────┐
│                    interfaces (REST)                     │
│              Controllers, DTOs, Error Handler            │
├─────────────────────────────────────────────────────────┤
│                   application (Use Cases)                │
│                  Ports (in/out interfaces)                │
├─────────────────────────────────────────────────────────┤
│                      domain (Core)                       │
│          Models, Domain Services, Domain Events          │
├─────────────────────────────────────────────────────────┤
│                 infrastructure (Adapters)                 │
│     Kafka, Sender, Persistence, Config, DLQ              │
└─────────────────────────────────────────────────────────┘
```

### 패키지 트리

```
src/main/java/com/jsoonworld/notification/
├── NotificationPlatformApplication.java          # Spring Boot 메인 클래스
│
├── domain/
│   ├── model/
│   │   ├── NotificationRequest.java              # 알림 전송 요청 (이벤트 → 알림 변환 결과)
│   │   ├── DeliveryResult.java                   # 채널 전송 결과 (성공/실패, 에러 메시지)
│   │   ├── NotificationSettings.java             # 사용자별 알림 설정 (채널 on/off)
│   │   ├── NotificationChannel.java              # 채널 enum (EMAIL, SLACK, PUSH, IN_APP)
│   │   ├── NotificationStatus.java               # 상태 enum (PENDING, SENT, FAILED)
│   │   └── EventType.java                        # 이벤트 타입 enum (PAYMENT_APPROVED, ORDER_CREATED, ...)
│   │
│   └── service/
│       ├── NotificationRouter.java               # 이벤트 타입 + 사용자 설정 → 채널 결정 로직
│       └── DeliveryTracker.java                  # notification_log 기록 + delivery_stats 갱신
│
├── application/
│   └── port/
│       ├── in/
│       │   ├── SendNotificationUseCase.java      # 알림 전송 유스케이스 (Consumer → Router → Sender)
│       │   ├── QueryNotificationUseCase.java     # 알림 이력 조회 유스케이스
│       │   ├── ManageSettingsUseCase.java         # 알림 설정 관리 유스케이스
│       │   └── ManageDlqUseCase.java             # DLQ 관리 유스케이스
│       └── out/
│           ├── NotificationSender.java           # 채널별 전송 포트 (Strategy interface)
│           ├── NotificationLogPort.java          # notification_log 저장 포트
│           ├── DeliveryStatsPort.java            # delivery_stats 갱신 포트
│           ├── NotificationSettingsPort.java     # 알림 설정 조회/저장 포트
│           ├── ProcessedEventPort.java           # 멱등성 체크 포트
│           └── DlqMessagePort.java              # DLQ 메시지 저장/조회 포트
│
├── infrastructure/
│   ├── kafka/
│   │   ├── PaymentNotifConsumer.java             # fluxpay.payment.events 소비
│   │   ├── SubscriptionNotifConsumer.java        # fluxpay.subscription.events 소비
│   │   ├── OrderNotifConsumer.java               # fluxpay.order.events 소비
│   │   ├── AbstractNotifConsumer.java            # 공통 Consumer 로직 (멱등성, ack, 에러 처리)
│   │   └── EventDeserializer.java               # fluxpay DomainEvent JSON 역직렬화
│   │
│   ├── sender/
│   │   ├── EmailSender.java                      # Spring Mail + Thymeleaf 이메일 전송
│   │   ├── SlackSender.java                      # WebClient → Slack Incoming Webhook
│   │   ├── PushSender.java                       # Push 전송 (Phase 2 — stub)
│   │   └── InAppSender.java                     # Redis Pub/Sub In-App 알림
│   │
│   ├── persistence/
│   │   ├── entity/
│   │   │   ├── NotificationLogEntity.java        # notification_log R2DBC 엔티티
│   │   │   ├── DeliveryStatsEntity.java          # delivery_stats R2DBC 엔티티
│   │   │   ├── NotificationSettingsEntity.java   # notification_settings R2DBC 엔티티
│   │   │   ├── ProcessedEventEntity.java         # processed_events R2DBC 엔티티
│   │   │   └── DlqMessageEntity.java            # dlq_messages R2DBC 엔티티
│   │   ├── repository/
│   │   │   ├── NotificationLogR2dbcRepository.java
│   │   │   ├── DeliveryStatsR2dbcRepository.java
│   │   │   ├── NotificationSettingsR2dbcRepository.java
│   │   │   ├── ProcessedEventR2dbcRepository.java
│   │   │   └── DlqMessageR2dbcRepository.java
│   │   └── adapter/
│   │       ├── NotificationLogAdapter.java       # NotificationLogPort 구현
│   │       ├── DeliveryStatsAdapter.java         # DeliveryStatsPort 구현
│   │       ├── NotificationSettingsAdapter.java  # NotificationSettingsPort 구현
│   │       ├── ProcessedEventAdapter.java        # ProcessedEventPort 구현
│   │       └── DlqMessageAdapter.java           # DlqMessagePort 구현
│   │
│   ├── config/
│   │   ├── KafkaConsumerConfig.java              # Kafka Consumer Factory, 수동 ack 설정
│   │   ├── KafkaProducerConfig.java              # Kafka Producer (DLQ 발행용)
│   │   ├── R2dbcConfig.java                      # R2DBC ConnectionFactory, 트랜잭션
│   │   ├── RedisConfig.java                      # ReactiveRedisTemplate, Pub/Sub
│   │   ├── MailConfig.java                       # JavaMailSender 설정
│   │   ├── WebClientConfig.java                  # Slack Webhook용 WebClient
│   │   ├── DomainConfig.java                     # Domain Service Bean 등록
│   │   └── MetricsConfig.java                    # Custom Prometheus 메트릭 등록
│   │
│   └── dlq/
│       ├── DlqProducer.java                      # DLQ 토픽 발행 (3회 실패 후)
│       ├── DlqConsumer.java                      # DLQ 토픽 소비 → dlq_messages 테이블 저장
│       └── DlqRetryService.java                  # DLQ 메시지 재처리 로직
│
├── interfaces/
│   └── rest/
│       ├── NotificationController.java           # 알림 이력 조회 API
│       ├── NotificationSettingsController.java   # 알림 설정 관리 API
│       ├── DashboardController.java              # 전송 통계 대시보드 API
│       ├── DlqAdminController.java               # DLQ 관리 Admin API
│       ├── dto/
│       │   ├── request/
│       │   │   └── NotificationSettingsRequest.java
│       │   └── response/
│       │       ├── NotificationResponse.java
│       │       ├── NotificationSettingsResponse.java
│       │       ├── DeliveryStatsResponse.java
│       │       ├── ChannelStatsResponse.java
│       │       ├── FailureResponse.java
│       │       └── DlqMessageResponse.java
│       └── GlobalExceptionHandler.java           # 전역 에러 핸들러
│
└── templates/                                     # Thymeleaf 템플릿 (src/main/resources/)
    ├── email/
    │   ├── payment-completed.html
    │   ├── payment-failed.html
    │   ├── subscription-renewed.html
    │   └── subscription-expiring.html
    └── slack/
        ├── payment-completed.txt
        └── payment-failed.txt

src/main/resources/
├── application.yml
├── application-local.yml
├── application-docker.yml
├── templates/                                     # Thymeleaf 템플릿
│   ├── email/
│   └── slack/
└── db/
    └── migration/
        └── V1__init_schema.sql
```

---

## 4. 핵심 모듈 설계

### 4.1 NotificationSender Interface (Strategy Pattern)

채널별 전송 로직을 Strategy 패턴으로 분리한다. 새 채널 추가 시 `NotificationSender` 구현체만 추가하면 된다.

```java
package com.jsoonworld.notification.application.port.out;

import com.jsoonworld.notification.domain.model.DeliveryResult;
import com.jsoonworld.notification.domain.model.NotificationChannel;
import com.jsoonworld.notification.domain.model.NotificationRequest;
import reactor.core.publisher.Mono;

/**
 * 알림 채널별 전송 포트.
 * Strategy 패턴 — 각 채널(Email, Slack, Push, In-App)이 이 인터페이스를 구현한다.
 */
public interface NotificationSender {

    /**
     * 알림을 전송하고 결과를 반환한다.
     *
     * @param request 전송할 알림 요청
     * @return 전송 결과 (성공/실패, 에러 메시지 포함)
     */
    Mono<DeliveryResult> send(NotificationRequest request);

    /**
     * 이 Sender가 담당하는 채널을 반환한다.
     */
    NotificationChannel channel();

    /**
     * 이 Sender가 현재 사용 가능한지 확인한다.
     * 기본값은 true. 외부 서비스 장애 시 false를 반환하여 Circuit Breaker 역할.
     */
    default Mono<Boolean> isAvailable() {
        return Mono.just(true);
    }
}
```

**채널별 구현체**:

```java
// EmailSender.java
@Component
public class EmailSender implements NotificationSender {

    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;

    @Override
    public Mono<DeliveryResult> send(NotificationRequest request) {
        return Mono.fromCallable(() -> {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(request.recipient());
            helper.setSubject(request.subject());

            Context context = new Context();
            context.setVariables(request.templateVariables());
            String html = templateEngine.process(request.templateName(), context);
            helper.setText(html, true);

            mailSender.send(message);
            return DeliveryResult.success(NotificationChannel.EMAIL, request.notificationId());
        })
        .subscribeOn(Schedulers.boundedElastic()) // blocking SMTP 호출을 별도 스레드에서
        .onErrorResume(e -> Mono.just(
            DeliveryResult.failure(NotificationChannel.EMAIL, request.notificationId(), e.getMessage())
        ));
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }
}
```

```java
// SlackSender.java
@Component
public class SlackSender implements NotificationSender {

    private final WebClient webClient;
    private final SpringTemplateEngine templateEngine;

    @Value("${notification.slack.webhook-url}")
    private String webhookUrl;

    @Override
    public Mono<DeliveryResult> send(NotificationRequest request) {
        Context context = new Context();
        context.setVariables(request.templateVariables());
        String text = templateEngine.process(request.templateName(), context);

        return webClient.post()
            .uri(webhookUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("text", text))
            .retrieve()
            .toBodilessEntity()
            .map(resp -> DeliveryResult.success(NotificationChannel.SLACK, request.notificationId()))
            .onErrorResume(e -> Mono.just(
                DeliveryResult.failure(NotificationChannel.SLACK, request.notificationId(), e.getMessage())
            ));
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SLACK;
    }
}
```

```java
// InAppSender.java
@Component
public class InAppSender implements NotificationSender {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String CHANNEL_PREFIX = "notification:inapp:user:";

    @Override
    public Mono<DeliveryResult> send(NotificationRequest request) {
        String channel = CHANNEL_PREFIX + request.userId();
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(request.toInAppPayload()))
            .flatMap(payload -> redisTemplate.convertAndSend(channel, payload))
            .map(receivers -> DeliveryResult.success(NotificationChannel.IN_APP, request.notificationId()))
            .onErrorResume(e -> Mono.just(
                DeliveryResult.failure(NotificationChannel.IN_APP, request.notificationId(), e.getMessage())
            ));
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.IN_APP;
    }
}
```

### 4.2 NotificationRouter

이벤트 타입과 사용자 알림 설정을 조합하여 전송 대상 채널을 결정한다.

```java
package com.jsoonworld.notification.domain.service;

import com.jsoonworld.notification.domain.model.EventType;
import com.jsoonworld.notification.domain.model.NotificationChannel;
import com.jsoonworld.notification.domain.model.NotificationSettings;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 이벤트 타입 → 기본 채널 매핑 + 사용자 설정 필터링.
 * Domain Service: 외부 의존성 없이 순수 로직으로 채널을 결정한다.
 */
public class NotificationRouter {

    /**
     * 이벤트 타입별 기본 채널 매핑.
     */
    private static final Map<EventType, Set<NotificationChannel>> DEFAULT_CHANNELS = Map.ofEntries(
        Map.entry(EventType.PAYMENT_APPROVED, Set.of(
            NotificationChannel.EMAIL, NotificationChannel.IN_APP
        )),
        Map.entry(EventType.PAYMENT_FAILED, Set.of(
            NotificationChannel.EMAIL, NotificationChannel.IN_APP, NotificationChannel.SLACK
        )),
        Map.entry(EventType.SUBSCRIPTION_RENEWED, Set.of(
            NotificationChannel.EMAIL
        )),
        Map.entry(EventType.SUBSCRIPTION_EXPIRING, Set.of(
            NotificationChannel.EMAIL, NotificationChannel.IN_APP
        )),
        Map.entry(EventType.SUBSCRIPTION_CANCELLED, Set.of(
            NotificationChannel.EMAIL, NotificationChannel.IN_APP
        )),
        Map.entry(EventType.ORDER_CREATED, Set.of(
            NotificationChannel.EMAIL, NotificationChannel.IN_APP
        )),
        Map.entry(EventType.ORDER_COMPLETED, Set.of(
            NotificationChannel.EMAIL
        )),
        Map.entry(EventType.REFUND_COMPLETED, Set.of(
            NotificationChannel.EMAIL, NotificationChannel.IN_APP
        )),
        Map.entry(EventType.REFUND_FAILED, Set.of(
            NotificationChannel.EMAIL, NotificationChannel.IN_APP, NotificationChannel.SLACK
        ))
    );

    /**
     * 이벤트 타입의 기본 채널 중 사용자가 활성화한 채널만 반환한다.
     *
     * @param eventType 이벤트 타입
     * @param settings  사용자 알림 설정 (null이면 기본 채널 전체 반환)
     * @return 전송 대상 채널 목록
     */
    public List<NotificationChannel> resolveChannels(EventType eventType, NotificationSettings settings) {
        Set<NotificationChannel> defaults = DEFAULT_CHANNELS.getOrDefault(eventType, Set.of());

        if (settings == null) {
            return List.copyOf(defaults);
        }

        return defaults.stream()
            .filter(settings::isChannelEnabled)
            .toList();
    }
}
```

### 4.3 AbstractNotifConsumer (Kafka Consumer Base)

모든 Consumer가 공유하는 공통 로직: 멱등성 체크, 수동 ack, 에러 처리/DLQ 전달.

```java
package com.jsoonworld.notification.infrastructure.kafka;

import com.jsoonworld.notification.application.port.in.SendNotificationUseCase;
import com.jsoonworld.notification.application.port.out.ProcessedEventPort;
import com.jsoonworld.notification.domain.model.EventType;
import com.jsoonworld.notification.domain.model.NotificationRequest;
import com.jsoonworld.notification.infrastructure.dlq.DlqProducer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.Acknowledgment;
import reactor.core.publisher.Mono;

/**
 * Kafka Consumer 공통 베이스 클래스.
 *
 * 처리 흐름:
 * 1. 이벤트 역직렬화
 * 2. 멱등성 체크 (processed_events 조회)
 * 3. 이벤트 → NotificationRequest 변환
 * 4. SendNotificationUseCase 실행
 * 5. processed_events 기록
 * 6. 수동 ack (offset commit)
 * 7. 실패 시 → DlqProducer로 DLQ 전달
 */
public abstract class AbstractNotifConsumer {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final ProcessedEventPort processedEventPort;
    private final SendNotificationUseCase sendNotificationUseCase;
    private final DlqProducer dlqProducer;

    protected AbstractNotifConsumer(
        ProcessedEventPort processedEventPort,
        SendNotificationUseCase sendNotificationUseCase,
        DlqProducer dlqProducer
    ) {
        this.processedEventPort = processedEventPort;
        this.sendNotificationUseCase = sendNotificationUseCase;
        this.dlqProducer = dlqProducer;
    }

    /**
     * Consumer 구현체가 호출하는 공통 처리 메서드.
     */
    protected void handleRecord(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String eventId = extractEventId(record);

        processedEventPort.existsByEventId(eventId)
            .flatMap(exists -> {
                if (exists) {
                    log.info("Duplicate event skipped: eventId={}", eventId);
                    return Mono.empty();
                }
                return processEvent(record)
                    .flatMap(request -> sendNotificationUseCase.execute(request))
                    .flatMap(result -> processedEventPort.save(eventId));
            })
            .doOnSuccess(v -> ack.acknowledge())
            .doOnError(e -> {
                log.error("Failed to process event: eventId={}, error={}", eventId, e.getMessage());
                dlqProducer.send(record, e.getMessage()).subscribe();
                ack.acknowledge(); // DLQ로 전달했으므로 ack
            })
            .subscribe();
    }

    /**
     * 하위 클래스에서 구현: 레코드에서 eventId를 추출한다.
     */
    protected abstract String extractEventId(ConsumerRecord<String, String> record);

    /**
     * 하위 클래스에서 구현: 레코드를 NotificationRequest로 변환한다.
     */
    protected abstract Mono<NotificationRequest> processEvent(ConsumerRecord<String, String> record);

    /**
     * 하위 클래스에서 구현: 이 Consumer가 처리하는 이벤트 타입을 반환한다.
     */
    protected abstract EventType resolveEventType(String eventType);
}
```

### 4.4 DeliveryTracker

전송 결과를 notification_log에 기록하고, delivery_stats를 갱신한다.

```java
package com.jsoonworld.notification.domain.service;

import com.jsoonworld.notification.application.port.out.DeliveryStatsPort;
import com.jsoonworld.notification.application.port.out.NotificationLogPort;
import com.jsoonworld.notification.domain.model.DeliveryResult;
import com.jsoonworld.notification.domain.model.NotificationRequest;
import com.jsoonworld.notification.domain.model.NotificationStatus;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * CQRS Write Side — 전송 결과를 기록하고 통계를 갱신한다.
 */
public class DeliveryTracker {

    private final NotificationLogPort notificationLogPort;
    private final DeliveryStatsPort deliveryStatsPort;

    public DeliveryTracker(NotificationLogPort notificationLogPort, DeliveryStatsPort deliveryStatsPort) {
        this.notificationLogPort = notificationLogPort;
        this.deliveryStatsPort = deliveryStatsPort;
    }

    /**
     * 전송 결과를 notification_log에 저장하고 delivery_stats를 갱신한다.
     */
    public Mono<Void> track(NotificationRequest request, DeliveryResult result) {
        NotificationStatus status = result.isSuccess()
            ? NotificationStatus.SENT
            : NotificationStatus.FAILED;

        return notificationLogPort.save(
                request.notificationId(),
                request.eventId(),
                request.eventType().name(),
                request.userId(),
                result.channel().name(),
                status.name(),
                request.recipient(),
                result.errorMessage(),
                result.sentAt()
            )
            .then(deliveryStatsPort.incrementCount(
                LocalDate.now(),
                result.channel().name(),
                result.isSuccess()
            ));
    }
}
```

### 4.5 Domain Models

```java
// NotificationRequest.java
package com.jsoonworld.notification.domain.model;

import java.util.Map;

public record NotificationRequest(
    String notificationId,       // UUID — 이 알림의 고유 ID
    String eventId,              // 원본 도메인 이벤트 ID
    EventType eventType,         // 이벤트 타입
    Long userId,                 // 수신 사용자 ID
    String recipient,            // 채널별 수신 주소 (이메일, 슬랙 채널 등)
    String subject,              // 이메일 제목 등
    String templateName,         // Thymeleaf 템플릿 이름
    Map<String, Object> templateVariables  // 템플릿 변수
) {
    public Map<String, Object> toInAppPayload() {
        return Map.of(
            "notificationId", notificationId,
            "eventType", eventType.name(),
            "subject", subject,
            "variables", templateVariables
        );
    }
}
```

```java
// DeliveryResult.java
package com.jsoonworld.notification.domain.model;

import java.time.Instant;

public record DeliveryResult(
    NotificationChannel channel,
    String notificationId,
    boolean success,
    String errorMessage,
    Instant sentAt
) {
    public static DeliveryResult success(NotificationChannel channel, String notificationId) {
        return new DeliveryResult(channel, notificationId, true, null, Instant.now());
    }

    public static DeliveryResult failure(NotificationChannel channel, String notificationId, String errorMessage) {
        return new DeliveryResult(channel, notificationId, false, errorMessage, Instant.now());
    }

    public boolean isSuccess() {
        return success;
    }
}
```

```java
// NotificationChannel.java
package com.jsoonworld.notification.domain.model;

public enum NotificationChannel {
    EMAIL,
    SLACK,
    PUSH,
    IN_APP
}
```

```java
// NotificationStatus.java
package com.jsoonworld.notification.domain.model;

public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED
}
```

```java
// EventType.java
package com.jsoonworld.notification.domain.model;

public enum EventType {
    // Payment
    PAYMENT_APPROVED,
    PAYMENT_FAILED,

    // Subscription
    SUBSCRIPTION_RENEWED,
    SUBSCRIPTION_EXPIRING,
    SUBSCRIPTION_CANCELLED,

    // Order
    ORDER_CREATED,
    ORDER_COMPLETED,
    ORDER_CANCELLED,

    // Refund
    REFUND_COMPLETED,
    REFUND_FAILED;

    /**
     * fluxpay-engine의 eventType 문자열 → enum 변환.
     * 예: "payment.approved" → PAYMENT_APPROVED
     */
    public static EventType fromEventTypeString(String eventType) {
        return switch (eventType) {
            case "payment.approved" -> PAYMENT_APPROVED;
            case "payment.failed" -> PAYMENT_FAILED;
            case "subscription.renewed" -> SUBSCRIPTION_RENEWED;
            case "subscription.expiring" -> SUBSCRIPTION_EXPIRING;
            case "subscription.cancelled" -> SUBSCRIPTION_CANCELLED;
            case "order.created" -> ORDER_CREATED;
            case "order.completed" -> ORDER_COMPLETED;
            case "order.cancelled" -> ORDER_CANCELLED;
            case "refund.completed" -> REFUND_COMPLETED;
            case "refund.failed" -> REFUND_FAILED;
            default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
        };
    }
}
```

```java
// NotificationSettings.java
package com.jsoonworld.notification.domain.model;

public record NotificationSettings(
    Long userId,
    boolean emailEnabled,
    boolean slackEnabled,
    boolean pushEnabled,
    boolean inAppEnabled
) {
    public boolean isChannelEnabled(NotificationChannel channel) {
        return switch (channel) {
            case EMAIL -> emailEnabled;
            case SLACK -> slackEnabled;
            case PUSH -> pushEnabled;
            case IN_APP -> inAppEnabled;
        };
    }

    /**
     * 모든 채널 활성화된 기본 설정.
     */
    public static NotificationSettings defaultSettings(Long userId) {
        return new NotificationSettings(userId, true, false, false, true);
    }
}
```

---

## 5. 데이터 모델

### 5.1 DDL

```sql
-- ============================================================================
-- Event-driven Notification Platform Schema
-- Database: PostgreSQL 16, shared with fluxpay-engine (fluxpay DB)
-- ============================================================================

-- 1. notification_log — 모든 알림 전송 이력
CREATE TABLE IF NOT EXISTS notification_log (
    id              BIGSERIAL PRIMARY KEY,
    notification_id VARCHAR(50)  NOT NULL UNIQUE,
    event_id        VARCHAR(50)  NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    user_id         BIGINT       NOT NULL,
    channel         VARCHAR(20)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    recipient       VARCHAR(255),
    error_message   TEXT,
    sent_at         TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    CONSTRAINT chk_notif_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT chk_notif_channel CHECK (channel IN ('EMAIL', 'SLACK', 'PUSH', 'IN_APP'))
);

CREATE INDEX idx_notif_log_user_id ON notification_log (user_id);
CREATE INDEX idx_notif_log_event_id ON notification_log (event_id);
CREATE INDEX idx_notif_log_status ON notification_log (status);
CREATE INDEX idx_notif_log_channel ON notification_log (channel);
CREATE INDEX idx_notif_log_created_at ON notification_log (created_at DESC);
CREATE INDEX idx_notif_log_user_created ON notification_log (user_id, created_at DESC);

-- 2. delivery_stats — 일별 채널별 전송 통계 (CQRS Read Model)
CREATE TABLE IF NOT EXISTS delivery_stats (
    id             BIGSERIAL PRIMARY KEY,
    date           DATE        NOT NULL,
    channel        VARCHAR(20) NOT NULL,
    sent_count     INT         DEFAULT 0,
    failed_count   INT         DEFAULT 0,
    success_rate   DECIMAL(5,2),

    CONSTRAINT uq_delivery_stats_date_channel UNIQUE (date, channel),
    CONSTRAINT chk_stats_channel CHECK (channel IN ('EMAIL', 'SLACK', 'PUSH', 'IN_APP'))
);

CREATE INDEX idx_delivery_stats_date ON delivery_stats (date DESC);

-- 3. notification_settings — 사용자별 알림 채널 설정
CREATE TABLE IF NOT EXISTS notification_settings (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT       NOT NULL UNIQUE,
    email_enabled  BOOLEAN      DEFAULT TRUE,
    slack_enabled  BOOLEAN      DEFAULT FALSE,
    push_enabled   BOOLEAN      DEFAULT FALSE,
    in_app_enabled BOOLEAN      DEFAULT TRUE,
    created_at     TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at     TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_notif_settings_user_id ON notification_settings (user_id);

-- 4. processed_events — 멱등성 보장 (at-least-once)
CREATE TABLE IF NOT EXISTS processed_events (
    id            BIGSERIAL PRIMARY KEY,
    event_id      VARCHAR(50) NOT NULL UNIQUE,
    consumer_group VARCHAR(50) NOT NULL,
    processed_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_processed_events_event_id ON processed_events (event_id);
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);

-- 5. dlq_messages — DLQ 메시지 저장 (Admin API용)
CREATE TABLE IF NOT EXISTS dlq_messages (
    id               BIGSERIAL PRIMARY KEY,
    original_topic   VARCHAR(100) NOT NULL,
    event_id         VARCHAR(50),
    payload          TEXT         NOT NULL,
    error_message    TEXT,
    retry_count      INT          DEFAULT 0,
    status           VARCHAR(20)  DEFAULT 'PENDING',
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    retried_at       TIMESTAMP WITH TIME ZONE,

    CONSTRAINT chk_dlq_status CHECK (status IN ('PENDING', 'RETRIED', 'DISCARDED'))
);

CREATE INDEX idx_dlq_messages_status ON dlq_messages (status);
CREATE INDEX idx_dlq_messages_original_topic ON dlq_messages (original_topic);
CREATE INDEX idx_dlq_messages_created_at ON dlq_messages (created_at DESC);
```

### 5.2 R2DBC Entity 클래스

```java
// NotificationLogEntity.java
package com.jsoonworld.notification.infrastructure.persistence.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("notification_log")
public class NotificationLogEntity {

    @Id
    private Long id;

    @Column("notification_id")
    private String notificationId;

    @Column("event_id")
    private String eventId;

    @Column("event_type")
    private String eventType;

    @Column("user_id")
    private Long userId;

    @Column("channel")
    private String channel;

    @Column("status")
    private String status;

    @Column("recipient")
    private String recipient;

    @Column("error_message")
    private String errorMessage;

    @Column("sent_at")
    private Instant sentAt;

    @Column("created_at")
    private Instant createdAt;

    // 기본 생성자 + getter/setter (Lombok @Data 또는 수동)
    public NotificationLogEntity() {}

    // getter, setter 생략 (Lombok @Data 사용 권장)
}
```

```java
// DeliveryStatsEntity.java
package com.jsoonworld.notification.infrastructure.persistence.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

@Table("delivery_stats")
public class DeliveryStatsEntity {

    @Id
    private Long id;

    @Column("date")
    private LocalDate date;

    @Column("channel")
    private String channel;

    @Column("sent_count")
    private Integer sentCount;

    @Column("failed_count")
    private Integer failedCount;

    @Column("success_rate")
    private BigDecimal successRate;

    public DeliveryStatsEntity() {}
}
```

```java
// NotificationSettingsEntity.java
package com.jsoonworld.notification.infrastructure.persistence.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("notification_settings")
public class NotificationSettingsEntity {

    @Id
    private Long id;

    @Column("user_id")
    private Long userId;

    @Column("email_enabled")
    private Boolean emailEnabled;

    @Column("slack_enabled")
    private Boolean slackEnabled;

    @Column("push_enabled")
    private Boolean pushEnabled;

    @Column("in_app_enabled")
    private Boolean inAppEnabled;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public NotificationSettingsEntity() {}
}
```

```java
// ProcessedEventEntity.java
package com.jsoonworld.notification.infrastructure.persistence.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("processed_events")
public class ProcessedEventEntity {

    @Id
    private Long id;

    @Column("event_id")
    private String eventId;

    @Column("consumer_group")
    private String consumerGroup;

    @Column("processed_at")
    private Instant processedAt;

    public ProcessedEventEntity() {}

    public ProcessedEventEntity(String eventId, String consumerGroup) {
        this.eventId = eventId;
        this.consumerGroup = consumerGroup;
        this.processedAt = Instant.now();
    }
}
```

```java
// DlqMessageEntity.java
package com.jsoonworld.notification.infrastructure.persistence.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("dlq_messages")
public class DlqMessageEntity {

    @Id
    private Long id;

    @Column("original_topic")
    private String originalTopic;

    @Column("event_id")
    private String eventId;

    @Column("payload")
    private String payload;

    @Column("error_message")
    private String errorMessage;

    @Column("retry_count")
    private Integer retryCount;

    @Column("status")
    private String status;

    @Column("created_at")
    private Instant createdAt;

    @Column("retried_at")
    private Instant retriedAt;

    public DlqMessageEntity() {}
}
```

---

## 6. API 상세 설계

모든 API 응답은 fluxpay-engine과 동일한 `ApiResponse` 래퍼를 사용한다.

```java
public record ApiResponse<T>(
    boolean success,
    T data,
    ErrorInfo error,
    ResponseMetadata metadata
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, ResponseMetadata.now());
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(false, null, new ErrorInfo(code, message), ResponseMetadata.now());
    }
}
```

### 6.1 알림 이력 조회

#### `GET /api/v1/notifications`

사용자별 알림 전송 이력을 조회한다.

| 항목 | 값 |
|------|------|
| Method | `GET` |
| Path | `/api/v1/notifications` |
| Query Params | `userId` (필수, Long), `page` (선택, default 0), `size` (선택, default 20), `channel` (선택), `status` (선택) |
| 인증 | 필요 (추후) |

**Response 200 OK**:
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "notificationId": "ntf_abc123",
        "eventId": "evt_xyz789",
        "eventType": "PAYMENT_APPROVED",
        "channel": "EMAIL",
        "status": "SENT",
        "recipient": "user@example.com",
        "sentAt": "2026-02-16T10:30:00Z",
        "createdAt": "2026-02-16T10:29:58Z"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 42
  },
  "error": null,
  "metadata": {
    "timestamp": "2026-02-16T12:00:00Z",
    "traceId": "abc-123"
  }
}
```

**Status Codes**: `200` 성공, `400` userId 누락, `500` 서버 에러

---

### 6.2 알림 설정

#### `GET /api/v1/notifications/settings`

사용자의 알림 채널 설정을 조회한다.

| 항목 | 값 |
|------|------|
| Method | `GET` |
| Path | `/api/v1/notifications/settings` |
| Query Params | `userId` (필수, Long) |

**Response 200 OK**:
```json
{
  "success": true,
  "data": {
    "userId": 1001,
    "emailEnabled": true,
    "slackEnabled": false,
    "pushEnabled": false,
    "inAppEnabled": true,
    "updatedAt": "2026-02-16T08:00:00Z"
  },
  "error": null,
  "metadata": { "timestamp": "2026-02-16T12:00:00Z", "traceId": "abc-123" }
}
```

#### `PUT /api/v1/notifications/settings`

사용자의 알림 채널 설정을 변경한다.

| 항목 | 값 |
|------|------|
| Method | `PUT` |
| Path | `/api/v1/notifications/settings` |
| Content-Type | `application/json` |

**Request Body**:
```json
{
  "userId": 1001,
  "emailEnabled": true,
  "slackEnabled": true,
  "pushEnabled": false,
  "inAppEnabled": true
}
```

**Response 200 OK**:
```json
{
  "success": true,
  "data": {
    "userId": 1001,
    "emailEnabled": true,
    "slackEnabled": true,
    "pushEnabled": false,
    "inAppEnabled": true,
    "updatedAt": "2026-02-16T12:05:00Z"
  },
  "error": null,
  "metadata": { "timestamp": "2026-02-16T12:05:00Z", "traceId": "def-456" }
}
```

**Status Codes**: `200` 성공, `400` 유효성 검증 실패, `500` 서버 에러

---

### 6.3 대시보드

#### `GET /api/v1/dashboard/delivery`

일별 전체 전송 통계를 조회한다.

| 항목 | 값 |
|------|------|
| Method | `GET` |
| Path | `/api/v1/dashboard/delivery` |
| Query Params | `from` (선택, LocalDate, default 7일 전), `to` (선택, LocalDate, default 오늘) |

**Response 200 OK**:
```json
{
  "success": true,
  "data": [
    {
      "date": "2026-02-16",
      "totalSent": 1520,
      "totalFailed": 23,
      "successRate": 98.49
    },
    {
      "date": "2026-02-15",
      "totalSent": 1380,
      "totalFailed": 15,
      "successRate": 98.92
    }
  ],
  "error": null,
  "metadata": { "timestamp": "2026-02-16T12:00:00Z", "traceId": "ghi-789" }
}
```

#### `GET /api/v1/dashboard/delivery/channels`

채널별 성공률 통계를 조회한다.

| 항목 | 값 |
|------|------|
| Method | `GET` |
| Path | `/api/v1/dashboard/delivery/channels` |
| Query Params | `from`, `to` (선택) |

**Response 200 OK**:
```json
{
  "success": true,
  "data": [
    { "channel": "EMAIL", "sentCount": 3200, "failedCount": 18, "successRate": 99.44 },
    { "channel": "SLACK", "sentCount": 450, "failedCount": 5, "successRate": 98.90 },
    { "channel": "IN_APP", "sentCount": 2800, "failedCount": 12, "successRate": 99.57 }
  ],
  "error": null,
  "metadata": { "timestamp": "2026-02-16T12:00:00Z", "traceId": "jkl-012" }
}
```

#### `GET /api/v1/dashboard/delivery/failures`

최근 실패 알림 목록을 조회한다.

| 항목 | 값 |
|------|------|
| Method | `GET` |
| Path | `/api/v1/dashboard/delivery/failures` |
| Query Params | `limit` (선택, default 50) |

**Response 200 OK**:
```json
{
  "success": true,
  "data": [
    {
      "notificationId": "ntf_fail001",
      "eventType": "PAYMENT_FAILED",
      "channel": "EMAIL",
      "recipient": "user@invalid.com",
      "errorMessage": "550 5.1.1 The email account does not exist",
      "createdAt": "2026-02-16T11:55:00Z"
    }
  ],
  "error": null,
  "metadata": { "timestamp": "2026-02-16T12:00:00Z", "traceId": "mno-345" }
}
```

---

### 6.4 DLQ Admin API

#### `GET /api/v1/admin/dlq`

DLQ에 적재된 메시지 목록을 조회한다.

| 항목 | 값 |
|------|------|
| Method | `GET` |
| Path | `/api/v1/admin/dlq` |
| Query Params | `status` (선택, default PENDING), `page` (선택), `size` (선택) |

**Response 200 OK**:
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 1,
        "originalTopic": "fluxpay.payment.events",
        "eventId": "evt_err001",
        "errorMessage": "SMTP connection refused",
        "retryCount": 3,
        "status": "PENDING",
        "createdAt": "2026-02-16T10:00:00Z"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 5
  },
  "error": null,
  "metadata": { "timestamp": "2026-02-16T12:00:00Z", "traceId": "pqr-678" }
}
```

#### `POST /api/v1/admin/dlq/{id}/retry`

특정 DLQ 메시지를 재처리한다.

| 항목 | 값 |
|------|------|
| Method | `POST` |
| Path | `/api/v1/admin/dlq/{id}/retry` |
| Path Variable | `id` (Long) |

**Response 200 OK**:
```json
{
  "success": true,
  "data": {
    "id": 1,
    "status": "RETRIED",
    "retriedAt": "2026-02-16T12:01:00Z"
  },
  "error": null,
  "metadata": { "timestamp": "2026-02-16T12:01:00Z", "traceId": "stu-901" }
}
```

**Status Codes**: `200` 성공, `404` 메시지 없음, `409` 이미 처리된 메시지, `500` 재처리 실패

#### `POST /api/v1/admin/dlq/retry-all`

PENDING 상태의 모든 DLQ 메시지를 재처리한다.

| 항목 | 값 |
|------|------|
| Method | `POST` |
| Path | `/api/v1/admin/dlq/retry-all` |

**Response 200 OK**:
```json
{
  "success": true,
  "data": {
    "retriedCount": 5,
    "failedCount": 1,
    "retriedAt": "2026-02-16T12:02:00Z"
  },
  "error": null,
  "metadata": { "timestamp": "2026-02-16T12:02:00Z", "traceId": "vwx-234" }
}
```

#### `DELETE /api/v1/admin/dlq/{id}`

DLQ 메시지를 폐기(discard)한다.

| 항목 | 값 |
|------|------|
| Method | `DELETE` |
| Path | `/api/v1/admin/dlq/{id}` |
| Path Variable | `id` (Long) |

**Response 200 OK**:
```json
{
  "success": true,
  "data": {
    "id": 1,
    "status": "DISCARDED"
  },
  "error": null,
  "metadata": { "timestamp": "2026-02-16T12:03:00Z", "traceId": "yza-567" }
}
```

**Status Codes**: `200` 성공, `404` 메시지 없음, `409` 이미 처리된 메시지

### 6.5 Health Check

#### `GET /api/v1/health`

서비스 상태를 확인한다.

| 항목 | 값 |
|------|------|
| Method | `GET` |
| Path | `/api/v1/health` |

**Response 200 OK**:
```json
{
  "success": true,
  "data": {
    "status": "UP",
    "components": {
      "kafka": "UP",
      "postgres": "UP",
      "redis": "UP",
      "smtp": "UP"
    }
  },
  "error": null,
  "metadata": { "timestamp": "2026-02-16T12:00:00Z", "traceId": null }
}
```

---

## 7. Kafka Consumer 설계

### 7.1 Consumer Configuration

```java
package com.jsoonworld.notification.infrastructure.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000); // 5분
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());

        // 수동 ack 모드
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);

        // Consumer 동시성 (파티션 수에 맞춰 조정)
        factory.setConcurrency(3);

        return factory;
    }
}
```

### 7.2 Consumer 설정 값 요약

| 설정 | 값 | 이유 |
|------|------|------|
| `enable.auto.commit` | `false` | 수동 ack으로 at-least-once 보장 |
| `auto.offset.reset` | `earliest` | 첫 기동 시 기존 이벤트도 소비 |
| `max.poll.records` | `10` | 배치 크기 제한으로 처리 시간 제어 |
| `session.timeout.ms` | `30000` | Consumer 장애 감지 (30초) |
| `concurrency` | `3` | 3개 파티션 기준, 1:1 매핑 |
| `ack-mode` | `MANUAL` | 처리 완료 후 명시적 commit |

### 7.3 Consumer 구현 (PaymentNotifConsumer 예시)

```java
package com.jsoonworld.notification.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsoonworld.notification.application.port.in.SendNotificationUseCase;
import com.jsoonworld.notification.application.port.out.ProcessedEventPort;
import com.jsoonworld.notification.domain.model.EventType;
import com.jsoonworld.notification.domain.model.NotificationRequest;
import com.jsoonworld.notification.infrastructure.dlq.DlqProducer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@Component
public class PaymentNotifConsumer extends AbstractNotifConsumer {

    private final ObjectMapper objectMapper;

    public PaymentNotifConsumer(
        ProcessedEventPort processedEventPort,
        SendNotificationUseCase sendNotificationUseCase,
        DlqProducer dlqProducer,
        ObjectMapper objectMapper
    ) {
        super(processedEventPort, sendNotificationUseCase, dlqProducer);
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
        topics = "fluxpay.payment.events",
        groupId = "notif-payment",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        handleRecord(record, ack);
    }

    @Override
    protected String extractEventId(ConsumerRecord<String, String> record) {
        try {
            JsonNode node = objectMapper.readTree(record.value());
            return node.get("eventId").asText();
        } catch (Exception e) {
            log.error("Failed to extract eventId from record", e);
            return "unknown-" + record.offset();
        }
    }

    @Override
    protected Mono<NotificationRequest> processEvent(ConsumerRecord<String, String> record) {
        return Mono.fromCallable(() -> {
            JsonNode node = objectMapper.readTree(record.value());
            String eventId = node.get("eventId").asText();
            String eventTypeStr = node.get("eventType").asText();
            EventType eventType = EventType.fromEventTypeString(eventTypeStr);

            return new NotificationRequest(
                "ntf_" + UUID.randomUUID().toString().substring(0, 8),
                eventId,
                eventType,
                null, // userId — payment 이벤트에서 추출 (orderId → user 조회 필요)
                null, // recipient — 사용자 설정에서 조회
                resolveSubject(eventType),
                resolveTemplateName(eventType),
                Map.of(
                    "paymentId", node.get("paymentId").asText(),
                    "orderId", node.has("orderId") ? node.get("orderId").asText() : "",
                    "amount", node.has("amount") ? node.get("amount").asText() : "0",
                    "currency", node.has("currency") ? node.get("currency").asText() : "KRW"
                )
            );
        });
    }

    @Override
    protected EventType resolveEventType(String eventType) {
        return EventType.fromEventTypeString(eventType);
    }

    private String resolveSubject(EventType eventType) {
        return switch (eventType) {
            case PAYMENT_APPROVED -> "[moalog] 결제가 완료되었습니다";
            case PAYMENT_FAILED -> "[moalog] 결제 처리에 실패했습니다";
            default -> "[moalog] 결제 알림";
        };
    }

    private String resolveTemplateName(EventType eventType) {
        return switch (eventType) {
            case PAYMENT_APPROVED -> "email/payment-completed";
            case PAYMENT_FAILED -> "email/payment-failed";
            default -> "email/payment-completed";
        };
    }
}
```

### 7.4 수동 Commit 전략

```
Consumer.poll()
  │
  ├─ record 수신
  │
  ├─ processedEventPort.existsByEventId(eventId)
  │   ├─ true  → skip (이미 처리됨)
  │   └─ false → processEvent()
  │               ├─ 성공 → processedEventPort.save(eventId)
  │               │         → ack.acknowledge()  // offset commit
  │               └─ 실패 → dlqProducer.send(record, error)
  │                         → ack.acknowledge()  // DLQ 전달 후 commit
  │
  └─ 다음 record
```

핵심 원칙:
- **성공 시**: processed_events 저장 후 ack
- **실패 시**: DLQ 전달 후 ack (재소비 방지)
- **멱등성 중복 시**: skip 후 ack

### 7.5 역직렬화

fluxpay-engine은 `KafkaEventPublisher`에서 `KafkaTemplate<String, String>`으로 CloudEvent JSON을 String으로 발행한다. Notification Platform은 `StringDeserializer`로 수신 후 `ObjectMapper`로 파싱한다.

**이유**: `JsonDeserializer` + trusted packages 방식 대신 `StringDeserializer` + 수동 파싱을 선택. fluxpay-engine의 CloudEvent 포맷은 도메인 클래스를 직접 참조하므로, 별도 서비스에서 해당 클래스를 의존하지 않기 위함.

---

## 8. DLQ 파이프라인

### 8.1 DLQ 토픽 네이밍

| 원본 토픽 | DLQ 토픽 |
|-----------|----------|
| `fluxpay.payment.events` | `notification.dlq.payment` |
| `fluxpay.subscription.events` | `notification.dlq.subscription` |
| `fluxpay.order.events` | `notification.dlq.order` |

### 8.2 DLQ 메시지 포맷

DLQ 토픽으로 전달되는 메시지는 원본 payload + Kafka Headers로 구성된다.

| Header | 타입 | 설명 |
|--------|------|------|
| `X-Error-Message` | String | 실패 원인 메시지 |
| `X-Retry-Count` | Integer | 재시도 횟수 (최대 3) |
| `X-Original-Topic` | String | 원본 Kafka 토픽 이름 |
| `X-Original-Partition` | Integer | 원본 파티션 번호 |
| `X-Original-Offset` | Long | 원본 오프셋 |
| `X-Failed-At` | String | 실패 시각 (ISO-8601) |

### 8.3 DLQ Producer

```java
package com.jsoonworld.notification.infrastructure.dlq;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component
public class DlqProducer {

    private static final Logger log = LoggerFactory.getLogger(DlqProducer.class);
    private static final String DLQ_TOPIC_PREFIX = "notification.dlq.";

    private final KafkaTemplate<String, String> kafkaTemplate;

    public DlqProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 처리 실패한 레코드를 DLQ 토픽으로 전달한다.
     *
     * @param originalRecord 원본 Consumer Record
     * @param errorMessage   실패 원인 메시지
     * @return Mono<Void>
     */
    public Mono<Void> send(ConsumerRecord<String, String> originalRecord, String errorMessage) {
        String dlqTopic = deriveDlqTopic(originalRecord.topic());

        ProducerRecord<String, String> dlqRecord = new ProducerRecord<>(
            dlqTopic, originalRecord.key(), originalRecord.value()
        );

        // 메타데이터 헤더 추가
        dlqRecord.headers().add(new RecordHeader(
            "X-Error-Message", errorMessage.getBytes(StandardCharsets.UTF_8)));
        dlqRecord.headers().add(new RecordHeader(
            "X-Retry-Count", "3".getBytes(StandardCharsets.UTF_8)));
        dlqRecord.headers().add(new RecordHeader(
            "X-Original-Topic", originalRecord.topic().getBytes(StandardCharsets.UTF_8)));
        dlqRecord.headers().add(new RecordHeader(
            "X-Original-Partition", String.valueOf(originalRecord.partition()).getBytes(StandardCharsets.UTF_8)));
        dlqRecord.headers().add(new RecordHeader(
            "X-Original-Offset", String.valueOf(originalRecord.offset()).getBytes(StandardCharsets.UTF_8)));
        dlqRecord.headers().add(new RecordHeader(
            "X-Failed-At", Instant.now().toString().getBytes(StandardCharsets.UTF_8)));

        return Mono.fromFuture(() ->
            kafkaTemplate.send(dlqRecord).toCompletableFuture()
        )
        .doOnSuccess(result -> log.info("Message sent to DLQ: topic={}, key={}", dlqTopic, originalRecord.key()))
        .doOnError(error -> log.error("Failed to send to DLQ: topic={}", dlqTopic, error))
        .then();
    }

    /**
     * 원본 토픽에서 DLQ 토픽 이름을 유도한다.
     * 예: fluxpay.payment.events → notification.dlq.payment
     */
    private String deriveDlqTopic(String originalTopic) {
        // fluxpay.{domain}.events → notification.dlq.{domain}
        String[] parts = originalTopic.split("\\.");
        String domain = parts.length >= 2 ? parts[1] : "unknown";
        return DLQ_TOPIC_PREFIX + domain;
    }
}
```

### 8.4 DLQ Consumer

DLQ 토픽을 소비하여 `dlq_messages` 테이블에 저장한다. Admin API에서 조회 및 재처리에 사용.

```java
package com.jsoonworld.notification.infrastructure.dlq;

import com.jsoonworld.notification.application.port.out.DlqMessagePort;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class DlqConsumer {

    private static final Logger log = LoggerFactory.getLogger(DlqConsumer.class);

    private final DlqMessagePort dlqMessagePort;

    public DlqConsumer(DlqMessagePort dlqMessagePort) {
        this.dlqMessagePort = dlqMessagePort;
    }

    @KafkaListener(
        topicPattern = "notification\\.dlq\\..*",
        groupId = "notif-dlq",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String originalTopic = extractHeader(record, "X-Original-Topic");
        String errorMessage = extractHeader(record, "X-Error-Message");
        String retryCount = extractHeader(record, "X-Retry-Count");
        String eventId = extractEventIdFromPayload(record.value());

        dlqMessagePort.save(
            originalTopic,
            eventId,
            record.value(),
            errorMessage,
            Integer.parseInt(retryCount != null ? retryCount : "0")
        )
        .doOnSuccess(v -> {
            log.info("DLQ message saved: originalTopic={}, eventId={}", originalTopic, eventId);
            ack.acknowledge();
        })
        .doOnError(e -> {
            log.error("Failed to save DLQ message", e);
            ack.acknowledge(); // 저장 실패해도 ack (무한 루프 방지)
        })
        .subscribe();
    }

    private String extractHeader(ConsumerRecord<String, String> record, String headerName) {
        Header header = record.headers().lastHeader(headerName);
        if (header == null) return null;
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private String extractEventIdFromPayload(String payload) {
        // 간단한 JSON 파싱으로 eventId 추출
        try {
            int idx = payload.indexOf("\"eventId\"");
            if (idx < 0) return "unknown";
            int start = payload.indexOf("\"", idx + 10) + 1;
            int end = payload.indexOf("\"", start);
            return payload.substring(start, end);
        } catch (Exception e) {
            return "unknown";
        }
    }
}
```

### 8.5 Retry Backoff 전략

Consumer 내부 재시도는 Spring Retry를 사용한다.

```
시도 1: 즉시 실행
시도 2: 1초 대기 후 재시도
시도 3: 2초 대기 후 재시도
시도 4: 4초 대기 후 재시도 (실패 시 DLQ)
```

| 재시도 | 대기 시간 | 누적 시간 |
|--------|----------|----------|
| 1차 | 0s | 0s |
| 2차 | 1s | 1s |
| 3차 | 2s | 3s |
| 4차 (DLQ) | 4s | 7s |

**공식**: `delay = initialInterval * 2^(retryCount - 1)` (exponential backoff, multiplier=2)

```java
// KafkaConsumerConfig에 RetryTemplate 추가
@Bean
public RetryTemplate retryTemplate() {
    RetryTemplate retryTemplate = new RetryTemplate();

    ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
    backOff.setInitialInterval(1000L);  // 1초
    backOff.setMultiplier(2.0);          // 2배
    backOff.setMaxInterval(4000L);       // 최대 4초
    retryTemplate.setBackOffPolicy(backOff);

    SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
    retryPolicy.setMaxAttempts(3);       // 최대 3회 재시도
    retryTemplate.setRetryPolicy(retryPolicy);

    return retryTemplate;
}
```

### 8.6 DLQ 재처리 (Admin API)

```java
package com.jsoonworld.notification.infrastructure.dlq;

import com.jsoonworld.notification.application.port.out.DlqMessagePort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Service
public class DlqRetryService {

    private final DlqMessagePort dlqMessagePort;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public DlqRetryService(DlqMessagePort dlqMessagePort, KafkaTemplate<String, String> kafkaTemplate) {
        this.dlqMessagePort = dlqMessagePort;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * DLQ 메시지를 원본 토픽으로 재발행한다.
     */
    public Mono<Void> retry(Long dlqMessageId) {
        return dlqMessagePort.findById(dlqMessageId)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("DLQ message not found: " + dlqMessageId)))
            .flatMap(msg -> {
                if (!"PENDING".equals(msg.status())) {
                    return Mono.error(new IllegalStateException("Message already processed: " + msg.status()));
                }
                return Mono.fromFuture(() ->
                    kafkaTemplate.send(msg.originalTopic(), msg.payload()).toCompletableFuture()
                ).then(dlqMessagePort.updateStatus(dlqMessageId, "RETRIED", Instant.now()));
            });
    }

    /**
     * DLQ 메시지를 폐기한다.
     */
    public Mono<Void> discard(Long dlqMessageId) {
        return dlqMessagePort.findById(dlqMessageId)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("DLQ message not found: " + dlqMessageId)))
            .flatMap(msg -> {
                if (!"PENDING".equals(msg.status())) {
                    return Mono.error(new IllegalStateException("Message already processed: " + msg.status()));
                }
                return dlqMessagePort.updateStatus(dlqMessageId, "DISCARDED", Instant.now());
            });
    }
}
```

---

## 9. 에러 처리

### 9.1 에러 코드 테이블

에러 코드 패턴: `{DOMAIN}_{NUMBER}`

| 코드 | HTTP Status | 설명 |
|------|-------------|------|
| `NTF_001` | 400 | userId 파라미터 누락 |
| `NTF_002` | 404 | 알림 이력 없음 |
| `NTF_003` | 400 | 유효하지 않은 채널 값 |
| `NTF_004` | 400 | 유효하지 않은 알림 설정 요청 |
| `DLQ_001` | 404 | DLQ 메시지 없음 |
| `DLQ_002` | 409 | 이미 처리된 DLQ 메시지 (RETRIED/DISCARDED) |
| `DLQ_003` | 500 | DLQ 재처리 실패 |
| `KFK_001` | 500 | Kafka Consumer 에러 |
| `KFK_002` | 500 | Kafka Producer (DLQ 발행) 에러 |
| `SND_001` | 500 | 이메일 전송 실패 (SMTP) |
| `SND_002` | 500 | Slack Webhook 전송 실패 |
| `SND_003` | 500 | In-App (Redis Pub/Sub) 전송 실패 |
| `SND_004` | 500 | Push 전송 실패 |
| `SYS_001` | 500 | Internal Server Error |
| `SYS_002` | 503 | Service Unavailable (DB/Redis/Kafka 연결 불가) |

### 9.2 Global Error Handler

```java
package com.jsoonworld.notification.interfaces.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleValidationError(WebExchangeBindException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        return Mono.just(ResponseEntity
            .badRequest()
            .body(ApiResponse.error("NTF_004", "Validation failed: " + ex.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return Mono.just(ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.error("DLQ_001", ex.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleIllegalState(IllegalStateException ex) {
        log.warn("Conflict: {}", ex.getMessage());
        return Mono.just(ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ApiResponse.error("DLQ_002", ex.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ApiResponse<Void>>> handleGeneral(Exception ex) {
        log.error("Unexpected error", ex);
        return Mono.just(ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error("SYS_001", "Internal server error")));
    }
}
```

### 9.3 채널별 에러 처리

| 채널 | 에러 유형 | 처리 방식 |
|------|----------|----------|
| **Email** | SMTP 연결 실패 (`MailSendException`) | DLQ 전달, `SND_001` 로그 |
| **Email** | 잘못된 수신 주소 (`AddressException`) | DLQ 전달, recipient 정보와 함께 기록 |
| **Slack** | Webhook URL 응답 4xx/5xx | DLQ 전달, HTTP status 기록 |
| **Slack** | 네트워크 타임아웃 (`WebClientRequestException`) | 재시도 후 DLQ |
| **In-App** | Redis 연결 불가 (`RedisConnectionException`) | DLQ 전달, Redis 상태 확인 알림 |
| **Push** | FCM 토큰 만료 (Phase 2) | 토큰 갱신 요청 후 재시도 |

---

## 10. 설정 관리

### 10.1 application.yml

```yaml
spring:
  application:
    name: notification-platform

  # R2DBC PostgreSQL (fluxpay DB 공유)
  r2dbc:
    url: r2dbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:fluxpay}
    username: ${DB_USERNAME:fluxpay}
    password: ${DB_PASSWORD:fluxpay}
    pool:
      initial-size: 5
      max-size: 20

  # Redis
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}

  # Kafka
  kafka:
    bootstrap-servers: ${KAFKA_SERVERS:localhost:9092}
    consumer:
      auto-offset-reset: earliest
      enable-auto-commit: false
      max-poll-records: 10
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer

  # Mail (SMTP)
  mail:
    host: ${SPRING_MAIL_HOST:localhost}
    port: ${SPRING_MAIL_PORT:1025}
    username: ${SPRING_MAIL_USERNAME:}
    password: ${SPRING_MAIL_PASSWORD:}
    properties:
      mail:
        smtp:
          auth: false
          starttls:
            enable: false
          connectiontimeout: 5000
          timeout: 5000
          writetimeout: 5000

  # Thymeleaf
  thymeleaf:
    prefix: classpath:/templates/
    suffix: .html
    mode: HTML
    cache: true

  # Jackson
  jackson:
    default-property-inclusion: non_null
    serialization:
      write-dates-as-timestamps: false
    deserialization:
      fail-on-unknown-properties: false

server:
  port: 8080

# Actuator
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: when-authorized
  health:
    r2dbc:
      enabled: true
    redis:
      enabled: true
    kafka:
      enabled: true

# Logging
logging:
  level:
    root: INFO
    com.jsoonworld.notification: DEBUG
    org.springframework.kafka: INFO
    org.springframework.r2dbc: DEBUG
    org.springframework.mail: DEBUG

# Custom Notification Config
notification:
  slack:
    webhook-url: ${SLACK_WEBHOOK_URL:https://hooks.slack.com/services/placeholder}
  retry:
    max-attempts: 3
    initial-interval-ms: 1000
    multiplier: 2.0
    max-interval-ms: 4000
  dlq:
    enabled: true
  template:
    email:
      from: ${NOTIFICATION_EMAIL_FROM:noreply@moalog.com}
```

### 10.2 Docker 환경변수

| 환경변수 | 기본값 | 설명 |
|----------|--------|------|
| `KAFKA_SERVERS` | `localhost:9092` | Kafka bootstrap servers |
| `DB_HOST` | `localhost` | PostgreSQL 호스트 |
| `DB_PORT` | `5432` | PostgreSQL 포트 |
| `DB_NAME` | `fluxpay` | 데이터베이스 이름 |
| `DB_USERNAME` | `fluxpay` | DB 사용자 |
| `DB_PASSWORD` | `fluxpay` | DB 비밀번호 |
| `REDIS_HOST` | `localhost` | Redis 호스트 |
| `REDIS_PORT` | `6379` | Redis 포트 |
| `REDIS_PASSWORD` | (빈값) | Redis 비밀번호 |
| `SPRING_MAIL_HOST` | `localhost` | SMTP 호스트 (MailHog) |
| `SPRING_MAIL_PORT` | `1025` | SMTP 포트 |
| `SLACK_WEBHOOK_URL` | placeholder | Slack Incoming Webhook URL |
| `NOTIFICATION_EMAIL_FROM` | `noreply@moalog.com` | 발신 이메일 주소 |

---

## 11. 모니터링

### 11.1 Prometheus 메트릭

| 메트릭 이름 | 타입 | Labels | 설명 |
|-------------|------|--------|------|
| `notification_sent_total` | Counter | `channel`, `status`, `event_type` | 채널/상태/이벤트별 전송 건수 |
| `notification_delivery_latency_seconds` | Histogram | `channel` | 채널별 전송 소요 시간 (초) |
| `notification_kafka_consumer_lag` | Gauge | `topic`, `group_id` | Consumer Group별 Kafka lag |
| `notification_dlq_message_count` | Gauge | `topic`, `status` | DLQ 메시지 수 (상태별) |
| `notification_processed_events_total` | Counter | `consumer_group` | Consumer Group별 처리 완료 이벤트 수 |
| `notification_duplicate_events_total` | Counter | `consumer_group` | 멱등성 체크로 skip된 이벤트 수 |

### 11.2 Custom Metrics 등록

```java
package com.jsoonworld.notification.infrastructure.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    @Bean
    public Counter emailSentCounter(MeterRegistry registry) {
        return Counter.builder("notification_sent_total")
            .tag("channel", "EMAIL")
            .tag("status", "SENT")
            .description("Total email notifications sent")
            .register(registry);
    }

    @Bean
    public Timer emailDeliveryTimer(MeterRegistry registry) {
        return Timer.builder("notification_delivery_latency_seconds")
            .tag("channel", "EMAIL")
            .description("Email delivery latency")
            .register(registry);
    }

    // 기타 채널별 Counter/Timer도 동일 패턴으로 등록
}
```

### 11.3 Health Check

Spring Actuator의 `/actuator/health` 엔드포인트를 사용하되, 추가로 `/api/v1/health`에서 서비스 전용 상태를 노출한다.

Health Indicator 구성:
- **R2DBC**: PostgreSQL 연결 상태
- **Redis**: Redis 연결 상태
- **Kafka**: Kafka 브로커 연결 상태
- **SMTP**: MailHog/SMTP 서버 연결 상태 (custom)

### 11.4 Grafana Dashboard 패널

**Notification Overview Dashboard**에 포함할 패널:

| 패널 | 쿼리 | 시각화 |
|------|------|--------|
| 전송 성공률 (전체) | `rate(notification_sent_total{status="SENT"}[5m]) / rate(notification_sent_total[5m])` | Gauge (%) |
| 채널별 전송 건수 | `sum by (channel) (rate(notification_sent_total[5m]))` | Bar chart |
| 전송 지연 시간 (p95) | `histogram_quantile(0.95, rate(notification_delivery_latency_seconds_bucket[5m]))` | Time series |
| Kafka Consumer Lag | `notification_kafka_consumer_lag` | Time series |
| DLQ 적체량 | `notification_dlq_message_count{status="PENDING"}` | Stat (single value) |
| 최근 실패 목록 | notification_log 테이블 직접 쿼리 (Grafana PostgreSQL datasource) | Table |
| 중복 이벤트 비율 | `rate(notification_duplicate_events_total[5m])` | Time series |

---

## 12. Docker

### 12.1 Dockerfile

```dockerfile
# Notification Platform Dockerfile
# jammy (NOT alpine) — Alpine's musl libc crashes Gradle native-platform lib on ARM/Docker

FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app

# Gradle wrapper & build files first for caching
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY build.gradle settings.gradle ./

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon || true

# Copy source
COPY src/ src/

# Build (skip tests — run separately)
RUN ./gradlew build -x test --no-daemon

# Runtime image
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Copy built jar
COPY --from=builder /app/build/libs/*.jar app.jar

# Security: non-root user
RUN groupadd --gid 1001 appuser && \
    useradd --uid 1001 --gid appuser --create-home appuser && \
    chown -R appuser:appuser /app

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 12.2 docker-compose 서비스 정의

moalog-server의 `docker-compose.yaml`에 추가할 서비스:

```yaml
  # ─── Notification Platform ──────────────────────────────
  notification-platform:
    build:
      context: ../event-notification-platform
      dockerfile: Dockerfile
    container_name: moalog-notification
    depends_on:
      fluxpay-kafka:
        condition: service_healthy
      fluxpay-postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
    environment:
      KAFKA_SERVERS: fluxpay-kafka:9092
      SPRING_R2DBC_URL: r2dbc:postgresql://fluxpay-postgres:5432/fluxpay
      DB_HOST: fluxpay-postgres
      DB_PORT: 5432
      DB_NAME: fluxpay
      DB_USERNAME: ${FLUXPAY_DB_USERNAME:-fluxpay}
      DB_PASSWORD: ${FLUXPAY_DB_PASSWORD:-fluxpay}
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: 6379
      REDIS_HOST: redis
      REDIS_PORT: 6379
      REDIS_PASSWORD: ${REDIS_PASSWORD:-moalog_redis_local}
      SPRING_MAIL_HOST: mailhog
      SPRING_MAIL_PORT: 1025
      SLACK_WEBHOOK_URL: ${SLACK_WEBHOOK_URL:-https://hooks.slack.com/services/placeholder}
    ports:
      - "${NOTIFICATION_PORT:-8084}:8080"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      start_period: 60s
      retries: 3
    restart: unless-stopped

  # ─── MailHog (개발용 SMTP 서버) ──────────────────────────
  mailhog:
    image: mailhog/mailhog:latest
    container_name: moalog-mailhog
    ports:
      - "${MAILHOG_HTTP_PORT:-8025}:8025"   # Web UI (이메일 확인)
      - "${MAILHOG_SMTP_PORT:-1025}:1025"   # SMTP
    restart: unless-stopped
```

### 12.3 포트 매핑

| 서비스 | 내부 포트 | 외부 포트 (기본값) | 설명 |
|--------|----------|-------------------|------|
| notification-platform | 8080 | 8084 | REST API + Actuator |
| mailhog (HTTP) | 8025 | 8025 | 이메일 확인 Web UI |
| mailhog (SMTP) | 1025 | 1025 | SMTP 수신 |

---

## 13. 테스트 전략

### 13.1 Unit Tests

**대상**: Domain 모델, Domain Service, Strategy 구현체

```java
// NotificationRouterTest.java
@Test
void shouldRoutePaymentApprovedToEmailAndInApp() {
    // Given
    NotificationRouter router = new NotificationRouter();
    NotificationSettings settings = NotificationSettings.defaultSettings(1L);

    // When
    List<NotificationChannel> channels = router.resolveChannels(
        EventType.PAYMENT_APPROVED, settings
    );

    // Then
    assertThat(channels).containsExactlyInAnyOrder(
        NotificationChannel.EMAIL, NotificationChannel.IN_APP
    );
}

@Test
void shouldFilterDisabledChannels() {
    // Given
    NotificationRouter router = new NotificationRouter();
    NotificationSettings settings = new NotificationSettings(1L, false, true, false, true);

    // When
    List<NotificationChannel> channels = router.resolveChannels(
        EventType.PAYMENT_FAILED, settings
    );

    // Then — PAYMENT_FAILED 기본: EMAIL + IN_APP + SLACK, 사용자가 EMAIL off
    assertThat(channels).containsExactlyInAnyOrder(
        NotificationChannel.SLACK, NotificationChannel.IN_APP
    );
}
```

```java
// EmailSenderTest.java (Mocking)
@ExtendWith(MockitoExtension.class)
class EmailSenderTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private SpringTemplateEngine templateEngine;

    @InjectMocks
    private EmailSender emailSender;

    @Test
    void shouldReturnSuccessOnSend() {
        // Given
        when(templateEngine.process(anyString(), any())).thenReturn("<html>Test</html>");
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        NotificationRequest request = new NotificationRequest(
            "ntf_001", "evt_001", EventType.PAYMENT_APPROVED,
            1L, "user@test.com", "Test Subject", "email/payment-completed",
            Map.of("paymentId", "pay_001")
        );

        // When
        DeliveryResult result = emailSender.send(request).block();

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.channel()).isEqualTo(NotificationChannel.EMAIL);
    }

    @Test
    void shouldReturnFailureOnSmtpError() {
        // Given
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        doThrow(new MailSendException("SMTP connection refused"))
            .when(mailSender).send(any(MimeMessage.class));

        NotificationRequest request = new NotificationRequest(
            "ntf_002", "evt_002", EventType.PAYMENT_FAILED,
            1L, "user@test.com", "Test", "email/payment-failed", Map.of()
        );

        // When
        DeliveryResult result = emailSender.send(request).block();

        // Then
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).contains("SMTP connection refused");
    }
}
```

### 13.2 Integration Tests (Testcontainers)

```java
// KafkaConsumerIntegrationTest.java
@SpringBootTest
@Testcontainers
class KafkaConsumerIntegrationTest {

    @Container
    static KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
    );

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        DockerImageName.parse("postgres:16-alpine")
    )
    .withDatabaseName("fluxpay")
    .withUsername("fluxpay")
    .withPassword("fluxpay")
    .withInitScript("db/migration/V1__init_schema.sql");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(
        DockerImageName.parse("redis:7-alpine")
    ).withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.r2dbc.url", () ->
            "r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/fluxpay");
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getFirstMappedPort().toString());
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private NotificationLogR2dbcRepository notificationLogRepository;

    @Test
    void shouldConsumePaymentEventAndSaveLog() throws Exception {
        // Given — Kafka에 결제 이벤트 발행
        String eventPayload = """
            {
                "eventId": "evt_test_001",
                "paymentId": "pay_test_001",
                "orderId": "ord_test_001",
                "amount": "10000",
                "currency": "KRW",
                "eventType": "payment.approved",
                "occurredAt": "2026-02-16T10:00:00Z"
            }
            """;

        kafkaTemplate.send("fluxpay.payment.events", "key_001", eventPayload).get();

        // When — Consumer가 처리할 때까지 대기
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            // Then — notification_log에 기록 확인
            var logs = notificationLogRepository.findByEventId("evt_test_001")
                .collectList().block();
            assertThat(logs).isNotEmpty();
            assertThat(logs.get(0).getStatus()).isEqualTo("SENT");
        });
    }
}
```

### 13.3 E2E Tests

**시나리오**: Kafka 이벤트 발행 → Notification Platform 처리 → MailHog에서 이메일 확인

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class NotificationE2eTest {

    // Testcontainers: Kafka, PostgreSQL, Redis, MailHog
    @Container
    static GenericContainer<?> mailhog = new GenericContainer<>(
        DockerImageName.parse("mailhog/mailhog:latest")
    ).withExposedPorts(8025, 1025);

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void shouldDeliverEmailOnPaymentApproved() throws Exception {
        // 1. Kafka에 결제 완료 이벤트 발행
        kafkaTemplate.send("fluxpay.payment.events", "key", paymentEventJson()).get();

        // 2. MailHog API로 이메일 도착 확인
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            String mailhogUrl = "http://" + mailhog.getHost() + ":" +
                mailhog.getMappedPort(8025) + "/api/v2/messages";

            var response = WebClient.create(mailhogUrl)
                .get().retrieve().bodyToMono(String.class).block();

            assertThat(response).contains("결제가 완료되었습니다");
        });

        // 3. Dashboard API로 전송 통계 확인
        webTestClient.get()
            .uri("/api/v1/dashboard/delivery")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.data[0].totalSent").isEqualTo(1);
    }
}
```

### 13.4 테스트 커버리지 목표

| 레이어 | 목표 커버리지 | 테스트 유형 |
|--------|-------------|------------|
| domain/model | 95%+ | Unit |
| domain/service | 90%+ | Unit |
| infrastructure/sender | 85%+ | Unit (mock) + Integration |
| infrastructure/kafka | 80%+ | Integration (Testcontainers) |
| infrastructure/dlq | 80%+ | Integration |
| interfaces/rest | 85%+ | WebFluxTest + Integration |
| 전체 | 80%+ | JaCoCo 기준 |

---

## 14. 구현 페이즈

### Phase 1: Foundation (핵심 인프라 + 이메일 채널)

**목표**: 프로젝트 스캐폴딩, Kafka Consumer 1개, Email 전송, CQRS 기본

**산출물**:
- Gradle 프로젝트 초기 설정 (build.gradle, application.yml)
- Hexagonal architecture 패키지 구조
- DB 스키마 (notification_log, delivery_stats, processed_events, notification_settings)
- `PaymentNotifConsumer` — `fluxpay.payment.events` 소비
- `EmailSender` — Spring Mail + Thymeleaf
- `NotificationRouter` — 이벤트 → 채널 매핑
- `DeliveryTracker` — notification_log 저장, delivery_stats 갱신
- 멱등성 체크 (processed_events)
- Unit + Integration 테스트 (Testcontainers)

### Phase 2: 다채널 + DLQ

**목표**: Slack/In-App 채널 추가, DLQ 파이프라인 완성

**산출물**:
- `SlackSender` — WebClient + Incoming Webhook
- `InAppSender` — Redis Pub/Sub
- `SubscriptionNotifConsumer`, `OrderNotifConsumer` 추가
- DLQ Producer + DLQ Consumer + dlq_messages 테이블
- DLQ Admin API (GET/POST/DELETE)
- Retry backoff 구현 (3회, 1s/2s/4s)

### Phase 3: API + Dashboard

**목표**: REST API 완성, CQRS Read Model 조회

**산출물**:
- `NotificationController` — 알림 이력 조회
- `NotificationSettingsController` — 설정 관리
- `DashboardController` — 전송 통계
- `DlqAdminController` — DLQ 관리
- Redis 기반 알림 설정 캐시
- 페이징 처리

### Phase 4: 모니터링 + Docker

**목표**: Prometheus 메트릭, Grafana 대시보드, Docker Compose 통합

**산출물**:
- Custom Prometheus 메트릭 등록
- Grafana Notification Overview 대시보드 JSON
- Dockerfile (multi-stage)
- docker-compose 서비스 추가 (notification-platform + mailhog)
- MailHog 연동 E2E 테스트
- Health check 엔드포인트

### Phase 5: 안정화 + 문서화

**목표**: 엣지 케이스 처리, 부하 테스트, 운영 문서

**산출물**:
- 에러 처리 강화 (채널별 세분화)
- k6 부하 테스트 시나리오 (Kafka 이벤트 대량 발행 → 전송 지연 측정)
- Consumer lag 모니터링 알림 (Alertmanager rule)
- 운영 Runbook 작성
- API 문서 (OpenAPI/Swagger)

---

## 15. Open Questions / Future Work

### Open Questions

| # | 질문 | 현재 결정 | 재검토 시점 |
|---|------|----------|------------|
| Q1 | userId를 Payment 이벤트에서 어떻게 획득하는가? | orderId → fluxpay DB에서 user_id 조회 | Phase 1 구현 시 |
| Q2 | 알림 설정을 moalog-server와 어떻게 동기화하는가? | 별도 테이블로 독립 관리 | API Gateway 도입 시 |
| Q3 | In-App 알림을 WebSocket으로 실시간 전달해야 하는가? | Redis Pub/Sub 발행까지만 | 프론트엔드 준비 시 |
| Q4 | DLQ 메시지 보관 기간은? | 무기한 (수동 폐기) | 운영 데이터 축적 후 |
| Q5 | processed_events 테이블 정리 주기는? | 30일 보관, 크론 정리 | Phase 5 |

### Future Work

| 항목 | 설명 | 우선순위 |
|------|------|---------|
| **Push Notification (FCM/APNs)** | `PushSender` 구현, FCM 토큰 관리 테이블 추가 | 높음 |
| **템플릿 관리 UI** | 관리자가 웹에서 이메일/슬랙 템플릿을 편집 | 중간 |
| **알림 설정 동기화** | moalog-server 사용자 프로필에서 알림 설정 동기화 (Event 기반) | 중간 |
| **배치 알림** | 일별 요약 이메일 (Daily Digest) — 스케줄러 기반 | 낮음 |
| **A/B 테스트** | 이메일 제목/본문 변형 테스트 | 낮음 |
| **Rate Limiting** | 사용자당 알림 전송 빈도 제한 (스팸 방지) | 중간 |
| **Kafka Streams** | 실시간 전송 통계 집계를 Kafka Streams로 전환 (DB 부하 감소) | 낮음 |
| **Multi-tenant** | fluxpay-engine tenant_id 기반 알림 라우팅 분리 | 낮음 |
| **OpenTelemetry** | 분산 추적 (Jaeger 연동) — 기존 OTel Collector 활용 | 높음 |
