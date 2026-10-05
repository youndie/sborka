# Перевод репозитория на sborka

Порядок неслучайный: сначала то, что нельзя проверить иначе как публикацией, потом то, что проверяется
локально. Каждый шаг отдельным коммитом — иначе непонятно, что именно поменяло поведение.

Это инструкция, а не отчёт. Сколько репозиториев уже переведено, здесь не пишется: число устаревало бы
с каждой следующей миграцией (первая версия этой строки говорила «ни одного»). Кто на sborka и на
какой версии, отвечают сами репозитории:

```bash
gh repo list youndie --limit 300 --no-archived --json nameWithOwner --jq '.[].nameWithOwner' |
  while read -r r; do
    gh api -H 'Accept: application/vnd.github.raw' "repos/$r/contents/settings.gradle.kts" 2>/dev/null |
      grep -m1 'io.github.youndie.sborka' | grep -oE '[0-9]+(\.[0-9]+){3}' | sed "s|^|$r |"
  done
```

Строка на репозиторий: имя и версия из `settings.gradle.kts` — у плагина настроек или, если
репозиторий берёт только каталог, у координаты каталога. Не видно отсюда репозиториев без remote и
второго места версии, `gradle/libs.versions.toml`; число там обязано совпадать (§9).

## 0. Убедиться, что sborka опубликована

```bash
curl -s https://reposilite.kotlin.website/snapshots/io/github/youndie/sborka/conventions/maven-metadata.xml
```

Версию оттуда подставлять ниже.

## 1. `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Выписывается руками, и иначе нельзя: pluginManagement вычисляется до применения любого
        // settings-плагина — включая тот, который через него же и достаётся.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content {
                // Обе группы. Портфель переезжает на `io.github.youndie`, и sborka уже там —
                // маркер плагина и jar за ним лежат под новой. Старую держат версии библиотек,
                // выложенные до переезда: они с сервера никуда не делись и резолвятся как прежде.
                includeGroupByRegex("io\\.github\\.youndie.*")
                includeGroupByRegex("ru\\.workinprogress.*")
            }
        }
    }
}

plugins {
    id("io.github.youndie.sborka.settings") version "<версия>"
}
```

Из `dependencyResolutionManagement` после этого убирается всё, что плагин уже объявляет: `google()` с
фильтрами, `mavenCentral()`, снапшот-репозиторий с фильтрами. Остаётся то, что своё, — например
собственный каталог `libs`.

**Проверить:** `./gradlew help`. Если `repositoriesMode` теперь `FAIL_ON_PROJECT_REPOS`, а какой-то
модуль объявлял репозиторий у себя, сборка упадёт здесь и назовёт модуль. Это находка, а не помеха:
модуль, объявляющий репозиторий себе, заставляет сборку резолвить одну координату из разных мест в
зависимости от того, кто спросил.

## 2. `gradle.properties`

```properties
version=0.4.0
sborka.group=io.github.youndie.myrepo
sborka.repository=youndie/myrepo
sborka.description=Одна строка о том, что это за библиотека
sborka.inceptionYear=2026
sborka.jvmToolchain=25
sborka.jvmFloor=21
sborka.junitVersion=6.1.3
```

`version` — голова, номер следующего релиза (§7); хвост приклеивает CI через `-PVERSION`. Если
репозиторий держал голову под своим ключом (`<имя>.version`), ключ переезжает в `version` — иначе
номер живёт в двух местах, а шаг CI, который его собирает, читает не тот.

`sborka.jvmFloor` подобрать **осознанно**, а не переписать из тулчейна. Это самая старая Java, на
которой потребителю можно быть; поднятая не подумав, она отбирает библиотеку у всех, кто ниже.

## 3. `.editorconfig`

```bash
./gradlew updateEditorconfig
git diff .editorconfig
```

Диф прочитать. Если в репозитории было что-то своё и оно нужно — не возвращать его руками, а либо
добавить в эталон в sborka (если это общее), либо поставить `sborka.editorconfig=custom` (если это
правда только про этот репозиторий) и написать рядом, почему.

Дальше `./gradlew ktlintFormat` перелопатит форматирование. **Отдельным коммитом**, иначе следующий
шаг утонет в переносах строк.

После него — компиляция, а не только `ktlintCheck`: форматтер умеет превратить `else -> Unit` в
неиспользуемое выражение, которое `allWarningsAsErrors` делает ошибкой. Ловушка и лечение
(`else -> {}`) — в [conventions.md](conventions.md), раздел `sborka.lint`.

## 4. Модули

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
    id("io.github.youndie.sborka.publish")
}

kotlin {
    // Остаётся здесь: список таргетов — решение модуля, и у него есть причина.
    jvm()
    linuxX64()
}
```

