# Test-design ядра: Authorization и Card-Management

## 1. Базис тестирования

Тест-дизайн опирается на `tz/04-authorization.md`, `tz/05-card-management.md`, `docs/api-spec.md` и модель данных.
Используются только техники из лекции 3: классы эквивалентности, граничные значения, попарное тестирование.

### 1.1. Требования к Authorization

| Код     | Требование                                                                                   |
|---------|----------------------------------------------------------------------------------------------|
| `AR-1`  | Получение карты из CMS: успех - продолжение; карта отсутствует - DECLINED, `responseCode=14` |
| `AR-2`  | Статус проверяется до остальных бизнес-правил: INACTIVE/BLOCKED/EXPIRED дают отказ           |
| `AR-3`  | Истёкший `expiryDate` - DECLINED, `responseCode=54`                                          |
| `AR-4`  | Дневное использование вместе с текущей суммой ≤ `dailyLimit`                                 |
| `AR-5`  | Месячное использование вместе с текущей суммой ≤ `monthlyLimit`                              |
| `AR-6`  | Сумма операции ≤ `availableBalance`; иначе `responseCode=51`                                 |
| `AR-7`  | При успехе - резервирование, APPROVED, `responseCode=00`, RRN и authCode                     |
| `AR-8`  | Недоступность CMS - DECLINED, `responseCode=05`, `ISSUER_TIMEOUT`                            |
| `AR-9`  | Недоступность Bin Lookup - fallback на issuerId от Switch, авторизация продолжается          |
| `AR-10` | Ноль и отрицательная сумма отклоняются Gateway до вызова Authorization                       |

### 1.2. Требования к Card-Management

| Код    | Требование                                                                      |
|--------|---------------------------------------------------------------------------------|
| `CR-1` | Создание карты: PAN из BIN по алг. Луна, `expiryDate` +3 года, статус ACTIVE    |
| `CR-2` | GET по PAN: существующая - 200, отсутствующая - 404                             |
| `CR-3` | Список карт с пагинацией и фильтрами `status`/`bin`                             |
| `CR-4` | PATCH: частичное обновление статуса, лимитов, баланса                           |
| `CR-5` | DELETE - мягкое; DELETED не возвращается и не участвует в транзакциях           |
| `CR-6` | Генерация: положительный count и непустой список шестизначных BIN               |
| `CR-7` | PAN = 16 цифр с корректной контрольной цифрой Луна; BIN = 6 цифр; expiry = MMYY |
| `CR-8` | Reserve: положительная сумма ≤ баланса; обработка нехватки и повторного RRN     |

### 1.3. Порядок проверок в алгоритме авторизации

Этот порядок критичен для pairwise-кейсов, где одновременно нарушено несколько правил: ожидаемый результат определяется
**первой** нарушенной проверкой.

| # | Проверка                   | Отказ                                                     |
|:-:|----------------------------|-----------------------------------------------------------|
| 1 | Карта существует           | 14, `CARD_NOT_FOUND`                                      |
| 2 | Статус = ACTIVE            | 05, `CARD_INACTIVE` / `CARD_BLOCKED` / 54, `CARD_EXPIRED` |
| 3 | Срок не истёк              | 54, `CARD_EXPIRED`                                        |
| 4 | Дневной лимит не превышен  | 61, `EXCEEDS_AMOUNT_LIMIT`                                |
| 5 | Месячный лимит не превышен | 61, `EXCEEDS_AMOUNT_LIMIT`                                |
| 6 | Баланс достаточен          | 51, `INSUFFICIENT_FUNDS`                                  |
| 7 | Резервирование и APPROVED  | 00                                                        |

Проверки 1–3 выполняются до обращения к балансу и лимитам. Проверки 4–5 идут до проверки баланса.

## 2. Классы эквивалентности (EC)

### 2.1. Authorization

