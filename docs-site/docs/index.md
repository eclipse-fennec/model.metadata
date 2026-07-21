---
layout: home

hero:
  name: Fennec Model Metadata
  text: A runtime metadata & aspect layer for EMF
  tagline: Decouple technical concerns from your Ecore domain model — a computed "shadow model" that indexes packages, classes and features and enriches them with pluggable aspects.
  image:
    src: /fennec-logo.png
    alt: Eclipse Fennec logo
  actions:
    - theme: brand
      text: Architecture
      link: /guides/architecture
    - theme: alt
      text: View on GitHub
      link: https://github.com/eclipse-fennec/model.metadata

features:
  - icon: 🗂️
    title: Shadow metadata model
    details: A dedicated EMF model (metadata.ecore) wraps every registered EPackage with PackageMetadata, ClassMetadata and FeatureMetadata — computed once at startup, type-safe, and persistable.
    link: /guides/architecture
    linkText: Read the architecture
  - icon: 🧩
    title: Pluggable aspects
    details: Attach technical concerns — codecs, ORM mapping, historization, units — as aspects contributed by AspectProviders, without polluting the domain model or parsing EAnnotations at runtime.
  - icon: 🔌
    title: External models welcome
    details: Enrich third-party Ecore models you cannot modify. Metadata is attached externally instead of requiring physical EAnnotations on the source model.
  - icon: ⚡
    title: Built for performance
    details: Pre-computed metadata replaces slow EAnnotation iteration and string parsing on hot paths like serialization and persistence.
---

## About Fennec Model Metadata

Fennec Model Metadata (`org.eclipse.fennec.model.metadata`) is a centralized
runtime metadata registry for EMF-based frameworks. It decouples technical
concerns — serialization/codecs, ORM mapping, historization, units of
measurement — from the core [Ecore](https://eclipse.dev/modeling/emf/) domain
model.

Instead of relying on slow runtime parsing of `EAnnotation`s or polluting the
domain model with technical attributes, it establishes a dedicated **shadow
model** (the metadata layer) that is computed once at startup. Because that layer
is itself an EMF model (`metadata.ecore`), it gives you type-safe access to
configuration, serialization/persistence of pre-computed metadata, and extension
via EMF inheritance (codec aspects, ORM aspects, and more).

See the **[Architecture](/guides/architecture)** guide for the full design — the
metadata registry, the feature-aspect pattern, and the `AspectProvider` extension
mechanism.