Убирается: `jvmToolchain(n)`, `explicitApi()`, `allWarningsAsErrors`, `useJUnitPlatform()`,
`testLogging`, `group =`, `version =`, блок `publishing { repositories { ... reposilite ... } }`, блок
`pom { }`, ручная регистрация `MavenPublication` для `kotlin("jvm")`.

Остаётся: таргеты, зависимости, всё, что про этот модуль и ни про какой другой.

## 5. Корень

`allprojects { group = ... }` и `subprojects { apply(plugin = "...ktlint") }` уходят: и то и другое
теперь у конвенций. Комментарии, объяснявшие *почему* там стояло именно это число, — не выбрасывать,
а перенести туда, где число живёт теперь: в `gradle.properties` рядом со свойством или, если довод
общий для портфеля, в sborka.

## 6. Проверить публикацию по-настоящему

```bash
./gradlew publishAllPublicationsToWipRepository -PVERSION=0.4.0-migration1
```

и посмотреть, **что легло на сервер**:

```bash
curl -s https://reposilite.kotlin.website/snapshots/<group-путь>/<модуль>/0.4.0-migration1/ | head
```

Проверять именно так, а не по exit-коду: модуль без зарегистрированной публикации собирается, его
`publish` отчитывается об успехе и не выгружает ничего, и Gradle это от успеха не отличает.

Ещё лучше — поставить эту проверку в CI, чтобы её делал не человек и не один раз.

## 7. CI

```yaml
      - uses: youndie/sborka/.github/actions/setup-kotlin@main
        with:
          konan-cache: "true"

      - id: ver
        uses: youndie/sborka/.github/actions/determine-version@main
```

