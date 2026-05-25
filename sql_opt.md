# Проверки SQL и OPT_BATCH

## Запуск локально или на VPS

Требования:

- Docker установлен и запущен.
- Сервис имеет доступ к `/var/run/docker.sock`.
- Образы, используемые executor: `python:3.12-alpine`, `postgres:16-alpine`.
- Для SQL-проверок отдельная установка PostgreSQL не нужна; SQL выполняется в одноразовых Docker-контейнерах.
  docker network create studybytes_backend_net

```bash
cd CodeExecutorService
docker compose up --build -d
docker compose logs code-executor-service --tail 100
```

Если сеть уже существует, `docker network create` может вывести ошибку; просто продолжайте с compose.

Параметры пула тёплых Python-контейнеров по умолчанию:

```env
EXECUTOR_PYTHON_WARM_POOL_ENABLED=true
EXECUTOR_PYTHON_WARM_POOL_SIZE=3
EXECUTOR_PYTHON_WARM_POOL_MEMORY_LIMIT_MB=256
EXECUTOR_PYTHON_WARM_POOL_ACQUIRE_TIMEOUT_MS=30000
```

Проверки health:

```http
GET http://localhost:8084/health
GET http://localhost:8084/ready
```

## SQL BATCH

Метод: `POST`

URL:

```http
http://localhost:8084/executions/batch
```

Заголовки:

```http
Content-Type: application/json
```

Тело:

```json
{
  "language": "postgresql",
  "code": "select name from users order by id;",
  "tests": [
    {
      "id": "users_basic",
      "input": "create table users (id int primary key, name text not null);\ninsert into users (id, name) values (2, 'Bob'), (1, 'Ann');",
      "timeoutMs": 5000
    },
    {
      "id": "users_more_rows",
      "input": "create table users (id int primary key, name text not null);\ninsert into users (id, name) values (3, 'Cara'), (1, 'Ann'), (2, 'Bob');",
      "timeoutMs": 5000
    }
  ],
  "limits": {
    "timeLimitMs": 5000,
    "memoryLimitMb": 256,
    "outputLimitKb": 64
  },
  "executionPolicy": {
    "networkDisabled": true,
    "readOnlyFs": true
  },
  "metadata": {
    "taskId": "sql-select-users"
  }
}
```

Ожидаемые важные поля ответа:

```json
{
  "status": "FINISHED",
  "language": "postgresql",
  "tests": [
    {
      "testId": "users_basic",
      "outcome": "OK",
      "stdout": {
        "data": "Ann\nBob\n"
      }
    },
    {
      "testId": "users_more_rows",
      "outcome": "OK",
      "stdout": {
        "data": "Ann\nBob\nCara\n"
      }
    }
  ]
}
```

Для многоколоночного SQL-вывода колонки разделяются табуляцией. Пример stdout:

```text
1	Ann
2	Bob
```

Executor не сравнивает `expectedOutput`; LearningService по-прежнему сравнивает stdout с expected output из CourseService.

## Проверка runtime-ошибки SQL

```json
{
  "language": "sql",
  "code": "select missing_column from users;",
  "tests": [
    {
      "id": "bad_query",
      "input": "create table users (id int primary key, name text not null);\ninsert into users (id, name) values (1, 'Ann');",
      "timeoutMs": 5000
    }
  ],
  "limits": {
    "timeLimitMs": 5000,
    "memoryLimitMb": 256,
    "outputLimitKb": 64
  },
  "executionPolicy": {
    "networkDisabled": true,
    "readOnlyFs": true
  },
  "metadata": {
    "taskId": "sql-error"
  }
}
```

Ожидаемый результат для теста: `RUNTIME_ERROR`.

## Python BATCH default warm path

LearningService can keep calling the normal batch endpoint. For `language=python`, `/executions/batch` now uses the warm-container OPT_BATCH implementation by default.

Method: `POST`

URL:

```http
http://localhost:8084/executions/batch
```

Headers:

```http
Content-Type: application/json
```

Body:

```json
{
  "language": "python",
  "code": "n = int(input())\nprint(n * n)",
  "tests": [
    {
      "id": "square_2",
      "input": "2\n",
      "timeoutMs": 1500
    },
    {
      "id": "square_9",
      "input": "9\n",
      "timeoutMs": 1500
    }
  ],
  "limits": {
    "timeLimitMs": 1500,
    "memoryLimitMb": 256,
    "outputLimitKb": 64
  },
  "executionPolicy": {
    "networkDisabled": true,
    "readOnlyFs": true
  },
  "metadata": {
    "taskId": "python-default-warm-square"
  }
}
```

Expected important response fields:

```json
{
  "status": "FINISHED",
  "language": "python",
  "tests": [
    {
      "testId": "square_2",
      "outcome": "OK",
      "stdout": {
        "data": "4\n"
      }
    },
    {
      "testId": "square_9",
      "outcome": "OK",
      "stdout": {
        "data": "81\n"
      }
    }
  ]
}
```

## Python OPT_BATCH

Метод: `POST`

URL:

```http
http://localhost:8084/executions/opt-batch
```

Заголовки:

```http
Content-Type: application/json
```

Тело:

```json
{
  "language": "python",
  "code": "n = int(input())\nprint(n * n)",
  "tests": [
    {
      "id": "square_2",
      "input": "2\n",
      "timeoutMs": 1500
    },
    {
      "id": "square_9",
      "input": "9\n",
      "timeoutMs": 1500
    }
  ],
  "limits": {
    "timeLimitMs": 1500,
    "memoryLimitMb": 256,
    "outputLimitKb": 64
  },
  "executionPolicy": {
    "networkDisabled": true,
    "readOnlyFs": true
  },
  "metadata": {
    "taskId": "python-opt-square"
  }
}
```

Ожидаемые важные поля ответа:

```json
{
  "status": "FINISHED",
  "language": "python",
  "tests": [
    {
      "testId": "square_2",
      "outcome": "OK",
      "stdout": {
        "data": "4\n"
      }
    },
    {
      "testId": "square_9",
      "outcome": "OK",
      "stdout": {
        "data": "81\n"
      }
    }
  ]
}
```

Примечания:

- `/executions/batch` всё ещё работает для обычного Python и теперь также для SQL.
- `/executions/opt-batch` работает только для Python и использует один свободный тёплый контейнер из пула.
- Если все 3 тёплых контейнера заняты, запрос ждёт до `EXECUTOR_PYTHON_WARM_POOL_ACQUIRE_TIMEOUT_MS`.
- При исходе timeout или memory-limit использованный тёплый контейнер пересоздаётся перед возвратом в пул.
