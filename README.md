# sborka

**Конвенции сборки Gradle, вынесенные из девятнадцати репозиториев в один плагин.**

Координата и версия, тулчейн и пол JVM, публикация, линтер, тестовый гейт, механика KMP, нативный
сервис, настройки резолва и общий каталог версий — по одной строке в `gradle.properties` вместо
десятков строк Kotlin в каждом репозитории.

```kotlin
// settings.gradle.kts
plugins { id("ru.workinprogress.sborka.settings") version "<версия>" }

// модуль
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
}
```

---

## Зачем

Разбор девятнадцати сборок портфеля — не «многовато повторов», а список мест, где одно и то же
решение принято по-разному и никто об этом не знает:

| | |
|---|---|
| вызовов `jvmToolchain(n)` | 60 в 18 репозиториях |
| блоков репозитория reposilite | 36 в 17 |
| `useJUnitPlatform()` | 28 в 10 |
| блоков `maven-publish { }` | 18 в 11 |
| рукописных конвенций публикации | 5, и никакие две не знают одного и того же |
| обвязок ktlint | 4 разные на 13 репозиториев |
| файлов `.editorconfig` | 11 различных, ещё в 4 репозиториях нет вовсе |
| версий `gradle/actions/setup-gradle` одновременно | v4 (22 вызова), v5 (18), v6 (41) |

Разница между «пять копий одной конвенции» и «одна конвенция» здесь не в строках. Каждая из пяти
копий знала что-то, чего не знали остальные четыре: одна ставила `org.gradle.jvm.version`, вторая
переименовывала `.aar`, третья регистрировала публикацию для `kotlin("jvm")` — без которой модуль
собирается, `publish` отчитывается об успехе и не выгружает **ничего**. Собранные вместе, они стали
конвенцией, которая знает всё это сразу.

## Плагины

| id | что делает |
|---|---|
| `ru.workinprogress.sborka.settings` | репозитории с фильтрами, каталог `wip`, проверка `.editorconfig` — применяется в `settings.gradle.kts` |
| `…sborka.base` | группа, версия, тулчейн |
| `…sborka.lint` | ktlint прибитой версии, генерируемые исходники исключены |
| `…sborka.test` | JUnit Platform, читаемый лог падения, BOM — и проверка, что **каждый объявленный `@Test` был исполнен** |
| `…sborka.jvm` | `base` + `test` + `explicitApi`, `-Werror`, `jvmTarget` из пола |
| `…sborka.kmp` | то же для multiplatform — **кроме списка таргетов**: это аргумент репозитория, а не конвенция |
| `…sborka.publish` | публикация, pom из одного свойства, sources jar, атрибут пола, имя `.aar` с версией |
| `…sborka.mutation` | `mutationTest` на pitest; не висит на `check` |
| `…sborka.native-service` | имя бинаря, стейджинг под `build/native`, `writeNativeDockerfile` |

Плюс composite action `.github/actions/setup-kotlin` — Java, Gradle и кэш Kotlin/Native одним шагом
и одной версией.

## Быстрый старт

**`settings.gradle.kts`** — снапшот-репозиторий выписывается руками: `pluginManagement`
вычисляется раньше, чем применяется хоть один settings-плагин, включая этот.

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

**`gradle.properties`** — то, что раньше было кодом:

```properties
version=0.4.0
sborka.group=ru.workinprogress.mylib
sborka.repository=youndie/mylib
sborka.description=Одна строка о том, что это за библиотека
sborka.jvmToolchain=25
sborka.jvmFloor=21
```

`sborka.jvmFloor` подбирается **осознанно**: это самая старая Java, на которой потребителю можно
быть, а не та, на которой библиотеку собрали.

Пошагово — [docs/migration.md](docs/migration.md). Что читает каждый плагин и какие свойства
существуют — [docs/conventions.md](docs/conventions.md). Почему сделано именно так —
[docs/decisions.md](docs/decisions.md).

## Что публикуется

| артефакт | что это |
|---|---|
| `ru.workinprogress.sborka:conventions` | плагины проекта |
| `ru.workinprogress.sborka:settings` | плагин настроек |
| `ru.workinprogress.sborka:core` | общее для двух половин: эталонный `.editorconfig` и версия релиза |
| `ru.workinprogress.sborka:catalog` | версии, которые нескольким репозиториям приходится держать одинаковыми |

Три jar-а, а не один, и это не косметика. Gradle подбирает класслоадер по classpath: плагин настроек
и проектный плагин в одном jar-е делят загрузчик — тот, у которого Kotlin-плагина под собой нет, — и
проектный плагин падает `NoClassDefFoundError` на классе, который в его собственных зависимостях
есть. Подробности в [docs/decisions.md](docs/decisions.md).

## Проверка

```bash
./gradlew check
```

Собирает плагины, гоняет их тесты и **применяет их в отдельной сборке** — `stand/`, которая просит
плагины по id через `includeBuild`, как это будет делать репозиторий через опубликованный маркер.
Стенд публикуется в каталог на диске и читает, что туда легло: имена артефактов, версии class file
внутри jar-ов, атрибут пола в метаданных, содержимое pom. Без этого «плагин применился» и «плагин
сделал то, зачем он есть» — разные утверждения, и вторым никто не занимается.

Стенду видно не всё, и это записано честно: чего он спросить не может, находит первая же чужая
сборка — [что именно нашли миграции](docs/decisions.md#что-нашли-миграции).

## Лицензия

MIT.