Заменяет `setup-java` + `setup-gradle` + кэш `~/.konan` + скопированный шаг «Determine version».
`determine-version` приписывает к голове номер прогона, поэтому голова `version` в
`gradle.properties` должна быть простым `X.Y.Z` следующего релиза. `-SNAPSHOT` он отвергает: иначе
вышло бы `0.1.0-SNAPSHOT.1`, как у первой публикации kontainer (#119). Из семи репозиториев с
головой `-SNAPSHOT` (bochka, booblik, kachok, mongkn, s3kn, smtpkn, tracy) переезд на него каждому
начнётся с этой строки.
Заодно приводит `setup-gradle` к одной версии: сейчас в портфеле одновременно живут v4 (22 вызова),
v5 (18) и v6 (41).

Голова — номер **следующего** релиза: сразу после тега `vX.Y.Z` она поднимается до `X.Y.(Z+1)`,
иначе снапшоты `X.Y.Z.N` сортируются выше релиза. Голову, для которой тег `v<голова>` уже есть,
`determine-version` отказывает: версию не пишет, называет тег в выходе `tagged`, и публикация
красная, пока голову не поднимут ([conventions.md](conventions.md), `sborka.base`). Проверить свою
голову до переезда:
`git ls-remote --tags origin "refs/tags/v$(grep -E '^version=' gradle.properties | cut -d= -f2)"` —
пустой вывод значит, что тега нет.

А если репозиторий выкладывает снапшоты так же, как это делали telek, viddik и petich, — не шаги, а
весь воркфлоу:

```yaml
jobs:
  publish:
    uses: youndie/sborka/.github/workflows/publish-wip.yaml@main
    with:
      tasks: build publishAllPublicationsToWipRepository
      # konan-cache / dokka / test-results / check / runner — по надобности,
      # coordinates: список `group:artifact` (без версии) для job'ы proba
    secrets:
      REPOSILITE_USER: ${{ secrets.REPOSILITE_USER }}
      REPOSILITE_SECRET: ${{ secrets.REPOSILITE_SECRET }}
```

**Секреты перечислять поимённо, а не `secrets: inherit`.** Вызов передаёт их в воркфлоу, лежащий в
чужом репозитории; `inherit` передаёт туда все, какие есть в вызывающем, — включая те, к публикации
отношения не имеющие.

**`permissions` объявлять у вызывающей job'ы.** Вызванная не может получить больше, чем выдано
вызывающей, поэтому `contents: write` (его просит только выкладка dokka на Pages) стоит там, а не в
самом `publish-wip.yaml`: объяви его там — и упадёт каждый вызывающий, который выдаёт `contents:
read`.

## 8. Renovate

```json
{
  "$schema": "https://docs.renovatebot.com/renovate-schema.json",
  "extends": ["github>youndie/sborka"]
}
```

Всё, что девятнадцать конфигов писали по отдельности и одинаково, лежит в `default.json` sborka:
расписание, группы `kotlin` / `kotlinx` / `sborka` / `ci actions`, запрет на
`kotlinx-datetime:*-compat` (это новая версия, собранная против СТАРОГО API: сортируется как более
новая, апгрейдом не является) и мажоры человеку.

**Automerge — вторым пресетом и не везде:**

```json
"extends": ["github>youndie/sborka", "github>youndie/sborka:automerge-harness"]
```

Порядок обязателен: правила склеиваются в порядке перечисления пресетов, и правило про automerge
должно оказаться ПОСЛЕ базового «мажор получает человека», иначе оно не выиграет. А перед тем, как
его подключать, — посмотреть, что вообще запускается на `pull_request` в этом репозитории.
В mongkn автотриггеры сборки закомментированы, там зелёный PR означает, что никто ничего не
запускал, и automerge превратил бы это в «уже и не запустит».

Метки раннеров (`runs-on:`, датасорс `github-runners`) пресет сам НЕ вливает: метка решает ОС и
системные пакеты, а workflow, которому она важнее всего, — публикация — на `pull_request` не
запускается (mongkn#17, sborka#126). Такое обновление приходит отдельной группой `ci runners` и ждёт
человека; глушить `github-runners` у себя ради этого больше не нужно. `verifyHarnessPreset` в `check`
sborka падает, если пресет снова отдаст метку automerge-у.

**Что переопределять у себя, а не в пресете:** свои образы и чарты (`helm-values` на собственный
образ обычно надо глушить), группы, которые есть только у этого репозитория, и всё, у чего причина
местная. И помнить про склейку: `packageRules` из пресета конкатенируются со своими (пресет первым,
последнее совпадение выигрывает), а вот `ignorePaths` и `labels` **заменяются целиком** — добавляя
один путь, повторить и базовые два.

## 9. Новая голова sborka

Версия sborka — голова и номер прогона: `0.5.0.116` — голова `0.5.0`, 116-я публикация. Голова
(`sborka.version` в `gradle.properties` sborka) поднимается, когда опубликованное меняет вердикт
`check` у потребителя, который у себя ничего не трогал, или требует от него правки. Номер прогона
растёт с каждой публикацией из `main`, и на нём тоже приезжает своё — например, каталог `wip` с новым
стеком (#113 внутри 0.4), — поэтому «хвост тот же» ничего не обещает, а таблица ниже обещает только
про голову.

Версия у потребителя живёт в двух строках, и число в них одно:

- `settings.gradle.kts`: `id("io.github.youndie.sborka.settings") version "X"` — она же выбирает
  каталог `wip` (§1);
- `gradle/libs.versions.toml`: `sborka = "X"`.

Renovate (§8) поднимает обе одной PR группы `sborka`; в ней проверить, что число одно.

| голова | где | что меняется у потребителя |
|---|---|---|
| 0.2 | #14 | приходит kapkan: правила ktlint в `sborka.lint` и отчёт `kapkanJoins` |
| 0.3 | #23 | группа и id плагинов `ru.workinprogress.sborka` → `io.github.youndie.sborka`: правится каждая строка `plugins { id(...) }` |
| 0.4 | #37 | правило kapkan `cancellation-swallowed` |
| 0.5 | #115 | правило kapkan `native-identifier` |

### 0.4 → 0.5

С `0.4.0.111` — на ней стоял портфель, когда вышла 0.5, — меняется ровно одно: вердикт нового правила
`kapkan:native-identifier`. Это имя в обратных кавычках с символом, который не принимает
Kotlin/Native, в сорс-сете, который натив компилирует или может компилировать; что судится, а что нет,
— [kapkan.md](kapkan.md) §12. Судит только в модулях с `sborka.lint` и только через `ktlintCheck`, то
есть в `check` и `build`, но не в `jvmTest`. Ответ на находку — переименование, а в `commonTest`
модуля без нативного таргета — подавление с причиной ([conventions.md](conventions.md), раздел
`sborka.lint`).

Остальное, что вошло между 0.4.0.111 и 0.5.0, потребителя не задевает:

- **Каталог `wip` тот же.** Опубликованные `catalog-0.4.0.111.toml` и `catalog-0.5.0.116.toml`
  совпадают байт в байт: компилятор, KSP, AGP, CMP и Ktor после бампа прежние.
- **`sborka.kspHandWired`** (#114) — выход модуля из проводки KSP по пути. Модуль, не названный в
  этом свойстве, ведёт себя как раньше.
- **`determine-version` отказывает голове `-SNAPSHOT`** (#125), **у прогонов `central` и `portal`
  новые заголовки** (#124). Это экшен и воркфлоу, их берут по `@main`, а не по версии: отказ действует
  у всех с момента влития, независимо от пина, а заголовки видны только во вкладке Actions самой sborka.
- **Пресеты Renovate** (`default.json`, `automerge-harness.json`) тоже берутся с `main`, а не по
  версии, и в 0.5 не менялись.

Цену бампа видно до влития: поднять обе строки и прогнать `./gradlew ktlintCheck` — каждая находка
называет правило и сорс-сет.

## Чего миграция не трогает

- **Списки таргетов.** См. [conventions.md](conventions.md), `sborka.kmp`.
- **Профили JVM-аргументов, размеры кучи, пороги.** Это измерения конкретного репозитория.
  Одно с ними связано: `sborka.base` линкует Kotlin/Wasm и Kotlin/JS по одной на сборку
  ([conventions.md](conventions.md), `sborka.base`), и куча демона Kotlin, поднятая ради параллельных
  линковок (`kotlin.daemon.jvmargs`), после бампа может оказаться лишней. Возвращать прежний размер —
  только после холодных сборок без кэша (`--no-build-cache`) на нём: тёплая линковок не запускает. Своя очередь на тот же тип задачи (как в kompot#183)
  становится повтором: линковка тогда ждёт оба слота, результат тот же, и её можно удалить.
  Выключатель — `sborka.serializeWebLinks=false`.
- **Dockerfile.** `writeNativeDockerfile` даёт отправную точку, дальше файл репозиторный.
- **Комментарии с обоснованиями.** Их надо переносить, а не удалять. Централизация, уносящая довод из
  того места, где решение принималось, — это потеря, а не уборка.
