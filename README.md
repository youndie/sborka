# sborka

Конвенции сборки, общие для репозиториев `ru.workinprogress` / `io.github.youndie`: публикация,
линтер, тестовый гейт, механика KMP, нативный сервис, настройки резолва и общий каталог версий.

Появился из разбора девятнадцати Gradle-сборок портфеля. Что там нашлось:

| | |
|---|---|
| объявлений таргетов (`jvm()`, `linuxX64()`, `ios*`, `macos*`) | 243 в 14 репозиториях |
| вызовов `jvmToolchain(n)` | 60 в 18 |
| блоков репозитория reposilite | 36 в 17 |
| `useJUnitPlatform()` | 28 в 10 |
| блоков `maven-publish { }` | 18 в 11 |
| рукописных конвенций публикации | 5, и никакие две не знают одного и того же |
| обвязок ktlint | 4 разные на 13 репозиториев |
| файлов `.editorconfig` | 11 различных, ещё в 4 репозиториях нет вовсе |
| версий `gradle/actions/setup-gradle` одновременно | v4 (22 вызова), v5 (18), v6 (41) |

Полный разбор — [docs/inventory.md](docs/inventory.md).

## Что публикуется

| артефакт | что это |
|---|---|
| `ru.workinprogress.sborka:conventions` | плагины проекта: `base`, `lint`, `test`, `publish`, `jvm`, `kmp`, `mutation`, `native-service` |
| `ru.workinprogress.sborka:settings` | плагин настроек: репозитории с фильтрами, каталог `wip`, проверка `.editorconfig` |
| `ru.workinprogress.sborka:core` | общий для двух половин: эталонный `.editorconfig` и версия релиза |
| `ru.workinprogress.sborka:catalog` | каталог версий, которые нескольким репозиториям приходится держать одинаковыми |

Три jar-а, а не один, и это не косметика: Gradle подбирает класслоадер по classpath, и плагин настроек
с проектным плагином в одном jar-е делят загрузчик — тот, у которого Kotlin-плагина под собой нет.
Подробности в [docs/decisions.md](docs/decisions.md).

## Быстрый старт

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://reposilite.kotlin.website/snapshots") {
            content { includeGroupByRegex("ru\\.workinprogress.*") }
        }
    }
}

plugins {
    id("ru.workinprogress.sborka.settings") version "<версия>"
}
```

`gradle.properties`:

```properties
version=0.4.0
sborka.group=ru.workinprogress.myrepo
sborka.repository=youndie/myrepo
sborka.description=Одна строка о том, что это за библиотека
sborka.jvmToolchain=25
sborka.jvmFloor=21
```

Модуль:

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
}

kotlin {
    jvm()
    linuxX64()
}
```

Пошагово — [docs/migration.md](docs/migration.md). Что делает каждый плагин и какие свойства читает —
[docs/conventions.md](docs/conventions.md).

## Проверка

```bash
./gradlew check
```

Собирает плагины, гоняет их тесты и **применяет их в отдельной сборке** — `stand/`, которая просит
плагины по id через `includeBuild`, как это будет делать репозиторий через опубликованный маркер.
Стенд публикуется в каталог и читает, что туда легло: без этого «плагин применился» и «плагин сделал
то, зачем он есть» — разные утверждения, и вторым никто не занимается.

Стенд нашёл ровно одну ошибку, невидимую из компиляции, — тот самый общий класслоадер.
