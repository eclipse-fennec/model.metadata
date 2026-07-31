# Model Metadata Service — Purpose & Application Scenarios

> [!CAUTION]
> **Historical document — this project is closed.** The metadata service now lives in
> [`eclipse-fennec/emf.osgi`](https://github.com/eclipse-fennec/emf.osgi); this page describes the
> pre-migration design and is no longer updated. See [Project status](project-status.md), and
> [Porting from `emf.model.metadata` to `emf.osgi`](https://github.com/eclipse-fennec/emf.osgi/blob/main/docs/metadata-migration-from-model-metadata.md)
> to move a consumer.

> A conceptual overview of *why* the Model Metadata Service exists and *what* it is
> used for. For the detailed design (EMF classes, aspect pattern, registration
> lifecycle, APIs) see the [Architecture Specification](model-metadata-architecture.md).

## 1. The core idea: orthogonal, derived models

An Ecore domain model (`EPackage`) describes *what the data is* — classes,
features, types, relations. It should stay clean and free of technical concerns.

But almost every real system needs *additional, cross-cutting information* about
that same model: how to serialize it, how to map it to a database, which
constraints hold, how to expose it over a protocol. Encoding all of that directly
into the domain model (via `EAnnotations` or extra attributes) pollutes it, is slow
to parse at runtime, and is impossible when the model is a third-party Ecore you
cannot modify.

The **Model Metadata Service** turns this around. Whenever a new `EPackage`
arrives, it derives one or more **orthogonal models** from it — separate EMF models
that *reference* the original package's elements and attach the technical
information beside them, not inside them:

```
                         ┌───────────────────────────────────────────────┐
   new EPackage  ───────▶│           Model Metadata Service              │
   (domain model)        │   (whiteboard of AspectProviders)             │
                         └───────────────────────────────────────────────┘
                                        │  derives (once, on arrival)
                                        ▼
        ┌───────────────────┬───────────────────┬───────────────────────┐
        │  metadata /        │  ORM aspect        │  OCL constraint       │  …
        │  aspect model      │  model             │  cache model          │
        │ (shadow of the     │ (JPA mapping)      │ (compiled constraints)│
        │  EPackage)         │                    │                       │
        └───────────────────┴───────────────────┴───────────────────────┘
                     each is a self-contained, serializable EMF model
                     that points back at the source EPackage's elements
```

Every derived model is itself an `EObject` tree. Because they are plain EMF models,
they are **type-safe**, **navigable**, and — crucially — **serializable and
cacheable**.

## 2. Why "orthogonal"?

The derived models are orthogonal in two senses:

- **Orthogonal to the domain model** — they add a dimension (persistence, codec,
  constraints) without changing or depending on the domain model's own definition.
  The domain model does not know they exist.
- **Orthogonal to each other** — each concern (codec, ORM, OCL, mapping) is its own
  model, built by its own provider, isolated from the others. Adding a new concern
  never touches existing ones.

In the architecture these derived models take the shape of **aspects** (attached
per package / class / feature / operation) and pre-computed **profiles**. An
`AspectProvider` is the extension point that produces them; see
[§3.3 / §4 / §8.3 of the architecture doc](model-metadata-architecture.md).

## 3. Lifecycle: derive once, hold, reuse

1. **Arrival** — an `EPackage` is registered with the service (directly, or via the
   OSGi whiteboard when an `EPackage` service appears).
2. **Derivation** — the service walks the package (classes, features, **operations**,
   parameters) and asks every registered `AspectProvider` to contribute its
   orthogonal model for each element.
3. **Resolution** — cross-references between the derived elements are resolved and
   profiles are pre-computed, so nothing has to be recomputed on the hot path.
4. **Holding** — the resulting models are kept in memory for fast lookup, and can be
   **persisted / cached** because they are ordinary serializable EMF resources.

The expensive analysis (annotation parsing, constraint compilation, mapping
inference) happens **once, at registration**, not on every serialization or
database write.

## 4. Holding derived models in the Model Atlas

Because every derived model is an `EObject` tree, it fits naturally into an
**EObject registry**. The [Fennec Model Atlas](https://github.com/eclipse-fennec/model.atlas) provides
exactly this: a runtime registry (`EObjectRegistryService<T extends EObject>`) that
stores, versions and serves EMF models over a REST API and pluggable storage
backends (file, Apicurio, …).

This gives a clean division of labor:

- **Model Metadata Service** — *derives* the orthogonal models from incoming
  EPackages.
- **Model Atlas EObject registry** — *holds* those derived models: persisted,
  versioned, queryable, and shareable across processes.

A derived ORM model, constraint cache, or mapping model computed once can thus be
stored in the Atlas and re-loaded by any runtime that needs it, instead of being
recomputed everywhere.

## 5. Application scenarios

The pattern is deliberately open-ended — any concern that can be expressed as
"information derived from an EPackage" can plug in as an `AspectProvider`. Concrete
scenarios that motivate the design:

### 5.1 OCL constraint cache

An `EPackage` may carry OCL constraints (typically in `EAnnotations`). Parsing and
compiling OCL on every validation call is expensive. A constraint-cache provider
derives, at registration time, a model of the **pre-parsed / pre-compiled OCL
constraints** for each class and operation. Validation then looks the compiled
constraints up instead of re-parsing strings.

### 5.2 EORM generation (`emf.persistence-jpa`)

For persistence, a provider derives an **ORM model** — how each class maps to a
table, each attribute to a column, each reference to a foreign key / join, plus ID
and inheritance strategy. Consumed by `emf.persistence-jpa`, this pre-computed
mapping drives JPA-based persistence without annotating the domain model with
database concerns. (See the ORM aspect sketch in
[§4.2 of the architecture doc](model-metadata-architecture.md).)

### 5.3 SensiNact mapping (`emf.util`)

The SensiNact mapping in `emf.util` needs to know how model elements map onto
SensiNact providers/services/resources. A mapping provider derives this
**mapping model** from the EPackage once, so the mapping layer consumes a resolved,
type-safe structure rather than re-interpreting the Ecore model at runtime.

---

### In one sentence

> The Model Metadata Service is a whiteboard that, on arrival of each `EPackage`,
> derives orthogonal EMF models (codec, ORM, OCL, mapping, …) capturing
> cross-cutting concerns beside — not inside — the domain model; these derived
> models are computed once, held for fast reuse, and can be persisted in an EObject
> registry such as the Model Atlas.
