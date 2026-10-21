# Карта системы - 6 диаграмм (часть «а»)

Каждая диаграмма построена опираясь не только на документацию, но и на код, в случае несоответствий код признается
источником правды.
В файлах каждой диаграмы указано: чего не хватало в проекте с точки зрения отображения данного слоя в документации, что
было добавлено и отражено на каждом слое и ключевые ссылки на код.
Для удобства каждый `.md`-файл построен по единой структуре: блоки «Не было указано», «Что добавлено», «Объект
тестирования», диаграмма в нотации Mermaid, пояснение к схеме текстом, и раздел «Подтверждение в коде» (список с
относительными ссылками).

## Сводка

| №  | Диаграмма                                               | Файл                                                 | Чего не хватало в проекте                                                                                                                          | Что сделано                                                                                                                                                                 |
|:--:|---------------------------------------------------------|------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| A1 | C4 Level 1 - Context                                    | [`A1_context.md`](A1_context.md)                     | Отдельной Context-диаграммы нет; границы «СМП vs внешний мир» не проведены                                                                         | СМП показан как единый «чёрный ящик», перечислены 4 внешних актора и протоколы их входа (HTTP, WebSocket)                                                                   |
| A2 | C4 Level 2 - Container                                  | [`A2_container.md`](A2_container.md)                 | `architecture-diagram.puml` не различает sync/async; async-пути (outbox, publisher confirms) не отражены; схема не сверена с `docker-compose.yaml` | Все runtime-сервисы из `docker-compose.yaml`; сплошная линия = HTTP/JDBC (sync), пунктир = AMQP (async)                                                                     |
| A3 | C4 Level 3 - Component (Authorization, Card-Management) | [`A3_component.md`](A3_component.md)                 | Компонентного уровня нет                                                                                                                           | Раскрыта внутренняя структура двух сервисов: controller -> service -> client/repository; отмечены HTTP-клиенты (mock), JDBC (реальная БД), outbox (изолированный компонент) |
| A4 | Sequence                                                | [`A4_sequence.md`](A4_sequence.md)                   | Mermaid-sequence покрывает только happy path                                                                                                       | Добавлены declined (CARD_BLOCKED) и reversal (mti 0400); помечены синхронная цепочка и асинхронная ветвь                                                                    |
| A5 | RabbitMQ-топология                                      | [`A5_rabbitmq_topology.md`](A5_rabbitmq_topology.md) | Топология описана таблицей/текстом, но не визуализирована                                                                                          | Нарисованы обе async-ветки: exchange -> queue -> consumer, DLX/DLQ, routing key, retry (x-max-delivery=3), TTL DLQ (60s), publisher confirms (2s)                           |
| A6 | State-transition                                        | [`A6_state_transition.md`](A6_state_transition.md)   | State-диаграмм нет                                                                                                                                 | Построены переходы `Card.status` (ACTIVE/INACTIVE/BLOCKED/EXPIRED/DELETED) и жизненный цикл Transaction (APPROVED/DECLINED/ROLLED_BACK через reversal)                      |

## Выявленные расхождения кода и документации

Сводка несоответствий между `docs/architecture.md` и фактическим кодом, найденных при построении диаграмм
(white-box). Ссылки с номерами строк указывают на подтверждающие места в коде.

### Container (A2)

| # | В документации                                 | В коде                                                                             | Ссылка                                                                                                                                 |
|---|------------------------------------------------|------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| 1 | Все связи изображены одинаково                 | Связей три типа: HTTP, JDBC и AMQP; синхронные и асинхронные пути стоит различать  | -                                                                                                                                      |
| 2 | Связь Switch – Merchant Acquirer отсутствует   | Switch после каждой авторизации синхронно запрашивает комиссию у эквайрера         | [RouteService.java:70](../../../services/switch/src/main/java/com/processing/service/RouteService.java)                                |
| 3 | Terminal Simulator обращается только к Gateway | Терминал читает карты напрямую: `GET /api/cards` в Card Management (минуя Gateway) | [GatewayClient.java:35](../../../services/terminal-simulator/src/main/java/com/processing/terminalsimulator/client/GatewayClient.java) |
| 4 | У Merchant Acquirer нет хранилища              | Сервис имеет собственные таблицы (merchants, terminals, acquirer_fee)              | [db/migration](../../../services/merchant-acquirer/src/main/resources/db/migration)                                                    |
| 5 | Связь Gateway – Logger не показана             | Gateway проксирует `/api/transactions/**` и `/api/dashboard/**` к Logger           | [application.yml](../../../services/gateway/src/main/resources/application.yml)                                                        |

