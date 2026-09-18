import {themes as prismThemes} from 'prism-react-renderer';
import type {Config} from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';

const branchPath = process.env.DOCUSAURUS_BRANCH_PATH || 'master';

const config: Config = {
  title: 'FPSMatch Wiki',
  tagline: 'Minecraft 团队竞技 FPS 框架文档',
  favicon: 'img/icon.png',

  future: {v4: true},

  url: 'https://fpsmatch.ptcrys.net',
  baseUrl: process.env.DOCUSAURUS_BASE_URL || '/',

  organizationName: 'PhasetransCrystal',
  projectName: 'FPSMatch',
  trailingSlash: true,
  onBrokenLinks: 'throw',

  i18n: {
    defaultLocale: 'zh-Hans',
    locales: ['zh-Hans'],
  },

  markdown: {mermaid: true},
  themes: ['@docusaurus/theme-mermaid'],

  presets: [
    [
      'classic',
      {
        docs: {
          path: '../docs',
          include: ['**/*.md'],
          routeBasePath: 'docs',
          sidebarPath: './sidebars.ts',
          editUrl: 'https://github.com/PhasetransCrystal/FPSMatch/tree/master/',
          showLastUpdateTime: true,
        },
        blog: false,
        theme: {customCss: './src/css/custom.css'},
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    metadata: [
      {name: 'keywords', content: 'FPSMatch, Minecraft, Forge, team FPS, mod documentation'},
    ],
    colorMode: {respectPrefersColorScheme: true},
    navbar: {
      title: 'FPSMatch',
      logo: {alt: 'FPSMatch logo', src: 'img/icon.png'},
      items: [
        {to: '/', position: 'left', label: 'Wiki / 选择身份'},
        {href: 'https://github.com/PhasetransCrystal/BlockOffensive', label: 'BlockOffensive', position: 'right'},
        {href: 'https://github.com/PhasetransCrystal/FPSMatch', label: 'GitHub', position: 'right'},
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {title: '文档', items: [{label: 'Wiki 首页', to: '/'}]},
        {
          title: '项目',
          items: [
            {label: 'FPSMatch', href: 'https://github.com/PhasetransCrystal/FPSMatch'},
            {label: 'BlockOffensive', href: 'https://github.com/PhasetransCrystal/BlockOffensive'},
          ],
        },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} FPSMatch contributors. Built with Docusaurus.`,
    },
    prism: {theme: prismThemes.github, darkTheme: prismThemes.dracula},
  } satisfies Preset.ThemeConfig,
};

export default config;
