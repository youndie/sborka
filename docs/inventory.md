# Разбор сборок портфеля

Снято 29 августа 2026 года по девятнадцати Gradle-репозиториям профиля: appframe, bochka, booblik,
katcher, kobweb-ssr-experiment, konekt, kompot, kvadrant-ui, mani, metrik, mongkn, petich, proba,
s3kn, shildik, smtpkn, telek, tracy, viddik. Это исходные данные, из которых выведен состав sborka.

## Где конвенции уже были

Выделены в 6 репозиториях из 19, и каждый раз заново:

| репозиторий | где | что внутри |
|---|---|---|
| smtpkn | `build-logic` | `smtp.kmp`, `smtp.jvm`, `smtp.publish`, `smtp.example`, `smtp.kmp.web`, `smtp.kmp.native-host` |
| s3kn | `build-logic` | `s3kn.kmp`, `s3kn.publish`, `s3kn.example` |
| konekt | `build-logic` | `konekt.base`, `konekt.multiplatform`, `konekt.jvm`, `DeclaredTests.kt` |
| telek | `buildSrc` | `kotlin-jvm`, `kotlin-multiplatform` |
| kompot / petich / viddik | `buildSrc` | только `*.publishing` (+ `JvmFloor.kt` в двух) |

Остальные 13 держат то же самое в `subprojects { }` корневого скрипта или построчно в модулях.

## Сколько раз повторено

По всем `.gradle.kts` девятнадцати репозиториев:

| | всего | репозиториев |
|---|---|---|
| объявление таргетов (`jvm()`, `linuxX64()`, `ios*`, `macos*`) | 243 | 14 |
| `jvmToolchain(n)` | 60 | 18 |
| блок репозитория reposilite | 36 | 17 |
| `useJUnitPlatform()` | 28 | 10 |
| `maven-publish { }` | 18 | 11 |
| `allWarningsAsErrors` | 18 | 3 |
| ksp-обвязка | 14 | 4 |
| `pom { }` | 8 | 7 |

## Публикация: пять копий, знающих разное

- **kompot** — группа в конвенции, `-PVERSION` на *проектную* версию, регистрация публикации для
  `kotlin("jvm")` и `java-platform`, атрибут `org.gradle.jvm.version` для jvm-вариантов KMP, имя
  `.aar` с версией.
- **petich** — подмножество: нет группы, нет jvm-атрибута, нет `.aar`.
- **viddik** — 32 строки, ничего из перечисленного; регистрация публикации для `kotlin("jvm")` лежит в
  самом модуле `viddik-processor`, `group = "ru.workinprogress"` повторена в каждом из четырёх
  модулей. Пятый модуль на `kotlin("jvm")` опубликует ноль байт с зелёным exit-кодом.
- **s3kn** — обычный `maven-publish`, без jvm-атрибута.
- **smtpkn** — единственный на vanniktech + Central Portal.

Группы разъехались: `io.github.youndie`, `io.github.youndie.bochka`, `io.github.youndie.booblik`,
`io.github.youndie.mongkn`, `ru.workinprogress`, `ru.workinprogress.shildik` / `.metrik` / `.tracy` /
`.katcher` / `.mani`, `io.konekt`, `dev.youndie.proba`.

## Линтер: четыре обвязки на 13 репозиториев

| способ | репозитории |
|---|---|
| `subprojects { apply(...) }` | booblik, kvadrant-ui, metrik, mongkn, tracy, mani, shildik, katcher |
| `alias` в корне без применения | telek |
| ktlint CLI через `JavaExec` | s3kn, smtpkn (два почти идентичных файла) |
| ничего | viddik, kompot, petich, appframe, proba |

Ключ каталога `ktlint` означает два разных числа: версию **инструмента** `1.8.0` в девяти
репозиториях и версию **плагина** `14.2.0` в четырёх (katcher, viddik, shildik, telek). В mani третий
вариант — `ktlintTool`.

`.editorconfig`: 11 различных файлов; нет вовсе в tracy, kompot, petich, appframe, proba.

## Каталоги версий

Согласованы дисциплиной: `kotlin = 2.4.10` и `coroutines = 1.11.0` **во всех восемнадцати** каталогах.
Разъехалось:

| | |
|---|---|
| ktor | 3.5.2 (7) против 3.4.0 (proba) |
| ksp | 2.3.11 (5) против 2.3.10 (2) |
| agp | 9.3.0 / 9.3.1 / 9.3.2 |
| compose | 1.11.1 (3) против 1.12.0 (2) |
| junit | 6.1.3 (bochka) против 4.13.2 (mani) |

Литерал `io.ktor:ktor-version-catalog:3.5.2` вписан в три `settings.gradle.kts` (katcher, metrik,
shildik).

## Прочее, что нашлось

- **Нет `gradle.properties` вообще** — bochka, booblik, mongkn: ни build cache, ни configuration
  cache, ни parallel. У остальных шестнадцати — восемь разных значений `org.gradle.jvmargs`.
- **reposilite без фильтра контента** — mani, в обоих блоках; konekt и kvadrant-ui это уже починили.
- **Два почти одинаковых Dockerfile** — tracy/server и katcher/server отличаются одним сегментом пути
  (`bin/native/` против `bin/linuxX64/`): тот же `gradle:9.7.0-jdk25-noble` → `distroless/cc-debian13`,
  тот же ручной перенос `libcrypt.so.1`.
- **`gradle/actions/setup-gradle` в трёх версиях одновременно** — v4 (22 вызова), v5 (18), v6 (41).
- **Шаг «Determine version»** (`grep <name>.version gradle.properties | cut -d= -f2` + `run_number`)
  скопирован дословно в kompot, petich, viddik.
- **`youndie/proba`** уже вынесен в переиспользуемый action и используется в 2 репозиториях из 11
  публикующих.
- **Проверка выполненности `@Test`** (`DeclaredTests`) есть только в konekt. Задача `mutationTest` —
  только в bochka. Обе полностью общие.