### Sequence (A4)

| #  | В документации                                          | В коде                                                                                                                                                                                                                 | Ссылка                                                                                                                                                                                                                                                                                                                                                                    |
|----|---------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 6  | Authorization сначала вызывает Bin Lookup, затем CMS    | Сначала `getCard` в CMS; Bin Lookup вызывается только для найденной карты и на решение не влияет                                                                                                                       | [AuthServiceImpl.java:46,66](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java)                                                                                                                                                                                                                                    |
| 7  | RRN формируется после резервирования                    | RRN генерируется до reserve, так как служит ключом резерва в CMS                                                                                                                                                       | [AuthServiceImpl.java:149-151](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java)                                                                                                                                                                                                                                  |
| 8  | Резервирование не атомарно, нужен `SELECT FOR UPDATE`   | CMS уже использует `PESSIMISTIC_WRITE` и повторно проверяет баланс в `startReservation`                                                                                                                                | [CardJpaRepository.java:20](../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/CardJpaRepository.java), [Card.java:101](../../../services/card-management/src/main/java/com/processing/cardmanagement/models/Card.java)                                                                                                           |
| 9  | Обращения к Merchant Acquirer нет                       | Switch синхронно запрашивает комиссию после authorize                                                                                                                                                                  | [RouteService.java:70](../../../services/switch/src/main/java/com/processing/service/RouteService.java)                                                                                                                                                                                                                                                                   |
| 10 | Проверка лимитов в БД не показана                       | Authorization делает upsert в `limit_usage` до резервирования                                                                                                                                                          | [AuthServiceImpl.java:125](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java)                                                                                                                                                                                                                                      |
| 11 | declined и reversal отсутствуют                         | Добавлены сценарии 2 и 3 (см. [A4](A4_sequence.md))                                                                                                                                                                    | -                                                                                                                                                                                                                                                                                                                                                                         |
| 12 | Reversal: «MTI 0400, publisher confirm не пришёл за 2s» | MTI 0400 в коде нет (Gateway пропускает только `0100`); откат - HTTP `POST /api/internal/rollback {rrn, pan, amount}`; таймаута ожидания подтверждения нет, rollback срабатывает только при синхронном `AmqpException` | [TransactionRequestValidator.java:37](../../../services/gateway/src/main/java/com/processing/gateway/validation/TransactionRequestValidator.java), [LoggerClient.java:40-49](../../../services/switch/src/main/java/com/processing/service/LoggerClient.java), [RouteService.java:85-91](../../../services/switch/src/main/java/com/processing/service/RouteService.java) |

### RabbitMQ-топология (A5)

| #  | Механика из документации                                     | Что в коде                                                                                                                                                                                                                                                                       | Вывод                                                                                                          |
|----|--------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------|
| 13 | Publisher confirms: подтверждение за 2s, при nack - reversal | Настроены `correlated` и `returns`, но `ConfirmCallback`/`ReturnsCallback`/`CorrelationData` не задействованы; значение 2000 мс встречается только как HTTP-таймаут к Logger и не используется                                                                                   | Реализовано частично: откат возможен только по синхронному `AmqpException`                                     |
| 14 | Retry: 3 попытки в consumer, затем DLQ                       | Объявлен аргумент `x-max-delivery: 3`, которого в RabbitMQ нет (у quorum-очередей - `x-delivery-limit`); retry-интерцептор в listener не настроен; при исключении по умолчанию идёт requeue, поэтому в DLX уходят лишь фатальные ошибки (например, `MessageConversionException`) | Не работает как описано: сообщение может бесконечно возвращаться в очередь, а не уходить в DLQ после 3 попыток |

