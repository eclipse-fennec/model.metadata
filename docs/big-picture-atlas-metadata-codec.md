# Big Picture: Model Atlas → Metadata Service → Codec

> The end-to-end story of how a model version travels from the Model Atlas through
> the Metadata Service to a codec run — why the current nsURI-keyed chain breaks as
> soon as two versions of the same model are live, and what each party has to change.
> This repo is the **glue code** between the Atlas and the codec; the mechanics of
> fingerprinting and artifact reuse are described in
> [Mediator, Fingerprinting & the Derived-Artifact Lifecycle](mediator-and-fingerprinting.md).

## The three parties

```mermaid
flowchart LR
    subgraph Atlas["Model Atlas (eclipse-fennec/model.atlas)"]
        GIT["Model storage<br/>(git, stage-aware:<br/>one branch = one stage)"]
        REG["DynamicEPackageRegistrationService<br/>registers one EPackage service<br/>per stage/version"]
        GIT --> REG
    end

    subgraph OSGi["OSGi service registry"]
        EP1["EPackage service<br/>nsURI = …/person/1.0<br/>stage = draft"]
        EP2["EPackage service<br/>nsURI = …/person/1.0<br/>stage = approved"]
    end

    subgraph Meta["Metadata Service (this repo — the glue)"]
        WB["MetadataServiceComponent<br/>(whiteboard: binds every<br/>EPackage service)"]
        MS["MetadataServiceImpl<br/>builds PackageMetadata:<br/>aspects, profiles, modelFingerprint"]
        WB --> MS
    end

    subgraph Codec["Codec (fennec-codec)"]
        CR["CodecResource<br/>save()/load() needs the<br/>codec profile (= its config)"]
    end

    REG --> EP1 & EP2
    EP1 & EP2 -. "bind (EPackage + service properties)" .-> WB
    CR -- "getPackageMetadata(…)" --> MS
```

Two different registries have to stay joinable here:

- the **EPackage side** — the model services the Atlas publishes (one per stage/version), and
- the **derived side** — the `PackageMetadata` (with the codec profile) that the
  Metadata Service builds from each package and that `CodecResource` serializes with.

Everything below is about **what the reference between those two sides is keyed by**.

## Why nsURI as the key breaks: an observed production failure

