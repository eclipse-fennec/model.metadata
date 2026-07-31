---
layout: home

hero:
  name: Fennec Model Metadata
  text: Closed — migrated to emf.osgi
  tagline: This project is no longer developed. The runtime metadata & aspect layer for EMF now lives in eclipse-fennec/emf.osgi, together with fingerprinting and the derived-artifact lifecycle. These pages remain as a historical record of the pre-migration design.
  image:
    src: /fennec-logo.png
    alt: Eclipse Fennec logo
  actions:
    - theme: brand
      text: Project status
      link: /guides/project-status
    - theme: alt
      text: Porting guide
      link: https://github.com/eclipse-fennec/emf.osgi/blob/main/docs/metadata-migration-from-model-metadata.md
    - theme: alt
      text: emf.osgi documentation
      link: https://eclipse-fennec.github.io/emf.osgi/snapshot/

features:
  - icon: 🏁
    title: Project closed
    details: No further releases, features or bugfixes come from this repository. Issues and pull requests belong in eclipse-fennec/emf.osgi.
    link: /guides/project-status
    linkText: Read the closing summary
  - icon: 🧭
    title: Porting a consumer
    details: Bundle, package and nsURI mapping plus the breaks a rename cannot cover — Optional returns, AspectEntry composition instead of aspect inheritance, MetadataHandler instead of AspectProvider.
    link: https://github.com/eclipse-fennec/emf.osgi/blob/main/docs/metadata-migration-from-model-metadata.md
    linkText: Open the porting guide
  - icon: 🗂️
    title: Metadata service today
    details: Wiring, whiteboard, index and the fingerprint-keyed multi-version registry are documented in the new home.
    link: https://eclipse-fennec.github.io/emf.osgi/snapshot/guides/metadata-service
    linkText: Metadata Service guide
  - icon: 🔑
    title: Model fingerprints
    details: The fp1 canonical form carried over unchanged; the service property is now emf.fingerprint.
    link: https://eclipse-fennec.github.io/emf.osgi/snapshot/guides/model-fingerprints
    linkText: Model Fingerprints guide
---

## This project has been closed

**Fennec Model Metadata (`org.eclipse.fennec.model.metadata`) is archived.** Its content — the
metadata mirror tree, the multi-valued index, the fingerprint mechanism and the artifact store —
has been migrated to **[eclipse-fennec/emf.osgi](https://github.com/eclipse-fennec/emf.osgi)** and
is maintained there. Nothing further is released from this repository.

The mechanism turned out to be EMF/OSGi *infrastructure* rather than codec glue: the same
fingerprinting, per-version resolution and whiteboard lifecycle are needed by the codec,
persistence, the sensiNact mapping and the OCL delegate cache — and the integration points
(`EMFNamespaces` service properties, per-version `ResourceSet` resolution, registry components and
extender) all live in `emf.osgi`. During the move the codec vocabulary returned to the codec repo,
and aspect attachment was re-cut from inheritance to composition (`AspectEntry`).

- **Moving a consumer?** → [Porting from `emf.model.metadata` to `emf.osgi`](https://github.com/eclipse-fennec/emf.osgi/blob/main/docs/metadata-migration-from-model-metadata.md)
- **Closing summary and mapping table** → [Project status](/guides/project-status)
- **Why the move, and how it was cut** → [Migration concept](https://github.com/eclipse-fennec/model.metadata/blob/main/docs/migration-to-emf-osgi.md)

The remaining pages in the user manual describe the **pre-migration** design and API. They are kept
for reference and are not a guide to the current `emf.osgi` implementation.
