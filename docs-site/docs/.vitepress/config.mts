import { defineConfig } from 'vitepress'
import { GUIDES, EXAMPLES } from '../../guides.mjs'

// Per-project docs are served under a versioned sub-path, matching the org
// convention (https://eclipse-fennec.github.io/<repo>/<version>/). The snapshot
// branch publishes to /model.metadata/snapshot/; tagged releases / `latest` get
// added once the first release lands.
const version = process.env.DOCS_BRANCH || 'snapshot'
const base = `/model.metadata/${version}/`

// Canonical published origin. Links that point OUTSIDE the current docs base
// (other doc versions) must be full URLs — VitePress auto-prepends `base` to any
// root-absolute (`/…`) link, which would otherwise double the path.
const SITE = 'https://eclipse-fennec.github.io/model.metadata'

// Version selector. Only `snapshot` is deployed today; keep as data so adding
// `latest` and tagged versions later is a one-liner.
const versions = [{ text: 'snapshot', link: `${SITE}/snapshot/` }]

const guideItems = GUIDES.map((g) => ({ text: g.title, link: `/guides/${g.slug}` }))
const exampleItems = EXAMPLES.map((g) => ({ text: g.title, link: `/examples/${g.slug}` }))

// Examples are optional; only surface the section when there is something to show.
const nav = [
  { text: 'Home', link: '/' },
  { text: 'User Manual', items: guideItems },
  ...(exampleItems.length ? [{ text: 'Examples', items: exampleItems }] : []),
  { text: `version: ${version}`, items: versions },
]

const sidebar = {
  '/guides/': [{ text: 'User Manual', items: guideItems }],
  ...(exampleItems.length ? { '/examples/': [{ text: 'Examples', items: exampleItems }] } : {}),
}

export default defineConfig({
  title: 'Fennec Model Metadata',
  description:
    'A metadata and aspect model for EMF — capture, index and enrich Ecore packages, classes and features with pluggable aspect providers.',
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
    ['meta', { property: 'og:title', content: 'Fennec Model Metadata' }],
    [
      'meta',
      {
        property: 'og:description',
        content:
          'A metadata and aspect model for EMF — index and enrich Ecore models with pluggable aspects.',
      },
    ],
  ],

  themeConfig: {
    logo: '/fennec-logo.png',
    siteTitle: 'Fennec Model Metadata',

    nav,
    sidebar,

    socialLinks: [
      { icon: 'github', link: 'https://github.com/eclipse-fennec/model.metadata' },
    ],

    search: { provider: 'local' },

    editLink: {
      pattern: 'https://github.com/eclipse-fennec/model.metadata/edit/main/docs/:path',
      text: 'Edit this page on GitHub',
    },

    footer: {
      message:
        'Released under the EPL-2.0 License. Eclipse Fennec is part of the Eclipse Foundation.',
      copyright: 'Copyright © Eclipse Foundation and contributors',
    },
  },
})
