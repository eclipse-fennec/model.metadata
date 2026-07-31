# model.metadata — archived

> [!CAUTION]
> **This project is closed and is no longer developed.** Its content has been migrated to
> [**eclipse-fennec/emf.osgi**](https://github.com/eclipse-fennec/emf.osgi), where the metadata
> service, the fingerprint mechanism and the derived-artifact lifecycle are maintained from now on.
> The bundles published from this repository receive no further releases, features or bugfixes.

Common Model Metadata Framework — the runtime metadata and aspect layer for EMF that indexed
registered `EPackage`s into a "shadow model" and enriched it with pluggable aspects.

## Where it lives now

| What you used here | Where to find it |
|---|---|
| `org.eclipse.fennec.model.metadata.api` | `org.eclipse.fennec.emf.osgi.api` (fingerprint, artifact store) + `org.eclipse.fennec.emf.osgi.metadata` (metadata model and service API) |
| `org.eclipse.fennec.model.metadata` | `org.eclipse.fennec.emf.osgi` (fingerprint impl) + `org.eclipse.fennec.emf.osgi.metadata` (metadata impl) |
| Metadata service wiring, whiteboard, index | [Metadata Service guide](https://eclipse-fennec.github.io/emf.osgi/snapshot/guides/metadata-service) |
| Fingerprints, `emf.fingerprint` service property | [Model Fingerprints guide](https://eclipse-fennec.github.io/emf.osgi/snapshot/guides/model-fingerprints) |

## Migrating a consumer

Follow the porting guide in the new home — it maps every bundle, package, nsURI and API change,
including the ones a rename cannot cover (`Optional` returns, `AspectEntry` composition instead of
aspect inheritance, `MetadataHandler` instead of `AspectProvider`):

**➜ [Porting from `emf.model.metadata` to `emf.osgi`](https://github.com/eclipse-fennec/emf.osgi/blob/main/docs/metadata-migration-from-model-metadata.md)**

## What stays here

This repository is kept read-only as a historical record. The documentation under
[`docs/`](docs/) — and the [documentation site](https://eclipse-fennec.github.io/model.metadata/snapshot/)
built from it — describes the **pre-migration** design and API. Start with
[Project status](docs/project-status.md) for the closing summary, and see
[the migration concept](docs/migration-to-emf-osgi.md) for the reasoning behind the move.

Issues and pull requests are no longer accepted; open them in
[eclipse-fennec/emf.osgi](https://github.com/eclipse-fennec/emf.osgi/issues) instead.

## License

Released under the [EPL-2.0](LICENSE). Eclipse Fennec is part of the
[Eclipse Foundation](https://projects.eclipse.org/projects/modeling.fennec).
