# Project status: closed — migrated to `emf.osgi`

> [!CAUTION]
> **`model.metadata` is closed and is no longer developed.** The migration to
> [`eclipse-fennec/emf.osgi`](https://github.com/eclipse-fennec/emf.osgi) is complete. No further
> releases, features or bugfixes come from this repository; the documentation here is a historical
> record of the pre-migration design.

## What happened

The metadata service started as an extraction from `emf.codec` and grew a fingerprint mechanism and
a multi-version registry (one nsURI, several live model versions keyed by fingerprint). That work
showed the primitives were not codec glue but **EMF/OSGi infrastructure**: the same fingerprinting,
per-version resolution and whiteboard lifecycle are needed by the codec, persistence (eorm), the
sensiNact mapping and the OCL delegate cache — and their natural integration points
(`EMFNamespaces` service properties, per-version `ResourceSet` resolution, the registry
components and extender) all live in `emf.osgi`.

So instead of evolving a separate repository alongside them, the mechanism moved into `emf.osgi`,
the codec vocabulary went back to the codec repo, and aspect attachment was re-cut from
inheritance to composition. The reasoning, the target layout and the phase plan are recorded in
[the migration concept](migration-to-emf-osgi.md); it was executed as planned.

## Where the content went

| Here | New home in `emf.osgi` |
|---|---|
| `org.eclipse.fennec.model.metadata.api` | `org.eclipse.fennec.emf.osgi.api` (fingerprint, artifact store) + `org.eclipse.fennec.emf.osgi.metadata` (metadata model, service API) |
| `org.eclipse.fennec.model.metadata` | `org.eclipse.fennec.emf.osgi` (fingerprint impl, internal) + `org.eclipse.fennec.emf.osgi.metadata` (metadata impl) |
| `FingerprintService`, `CanonicalizationScheme` (fp1), `ArtifactStore` | ported verbatim, guarded by golden-value regression tests — fp1 values are unchanged |
| mirror tree (`PackageMetadata` / `ClassMetadata` / `FeatureMetadata` / `OperationMetadata`), `MetadataIndex`, multi-version registry | re-cut into the slim metadata model, nsURI `https://eclipse.org/fennec/emf/osgi/metadata/1.0.0` |
| `Aspect` / `PackageAspect` / … type hierarchy, `*Profile` classes | replaced by composition: one opaque `AspectEntry { typeId, content, diagnostics }` |
| `AspectProvider` with six `build*Aspect` callbacks | one coarse SPI hook, `MetadataHandler.onPackageRegistered(PackageMetadata)` |
| `Base*Config`, `SerializationFormat`, `TypeStrategy`, `IdKeyMode`, JSON key defaults | back to the codec repo — they were codec domain all along |
| service property `fennec.model.fingerprint` | `emf.fingerprint` (`EMFNamespaces.EMF_MODEL_FINGERPRINT`) |

## What to read instead

- **Porting a consumer:** [Porting from `emf.model.metadata` to `emf.osgi`](https://github.com/eclipse-fennec/emf.osgi/blob/main/docs/metadata-migration-from-model-metadata.md)
  — bundle, package, nsURI and API mapping, including the breaks a rename cannot cover.
- **Using the service:** [Metadata Service guide](https://eclipse-fennec.github.io/emf.osgi/snapshot/guides/metadata-service).
- **Fingerprints:** [Model Fingerprints guide](https://eclipse-fennec.github.io/emf.osgi/snapshot/guides/model-fingerprints).

## Status of this repository

- **Code:** frozen at the last pre-migration state. Buildable, but not released again.
- **Docs:** kept as-is, each page marked as historical. They describe APIs that no longer exist in
  this form — do not use them as a reference for new work against `emf.osgi`.
- **Issues and pull requests:** closed; raise anything in
  [eclipse-fennec/emf.osgi](https://github.com/eclipse-fennec/emf.osgi/issues).
