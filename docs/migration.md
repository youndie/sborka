# Перевод репозитория на sborka

Порядок неслучайный: сначала то, что нельзя проверить иначе как публикацией, потом то, что проверяется
локально. Каждый шаг отдельным коммитом — иначе непонятно, что именно поменяло поведение.

Миграция ни одного репозитория пока не сделана. Это инструкция, а не отчёт.

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

`version` — голова; хвост приклеивает CI через `-PVERSION`. Если репозиторий держал голову под своим
ключом (`<имя>.version`), ключ переезжает в `version` — иначе номер живёт в двух местах, а шаг CI,
который его собирает, читает не тот.

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
Заодно приводит `setup-gradle` к одной версии: сейчас в портфеле одновременно живут v4 (22 вызова),
v5 (18) и v6 (41).

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

## Чего миграция не трогает

- **Списки таргетов.** См. [conventions.md](conventions.md), `sborka.kmp`.
- **Профили JVM-аргументов, размеры кучи, пороги.** Это измерения конкретного репозитория.
- **Dockerfile.** `writeNativeDockerfile` даёт отправную точку, дальше файл репозиторный.
- **Комментарии с обоснованиями.** Их надо переносить, а не удалять. Централизация, уносящая довод из
  того места, где решение принималось, — это потеря, а не уборка.
