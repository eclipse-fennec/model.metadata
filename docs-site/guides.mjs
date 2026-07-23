// The published, user-facing pages (allowlist). Shared by the sync script and the
// VitePress config so the set and its order are defined exactly once.
//   file  — source markdown in ../docs (the single source of truth)
//   slug  — route name under the section
//   title — sidebar / nav label
//
// GUIDES   -> /guides/   (the user manual)
// EXAMPLES -> /examples/ (worked examples)
//
// Internal dev docs (e.g. extraction-requirements.md) are deliberately NOT listed
// here and therefore stay GitHub-only, matching the other Fennec projects.
export const GUIDES = [
  { file: 'model-metadata-purpose.md', slug: 'overview', title: 'Overview' },
  { file: 'mediator-and-fingerprinting.md', slug: 'mediator', title: 'Mediator & Fingerprinting' },
  { file: 'model-metadata-architecture.md', slug: 'architecture', title: 'Architecture' },
  { file: 'big-picture-atlas-metadata-codec.md', slug: 'big-picture', title: 'Big Picture: Atlas → Metadata → Codec' },
];

export const EXAMPLES = [];