| Фактор         | Классы                             | Ожидаемое поведение                                             | Кейсы                                    |
|----------------|------------------------------------|-----------------------------------------------------------------|------------------------------------------|
| Наличие карты  | существующая; отсутствующая        | существующая - продолжение; отсутствующая - DECLINED 14         | `AUTH-EC-01`, `AUTH-EC-02`               |
| Статус         | ACTIVE; INACTIVE; BLOCKED; EXPIRED | ACTIVE - продолжение; остальные - отказ по статусу              | `AUTH-EC-01`, `AUTH-EC-03`–`05`          |
| Сумма          | положительная; 0; отрицательная    | положительная - продолжение; 0 и −1 - HTTP 400 до Authorization | `AUTH-EC-01`, `AUTH-EC-06`, `AUTH-EC-07` |
| Срок действия  | не истёк; истёк                    | не истёк - продолжение; истёк - DECLINED 54                     | `AUTH-EC-01`, `AUTH-BV-10`–`12`          |
| Дневной лимит  | итог ≤ лимит; итог > лимит         | продолжение; DECLINED 61                                        | `AUTH-EC-01`, `AUTH-BV-01`–`03`          |
| Месячный лимит | итог ≤ лимит; итог > лимит         | продолжение; DECLINED 61                                        | `AUTH-EC-01`, `AUTH-BV-04`–`06`          |
| Баланс         | сумма ≤ баланс; сумма > баланс     | продолжение; DECLINED 51                                        | `AUTH-EC-01`, `AUTH-BV-07`–`09`          |
| CMS            | доступен; недоступен               | доступен - обычный алгоритм; недоступен - DECLINED 05           | `AUTH-EC-01`, `AUTH-EC-08`               |
| Bin Lookup     | доступен; недоступен               | оба класса допустимы; при недоступности - fallback              | `AUTH-EC-01`, `AUTH-EC-09`               |

### 2.2. Card-Management

| Операция / поле | Классы                                                                             | Ожидаемое поведение                         | Кейсы                                                       |
|-----------------|------------------------------------------------------------------------------------|---------------------------------------------|-------------------------------------------------------------|
| Создание        | валидный запрос                                                                    | 201; ACTIVE; PAN по Луну; expiry +3 года    | `CARD-EC-01`                                                |
| BIN             | 6 цифр; длина != 6; длина = 6, но есть не-цифра                                    | валидный - 201; невалидные - 400            | `CARD-EC-01`, `CARD-BV-04`–`06`, `CARD-EC-02`               |
| Имя держателя   | заглавные; пустое; строчные                                                        | валидный - 201; невалидные - 400            | `CARD-EC-01`, `CARD-EC-03`, `CARD-EC-04`                    |
| Currency        | 3 цифры; 2 цифры; буква                                                            | валидный - 201; невалидные - 400            | `CARD-EC-01`, `CARD-EC-05`, `CARD-EC-06`                    |
| Лимиты          | неотрицательные; отрицательный daily; отрицательный monthly                        | валидный - 201; невалидные - 400            | `CARD-EC-01`, `CARD-EC-07`, `CARD-EC-08`                    |
| GET по PAN      | существующая; отсутствующая; неверный формат                                       | 200; 404; 400                               | `CARD-EC-09`, `CARD-EC-10`, `CARD-BV-01`–`03`               |
| Фильтр статуса  | ACTIVE; INACTIVE; BLOCKED; EXPIRED                                                 | только неудалённые карты выбранного статуса | `CARD-EC-11`, `CARD-PW-*`                                   |
| DELETED         | отсутствует; есть                                                                  | DELETED не возвращается в GET и списке      | `CARD-EC-12`, `CARD-PW-*`                                   |
| PATCH           | существующая; отсутствующая                                                        | 200 с частичным обновлением; 404            | `CARD-EC-13`, `CARD-EC-14`                                  |
| Генерация       | count ≥ 1 и все BIN - 6 цифр; count < 1; пустой список bins; BIN неверного формата | 201 либо 400                                | `CARD-EC-15`, `CARD-BV-10`–`12`, `CARD-EC-16`, `CARD-EC-17` |
| Reserve amount  | положительная достаточная; выше баланса; неположительная                           | 200; 402; 400                               | `CARD-EC-18`, `CARD-EC-19`, `CARD-BV-19`–`21`               |
| Reserve RRN     | 12 цифр новый; неверный формат; повторный                                          | 200; 400; 409                               | `CARD-EC-18`, `CARD-EC-20`, `CARD-EC-21`                    |
| Reserve PAN     | существующая; отсутствующая                                                        | обработка; 404                              | `CARD-EC-18`, `CARD-EC-22`                                  |

## 3. Граничные значения (BV)

Рассматриваются три точки на каждую границу: чуть ниже, само граничное значение, чуть выше.

### 3.1. Authorization