### Дополнительные наблюдения

- Состав сверен с `docker-compose.yaml`: 11 сервисов (9 Java-сервисов, dashboard и autoscaler), PostgreSQL,
  RabbitMQ. Autoscaler (профиль `observability`) и Prometheus/Grafana/Loki/Promtail не вынесены на схему, так как
  в обработке транзакций не участвуют.
- У Notification Service есть endpoint `GET /api/notifications`
  ([NotificationController.java](../../../services/notification-service/src/main/java/com/processing/notification/controller/NotificationController.java)),
  но маршрута на него в Gateway нет, поэтому уведомления читаются только напрямую по порту `:8097`.

## Ключевые подтверждающие ссылки на код

- **Gateway (маршруты, валидация):** [application.yml](../../../services/gateway/src/main/resources/application.yml)
- **Switch (маршрутизация/компенсация):
  ** [RouteService.java](../../../services/switch/src/main/java/com/processing/service/RouteService.java), [AuthorizationClient.java](../../../services/switch/src/main/java/com/processing/service/AuthorizationClient.java), [LoggerClient.java](../../../services/switch/src/main/java/com/processing/service/LoggerClient.java)
- **Switch RabbitMQ (publisher confirm):
  ** [application.yml](../../../services/switch/src/main/resources/application.yml), [RabbitMQConfig.java](../../../services/switch/src/main/java/com/processing/config/RabbitMQConfig.java)
- **Authorization (бизнес-логика, отказы):
  ** [AuthServiceImpl.java](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java), [DeclineOutcome.java](../../../services/authorization/src/main/java/com/processing/authorization/constants/DeclineOutcome.java)
- **Authorization клиенты (CMS/BIN):
  ** [CardManagementClientImpl.java](../../../services/authorization/src/main/java/com/processing/authorization/client/CardManagementClientImpl.java), [BinLookupClient.java](../../../services/authorization/src/main/java/com/processing/authorization/client/BinLookupClient.java)
- **Authorization репозиторий лимитов (JDBC):
  ** [LimitUsageRepository.java](../../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java)
- **Card-Management (бизнес-логика):
  ** [CardServiceImpl.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java)
- **Card-Management outbox:
  ** [OutboxProcessor.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxProcessor.java), [OutboxEventProcessorImpl.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java)
- **Card-Management RabbitMQ:
  ** [RabbitMQConfig.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/config/RabbitMQConfig.java)
- **Logger RabbitMQ + слушатель:
  ** [RabbitMQConfig.java](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/config/RabbitMQConfig.java), [TransactionLogListener.java](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/listener/TransactionLogListener.java)
- **Notification RabbitMQ + слушатель:
  ** [RabbitMQConfig.java](../../../services/notification-service/src/main/java/com/processing/notification/config/RabbitMQConfig.java), [CardEventListener.java](../../../services/notification-service/src/main/java/com/processing/notification/listener/CardEventListener.java)
- **Статусы карты:
  ** [CardStatus.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/models/CardStatus.java)
- **Статусы транзакции:
  ** [TransactionStatus.java](../../../services/common/src/main/java/com/processing/common/dto/transactionlogger/TransactionStatus.java)
- **Состав стенда:** [docker-compose.yaml](../../../docker-compose.yaml)

## Как диаграммы соотносятся с уровнями пирамиды

- **Component (A3):** unit-тесты (где проверяется логика, что мокать)
- **Container (A2) + Sequence (A4):** API-контракты (синхронные взаимодействия)
- **RabbitMQ-топология (A5):** интеграционные тесты (очереди сообщений, DLQ, eventual consistency)
- **Context (A1) + Sequence (A4) + State (A6):** E2E-сценарии (полный контур, эмуляция/заглушки для акторов)
