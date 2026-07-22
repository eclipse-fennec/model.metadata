# Mediator, Fingerprinting & the Derived-Artifact Lifecycle

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
  operation; change a type; change a *log-relevant* annotation) ⇒ a different value.
  The `nsURI` alone is never the key.
- **Canonical** — irrelevant differences do not matter: **classifier order** in the
  package is canonicalized away, and GenModel **`documentation`** annotations are
  ignored. Feature / operation / parameter order *is* significant and is kept.

```java
String fp = fingerprintService.fingerprint(ePackage);
// with provider-specific derivation inputs (tool/engine version, config, ...):
String artifactFp = fingerprintService.fingerprint(ePackage, "oclEngine=1.2.0", "cfg=abc");
```

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