| Граница                                           |       Ниже | Граничное |      Выше | Ожидание                        | Кейсы             |
|---------------------------------------------------|-----------:|----------:|----------:|---------------------------------|-------------------|
| `dailyUsage + amount`, `dailyLimit=200'000`       |     199999 |    200000 |    200001 | APPROVED; APPROVED; DECLINED 61 | `AUTH-BV-01`–`03` |
| `monthlyUsage + amount`, `monthlyLimit=2'000'000` |    1999999 |   2000000 |   2000001 | APPROVED; APPROVED; DECLINED 61 | `AUTH-BV-04`–`06` |
| `amount`, `availableBalance=30'000`               |      29999 |     30000 |     30001 | APPROVED; APPROVED; DECLINED 51 | `AUTH-BV-07`–`09` |
| `expiryDate` относительно текущего месяца         | предыдущий |   текущий | следующий | DECLINED 54; APPROVED; APPROVED | `AUTH-BV-10`–`12` |

### 3.2. Card-Management

| Граница                      | Ниже       | Граничное | Выше      | Ожидание                        | Кейсы             |
|------------------------------|------------|-----------|-----------|---------------------------------|-------------------|
| Длина PAN = 16               | 15         | 16        | 17        | 400; 200; 400                   | `CARD-BV-01`–`03` |
| Длина BIN = 6                | 5          | 6         | 7         | 400; 201; 400                   | `CARD-BV-04`–`06` |
| `expiryDate` MMYY            | предыдущий | текущий   | следующий | CMS возвращает сохранённый MMYY | `CARD-BV-07`–`09` |
| `count` генератора ≥ 1       | 0          | 1         | 2         | 400; 1 карта; 2 карты           | `CARD-BV-10`–`12` |
| Reserve amount, баланс 30000 | 29999      | 30000     | 30001     | 200; 200 и 0; 402               | `CARD-BV-13`–`15` |
| `limit` списка ≥ 1           | 0          | 1         | 2         | 400; ≤ 1; ≤ 2                   | `CARD-BV-16`–`18` |
| Reserve amount ≥ 1 копейка   | 0          | 1         | 2         | 400; 200; 200                   | `CARD-BV-19`–`21` |

`expiryDate` не является входным полем create/patch - три даты готовятся как состояние карты, а проверяется корректная
выдача MMYY. Решение «истекла ли карта» принимает Authorization.

Граничные точки, совпадающие с представителями соседних EC-классов, не дублируются отдельными EC-кейсами: один тест-кейс
учитывается в покрытии обеих техник.

## 4. Попарное тестирование

PICT-модели лежат в `docs/practice-2/pict/`. Ниже - параметры и constraints; сгенерированные наборы ссылаются на
тест-кейсы через `AUTH-PW-*` и `CARD-PW-*`.

### 4.1. Модель Authorization

| Параметр        | Значения                           | Смысл                                         |
|-----------------|------------------------------------|-----------------------------------------------|
| `card_status`   | ACTIVE, INACTIVE, BLOCKED, EXPIRED | Статус; только ACTIVE продолжает алгоритм     |
| `daily_usage`   | within, boundary, over             | Использование + сумма против дневного лимита  |
| `monthly_usage` | within, boundary, over             | Использование + сумма против месячного лимита |
| `balance_usage` | within, boundary, over             | Сумма против баланса                          |
| `expiry_state`  | future, current, past              | Будущий, текущий, прошедший месяц             |
| `terminal_type` | POS, ATM, ECOM                     | Тип терминала                                 |
| `mcc`           | grocery, restaurant, fuel, hotel   | 5411, 5812, 5541, 7011                        |

Constraints для pict (исключаем бессмысленные/непроверяемые сочетания):

```
IF [card_status] <> "ACTIVE" THEN [daily_usage] = "within"
    AND [monthly_usage] = "within" AND [balance_usage] = "within";
IF [expiry_state] = "past" THEN [balance_usage] = "within";
```

### 4.2. Модель Card-Management

Модель для `GET /api/cards`: факторы задаются query-параметрами или воспроизводимым состоянием БД.

| Параметр         | Значения                        | Смысл                                            |
|------------------|---------------------------------|--------------------------------------------------|
| `page_size`      | default, one, ten, hundred      | Отсутствующий limit или положительное значение   |
| `offset_pos`     | zero, inside, at_end, beyond    | Позиция offset относительно выборки              |
| `status_filter`  | none, ACTIVE, INACTIVE, BLOCKED | Отсутствующий или поддерживаемый фильтр          |
| `bin_filter`     | none, match, mismatch           | Отсутствующий, совпадающий или несовпадающий BIN |
| `matching_count` | zero, one, many                 | Подходящие неудалённые карты в предусловии       |
| `has_deleted`    | no, yes                         | Наличие DELETED-карт в БД                        |

Constraints для pict (исключаем бессмысленные/непроверяемые сочетания):

