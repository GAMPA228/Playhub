<p align="center">
  <img src="https://img.shields.io/badge/Java-17+-orange?logo=openjdk" alt="Java 17+">
  <img src="https://img.shields.io/badge/Spring_Boot-3.x-green?logo=springboot" alt="Spring Boot">
  <img src="https://img.shields.io/badge/React-18.x-61DAFB?logo=react" alt="React 18">
  <img src="https://img.shields.io/badge/Ant_Design-5.x-1677FF?logo=antdesign" alt="Ant Design">
  <img src="https://img.shields.io/badge/Docker-Ready-2496ED?logo=docker" alt="Docker">
</p>

<h1 align="center">
  <img src="https://raw.githubusercontent.com/twitter/twemoji/master/assets/svg/1f3ac.svg" width="40" />
  PlayHub
</h1>

<p align="center">
  <strong>AI 增强 · 多源聚合 · 影视播放平台</strong>
</p>

![项目访问预览](https://esa-bucket.humorously.cn/QQ20260601-173550.webp)

<p align="center">
  <a href="#-核心亮点">核心亮点</a> •
  <a href="#-快速部署">快速部署</a> •
  <a href="#-使用指南">使用指南</a> •
  <a href="#-架构概览">架构概览</a>
</p>

---

## <img src="https://raw.githubusercontent.com/twitter/twemoji/master/assets/svg/2728.svg" width="24" /> 核心亮点

<table>
  <tr>
    <td align="center" width="33%">
      <img src="https://raw.githubusercontent.com/twitter/twemoji/master/assets/svg/1f916.svg" width="48" />
      <br /><strong>AI 智能聚合</strong>
    </td>
    <td align="center" width="33%">
      <img src="https://raw.githubusercontent.com/twitter/twemoji/master/assets/svg/269b.svg" width="48" />
      <br /><strong>多源并行搜索</strong>
    </td>
    <td align="center" width="33%">
      <img src="https://raw.githubusercontent.com/twitter/twemoji/master/assets/svg/26a1.svg" width="48" />
      <br /><strong>秒级响应</strong>
    </td>
  </tr>
  <tr>
    <td>
      告别逐个源翻找。AI 自动将同名影片的多个播放源合并在一起，并智能推荐最匹配的内容。<b>一次搜索，全网结果。</b>
    </td>
    <td>
      后端多线程并发搜索所有数据源，前端轮询进度实时反馈。
    </td>
    <td>
      播放地址缓存、详情缓存、智能增量更新。<b>点击即播，流畅不等待。</b>
    </td>
  </tr>
</table>

<br />

<table>
  <tr>
    <td align="center" width="33%">
      <img src="https://raw.githubusercontent.com/twitter/twemoji/master/assets/svg/1f4fa.svg" width="48" />
      <br /><strong>直播聚合 &amp; EPG</strong>
    </td>
    <td align="center" width="33%">
      <img src="https://raw.githubusercontent.com/twitter/twemoji/master/assets/svg/1f31f.svg" width="48" />
      <br /><strong>AI 增强体验</strong>
    </td>
  </tr>
  <tr>
    <td>
      同名频道自动合并，多源互为备份。<b>EPG 节目单时间轴</b>，想看什么一目了然。
    </td>
    <td>
      AI 不仅合并搜索结果，还能<b>整合多源影片详情</b>（导演、演员、简介），<b>清洗播放地址</b>去除无效参数。
    </td>
  </tr>
</table>

---

## <img src="https://raw.githubusercontent.com/twitter/twemoji/master/assets/svg/1f4e6.svg" width="24" /> 快速部署

### Docker（推荐）

```bash
docker pull hurryos/playhub
docker run -d --name playhub -p 18080:18080 hurryos/playhub
```

启动后访问 **http://localhost:18080**

> 自定义端口：`-p 8080:18080` → 访问 `http://localhost:8080`

### 从本地仓库使用 Docker Compose

在项目根目录执行：

```bash
# Dockerfile 使用已打包的 JAR，不会自动编译源码
ls -lh target/playhub-*.jar
docker compose up -d --build
docker compose logs -f --tail 100
```

访问 `http://服务器IP:18080`。如果缺少 JAR 或修改了 Java 源码，先使用 JDK 17 和 Maven 执行 `mvn clean package -DskipTests`；如修改了前端，需先在 `frontend` 目录安装依赖并执行 `npm run build`，再打包 JAR。

Compose 会持久化应用数据、日志和 Android 兼容层缓存，健康检查沿用 Dockerfile。使用 `docker compose down` 停止服务并保留数据，`docker compose down -v` 会删除这些数据卷。

默认开放服务器的 18080 端口，建议通过防火墙限制为可信内网访问，不要直接无鉴权暴露到公网。若仅供本机或本机反向代理访问，将 `compose.yaml` 的端口映射改为 `127.0.0.1:18080:18080`。

---

## <img src="https://raw.githubusercontent.com/twitter/twemoji/master/assets/svg/1f4d6.svg" width="24" /> 使用指南

### 1. 加载配置

打开设置面板，输入 TVBox 配置 JSON 地址（支持历史记录自动保存）：

```
https://example.com/tvbox.json
```

### 2. 搜索影片

在搜索框输入关键词，PlayHub 会**并行搜索所有数据源**，并以进度条展示实时进度。

搜索由后端统一调度，默认最多同时搜索 2 个源，每源最多等待 10 秒（含初始化）。结果逐步显示，失效源和超时汇总计数，不再逐条弹错。可以点击“停止搜索”保留已返回的结果；打开影片或离开首页也会停止后台搜索。设置中的“仅当前源”对普通搜索和 AI 搜索均生效。

搜索使用独立的有界执行池，不占用 Java 详情/播放调用池；不会因插件不响应中断而无限增加线程。第三方插件的共享锁、上游网络、鉴权及浏览器跨域问题仍可能影响播放，停止操作不能保证强制终止任意第三方代码。

Docker 中可通过 `APP_SEARCH_MAX_CONCURRENCY`（默认 `2`）和 `APP_SEARCH_TIMEOUT_SECONDS`（默认 `10`）调整搜索容量与单源等待时间；通常不建议单纯调大并发。默认脚本总并发为 2，搜索最多占 1 个脚本进程位，为详情/播放保留容量；若手动把脚本总并发降至 1，则无法同时运行搜索和播放脚本。

> 开启 **AI 增强**（设置 → AI 增强）后，AI 自动将同名的不同源合并，并推荐最符合搜索意图的影片。

### 3. 播放体验

- 进入详情页，选择线路和集数
- 播放器支持长按 3x 倍速、空格键暂停/播放
- 浏览记录**自动保存**播放进度，下次进入直接跳转

### 4. 多源聚合（AI 增强）

当 AI 增强开启时：

| 阶段 | 说明 |
|------|------|
| 🔍 搜索 | AI 分析所有结果，合并同名影片 |
| 🎯 推荐 | AI 选出最符合搜索意图的影片 |
| 📋 详情 | AI 整合多个源的导演、演员、简介 |
| 🔗 播放 | AI 清洗 URL，去除无效参数 |

### 5. 直播

设置面板填写 M3U 直播源地址，PlayHub 自动合并同名频道、加载 EPG 节目单。

---

## <img src="https://raw.githubusercontent.com/twitter/twemoji/master/assets/svg/1f31f.svg" width="24" /> 致谢

- [takagen99/TVBoxOSC](https://github.com/takagen99/TVBoxOSC) — 灵感与核心逻辑来源

<p align="center">
  <br />
  <sub>如果 PlayHub 对你有用，欢迎给个 ⭐</sub>
</p>
