# Migration concept: model.metadata → emf.osgi

> **Status:** working document — migration plan, agreed in the architecture discussion of
> 2026-07-27. Builds on the analysis chain: [big picture](big-picture-atlas-metadata-codec.md),
> [mediator & fingerprinting](mediator-and-fingerprinting.md), the unified-persistence concept
> (`emf.persistence-jpa/docs/unified-persistence/concept.md`), and the emf.osgi
> multi-runtime concept (`emf.osgi/multi-runtime-concept.md`).
>
> **Open points are tracked on the target side:** `emf.osgi/docs/metadata-migration.md` reviews
> this plan against the verified state of emf.osgi and carries the decisions still to be made
> (M1–M12, including D1–D4 of §9 under new IDs). That document is temporary and is deleted when
> the migration finishes; the concept here is the permanent record.

## 1. What moves, and why

The fingerprint mechanism and the multi-version-per-nsURI registry semantics are EMF/OSGi
*infrastructure*, not codec glue: the same primitive is needed by the codec, eorm
(persistence), the sensiNact mapping, the OCL delegate cache (m2x), and the unified-persistence
concept — and the natural integration points (service property emission at registration,
per-version `ResourceSet` resolution, whiteboard lifecycle) all live in emf.osgi. At the same
time the review of `metadata.ecore` showed that the current aspect model over-generalizes its
single consumer:

- **~half of `metadata.ecore` is codec vocabulary** (`Base*Config`, `SerializationFormat`,
  `TypeStrategy`, `IdKeyMode`, … — down to JSON key defaults). It returns to the codec repo.
- **Aspect attachment by inheritance** (`Aspect`/`PackageAspect`/… supertypes, `*Profile`
  hierarchy) is replaced by **composition**: an opaque `AspectEntry { typeId, content: EObject
  (containment), diagnostics }`. Consumers attach whatever model they own — no Ecore
  dependency on the base model, cross-aspect lookup by `(element, typeId)` stays.
- **The per-element `AspectProvider` callbacks** (`buildClassAspect`, `buildFeatureAspect`, …)
  are the codec's traversal shape, not a universal one (eorm and the OCL cache traverse
  themselves). The SPI shrinks to a coarse hook: `onPackageRegistered(PackageMetadata)`
  returning optional entries.
- **`metadata-api.ecore` is dropped**: pure service contracts become plain Java interfaces
  with `@ProviderType` (the emf.osgi API style). Only the *data* model (mirror tree,
  `AspectEntry`, diagnostics) stays Ecore.
- **What carries the value and stays**: the mirror tree (`PackageMetadata` /
  `ClassMetadata` / `FeatureMetadata` / `OperationMetadata` with caches, supertype closure,
  id features), the multi-valued `MetadataIndex`, the fingerprint-keyed multi-version registry
  (WP6), and the fingerprint/mediation machinery (`FingerprintService`, scheme seam,
  `ArtifactStore`).

## 2. Where the work happens: directly in emf.osgi

**Decision: rework directly in emf.osgi on a feature branch. No rework branch in this repo.**

Rationale:

1. **The rework is a re-cut, not an increment.** Bundle names, Java packages, the model nsURI
   and the API style all change. Doing the rework here first would mean renaming everything
   twice; almost no line would survive the second move verbatim.
2. **The integration points only exist in emf.osgi.** `EMFNamespaces` property, emission in
   the registry components/extender/codegen, per-version `ResourceSet` resolution, itest
   infrastructure with a real service registry — none of that can be developed here.
3. **No broken intermediate state.** This repo stays frozen but releasable during the whole
   migration; the codec keeps building against its bundled copy until Phase 3 flips it. There
   is never a moment where a consumer builds against nothing.

This repo's role during migration: **reference and test donor, bugfix-only**. After Phase 4 it
is archived with a pointer to the new home.

Cross-repo moves lose git history: every moving commit in emf.osgi references the source
commit SHA in this repo in its message.

## 3. Target layout