```
IF [bin_filter] = "mismatch" THEN [matching_count] = "zero";
IF [matching_count] = "zero" THEN [offset_pos] <> "inside"
    AND [offset_pos] <> "at_end";
IF [matching_count] = "one" THEN [offset_pos] <> "inside";
```

### 4.3. Ручные сочетания сверх PICT

- `AUTH-MAN-01`: ACTIVE + future + daily/monthly/balance = over одновременно. В pairwise-наборе такой полной
  комбинации нет; проверяет порядок проверок (первым срабатывает дневной лимит).
- `AUTH-MAN-02`: ACTIVE + future + daily/balance = boundary одновременно. Проверяет, что при граничном
  значении по обоим правилам решение APPROVED.
- `CARD-MAN-01`: DELETED + совпадающий BIN + many + yes. Проверяет, что DELETED не попадает в `cards`,
  даже если BIN совпадает.

## 5. Тестовые данные

Раздел связывает абстрактные значения Pict-моделей с конкретными числами, PAN и состоянием БД: одна таблица профилей
применяется и в граничных значениях, и в pairwise-кейсах.

### 5.1. Профили Authorization

Во всех кейсах (если не указано иначе): amount `25000`, базовая карта `4000001234560005`, остальные поля запроса
валидны.

| Профиль                                 | Конкретные данные                                                           |
|-----------------------------------------|-----------------------------------------------------------------------------|
| daily within / boundary / over          | daily usage 170000 / 175000 / 175001; итог 195000 / 200000 / 200001         |
| monthly within / boundary / over        | monthly usage 1900000 / 1975000 / 1975001; итог 1925000 / 2000000 / 2000001 |
| balance within / boundary / over        | balance 35000 / 25000 / 24999                                               |
| expiry future / current / past          | следующий / текущий / предыдущий календарный месяц                          |
| terminal POS / ATM / ECOM               | POS + TERM0001 / ATM + ATM00001 / ECOM + ECOM0001                           |
| MCC grocery / restaurant / fuel / hotel | 5411 / 5812 / 5541 / 7011                                                   |

### 5.2. Профили Card-Management

Валидный create-запрос: BIN `400000`, имя `IVAN IVANOV`, currency `643`, dailyLimit `15000000`, monthlyLimit
`300000000`, initialBalance `100000000`. В list-кейсах `matching_count=many` = 3 неудалённые карты с совпадающим BIN;
`has_deleted=yes` = дополнительная DELETED-карта. Совпадающий BIN - `400000`, несовпадающий - `499999`.

## 6. Тест-кейсы

### 6.1. Authorization: классы эквивалентности

| ID         | Требование            | Вид        | Предусловие                                                                          | Шаги                     | Ожидаемый результат                                                     |
|------------|-----------------------|------------|--------------------------------------------------------------------------------------|--------------------------|-------------------------------------------------------------------------|
| AUTH-EC-01 | `AR-1`–`7`, `9`, `10` | позитивный | ACTIVE; amount=25000; срок future; usage и balance within; CMS и Bin Lookup доступны | POST `/api/transactions` | APPROVED 00; RRN 12 цифр; authCode 6 символов; баланс уменьшен на 25000 |
| AUTH-EC-02 | `AR-1`                | негативный | PAN валидного формата отсутствует                                                    | POST с отсутствующим PAN | DECLINED 14, `CARD_NOT_FOUND`                                           |
| AUTH-EC-03 | `AR-2`                | негативный | Карта INACTIVE                                                                       | POST                     | DECLINED 05, `CARD_INACTIVE`; дальнейшие проверки не выполняются        |
| AUTH-EC-04 | `AR-2`                | негативный | Карта BLOCKED                                                                        | POST                     | DECLINED 05, `CARD_BLOCKED`                                             |
| AUTH-EC-05 | `AR-2`                | негативный | Статус EXPIRED, дата future                                                          | POST                     | DECLINED 54, `CARD_EXPIRED`; приоритет статуса                          |
| AUTH-EC-06 | `AR-10`               | негативный | amount=0                                                                             | POST                     | HTTP 400; Switch и Authorization не вызваны                             |
| AUTH-EC-07 | `AR-10`               | негативный | amount=-1                                                                            | POST                     | HTTP 400; Switch и Authorization не вызваны                             |
| AUTH-EC-08 | `AR-8`                | негативный | CMS недоступен                                                                       | POST                     | DECLINED 05, `ISSUER_TIMEOUT`                                           |
| AUTH-EC-09 | `AR-9`                | позитивный | Bin Lookup недоступен; issuerId от Switch                                            | POST                     | APPROVED 00 по fallback                                                 |

### 6.2. Authorization: граничные значения

