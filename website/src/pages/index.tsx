import Link from '@docusaurus/Link';
import Layout from '@theme/Layout';
import type {JSX} from 'react';
import {useEffect, useState} from 'react';

import styles from './index.module.css';

type RoleKey = 'player' | 'mapper' | 'developer';

type Role = {
  key: RoleKey;
  index: string;
  label: string;
  shortLabel: string;
  description: string;
  signal: string;
  to: string;
};

const roles: Role[] = [
  {
    key: 'player',
    index: '01',
    label: '玩家',
    shortLabel: 'PLAY',
    description: '从加入房间、选队、准备到对局内 HUD 与旁观，快速找到你要做的事。',
    signal: '进入一场比赛',
    to: '/docs/player/',
  },
  {
    key: 'mapper',
    index: '02',
    label: '地图制作者',
    shortLabel: 'BUILD',
    description: '从区域和出生点开始，配置队伍、商店、能力、贴图与比赛中的编辑边界。',
    signal: '部署一张地图',
    to: '/docs/mapper/',
  },
  {
    key: 'developer',
    index: '03',
    label: '模组开发者',
    shortLabel: 'EXTEND',
    description: '理解 BaseMap、Capability、事件、命令和客户端同步，接入自己的玩法。',
    signal: '扩展框架能力',
    to: '/docs/developer/',
  },
];

const memoryKey = 'fpsmatch-wiki-role';

export default function Home(): JSX.Element {
  const [activeRole, setActiveRole] = useState<RoleKey | null>(null);
  const [pickerOpen, setPickerOpen] = useState(true);

  useEffect(() => {
    const saved = window.localStorage.getItem(memoryKey) as RoleKey | null;
    if (roles.some((role) => role.key === saved)) {
      setActiveRole(saved);
      setPickerOpen(false);
    }
  }, []);

  useEffect(() => {
    if (!pickerOpen) return undefined;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setPickerOpen(false);
    };
    document.addEventListener('keydown', closeOnEscape);
    return () => document.removeEventListener('keydown', closeOnEscape);
  }, [pickerOpen]);

  const chooseRole = (role: RoleKey) => {
    setActiveRole(role);
    window.localStorage.setItem(memoryKey, role);
    setPickerOpen(false);
  };

  const selected = roles.find((role) => role.key === activeRole);

  return (
    <Layout
      title="FPSMatch Wiki"
      description="FPSMatch Minecraft 团队竞技框架的玩家、地图制作者与模组开发者文档"
    >
      <main className={styles.main}>
        <header className={styles.hero}>
          <div className={styles.heroGrid} aria-hidden="true" />
          <img className={styles.heroMark} src="img/icon.png" alt="" aria-hidden="true" />
          <div className={styles.heroContent}>
            <p className={styles.eyebrow}>FPSM / FIELD MANUAL / 1.3.0-SNAPSHOT</p>
            <h1>FPSMatch</h1>
            <p className={styles.summary}>
              面向 Minecraft 1.20.1 Forge 的团队竞技 FPS 框架。地图、回合、经济、房间和扩展接口，都从同一套比赛状态开始。
            </p>
            <div className={styles.actions}>
              <button className={styles.primaryAction} type="button" onClick={() => setPickerOpen(true)}>
                选择阅读身份 <span aria-hidden="true">→</span>
              </button>
              <Link className={styles.secondaryAction} to={selected?.to ?? '/docs/'}>
                {selected ? `继续：${selected.label}` : '浏览总览'}
              </Link>
            </div>
          </div>
          <div className={styles.statusRail} aria-label="运行环境">
            <span>MINECRAFT 1.20.1</span>
            <span>FORGE 47.4+</span>
            <span>JAVA 17</span>
          </div>
        </header>

        <section className={styles.routes} aria-labelledby="routes-title">
          <div className={styles.sectionHeading}>
            <p>READING INDEX / 00</p>
            <h2 id="routes-title">选择你的工作位置</h2>
            <span>同一套框架，三种入口。身份只影响推荐顺序，不会限制你访问其他章节。</span>
          </div>
          <div className={styles.routeList}>
            {roles.map((role) => (
              <Link
                className={`${styles.route} ${activeRole === role.key ? styles.routeActive : ''}`}
                key={role.key}
                to={role.to}
                onClick={() => chooseRole(role.key)}
              >
                <span className={styles.routeIndex}>{role.index}</span>
                <span className={styles.routeText}>
                  <strong>{role.label}</strong>
                  <span>{role.description}</span>
                </span>
                <span className={styles.routeSignal}>{role.signal}</span>
                <span className={styles.routeArrow} aria-hidden="true">→</span>
              </Link>
            ))}
          </div>
        </section>

        <section className={styles.referenceBand} aria-label="框架摘要">
          <div className={styles.referenceInner}>
            <div>
              <p className={styles.sectionLabel}>SHARED CORE / 04</p>
              <h2>先理解比赛，再理解工具。</h2>
            </div>
            <p>FPSMatch 是可复用的玩法底座，不是单独开箱即玩的游戏模式。BlockOffensive 等模组负责注册具体的 <code>cs</code>、<code>csdm</code> 等玩法。</p>
            <Link className={styles.sourceLink} to="/docs/">查看框架总览 <span aria-hidden="true">→</span></Link>
          </div>
        </section>

        {pickerOpen && (
          <div className={styles.pickerBackdrop} role="presentation">
            <section className={styles.picker} role="dialog" aria-modal="true" aria-labelledby="picker-title">
              <div className={styles.pickerHeader}>
                <p className={styles.eyebrow}>WELCOME / ROUTE SELECT</p>
                <span className={styles.pickerCode}>FPSM-WIKI / 00</span>
              </div>
              <h2 id="picker-title">你准备如何使用 FPSMatch？</h2>
              <p className={styles.pickerIntro}>选择一个身份，Wiki 会把最相关的章节放在第一位。之后可以随时切换。</p>
              <div className={styles.pickerOptions}>
                {roles.map((role) => (
                  <Link
                    className={styles.pickerOption}
                    key={role.key}
                    to={role.to}
                    onClick={() => chooseRole(role.key)}
                  >
                    <span className={styles.pickerOptionIndex}>{role.index}</span>
                    <span className={styles.pickerOptionBody}>
                      <strong>{role.label}</strong>
                      <span>{role.shortLabel} / {role.description}</span>
                    </span>
                    <span className={styles.pickerOptionArrow} aria-hidden="true">↗</span>
                  </Link>
                ))}
              </div>
              <button className={styles.dismissPicker} type="button" onClick={() => setPickerOpen(false)}>
                先看总览
              </button>
            </section>
          </div>
        )}
      </main>
    </Layout>
  );
}