The scenario from
[model.atlas#156](https://github.com/eclipse-fennec/model.atlas/issues/156)
(read-only git storage e2e test): one model, `http://example.org/person/1.0`, on two
branches with **diverging content** — `draft` has `Person.name`, `approved` has
`Person.fullName`. Both are legitimately live at the same time; the Atlas registers
one `EPackage` service per stage *by design*. Then the schema file is deleted on
`approved` only.

```mermaid
sequenceDiagram
    autonumber
    participant Atlas
    participant WB as Whiteboard
    participant MS as MetadataService<br/>(packagesByNsURI)
    participant Codec as CodecResource

    Atlas->>WB: register EPackage draft (nsURI P)
    WB->>MS: registerPackage(draft)
    Note over MS: entry P → metadata(draft)
    Atlas->>WB: register EPackage approved (nsURI P)
    WB->>MS: registerPackage(approved)
    Note over MS: ❌ first-wins: silent no-op —<br/>approved's metadata is never built
    Codec->>MS: getPackageMetadata(nsURI P) for an APPROVED object
    MS-->>Codec: metadata(draft)
    Note over Codec: ❌ approved objects silently serialized<br/>with draft's metadata
    Atlas->>WB: unregister EPackage approved (file deleted on approved)
    WB->>MS: unregisterPackage(approved)
    Note over MS: ❌ unconditional remove(P) —<br/>deletes the entry draft still needs
    Codec->>MS: getPackageMetadata(nsURI P) for a DRAFT object
    MS-->>Codec: null
    Note over Codec: ❌ persistent HTTP 500<br/>("Package P is not registered")
```

Three code facts produce this, all in `MetadataServiceImpl`:

| Fact | Effect |
|---|---|
| `packagesByNsURI` is a flat `Map<String, PackageMetadata>` | the derived side can only represent **one** version per nsURI |
| `registerPackage` is **first-wins** | the second version's fingerprint is never even computed; its objects get the *other* version's metadata |
| `unregisterPackage` does an unconditional `remove(nsURI)` | any one version's unbind deletes the entry every other live version needs |

Note that the `modelFingerprint` introduced in WP5 is computed and stored — but it
sits *behind* the first-wins check. It is metadata about the entry, **not the key of
the registry**. That is exactly what WP6 changes.

> The same flat-put / unconditional-remove pattern exists in other nsURI-keyed
> `EPackage`-whiteboard consumers (Atlas: `DynamicEPackageRegistrationService`,
> `DynamicEPackageConfigurator`; codec: `TypeDiscriminatorService`'s
> `EPackage.Registry.INSTANCE` fallback). **Any consumer that whiteboard-tracks
> EPackage services and keys by nsURI is broken by multi-version registration.**

## The target: fingerprint-keyed, pull-based, stateless codec runs

The fingerprint (see [fingerprinting](mediator-and-fingerprinting.md#fingerprinting))
is reproducible and content-derived: two diverging branch versions get two
fingerprints; identical content on two branches gets one. Keyed by it, the
multi-version case is correct *by construction* — there is nothing a second
registration or an unbind can corrupt.

The consumer model flips from push (register first, look up by name later) to
**pull**: every codec run is stateless — it receives the concrete `EPackage`
instance and its runtime options as parameters, and obtains its configuration (the
codec profile) from the Metadata Service via the fingerprint of exactly that
instance. No global `EPackage.Registry`, no prior registration required.

```mermaid
sequenceDiagram
    autonumber
    participant Caller as Caller<br/>(e.g. per-stage ResourceSet)
    participant Codec as CodecResource
    participant FS as FingerprintService
    participant MS as MetadataService<br/>(keyed by modelFingerprint)

    Caller->>Codec: run(EPackage, options) — load or save
    Codec->>MS: getPackageMetadata(ePackage)
    MS->>FS: fingerprint(ePackage)  [memoized per instance]
    FS-->>MS: fp
    alt cache hit (this exact model version)
        MS-->>Codec: PackageMetadata(fp) incl. codec profile
    else miss — resolve-or-build (WP3 principle, one level up)
        MS->>MS: build metadata from the passed instance, store under fp
        MS-->>Codec: PackageMetadata(fp)
    end
    Codec->>Codec: serialize/parse with exactly this version's profile
    Note over MS: unbind of some EPackage service?<br/>at most a liveness signal for ITS fingerprint —<br/>never removes another version's entry.<br/>Cleanup = eviction with grace period<br/>(the atlas#156 GC/housekeeping model)
```

Key properties:

- **The version question is answered at the caller**, where it belongs: whoever hands
  the codec run its `EPackage` (e.g. the per-stage `ResourceSet` the Atlas configures)
  has already chosen the version. The codec never guesses by name.
- **The codec computes the fingerprint locally** (via the Metadata Service). The
  Atlas-published service property `fennec.model.fingerprint` remains a
  **cross-check** for detecting canonicalization drift — it is never trusted as the
  key (decision recorded in atlas#156). A drift shows up as a loud lookup miss, not
  as silent wrong-version serialization.
- **Unregister disappears as a correctness concern.** A service unbind is a liveness
  signal for one fingerprint; actual removal becomes cache eviction with a grace
  period, converging on the `git gc`-style orphan housekeeping planned in atlas#156.
- **The whiteboard becomes a warm-up optimization**: binding an `EPackage` service may
  pre-build its metadata so the first codec run pays no build cost — but nothing is
  correctness-relevant about it anymore.
- nsURI-based lookups (`getPackageMetadata(String)` & friends) stay for REST-ish
  consumers, documented as **best effort** under ambiguity.

## Who does what

| Party | What | Where it is tracked |
|---|---|---|
| **This repo** (glue) | WP6: registry keyed by `modelFingerprint` (nsURI = secondary index), `registerPackage` dedupes by fingerprint instead of first-wins, unregister = per-fingerprint liveness (never cross-version removal), new API `getPackageMetadata(EPackage)` (resolve-or-build) + `getPackageMetadataByFingerprint(String)`. Acceptance: the multi-version repro test from atlas#156. | [model.metadata#15](https://github.com/eclipse-fennec/model.metadata/issues/15) (parent: [#9](https://github.com/eclipse-fennec/model.metadata/issues/9)) |
| **Model Atlas** | (a) Fix its own nsURI-keyed registration map so a second version per nsURI is actually published; (b) optionally emit `fennec.model.fingerprint` as a service property (cross-check only); (c) independently: the dedicated fingerprint field in the EObject registry for orphan housekeeping. **Timing:** coordinated separately — ongoing developments there must not be disrupted; to be scheduled via the weekly. | dedicated ticket in model.atlas (to be created; referenced from [model.atlas#156](https://github.com/eclipse-fennec/model.atlas/issues/156)) |
| **Codec** | (a) Drop its bundled copy of `org.eclipse.fennec.model.metadata` and build against this repo's `…metadata.api` + `…metadata` bundles (same Java packages — imports stay unchanged, it is buildpath work); (b) `CodecAspectProvider` implements the new abstract `buildOperationAspect` (may return `null`); (c) `CodecResource.requirePackageRegistered` switches from `getPackageMetadata(nsURI)` to `getPackageMetadata(ePackage)` — the "register first" precondition disappears; (d) audit `TypeDiscriminatorService` for nsURI keying. | dedicated tickets in the codec repo (to be created) |

Because this repo's API is a **superset** of the codec's bundled copy, the codec
migration can start before its lookup call sites are switched — the nsURI lookup keeps
working for the single-version case throughout (an explicit WP6 acceptance criterion).

## Reading path

1. [Overview / purpose](model-metadata-purpose.md) — why the Metadata Service exists.
2. [Architecture](model-metadata-architecture.md) — the metadata/aspect/profile data model.
3. [Mediator & fingerprinting](mediator-and-fingerprinting.md) — fingerprint function,
   two-fingerprint scheme, resolve-or-build, `ArtifactStore`.
4. This document — the cross-repo picture and the multi-version problem.
5. [model.metadata#15](https://github.com/eclipse-fennec/model.metadata/issues/15) — the
   implementation work package (WP6).