| ID         | Требование | Вид        | Вход                             | Ожидаемый результат   |
|------------|------------|------------|----------------------------------|-----------------------|
| AUTH-BV-01 | `AR-4`     | позитивный | daily usage + amount = 199999    | APPROVED 00           |
| AUTH-BV-02 | `AR-4`     | позитивный | daily usage + amount = 200000    | APPROVED 00           |
| AUTH-BV-03 | `AR-4`     | негативный | daily usage + amount = 200001    | DECLINED 61           |
| AUTH-BV-04 | `AR-5`     | позитивный | monthly usage + amount = 1999999 | APPROVED 00           |
| AUTH-BV-05 | `AR-5`     | позитивный | monthly usage + amount = 2000000 | APPROVED 00           |
| AUTH-BV-06 | `AR-5`     | негативный | monthly usage + amount = 2000001 | DECLINED 61           |
| AUTH-BV-07 | `AR-6`     | позитивный | balance=30000, amount=29999      | APPROVED 00; баланс 1 |
| AUTH-BV-08 | `AR-6`     | позитивный | balance=30000, amount=30000      | APPROVED 00; баланс 0 |
| AUTH-BV-09 | `AR-6`     | негативный | balance=30000, amount=30001      | DECLINED 51           |
| AUTH-BV-10 | `AR-3`     | негативный | ACTIVE; expiry предыдущий месяц  | DECLINED 54           |
| AUTH-BV-11 | `AR-3`     | позитивный | ACTIVE; expiry текущий месяц     | APPROVED 00           |
| AUTH-BV-12 | `AR-3`     | позитивный | ACTIVE; expiry следующий месяц   | APPROVED 00           |

### 6.3. Authorization: pairwise

Кейсы соответствуют строкам `pict/authorization-cases.txt` (нумерация `AUTH-PW-NN` совпадает с номером строки). В каждом
кейсе проверяется только первая нарушенная проверка из раздела 0.3.

| ID          | Требование | Строка PICT                                                | Ожидаемый результат                           |
|-------------|------------|------------------------------------------------------------|-----------------------------------------------|
| AUTH-PW-01  | `AR-2`     | INACTIVE, within×3, future, POS, electronics               | DECLINED 05, `CARD_INACTIVE`                  |
| AUTH-PW-02  | `AR-5`     | ACTIVE, within, over, over, current, ATM, restaurant       | DECLINED 61                                   |
| AUTH-PW-03  | `AR-3`     | ACTIVE, over, over, within, past, ECOM, fuel               | DECLINED 54                                   |
| AUTH-PW-04  | `AR-6`     | ACTIVE, within, boundary, over, future, ECOM, hotel        | DECLINED 51                                   |
| AUTH-PW-05  | `AR-4`     | ACTIVE, boundary, over, within, current, POS, grocery      | DECLINED 61                                   |
| AUTH-PW-06  | `AR-4`     | ACTIVE, over, within, boundary, future, ATM, restaurant    | DECLINED 61                                   |
| AUTH-PW-07  | `AR-5`     | ACTIVE, within, over, boundary, current, ECOM, fuel        | DECLINED 61                                   |
| AUTH-PW-08  | `AR-6`     | ACTIVE, boundary, within, over, future, POS, hotel         | DECLINED 51                                   |
| AUTH-PW-09  | `AR-3`     | ACTIVE, over, boundary, within, past, ATM, grocery         | DECLINED 54                                   |
| AUTH-PW-10  | `AR-7`     | ACTIVE, within, within, boundary, current, POS, restaurant | APPROVED 00; баланс −25000                    |
| AUTH-PW-11  | `AR-7`     | ACTIVE, boundary, boundary, within, future, ECOM, fuel     | APPROVED 00; баланс −25000                    |
| AUTH-PW-12  | `AR-3`     | ACTIVE, over, within, within, past, ECOM, hotel            | DECLINED 54                                   |
| AUTH-PW-13  | `AR-4`     | ACTIVE, within, over, over, future, ATM, grocery           | DECLINED 61                                   |
| AUTH-PW-14  | `AR-4`     | ACTIVE, boundary, over, over, current, ECOM, restaurant    | DECLINED 61                                   |
| AUTH-PW-15  | `AR-4`     | ACTIVE, over, over, boundary, future, POS, fuel            | DECLINED 61                                   |
| AUTH-PW-16  | `AR-2`     | INACTIVE, within×3, future, POS, grocery                   | DECLINED 05, `CARD_INACTIVE`                  |
| AUTH-PW-17  | `AR-2`     | INACTIVE, within×3, current, ATM, restaurant               | DECLINED 05, `CARD_INACTIVE`                  |
| AUTH-PW-18  | `AR-2`     | INACTIVE, within×3, past, ECOM, fuel                       | DECLINED 05, `CARD_INACTIVE`                  |
| AUTH-PW-19  | `AR-2`     | INACTIVE, within×3, future, ECOM, hotel                    | DECLINED 05, `CARD_INACTIVE`                  |
| AUTH-PW-20  | `AR-2`     | BLOCKED, within×3, future, ATM, grocery                    | DECLINED 05, `CARD_BLOCKED`                   |
| AUTH-PW-21  | `AR-2`     | BLOCKED, within×3, current, POS, restaurant                | DECLINED 05, `CARD_BLOCKED`                   |
| AUTH-PW-22  | `AR-2`     | BLOCKED, within×3, past, ECOM, fuel                        | DECLINED 05, `CARD_BLOCKED`                   |
| AUTH-PW-23  | `AR-2`     | EXPIRED, within×3, future, POS, hotel                      | DECLINED 54, `CARD_EXPIRED`                   |
| AUTH-PW-24  | `AR-2`     | EXPIRED, within×3, current, ATM, grocery                   | DECLINED 54, `CARD_EXPIRED`                   |
| AUTH-PW-25  | `AR-2`     | EXPIRED, within×3, past, ECOM, restaurant                  | DECLINED 54, `CARD_EXPIRED`                   |
| AUTH-PW-26  | `AR-7`     | ACTIVE, within×3, current, POS, grocery                    | APPROVED 00                                   |
| AUTH-MAN-01 | `AR-4`     | ACTIVE, over×3, future, POS, grocery                       | DECLINED 61; первым срабатывает дневной лимит |
| AUTH-MAN-02 | `AR-7`     | ACTIVE, boundary, within, boundary, future, POS, grocery   | APPROVED 00                                   |

