# CodeExecutorService

MVP для микросервиса исполнения кода.

- Spring Boot (Maven)
- Исполнение Python идёт через Docker с лимитами
- Сервис **ничего не сравнивает** (нет PASSED/FAILED) — он только возвращает фактические результаты выполнения

## Режимы прогона

### 1) STEP (пошаговый)

- `POST /executions` — создаёт сессию (поднимает 1 контейнер под задачу)
- `POST /executions/{id}/tests` — прогоняет **один** тест в этом контейнере
- `GET /executions/{id}` — возвращает агрегированные результаты сессии (выполненные тесты, `durationMs`, `peakMemoryMb`)
- `POST /executions/{id}/cancel` — завершает сессию и освобождает ресурсы

### 2) BATCH (пакетный)

- `POST /executions/batch` — прогоняет все тесты **в одном контейнере** и возвращает результаты одним ответом

> Примечание: сервис возвращает технический исход (`OK`, `RUNTIME_ERROR`, `TIMEOUT`, ...), но **не** определяет правильность ответа.

## Требования

- Установлен и запущен Docker (Docker Desktop на Windows)
- Команда `docker` доступна из терминала (`docker version` работает)

Образ по умолчанию: `python:3.12-alpine`.

## Запуск

```bash
./mvnw spring-boot:run
```

По умолчанию сервис стартует на `http://localhost:8080`.

---

## Пример 1 — STEP (пошаговый прогон)

### Шаг 1: создать сессию

- Method: `POST`
- URL: `http://localhost:8080/executions`
- Header: `Content-Type: application/json`

Body (raw JSON):

```json
{
  "language": "python",
  "code": "n = int(input())\n\na, b = 0, 1\nfor _ in range(n):\n    a, b = b, a + b\nprint(a)\n",
  "limits": {
    "timeLimitMs": 1500,
    "memoryLimitMb": 256,
    "outputLimitKb": 256
  },
  "executionPolicy": {
    "networkDisabled": true,
    "readOnlyFs": true
  },
  "metadata": {
    "taskId": "fibonacci"
  }
}
```

Ответ `201` вернёт объект `Execution`, например:

```json
{
  "id": "<SESSION_UUID>",
  "status": "RUNNING",
  "language": "python",
  "durationMs": null,
  "peakMemoryMb": null,
  "tests": null,
  "metadata": { "taskId": "fibonacci" }
}
```

Сохрани `id` — он нужен для следующих запросов.

### Шаг 2: прогнать тест №1

- Method: `POST`
- URL: `http://localhost:8080/executions/<SESSION_UUID>/tests`
- Header: `Content-Type: application/json`

```json
{ "id": "n0", "input": "0\n" }
```

Ответ `200` (пример):

```json
{
  "testId": "n0",
  "outcome": "OK",
  "exitCode": 0,
  "stdout": { "data": "0\n", "truncated": false },
  "stderr": { "data": "", "truncated": false },
  "durationMs": 5,
  "memoryMb": null
}
```

### Шаг 3: прогнать тест №2 (и т.д.)

```http
POST http://localhost:8080/executions/<SESSION_UUID>/tests
```

```json
{ "id": "n10", "input": "10\n" }
```

### Шаг 4: получить агрегированный результат сессии

- Method: `GET`
- URL: `http://localhost:8080/executions/<SESSION_UUID>`

Ответ `200` содержит уже выполненные тесты + агрегаты:

- `durationMs` — сумма `durationMs` по уже выполненным тестам
- `peakMemoryMb` — пиковая память (если сбор метрики реализован)

### Шаг 5: завершить сессию

- Method: `POST`
- URL: `http://localhost:8080/executions/<SESSION_UUID>/cancel`

---

## Пример 2 — BATCH (пакетный прогон)

- Method: `POST`
- URL: `http://localhost:8080/executions/batch`
- Header: `Content-Type: application/json`

```json
{
  "language": "python",
  "code": "n = int(input())\n\na, b = 0, 1\nfor _ in range(n):\n    a, b = b, a + b\nprint(a)\n",
  "tests": [
    { "id": "n0", "input": "0\n" },
    { "id": "n1", "input": "1\n" },
    { "id": "n5", "input": "5\n" },
    { "id": "n10", "input": "10\n" },
    { "id": "n20", "input": "20\n" }
  ],
  "limits": {
    "timeLimitMs": 1500,
    "memoryLimitMb": 256,
    "outputLimitKb": 256
  },
  "executionPolicy": {
    "networkDisabled": true,
    "readOnlyFs": true
  },
  "metadata": {
    "taskId": "fibonacci"
  }
}
```

Ответ `201` вернёт объект `Execution` со списком `tests[]`, а также:

- `durationMs` — суммарное время выполнения (по всем тестам)
- `peakMemoryMb` — пиковая память (если сбор метрики реализован)

---

## Ответ

Сервис возвращает JSON с полями:

- `id`, `status`, `language`
- `durationMs`, `peakMemoryMb`
- `tests[]` (outcome/exitCode/stdout/stderr/durationMs/memoryMb)
- `metadata` (echo)
