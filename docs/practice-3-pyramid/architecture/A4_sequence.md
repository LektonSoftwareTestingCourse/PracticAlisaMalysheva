# А4. Sequence - путь транзакции (happy path, declined, reversal)

**Не было указано:** mermaid-sequence в `docs/architecture.md` покрывает только happy path; declined
и reversal отсутствуют, хотя в коде есть и все ветки отказов в `DeclineOutcome`, и`publisherConfirmType=CORRELATED` 
с откатом резервирования.

**Что добавлено:**

- Три сценария: happy path, declined (карта BLOCKED) и reversal (mti 0400)
- Помечено, где проходит синхронная цепочка (HTTP), а где после ответа клиенту начинается асинхронная цепочка (AMQP)

**Область тестирования:** последовательность задаёт сквозные и интеграционные сценарии. Happy path и declined
проверяются на полном контуре (E2E); reversal (0400) - интеграционным тестом на недоставку сообщения в очередь.
Eventual consistency проверяется отложенно: запись в Logger появляется не в момент ответа, а позже.

### Сценарий 1 - Happy path (одобрено)

```mermaid
sequenceDiagram
    actor T as Terminal Simulator
    participant GW as Gateway
    participant SW as Switch
    participant AUTH as Authorization
    participant CMS as Card Management
    participant BIN as Bin Lookup
    participant MERCH as Merchant/Acquirer
    participant RMQ as RabbitMQ
    participant LOG as Transaction Logger
    participant NS as Notification

    T->>GW: POST /api/transactions (0100)
    GW->>GW: validate (фильтр)
    GW->>SW: /api/internal/route
    SW->>SW: extract BIN, routing
    SW->>AUTH: POST /api/internal/authorize
    AUTH->>CMS: GET /api/cards/{pan}
    CMS-->>AUTH: Card (ACTIVE)
    AUTH->>BIN: GET /api/bin/{bin}
    BIN-->>AUTH: issuerId
    AUTH->>AUTH: статус/срок/лимиты/баланс
    AUTH->>CMS: POST /api/cards/{pan}/reserve
    CMS-->>AUTH: OK
    AUTH-->>SW: APPROVED (rrn, authCode)
    SW->>MERCH: комиссия эквайринга
    Note over SW: асинхронная публикация после ответа клиенту
    SW->>RMQ: publish smp.transactions
    SW-->>GW: TransactionResponse
    GW-->>T: HTTP 200 {APPROVED}
    RMQ-->>LOG: consume transaction-log
    LOG->>LOG: store в transactions
    CMS-->>RMQ: outbox smp.card-events
    RMQ-->>NS: consume card-notifications
    NS->>NS: store в card_notifications
```

### Сценарий 2 - Declined (карта BLOCKED)

```mermaid
sequenceDiagram
    actor T as Terminal Simulator
    participant GW as Gateway
    participant SW as Switch
    participant AUTH as Authorization
    participant CMS as Card Management
    participant RMQ as RabbitMQ
    participant LOG as Transaction Logger

    T->>GW: POST /api/transactions (0100)
    GW->>SW: /api/internal/route
    SW->>AUTH: POST /api/internal/authorize
    AUTH->>CMS: GET /api/cards/{pan}
    CMS-->>AUTH: Card (BLOCKED)
    Note over AUTH: DeclineOutcome.CARD_BLOCKED -> DECLINED 05<br/>(резервирование не выполняется)
    AUTH-->>SW: DECLINED (CARD_BLOCKED)
    SW->>RMQ: publish smp.transactions
    SW-->>GW: TransactionResponse
    GW-->>T: HTTP 403 {DECLINED, CARD_BLOCKED}
    RMQ-->>LOG: consume -> store (DECLINED)
```

### Сценарий 3 - Reversal (mti 0400): publisher-confirm не получен за 2s

```mermaid
sequenceDiagram
    actor T as Terminal Simulator
    participant GW as Gateway
    participant SW as Switch
    participant AUTH as Authorization
    participant CMS as Card Management
    participant RMQ as RabbitMQ

    T->>GW: POST /api/transactions
    GW->>SW: /api/internal/route
    SW->>AUTH: POST /api/internal/authorize
    AUTH->>CMS: GET /api/cards/{pan}
    AUTH->>CMS: POST /api/cards/{pan}/reserve
    CMS-->>AUTH: OK (резерв создан)
    AUTH-->>SW: APPROVED (rrn)
    Note over SW: publisher-confirm не получен за 2s - инициируем откат
    SW->>RMQ: publish smp.transactions
    SW->>AUTH: POST /api/internal/rollback (reversal mti 0400)
    AUTH->>CMS: POST /api/cards/{pan}/rollback
    CMS->>CMS: reservation -> ROLLED_BACK
    CMS-->>AUTH: OK
    AUTH-->>SW: RollbackResponse APPROVED
    SW-->>GW: DECLINED responseCode 96
    GW-->>T: HTTP {DECLINED, code=96, System Error}
```

Сплошная стрелка - синхронный HTTP-вызов с ответом; пунктир - асинхронное сообщение по AMQP. Топология очередей
и механика retry/DLQ раскрыты в [A5](A5_rabbitmq_topology.md), статусы карты и транзакции -
в [A6](A6_state_transition.md).

## Подтверждение в коде

- [RouteService.java](../../../services/switch/src/main/java/com/processing/service/RouteService.java) - оркестрация
  `route()`: BIN -> authorize -> tryLog -> rollback при `!logged && APPROVED`.
- [AuthorizationClient.java](../../../services/switch/src/main/java/com/processing/service/AuthorizationClient.java) -
  HTTP-клиент Switch -> Authorization (authorize/rollback, resilience).
- [LoggerClient.java](../../../services/switch/src/main/java/com/processing/service/LoggerClient.java)
  и [application.yml](../../../services/switch/src/main/resources/application.yml) - публикация с publisher confirms.
- [AuthServiceImpl.java](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java)
  и [DeclineOutcome.java](../../../services/authorization/src/main/java/com/processing/authorization/constants/DeclineOutcome.java) -
  ветки отказов при авторизации и rollback.
- [CardServiceImpl.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java) -
  резервирование и откат (`reserve`, `rollback`).
- [TransactionLogListener.java](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/listener/TransactionLogListener.java) -
  async-потребление и сохранение в `transactions`.