### 6.4. Card-Management: классы эквивалентности

| ID         | Требование     | Вид        | Вход                                  | Ожидаемый результат                                                 |
|------------|----------------|------------|---------------------------------------|---------------------------------------------------------------------|
| CARD-EC-01 | `CR-1`, `CR-7` | позитивный | Валидный create                       | 201; ACTIVE; PAN 16 цифр с Луном и префиксом 400000; expiry +3 года |
| CARD-EC-02 | `CR-1`, `CR-7` | негативный | bin=`40000A`                          | 400; карта не создана                                               |
| CARD-EC-03 | `CR-1`         | негативный | Пустое имя                            | 400; карта не создана                                               |
| CARD-EC-04 | `CR-1`         | негативный | cardholderName=`Ivan Ivanov`          | 400; карта не создана                                               |
| CARD-EC-05 | `CR-1`         | негативный | currencyCode=`64`                     | 400; карта не создана                                               |
| CARD-EC-06 | `CR-1`         | негативный | currencyCode=`64A`                    | 400; карта не создана                                               |
| CARD-EC-07 | `CR-1`         | негативный | dailyLimit=`-1`                       | 400; карта не создана                                               |
| CARD-EC-08 | `CR-1`         | негативный | monthlyLimit=`-1`                     | 400; карта не создана                                               |
| CARD-EC-09 | `CR-2`         | позитивный | Существующий PAN                      | 200; возвращена нужная карта                                        |
| CARD-EC-10 | `CR-2`         | негативный | Отсутствующий PAN                     | 404                                                                 |
| CARD-EC-11 | `CR-3`         | позитивный | status=EXPIRED                        | 200; только EXPIRED; total согласован                               |
| CARD-EC-12 | `CR-5`         | позитивный | Карта удалена                         | 404 по PAN; отсутствует в списке                                    |
| CARD-EC-13 | `CR-4`         | позитивный | PATCH availableBalance=50000          | 200; остальные поля сохранены                                       |
| CARD-EC-14 | `CR-4`         | негативный | PATCH отсутствующей                   | 404                                                                 |
| CARD-EC-15 | `CR-6`, `CR-7` | позитивный | count=20, BIN 400000 и 400001         | 201; 20 карт; равномерно; PAN по Луну                               |
| CARD-EC-16 | `CR-6`         | негативный | bins=[]                               | 400; карты не созданы                                               |
| CARD-EC-17 | `CR-6`         | негативный | bin=`40000`                           | 400; карты не созданы                                               |
| CARD-EC-18 | `CR-8`         | позитивный | balance=30000; amount=5000; новый RRN | 200; баланс 25000                                                   |
| CARD-EC-19 | `CR-8`         | негативный | balance=30000; amount=35000           | 402; баланс не изменён                                              |
| CARD-EC-20 | `CR-8`         | негативный | RRN из 11 цифр                        | 400; баланс не изменён                                              |
| CARD-EC-21 | `CR-8`         | негативный | Повторный RRN                         | 409; повторного списания нет                                        |
| CARD-EC-22 | `CR-8`         | негативный | Отсутствующий PAN                     | 404; резервирование не создано                                      |

