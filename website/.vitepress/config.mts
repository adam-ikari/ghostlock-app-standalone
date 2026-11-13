import { defineConfig } from 'vitepress'

const base = process.env.VP_BASE || '/ghostlock-site/'
const hostname = process.env.VP_HOST || 'https://adam-ikari.github.io'
const APK = 'apk/GhostLock-v1.2(649)-arm64-v8a-release.apk'

export default defineConfig({
  lang: 'zh-CN',
  title: 'GhostLock v1.2 (649) — Titan 2 临时 root',
  description: '仅适配 Unihertz Titan 2：利用 CVE-2026-43499 取得临时 root，内存加载 KernelSU。不解锁 bootloader，不刷 boot，重启即失效。',
  base,
  cleanUrls: true,

  sitemap: {
    hostname: hostname + base.replace(/\/$/, ''),
  },

  head: [
    ['meta', { name: 'viewport', content: 'width=device-width, initial-scale=1' }],
    ['meta', { name: 'theme-color', content: '#1a1a2e' }],
    ['meta', { name: 'keywords', content: 'GhostLock, CVE-2026-43499, Android 临时root, KernelSU, LSPosed, Vector, ReZygisk, Unihertz Titan 2' }],
    ['meta', { name: 'robots', content: 'index, follow' }],
    ['link', { rel: 'canonical', href: hostname + base }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'GhostLock' }],
    ['meta', { property: 'og:title', content: 'GhostLock v1.2 (649) — Titan 2 临时 root' }],
    ['meta', { property: 'og:description', content: '仅适配 Unihertz Titan 2。CVE-2026-43499 临时 root + KernelSU 内存加载，全离线，重启即失效。' }],
    ['meta', { property: 'og:url', content: hostname + base }],
    ['meta', { property: 'og:image', content: hostname + base + 'og.png' }],
    ['meta', { property: 'og:locale', content: 'zh_CN' }],
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:title', content: 'GhostLock v1.2 (649) — Titan 2 临时 root' }],
    ['meta', { name: 'twitter:description', content: '仅适配 Unihertz Titan 2。CVE-2026-43499 临时 root + KernelSU 内存加载，全离线，重启即失效。' }],
    ['meta', { name: 'twitter:image', content: hostname + base + 'og.png' }],
    ['link', { rel: 'icon', type: 'image/svg+xml', href: base + 'favicon.svg' }],
    ['script', { type: 'application/ld+json' }, JSON.stringify({
      '@context': 'https://schema.org',
      '@type': 'SoftwareApplication',
      name: 'GhostLock',
      applicationCategory: 'UtilitiesApplication',
      operatingSystem: 'Android 16 (Unihertz Titan 2)',
      description: '仅适配 Unihertz Titan 2：利用 CVE-2026-43499 取得临时 root 并加载 KernelSU。',
      softwareVersion: '1.2 (649)',
      license: 'https://www.gnu.org/licenses/gpl-3.0.html',
    })],
  ],

  themeConfig: {
    siteTitle: 'GhostLock',
    nav: [
      { text: '教程', link: '#使用教程' },
      { text: '支持设备', link: '#支持设备' },
      { text: '免责与协议', link: '#免责声明与用户协议' },
      { text: 'GitHub', link: 'https://github.com/adam-ikari/ghostlock-app-standalone' },
    ],
    socialLinks: [{ icon: 'github', link: 'https://github.com/adam-ikari/ghostlock-app-standalone' }],
    footer: {
      message: '<a href="https://www.gnu.org/licenses/gpl-3.0.html" target="_blank">GPL-3.0</a> — 自由使用、修改、再分发',
      copyright: '© 2026 GhostLock',
    },
    outline: { level: [2, 3], label: '本页内容' },
    darkModeSwitchLabel: '深色模式',
    returnToTopLabel: '回到顶部',
  },

  markdown: { lineNumbers: false },
})
