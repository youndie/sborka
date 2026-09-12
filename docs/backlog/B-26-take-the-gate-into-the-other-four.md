---
id: B-26
title: "Take the platform gate into the other four subjects, metrik first"
status: done
priority: P1
size: M
stage: stage-5-parity-gate
blocked_by: [B-25]
---

# B-26 — Take the platform gate into the other four subjects, metrik first

tracy runs `parityCheck` on every target it declares ([B-25](B-25-wire-the-probe-into-a-build.md)).
Four subjects do not: metrik, katcher, shildik and razves
([research-parity §1.1](../research/research-parity.md)). Each needs `sborka.parity`, a
`parityProbe { }` block and one test — and each has a shape that makes it a different question from
the last.

- **metrik first, and not because it is next alphabetically.** Its `:server` is the reason the TLS
  assertion exists: `ktor-client-curl` on native and `ktor-client-cio` on the JVM through
  `expect`/`actual`, because CIO's native half has no TLS and outbound notifications silently never
  left the process for months. It is the only subject here that can pass a real `tlsRequest`, so it
  is the only one that turns that assertion from written into exercised.
- **katcher resolves one native target from `os.name`.** Whatever host builds it decides which
  native half the gate probed, so a green run on a Mac says nothing about the Linux binary that
  ships. Either the gate runs where the image is built, or the item says plainly that it does not.
- **shildik ships `linuxX64` alone** (`:distribution`), while its `:server` declares `macosArm64`
  too. Probing a target nothing deploys is not wrong, but the report should not let anyone read it
  as coverage of the thing in production.
- **razves is a CLI whose `jvm` target publishes nothing.** The probe still applies — a CLI opens
  files and reads the environment — but the DNS half asserts something it does not do, and an
  assertion a program would never make is a test nobody will keep.
- **Rejected: one pull request for all four.** They share a mechanism and nothing else; a red run in
  the fourth would arrive with three unrelated changes attached, and the shapes above are exactly
  the kind of thing that gets skimmed past in a big diff.
- **Does not cover** moving the native-only suites, which is [B-23](B-23-move-the-rest-of-the-native-only-suites.md)
  — metrik and katcher have their server tests in `nativeTest`, so their probe runs on one target
  until that lands.

- AC: each of the four runs `parityCheck` on the targets it declares, and each names in its own
  report what that does not cover — metrik's including a real TLS request through the engine it
  ships.
- Anchors: `metrik/server/build.gradle.kts`, `katcher/server/build.gradle.kts`,
  `shildik/server/build.gradle.kts`, `razves/cli/build.gradle.kts`,
  `platform-probe/src/commonMain/kotlin/io/github/youndie/sborka/probe/PlatformProbe.kt`

## Two of four done, one refused, one blocked — 2026-09-12

| | |
|---|---|
| katcher | [#54](https://github.com/youndie/katcher/pull/54), `38703c4` |
| shildik | [#47](https://github.com/youndie/shildik/pull/47), `c365076` |
| razves | **not taking it** — see below |
| metrik | blocked, see below |

**katcher: the gate could not name what it probed, and the report could.** Its summary line says
`jvm, native`, because it lists Gradle targets and this module's native one is literally called
`native` — the target is resolved from `os.name`. The probe's own report says
`platform probe on native LINUX X64`, read out of `Platform` at run time. So the printing of the
report is not decoration: without it a green run on a Mac and a green run on Linux look identical,
and only one of them says anything about the binary that ships.

**shildik: three targets probed, one deployed.** `:server` declares `jvm`, `macosArm64` and
`linuxX64`; both distributions declare `linuxX64` alone. A green line for the other two is worth
having — the JVM target is where the code is developed — and is not coverage of production, and the
test says so where a reader of a green run will see it.

**razves refuses the gate, and the refusal is the item's own prediction coming true.** Checked what
it actually touches: files and `getenv`, yes; sockets and coroutines, no — it has **neither a ktor
nor a coroutines dependency at all**. Of the probe's five assertions, two apply. The other three
would be tests asserting things the program never does, which this item said in advance nobody would
keep; and taking the dependency pulls ktor and coroutines onto the test classpath of a tool whose
stated reason for existing is serving people who ship a single binary.

What would change that is a split in `platform-probe`: the file and environment assertions need no
dependency and could be usable without the networking ones. Worth doing only if a second
dependency-free consumer appears — one is not a pattern.

**metrik is blocked and not by anything technical.** Its branch `parity/metrik-findings` carries two
commits of mine (B-10, B-11) and one that is not mine — `7d898c7`, "flush the open window when the
agent stops", `Refs: #29`, written 2026-09-12 09:55. Pushing it inside my pull request or rebasing
over it is not mine to do. metrik is also the subject that matters most here: it is the only one that
can pass a real `tlsRequest`, since `ktor-client-curl` on native and `ktor-client-cio` on the JVM is
exactly what the TLS assertion exists for.

## Done, 2026-09-12 — three took it, one refused, and the TLS half came back out

metrik [#32](https://github.com/youndie/metrik/pull/32), `700caf4`. With katcher and shildik, three
of the four run `parityCheck`; razves refuses it for the reason recorded above.

**The TLS assertion was wired into metrik, run, and removed — and that is the item's real result.**
metrik is the only subject that can hand the probe a real engine, so if the assertion cannot be made
to mean something there, it cannot anywhere in this portfolio. On `linuxX64`:

```
reach-over-tls(https://localhost:56093): IllegalStateException: Connection failed …
Reason: SSL connect error (CURLE_SSL_CONNECT_ERROR)
```

Curl reports its **ordinary** handshake failure as `IllegalStateException`, which is the same type
ktor uses to refuse TLS outright. So no exception type separates *reached the TLS layer and failed*
from *has no TLS at all*; only the message text does, and a gate resting on a sentence passes the day
the sentence is reworded. The hermetic shape is not available, and the report saying "TLS uncovered"
is true where a green line would have proved nothing.

**What would cover it is a stand with a certificate** — `parityProbe` pointed at a real service.
That is the one thing this whole strand keeps arriving back at, and it is the same gap named in D1's
price: this gate does not exercise the real socket, DNS or TLS path, which is where all the
divergences that cost anything actually were.

Two smaller things the rollout paid for, both recorded where they bit: a listener that accepts and
never speaks makes a TLS handshake wait **forever** — a ten-minute hang that reads as a slow build;
and `parityCheck`'s summary names Gradle targets, so on katcher, whose native target is called
`native`, only the probe's own report says which platform it was.