### 6.5. Card-Management: граничные значения

| ID         | Требование     | Вид        | Вход                           | Ожидаемый результат       |
|------------|----------------|------------|--------------------------------|---------------------------|
| CARD-BV-01 | `CR-2`, `CR-7` | негативный | PAN 15 цифр                    | 400                       |
| CARD-BV-02 | `CR-2`, `CR-7` | позитивный | PAN 16 цифр                    | 200                       |
| CARD-BV-03 | `CR-2`, `CR-7` | негативный | PAN 17 цифр                    | 400                       |
| CARD-BV-04 | `CR-1`, `CR-7` | негативный | bin=`40000`                    | 400                       |
| CARD-BV-05 | `CR-1`, `CR-7` | позитивный | bin=`400000`                   | 201; PAN начинается с BIN |
| CARD-BV-06 | `CR-1`, `CR-7` | негативный | bin=`4000000`                  | 400                       |
| CARD-BV-07 | `CR-7`         | позитивный | Карта с expiry предыдущий MMYY | 200; точный MMYY          |
| CARD-BV-08 | `CR-7`         | позитивный | Карта с expiry текущий MMYY    | 200; точный MMYY          |
| CARD-BV-09 | `CR-7`         | позитивный | Карта с expiry следующий MMYY  | 200; точный MMYY          |
| CARD-BV-10 | `CR-6`         | негативный | count=0                        | 400                       |
| CARD-BV-11 | `CR-6`         | позитивный | count=1                        | 201; одна карта           |
| CARD-BV-12 | `CR-6`         | позитивный | count=2                        | 201; две карты            |
| CARD-BV-13 | `CR-8`         | позитивный | balance=30000, amount=29999    | 200; баланс 1             |
| CARD-BV-14 | `CR-8`         | позитивный | balance=30000, amount=30000    | 200; баланс 0             |
| CARD-BV-15 | `CR-8`         | негативный | balance=30000, amount=30001    | 402                       |
| CARD-BV-16 | `CR-3`         | негативный | limit=0                        | 400                       |
| CARD-BV-17 | `CR-3`         | позитивный | limit=1; ≥ 2 карты             | 200; ≤ 1 карта            |
| CARD-BV-18 | `CR-3`         | позитивный | limit=2; ≥ 3 карты             | 200; ≤ 2 карты            |
| CARD-BV-19 | `CR-8`         | негативный | amount=0                       | 400                       |
| CARD-BV-20 | `CR-8`         | позитивный | balance ≥ 2; amount=1          | 200; баланс −1            |
| CARD-BV-21 | `CR-8`         | позитивный | balance ≥ 2; amount=2          | 200; баланс −2            |

### 6.6. Card-Management: pairwise

Кейсы соответствуют строкам `pict/card-management-cases.txt` (нумерация `CARD-PW-NN` = номер строки).

