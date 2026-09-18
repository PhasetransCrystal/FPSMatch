import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  "wikiSidebar": [
    "README",
    {
      "type": "category",
      "label": "玩家 PLAY",
      "items": [
        "player"
      ]
    },
    {
      "type": "category",
      "label": "地图制作者 BUILD",
      "collapsed": false,
      "items": [
        "mapper",
        {
          "type": "category",
          "label": "制作第一张地图",
          "collapsed": false,
          "items": [
            "mapper/getting-started",
            "mapper/create-map",
            "mapper/teams-and-spawns",
            "mapper/regions",
            "mapper/settings",
            "mapper/kits",
            "mapper/shop",
            "mapper/shop-listener-modules",
            "mapper/room-management"
          ]
        },
        {
          "type": "category",
          "label": "工具与内容",
          "collapsed": true,
          "items": [
            "mapper/tools",
            "mapper/resources",
            "mapper/importing"
          ]
        },
        {
          "type": "category",
          "label": "参考与维护",
          "collapsed": true,
          "items": [
            "mapper/commands",
            "mapper/persistence"
          ]
        }
      ]
    },
    {
      "type": "category",
      "label": "模组开发者 EXTEND",
      "collapsed": false,
      "items": [
        "developer",
        "developer/module-index",
        {
          "type": "category",
          "label": "创建第一个游戏模式",
          "collapsed": true,
          "items": [
            "developer/game-mode-tutorial",
            "developer/getting-started",
            "developer/tutorial/first-map",
            "developer/tutorial/teams-and-spawns",
            "developer/tutorial/first-match",
            "developer/tutorial/multiple-rounds",
            "developer/tutorial/configurable-rules",
            "developer/tutorial/first-capability",
            "developer/tutorial/score-hud"
          ]
        },
        {
          "type": "category",
          "label": "地图与比赛",
          "collapsed": true,
          "items": [
            "developer/architecture",
            "developer/maps-and-matches",
            "developer/gameplay/lobby"
          ]
        },
        {
          "type": "category",
          "label": "队伍、玩家与战斗",
          "collapsed": true,
          "items": [
            "developer/teams-and-players",
            "developer/gameplay/spawn-points",
            "developer/gameplay/combat",
            "developer/gameplay/spectating-and-reconnect"
          ]
        },
        {
          "type": "category",
          "label": "回合与规则",
          "collapsed": true,
          "items": [
            "developer/rounds",
            "developer/round/rules",
            "developer/round/phases-and-pause"
          ]
        },
        {
          "type": "category",
          "label": "装备、商店与经济",
          "collapsed": true,
          "items": [
            "developer/gameplay/equipment-and-shop",
            "developer/gameplay/kits",
            "developer/gameplay/shop",
            "developer/gameplay/shop-types",
            "developer/gameplay/shop-listeners",
            "developer/gameplay/projectiles"
          ]
        },
        {
          "type": "category",
          "label": "地图设置",
          "collapsed": true,
          "items": [
            "developer/settings",
            "developer/configuration/validation"
          ]
        },
        {
          "type": "category",
          "label": "能力系统",
          "collapsed": true,
          "items": [
            "developer/capabilities",
            "developer/capability/lifecycle",
            "developer/capability/persistence",
            "developer/capability/synchronization",
            "developer/capability/built-ins"
          ]
        },
        {
          "type": "category",
          "label": "事件与命令",
          "collapsed": true,
          "items": [
            "developer/events",
            "developer/commands"
          ]
        },
        {
          "type": "category",
          "label": "持久化数据",
          "collapsed": true,
          "items": [
            "developer/data/codecs",
            "developer/persistence",
            "developer/data/migration"
          ]
        },
        {
          "type": "category",
          "label": "网络与外部服务",
          "collapsed": true,
          "items": [
            "developer/networking",
            "developer/network/c2s",
            "developer/network/s2c",
            "developer/http-client",
            "developer/network/rooms",
            "developer/network/downloads"
          ]
        },
        {
          "type": "category",
          "label": "客户端开发",
          "collapsed": true,
          "items": [
            "developer/client-and-compatibility",
            "developer/client/global-data",
            "developer/client/hud-manager",
            "developer/client/hud",
            "developer/client/tab",
            "developer/client/spectator",
            "developer/client/music",
            "developer/client/area-previews",
            "developer/client/resources"
          ]
        },
        {
          "type": "category",
          "label": "相机与演出",
          "collapsed": true,
          "items": [
            "developer/camera/first-scene",
            "developer/camera/rigs",
            "developer/camera/sequences",
            "developer/camera/policies-and-overlay",
            "developer/camera/lifecycle"
          ]
        },
        {
          "type": "category",
          "label": "第三方集成",
          "collapsed": true,
          "items": [
            "developer/integration/gun-provider",
            "developer/integration/kubejs",
            "developer/integration/bukkit",
            "developer/integration/tacz-client"
          ]
        },
        {
          "type": "category",
          "label": "参考",
          "collapsed": true,
          "items": [
            "developer/api-reference",
            "developer/reliability",
            "developer/utilities",
            {
              "type": "category",
              "label": "工具类专题",
              "collapsed": true,
              "items": [
                "developer/utilities/identifiers-and-codecs",
                "developer/utilities/gameplay",
                "developer/utilities/files-spawns",
                "developer/utilities/rendering",
                "developer/utilities/edit-tools"
              ]
            }
          ]
        }
      ]
    }
  ]
};

export default sidebars;