| Today (model.metadata) | Target (emf.osgi) |
|---|---|
| `…model.metadata.api` → `FingerprintService`, `ArtifactStore` | api bundle `org.eclipse.fennec.emf.osgi.api`, new package `org.eclipse.fennec.emf.osgi.fingerprint` (plus `CanonicalizationScheme` seam, promoted to API) |
| `…model.metadata` → `DefaultFingerprintService`, `Fp1CanonicalizationScheme`, `InMemoryArtifactStore` | impl bundle `org.eclipse.fennec.emf.osgi`, internal package `org.eclipse.fennec.emf.osgi.components.fingerprint` |
| `metadata.ecore` (slimmed: mirror tree + `AspectEntry` + diagnostics; codec vocabulary and `*Profile` classes removed) | new bundle `org.eclipse.fennec.emf.osgi.metadata`, generated base package `org.eclipse.fennec.emf.osgi.metadata.model`, new nsURI `https://eclipse.org/fennec/emf/osgi/metadata/1.0.0` |
| `metadata-api.ecore` (modeled interfaces) | **dropped** — plain Java API in `org.eclipse.fennec.emf.osgi.api`, package `org.eclipse.fennec.emf.osgi.metadata` (`MetadataService`, `MetadataWhiteboard`, `MetadataIndex[Reader|Writer]`, `MetadataHandler`, provider SPI) |
| `MetadataServiceComponent`, `MetadataServiceImpl`, `MapBasedMetadataIndex` | impl in bundle `org.eclipse.fennec.emf.osgi.metadata` |
| Atlas-emitted property `fennec.model.fingerprint` (cross-check) | `EMFNamespaces.EMF_MODEL_FINGERPRINT = "emf.fingerprint"` (follows `emf.nsURI`/`emf.version` convention), emitted by the registry components at bind time — **computed, never trusted** (atlas#156 decision unchanged); atlas aligns its property name (open decision D2) |
| docs (`big-picture…`, `mediator-and-fingerprinting`, this file) | move to `emf.osgi/docs/`, links updated |

Split-package check: API package `org.eclipse.fennec.emf.osgi.metadata` (api bundle) vs.
generated `…metadata.model` and impl `…metadata.impl` (metadata bundle) — no overlap.

## 4. Port verbatim vs. re-cut

**Verbatim (package rename only) — the fingerprint contract is frozen:**

- `Fp1CanonicalizationScheme`, `DefaultFingerprintService`, `CanonicalizationScheme` seam,
  `ArtifactStore` + `InMemoryArtifactStore`, and their complete test suites (fp1 canonical
  form, generics coverage from #17).
- **Golden-value regression tests are the acceptance criterion**: fingerprints computed by the
  ported code must be byte-identical to values recorded from this repo's implementation before
  the move. fp1 values become a public contract the moment they are stamped into service
  properties and persisted logs — any accidental canonicalization drift during the port would
  be a silent data corruption later.

**Re-cut (design agreed above, new code in emf.osgi):**

- `metadata.ecore` — slim model (user generates from the new ecore/genmodel; src-gen is never
  hand-edited).
- Plain-Java API — semantics ported from `metadata-api.ecore`, surface reduced: the six
  `build*Aspect` callbacks collapse into `onPackageRegistered`; `*Profile` accessors are
  dropped (profiles become consumer-owned `AspectEntry` content).
- `MetadataServiceImpl` / index / whiteboard — logic ported, adapted to the new model and API.
- **The WP6 multi-version tests (atlas#156 repro, index-survivor, versions-enumeration) move
  as the acceptance suite** — they encode the invariants the whole exercise exists for.

## 5. Phases

### Phase 0 — Decisions & seam check (this repo + emf.osgi, no code moves)

- Verify the `CanonicalizationScheme` API against the Merkle/composite requirements of the
  unified-persistence concept (§6.4, §17.2): a structured scheme (subtree hashes, composite
  root over multiple packages) must be addable as a *second scheme* without breaking the API.
  Adjust the seam here first if needed — cheaper than after the freeze.
- Fix names: property (D2), nsURI, packages (§3).
- Record golden fingerprint values for the regression suite.

**Exit:** seam confirmed or adjusted; naming decisions closed; golden values committed.

### Phase 1 — Fingerprint into emf.osgi (feature branch `metadata-migration` in emf.osgi)

- API package + impl port per §3, verbatim, with golden-value tests.
- `EMFNamespaces.EMF_MODEL_FINGERPRINT`; registry components/extender compute and emit the
  property at bind time.
- itest: registered `EPackage` service carries the property; two same-nsURI packages carry
  distinct fingerprints.

**Exit:** golden tests green; property visible in itests; emf.osgi snapshot published.

### Phase 2 — Metadata bundle in emf.osgi

- New slim `metadata.ecore` + genmodel (codec vocabulary removed, `AspectEntry` composition,
  no profiles) — generation by the project owner.
- Plain-Java API in the api bundle; service/whiteboard/index impl in the new bundle,
  fingerprint-keyed (WP6 semantics preserved: dedupe by fingerprint, unbind = per-fingerprint
  liveness, nsURI = secondary best-effort index, `getPackageMetadataVersions`).
- Port the WP6 acceptance suite.
- **Genericity gate before API freeze:** two spikes against the new SPI — (a) an eorm-style
  package visitor, (b) an OCL-cache-style derived artifact keyed by fingerprint via
  `ArtifactStore`. Both must work without inheriting from the metadata model. Findings feed
  back into the API *before* the first release tag.

**Exit:** WP6 suite green in emf.osgi; both spikes pass; API reviewed for semantic versioning.

### Phase 3 — Codec migration (codec repo)

See §6. **Exit:** codec builds against emf.osgi bundles, bundled copy deleted, codec test
suite green.

### Phase 4 — Decommission this repo

- Transfer/close remaining issues with pointers (#9, #15, #16, #17 lineage).
- Move/adapt docs; README deprecation notice; archive the repository.

**Exit:** repo archived; no consumer references `org.eclipse.fennec.model.metadata.*`.

## 6. Codec changes (Phase 3, dedicated tickets in the codec repo)

1. **Adopt the codec vocabulary.** `Base*Config`, `SerializationFormat`, `TypeStrategy`,
   `IdStrategy`, `IdKeyMode`, `SuperTypeSelection`, `EnumSerializationStrategy` and the
   `Codec*Profile` classes move into a codec-owned ecore (they were codec domain all along).
   `Codec*Profile` loses its `eSuperTypes` on the metadata model — standalone hierarchy,
   regenerated in the codec repo.
2. **Provider adaptation.** `CodecAspectProvider` implements the new coarse hook; it traverses
   the mirror tree itself and attaches its profile as `AspectEntry(typeId="codec")`.
3. **Lookup flip** (already planned in the big picture): `CodecResource` switches from
   `getPackageMetadata(nsURI)` to `getPackageMetadata(EPackage)` (resolve-or-build); the
   "register first" precondition disappears. Audit `TypeDiscriminatorService` for nsURI keying.
4. **Buildpath/imports:** drop the bundled `org.eclipse.fennec.model.metadata` copy; import
   `org.eclipse.fennec.emf.osgi.fingerprint`, `…osgi.metadata`, `…osgi.metadata.model`.

Note: unlike the previously planned same-package buildpath swap, the package rename makes this
an import rewrite — mechanical, but repo-wide.

## 7. Coordination

- **Model Atlas** is *not* blocked and not a phase here: it keeps its own schedule
  (weekly-coordinated, per atlas#156). Relevant touch points: property name alignment (D2) and
  — once Phase 1 is released — optionally consuming the emf.osgi fingerprint service instead
  of any own computation.
- **Release order:** emf.osgi snapshot after Phase 1 → codec starts Phase 3 against Phase 2
  snapshots → first emf.osgi release tag only after the Phase 2 genericity gate → codec
  release → archive.
- eorm / sensiNact / OCL onboarding stays out of scope of this migration (they were the
  genericity *evidence*, the spikes are their stand-in). First real onboarding after the
  release tag.

## 8. Risks

| Risk | Mitigation |
|---|---|
| fp1 canonicalization drift during the port | golden-value tests recorded *before* the move (Phase 0) |
| API frozen against one consumer's shape again | Phase 2 genericity gate (eorm + OCL spikes) before the release tag |
| Scheme seam too narrow for Merkle/composite | Phase 0 seam check *before* anything moves |
| codec import rewrite regressions | codec suite is the gate; bundled copy is deleted only after green |
| emf.osgi governance slows iteration | fingerprint contract *wants* freezing; the fast-moving parts (aspect content) live in consumer repos by design |

## 9. Open decisions

- **D1 — impl placement:** fingerprint impl in the core impl bundle (proposed, zero extra
  deps) vs. an own `…osgi.fingerprint` bundle (cleaner for the multi-runtime core extraction
  later). Proposal: core impl bundle now; extraction falls out of the multi-runtime work.
- **D2 — property name:** `emf.fingerprint` (emf.osgi convention, proposed) vs.
  `fennec.model.fingerprint` (atlas draft). One name, decided with the atlas team in the
  weekly, before Phase 1 ships.
- **D3 — `AspectEntry.content` type:** `EObject` containment (proposed: serializable in-tree,
  EMF lifecycle) vs. `EJavaObject` (any Java object, no serialization). Revisit in the Phase 2
  spikes — the OCL cache (parsed constraints are not EObjects) is the test case; possibly both
  (a transient object slot next to the EObject slot).
- **D4 — `OperationAspect`/operation-level entries:** keep operation-level attachment in the
  slim model or defer until a consumer needs it (codec's `buildOperationAspect` may return
  null today). Decide during the Phase 2 model cut.
