import { defineConfig } from 'vitepress'
import { GUIDES, EXAMPLES } from '../../guides.mjs'

// Per-project docs are served under a versioned sub-path, matching the org
// convention (https://eclipse-fennec.github.io/<repo>/<version>/). This project is
// closed (migrated to emf.osgi): the snapshot deploy at /model.metadata/snapshot/
// is the FINAL publication — no release version will ever be added.
const version = process.env.DOCS_BRANCH || 'snapshot'
const base = `/model.metadata/${version}/`

// Canonical published origin. Links that point OUTSIDE the current docs base
// (other doc versions) must be full URLs — VitePress auto-prepends `base` to any
// root-absolute (`/…`) link, which would otherwise double the path.
const SITE = 'https://eclipse-fennec.github.io/model.metadata'

// Version selector. `snapshot` is the only — and now final — deployed version;
// instead of further versions of this project, it points at its successor.
const versions = [
  { text: 'snapshot (final)', link: `${SITE}/snapshot/` },
  { text: 'continued in emf.osgi ↗', link: 'https://eclipse-fennec.github.io/emf.osgi/snapshot/' },
]

const guideItems = GUIDES.map((g) => ({ text: g.title, link: `/guides/${g.slug}` }))
const exampleItems = EXAMPLES.map((g) => ({ text: g.title, link: `/examples/${g.slug}` }))

// Examples are optional; only surface the section when there is something to show.
const nav = [
  { text: 'Home', link: '/' },
  { text: 'User Manual', items: guideItems },
  ...(exampleItems.length ? [{ text: 'Examples', items: exampleItems }] : []),
  { text: `version: ${version} (final)`, items: versions },
]

const sidebar = {
  '/guides/': [{ text: 'User Manual', items: guideItems }],
  ...(exampleItems.length ? { '/examples/': [{ text: 'Examples', items: exampleItems }] } : {}),
}

export default defineConfig({
  title: 'Fennec Model Metadata (archived)',
  description:
    'Archived project — the metadata and aspect model for EMF moved to eclipse-fennec/emf.osgi. These pages document the pre-migration design.',
  lang: 'en-US',
  base,
  cleanUrls: true,
  lastUpdated: true,
  ignoreDeadLinks: true,

  markdown: {
    // Shiki has no dedicated 'gradle' grammar; Gradle build files are Groovy.
    languageAlias: { gradle: 'groovy' },
  },

  head: [
    ['link', { rel: 'icon', type: 'image/png', href: `${base}fennec-logo.png` }],
    ['meta', { name: 'theme-color', content: '#c0631c' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:title', content: 'Fennec Model Metadata (archived)' }],
    [
      'meta',
      {
        property: 'og:description',
        content:
          'Archived — the metadata and aspect model for EMF is now developed in eclipse-fennec/emf.osgi.',
      },
    ],
  ],

  themeConfig: {
    logo: '/fennec-logo.png',
    siteTitle: 'Fennec Model Metadata (archived)',

    nav,
    sidebar,

    socialLinks: [
      { icon: 'github', link: 'https://github.com/eclipse-fennec/model.metadata' },
    ],

    search: { provider: 'local' },

    // No edit link: the repository is closed and becomes read-only/archived, so
    // "edit this page" would lead nowhere. Point at the successor instead.
    editLink: {
      pattern: 'https://github.com/eclipse-fennec/emf.osgi',
      text: 'This project is archived — continued in emf.osgi',
    },

    footer: {
      message:
        'Archived project — development continues in <a href="https://github.com/eclipse-fennec/emf.osgi">eclipse-fennec/emf.osgi</a>. Released under the EPL-2.0 License. Eclipse Fennec is part of the Eclipse Foundation.',
      copyright: 'Copyright © Eclipse Foundation and contributors',
    },
  },
})
