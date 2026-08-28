# Mediator, Fingerprinting & the Derived-Artifact Lifecycle

> [!CAUTION]
> **Historical document — this project is closed.** Fingerprinting and the derived-artifact
> lifecycle now live in [`eclipse-fennec/emf.osgi`](https://github.com/eclipse-fennec/emf.osgi):
> see the [Model Fingerprints guide](https://eclipse-fennec.github.io/emf.osgi/snapshot/guides/model-fingerprints)
> and the [Metadata Service guide](https://eclipse-fennec.github.io/emf.osgi/snapshot/guides/metadata-service).
> The fp1 canonical form carried over unchanged; the `AspectProvider` SPI described below did not —
> it is now `MetadataHandler`. See [Project status](project-status.md).

> How the Model Metadata Service turns an incoming `EPackage` into fingerprint-keyed,
> reusable derived artifacts — and how an `AspectProvider` plugs into that. For the
> conceptual "why" see the [Overview](model-metadata-purpose.md); for the full data
> model see the [Architecture](model-metadata-architecture.md).

## The idea in one paragraph

When an `EPackage` is registered, the service computes a **canonical fingerprint** of
that model version, captures the package's **service properties** as transient build
context, and then, per registered `AspectProvider`, does **resolve-or-build**: if a
durable store already holds this provider's artifact for this fingerprint it is
**reused** (not rebuilt); otherwise it is built and stored. The service is a
**mediator** — it derives and hands out context, but it does **not** depend on any
particular storage (e.g. the Model Atlas); providers own their own persistence.

## The moving parts

| Component | Role |
|---|---|
| `FingerprintService` | Canonical, reproducible, content-derived fingerprint of a model version. |
| `PackageMetadata.getModelFingerprint()` | The cached fingerprint of the `EPackage`, computed at registration. The **join key**. |
| `PackageMetadata.getProperties()` | Transient `EMap<String,String>` of the EPackage service properties (build context; **not** serialized). |
| `ArtifactStore` | Optional durable store: `resolve(fingerprint, typeId)` / `put(fingerprint, typeId, artifact)`. `InMemoryArtifactStore` is the default. |
| `AspectProvider` | Extension point: `buildProfiles(...)` (and `build*Aspect(...)`) produce the derived artifacts. |
| `MetadataWhiteboard.registerPackage(EPackage, Map<String,Object>)` | Registration entry point that also takes the service properties. |

## Fingerprinting

The fingerprint is the backbone: it decides *identity* of a model version and is the
key under which derived artifacts are stored, reused, and (later) replicated.

`DefaultFingerprintService` builds a **canonical textual form by traversing the
in-memory model** (never the serialized bytes) and hashes it with SHA-256, prefixed
with a scheme tag (`fp1:`). Its guarantees:

- **Reproducible** — same content + same derivation inputs ⇒ same value on every node,
  independent of object identity, registration order, serialization order/whitespace,
  or time.
- **Identifying** — a structural change (add/remove/rename a classifier, feature or
  operation; change a type; change a type parameter or type argument; change a
  *log-relevant* annotation) ⇒ a different value. The `nsURI` alone is never the key.
- **Canonical** — irrelevant differences do not matter: **classifier order** in the
  package is canonicalized away, and GenModel **`documentation`** annotations are
  ignored. Feature / operation / parameter order *is* significant and is kept.

```java
String fp = fingerprintService.fingerprint(ePackage);
// with provider-specific derivation inputs (tool/engine version, config, ...):
String artifactFp = fingerprintService.fingerprint(ePackage, "oclEngine=1.2.0", "cfg=abc");
```

### Canonicalization schemes

A value is `<scheme>:<digest>`. The tag versions the **algorithm**, not the model — two
values with different tags are not comparable, even for the same model. The
canonicalization sits behind a tag-addressed seam, so several schemes stay computable side
by side and a future bump *adds* an implementation instead of editing one whose values are
already in circulation:

```java
fingerprintService.currentScheme();                       // "fp1" — the tag new values carry
fingerprintService.supportedSchemes();                    // every tag that can be computed
fingerprintService.fingerprintInScheme("fp1", ePackage);  // address one explicitly
```

A published scheme is **frozen**: each implementation owns its algorithm end to end and
shares no canonicalization logic with another, because a helper refactored for a newer
scheme would silently change the older one's values.

Consumers that only resolve a value they read elsewhere need none of this — they pass it to
`getPackageMetadataByFingerprint` and react to the result. An unresolvable fingerprint is
already handled (warning plus `nsURI` fallback in LENIENT, error in STRICT), so a scheme
bump never makes a document unreadable that was readable before; it only costs *precision*
when several versions share an `nsURI`.

Recomputing candidates in a *retained legacy* scheme on an exact-match miss is designed but
deliberately not built: with a single scheme the path is structurally unreachable, and
compatibility machinery is built when there is persisted data to protect. It is triggered by
the first scheme bump that happens after such data exists.

#### Generics

`fp1` covers type parameters of classes and operations (with their bounds), type arguments,
type-parameter references and wildcards. Without them, two packages differing *solely* in
generics hashed identically — and since registration is keyed by fingerprint, the second
model version was discarded onto the first entry and its objects would have been served the
first version's metadata.

Generic detail is emitted **only where it adds information** beyond the plain `eType`. That
is a correctness requirement, not an optimization: EMF creates an `EGenericType` wrapper for
every `setEType` call, so emitting those wrappers unconditionally would move the fingerprint
of every model — including the majority that use no generics at all. Models without generics
therefore hash exactly as they did before.

Type parameter **names** are part of the form, consistent with the treatment of every other
name, so an alpha-rename yields a new model version. That is the conservative direction on
purpose: a false "same" serves one version's objects with another's metadata, while a false
"different" only costs precision.

### Two fingerprints, two purposes

- **`modelFingerprint = fingerprint(ePackage)`** — the reproducible identity of the
  model version. It is the **join key**: "which model version does this artifact belong
  to?" (used for reuse and for orphan housekeeping). Cached on `PackageMetadata`.
- **`artifactFingerprint = fingerprint(ePackage, derivationInputs…)`** — folds in a
  provider's derivation inputs, so a change in *how* an artifact is derived yields a
  new key even for identical Ecore. This is the store/reuse key for that provider.

The join key stays purely content-derived; an *external* fingerprint (e.g. one handed
over via a service property) may be folded into a provider's artifact fingerprint, but
never replaces the local, reproducible `modelFingerprint`.

## Registration lifecycle

```
EPackage appears  ──▶  registerPackage(ePackage, serviceProperties)
                        │
                        ├─ modelFingerprint = fingerprintService.fingerprint(ePackage)
                        ├─ PackageMetadata.properties  ← service properties (transient)
                        ├─ build ClassMetadata / FeatureMetadata / OperationMetadata + aspects
                        └─ per AspectProvider  ─ resolve-or-build:
                             hit  = store.resolve(modelFingerprint, provider.typeId)
                             hit? → attach reused profile   (no rebuild)
                             else → profile = provider.buildProfiles(filteredCopy)
                                    attach; store.put(modelFingerprint, typeId, profile)
```

- **Unregister** is per-node and local: the in-memory metadata is dropped, but the
  durable store is **not** touched (liveness is per node; a stored artifact may still be
  used elsewhere — see [Overview](model-metadata-purpose.md)).
- **Re-register the same content** ⇒ same `modelFingerprint` ⇒ the stored artifact is
  **reused** instead of rebuilt.
- **Re-register modified content** ⇒ different fingerprint ⇒ rebuilt and stored under
  the new key; the old artifact is left in place.
- With **no `ArtifactStore`** present, the service simply always builds
  (backward-compatible).

## Decoupling: providers own persistence

The service gives every provider what it needs to make its own decisions —
`getModelFingerprint()` and `getProperties()` are available both on `PackageMetadata`
(in `buildPackageAspect`) and on the `filteredMetadataCopy` passed to `buildProfiles`
(EcoreUtil.copy copies transient features in memory). So a provider can read an external
fingerprint from the properties, decide whether a package is even relevant, and — if it
wants — persist/reuse its artifact against any backend (e.g. the Model Atlas) without
the metadata core depending on that backend.

## A simple example

A minimal provider that derives a profile from an `EPackage`. The concrete
`GreetingPackageProfile` (a `PackageProfile` subclass) comes from the provider's **own**
Ecore model that extends `metadata.ecore` — providers bring their own concrete
artifact types.

```java
@Component(service = AspectProvider.class)
public class GreetingAspectProvider implements AspectProvider {

    @Override
    public String getAspectTypeId() {
        return "greeting";
    }

    // The expensive derivation lives here; it is skipped on reuse.
    @Override
    public PackageProfile buildProfiles(PackageMetadata metadata) {
        // Relevance decision from build context (optional):
        if ("true".equals(metadata.getProperties().get("greeting.skip"))) {
            return null; // this provider does not contribute for this package
        }

        GreetingPackageProfile profile = GreetingFactory.eINSTANCE.createGreetingPackageProfile();
        for (ClassMetadata cm : metadata.getClasses()) {
            profile.getGreetings().add("Hello, " + cm.getName() + "!");
        }
        // The provider may use the fingerprint / an external one from properties:
        profile.setBasedOn(metadata.getModelFingerprint());
        return profile; // typeId + storage are handled by the service
    }

    // Not contributing per-element aspects here.
    @Override public PackageAspect buildPackageAspect(PackageMetadata m) { return null; }
    @Override public ClassAspect buildClassAspect(ClassMetadata m) { return null; }
    @Override public FeatureAspect buildFeatureAspect(FeatureMetadata m) { return null; }
    @Override public FeatureAspect buildAttributeAspect(AttributeMetadata m) { return null; }
    @Override public FeatureAspect buildReferenceAspect(ReferenceMetadata m) { return null; }
    @Override public OperationAspect buildOperationAspect(OperationMetadata m) { return null; }
}
```

### How it is triggered

**In OSGi (the common case):** the provider and any `ArtifactStore` are DS services;
Fennec EMF publishes `EPackage`s as OSGi services. `MetadataServiceComponent` tracks
both, so an arriving `EPackage` service triggers registration **with its service
properties** automatically — no explicit call:

```java
// MetadataServiceComponent (simplified)
@Reference(cardinality = MULTIPLE, policy = DYNAMIC, unbind = "removeEPackage")
void addEPackage(EPackage ePackage, Map<String, Object> properties) {
    registerPackage(ePackage, properties); // service properties flow straight through
}
```

**Programmatically (plain Java / tests):**

```java
MetadataWhiteboard service = new MetadataServiceImpl();
service.setArtifactStore(new InMemoryArtifactStore()); // enable resolve-or-build (optional)
service.registerAspectProvider(new GreetingAspectProvider());

// Register with build-context properties:
service.registerPackage(myPackage, Map.of("greeting.skip", "false"));

// Consume the derived artifact:
GreetingPackageProfile p = (GreetingPackageProfile) service.getPackageProfile(myPackage, "greeting");
```

### Reuse in action

```java
service.registerPackage(myPackage);   // buildProfiles runs once, profile stored
service.unregisterPackage(myPackage); // in-memory dropped; stored artifact untouched
service.registerPackage(myPackage);   // same fingerprint -> reused, buildProfiles NOT called again
```

Change the package's content and the fingerprint changes, so the next registration
rebuilds instead of reusing — exactly the behavior needed for correctness under model
evolution.
