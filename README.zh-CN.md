# Maplestory Cosmic Solo

[English](README.md) | **中文 (机翻)**

从 **[P0nk/Cosmic](https://github.com/P0nk/Cosmic)**（冒险岛 v83 服务端）改的，改成一个人在自己
电脑上玩。不联网，没有别人。

原版 Cosmic 是多人服务端。这个只给自己用，很多改动跟原版方向相反，所以做成 fork 了，没往原版提。

> **这不是原版 Cosmic。** 这里的问题都是我的，跟 Ponk 和 Cosmic 贡献者无关。
> **请不要去原版那边报 bug。**

---

## ⚠️ 先看这里

管理面板没有密码。不是密码弱——是根本没有。谁打开了都能发道具、改倍率、传送、踢人。

它只在你自己电脑上跑（`127.0.0.1:8686`），不改代码外面打不开。别往外暴露。不想要的话，在
`config.yaml` 里把 `WEB_ADMIN_ENABLED` 改成 `false`。

Docker 跑不了，服务端锁了本机，Docker 里连不上。直接运行就好。

---

## 改了什么

**锁成单人。** 只有你自己电脑能连，去掉了 PIN/PIC，只开一个频道（启动更快），倍率调成经验 8×、
金币 5×、掉落 3×。

**加了网页管理面板。** 浏览器打开就能用，不用装别的。

想要什么道具装备都能给自己，按名字、分类、或者按怪物掉落来搜。能卖装备（按商店价）、调人气。
点世界地图就能传送，隐藏地图也行。角色卡死了直接踢掉重连，不用重启服务端。

也能挂机：自动打怪、按比例自动喝药、自动捡东西（能设过滤）、自动续 buff、拉怪。还能查掉落——
什么怪掉什么、哪张地图有什么怪。

**加了 5,000 多件时装。** 帽子、长袍、武器、鞋、披风、宠物装备、饰品、戒指、手套……从
MapleLegends 客户端转过来，放进现金商店，名字和属性都填好了。顺便修了 360 个截断的道具名，
下架了几件会崩的。

**美容院加了 100 多种新发型和眼睛，** 按性别分好，实际不显示的已经剔掉，所有肤色都能选。

**修了原版的 bug：**

- 怪的异常状态（中毒、晕眩、封印……）一直是坏的。一个取整 bug 让 179 只怪身上 86 种状态永远不
  触发。修好了。
- 有些任务游戏里显示能接，实际接不了，也没提示。修了任务 8255、五个可重复任务、Adonis 日常。
- 宠物技能任务不给技能。修了。
- 扎昆不再要求凑六个人。
- 89 张地图的地面和画面对不上。修了。
- 季节 NPC 不再全年站着；画不出来的雕像不再出现；Boss 血条只在有用时才显示；捡东西没捡到时
  角色不再卡住。

**存档失败会报错了，** 不再悄悄丢进度。另外堵了两个内存泄漏。

---

## 怎么搭

需要 **Java 21**（[Amazon Corretto](https://aws.amazon.com/corretto) 就行）、一个 **MySQL 8**
数据库、还有一个**冒险岛 v83 客户端**。大概 15 分钟。

### 1. 拉代码

```
git clone https://github.com/cobaltdisco/Maplestory-Cosmic-Solo.git
cd Maplestory-Cosmic-Solo
```

### 2. 起数据库

有 Docker 的话最省事：

```
docker run -d --name cosmic-mysql -p 127.0.0.1:3306:3306 -e MYSQL_DATABASE=cosmic -e MYSQL_ALLOW_EMPTY_PASSWORD=yes mysql:8.0
```

root 密码是空的，只绑在本机——自己玩没问题，别的场合别这么干。

不想用 Docker 就直接装 MySQL，建一个叫 `cosmic` 的库，记住 root 密码。

### 3. 让服务端连上它

打开 `config.yaml`，翻到底下的 `server:` 那一段：

```yaml
DB_HOST: "localhost"
DB_USER: "root"
DB_PASS: ""          # 填你的 root 密码；用上面那条 Docker 命令的话留空
```

表是服务端第一次启动时自己建的，不用管。

### 4. 编译、运行

```
./mvnw.cmd clean package
java -Xmx2048m -Dwz-path=wz -jar target/Cosmic.jar
```

（第二行 `launch.bat` 帮你跑了。）控制台出现 **"Cosmic is now online"** 就是好了。

### 5. 连客户端

客户端在 [P0nk/Cosmic-client](https://github.com/P0nk/Cosmic-client)，照它的 README 装。
IP 要指向 `127.0.0.1`。

用 **admin / admin** 登录——这个 fork 没有 PIN 和 PIC。建个角色就能进去了。

### 6. 打开管理面板

服务端跑着的时候，浏览器打开 **http://127.0.0.1:8686**。

### 有一样东西开箱是用不了的

那 5,000 多件时装和新发型，这个仓库里只有**服务端那一半数据**。对应的美术资源在客户端自己的
`Character.wz` 里，那个文件约 900 MB，没有发布。所以用原版客户端的话，现金商店里能看到这些
条目，但画不出来。

想把那个文件重做出来，用下面的工具加一个 MapleLegends 客户端是可以做到的。

---

## 工具

**[Maplestory-Cosmic-Solo-Tools](https://github.com/cobaltdisco/Maplestory-Cosmic-Solo-Tools)** —
82 个小工具（MIT），读写游戏文件、提取素材、比对数据、分析崩溃用的。不含游戏数据。

---

## 没有包含

- **管理面板的图片。** 约 16,400 个道具图标和怪物立绘，从客户端提取的，用上面的工具自己生成。
- **游戏客户端。** 没有，以后也不会有。

---

## 许可证

AGPL-3.0，跟原版一样。这里大部分代码都是别人写的——从 OdinMS（2008）到 HeavenMS（2019）再到
Cosmic，由 Ponk 维护。

不提供支持，不处理 issue。风险自担。