| ID          | Требование     | Строка PICT                                  | Ожидаемый результат                |
|-------------|----------------|----------------------------------------------|------------------------------------|
| CARD-PW-01  | `CR-3`, `CR-5` | ten, at_end, ACTIVE, match, many, no         | 200; total=3; cards=0; DELETED нет |
| CARD-PW-02  | `CR-3`, `CR-5` | hundred, zero, ACTIVE, none, zero, yes       | 200; total=0; cards=0              |
| CARD-PW-03  | `CR-3`, `CR-5` | ten, beyond, BLOCKED, none, one, yes         | 200; total=1; cards=0              |
| CARD-PW-04  | `CR-3`, `CR-5` | one, zero, BLOCKED, mismatch, zero, no       | 200; total=0; cards=0              |
| CARD-PW-05  | `CR-3`, `CR-5` | ten, inside, INACTIVE, match, many, yes      | 200; total=3; cards=2              |
| CARD-PW-06  | `CR-3`, `CR-5` | one, at_end, none, match, one, yes           | 200; total=1; cards=0              |
| CARD-PW-07  | `CR-3`, `CR-5` | default, inside, BLOCKED, none, many, no     | 200; total=3; cards=2              |
| CARD-PW-08  | `CR-3`, `CR-5` | default, inside, ACTIVE, match, many, yes    | 200; total=3; cards=2              |
| CARD-PW-09  | `CR-3`, `CR-5` | hundred, beyond, INACTIVE, match, one, no    | 200; total=1; cards=0              |
| CARD-PW-10  | `CR-3`, `CR-5` | one, beyond, none, none, many, yes           | 200; total=3; cards=0              |
| CARD-PW-11  | `CR-3`, `CR-5` | one, zero, ACTIVE, match, one, yes           | 200; total=1; cards=1              |
| CARD-PW-12  | `CR-3`, `CR-5` | one, inside, none, match, many, no           | 200; total=3; cards=1              |
| CARD-PW-13  | `CR-3`, `CR-5` | hundred, beyond, none, mismatch, zero, yes   | 200; total=0; cards=0              |
| CARD-PW-14  | `CR-3`, `CR-5` | hundred, inside, INACTIVE, none, many, yes   | 200; total=3; cards=2              |
| CARD-PW-15  | `CR-3`, `CR-5` | default, zero, INACTIVE, mismatch, zero, yes | 200; total=0; cards=0              |
| CARD-PW-16  | `CR-3`, `CR-5` | default, beyond, BLOCKED, match, one, no     | 200; total=1; cards=0              |
| CARD-PW-17  | `CR-3`, `CR-5` | ten, zero, none, match, many, yes            | 200; total=3; cards=3              |
| CARD-PW-18  | `CR-3`, `CR-5` | ten, beyond, ACTIVE, mismatch, zero, no      | 200; total=0; cards=0              |
| CARD-PW-19  | `CR-3`, `CR-5` | default, beyond, none, match, zero, no       | 200; total=0; cards=0              |
| CARD-PW-20  | `CR-3`, `CR-5` | default, at_end, INACTIVE, none, many, yes   | 200; total=3; cards=0              |
| CARD-PW-21  | `CR-3`, `CR-5` | hundred, at_end, BLOCKED, match, many, no    | 200; total=3; cards=0              |
| CARD-PW-22  | `CR-3`, `CR-5` | one, beyond, INACTIVE, none, zero, no        | 200; total=0; cards=0              |
| CARD-MAN-01 | `CR-3`, `CR-5` | DELETED + match + many + yes                 | DELETED не попадает в cards        |

## 7. Покрытие требований

| Требование | EC                 | BV                   | Pairwise              | Ручной      |
|------------|--------------------|----------------------|-----------------------|-------------|
| `AR-1`     | AUTH-EC-01, 02     | -                    | -                     | -           |
| `AR-2`     | AUTH-EC-01, 03–05  | -                    | AUTH-PW-01, 16–25     | -           |
| `AR-3`     | AUTH-EC-01         | AUTH-BV-10–12        | AUTH-PW-03, 09, 12    | -           |
| `AR-4`     | AUTH-EC-01         | AUTH-BV-01–03        | AUTH-PW-05, 06, 13–15 | AUTH-MAN-01 |
| `AR-5`     | AUTH-EC-01         | AUTH-BV-04–06        | AUTH-PW-02, 07        | -           |
| `AR-6`     | AUTH-EC-01         | AUTH-BV-07–09        | AUTH-PW-04, 08        | -           |
| `AR-7`     | AUTH-EC-01         | -                    | AUTH-PW-10, 11, 26    | AUTH-MAN-02 |
| `AR-8`     | AUTH-EC-08         | -                    | -                     | -           |
| `AR-9`     | AUTH-EC-09         | -                    | -                     | -           |
| `AR-10`    | AUTH-EC-06, 07     | -                    | -                     | -           |
| `CR-1`     | CARD-EC-01–08      | CARD-BV-04–06        | -                     | -           |
| `CR-2`     | CARD-EC-09, 10     | CARD-BV-01–03        | -                     | -           |
| `CR-3`     | CARD-EC-11         | CARD-BV-16–18        | CARD-PW-01–22         | CARD-MAN-01 |
| `CR-4`     | CARD-EC-13, 14     | -                    | -                     | -           |
| `CR-5`     | CARD-EC-12         | -                    | CARD-PW-01–22         | CARD-MAN-01 |
| `CR-6`     | CARD-EC-15–17      | CARD-BV-10–12        | -                     | -           |
| `CR-7`     | CARD-EC-01, 02, 15 | CARD-BV-01–09        | -                     | -           |
| `CR-8`     | CARD-EC-18–22      | CARD-BV-13–15, 19–21 | -                     | -           |
