# MirrorWebView

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[English](#english) | [中文](#中文)

---

## English

### Overview

**MirrorWebView** is a Rayneo X3 glasses application that demonstrates real-time WebView content mirroring, with dark mode injection to prevent current overload and device reboot, and a focus management system. It uses the **Mercury Android SDK** for screen mirroring capabilities, capturing WebView content frame-by-frame and displaying it on a `TextureView`.

### Architecture

```
com.example.mirror.webview
├── MainActivity.kt              # Entry point with URL input
├── MyApplication.kt             # Application class, initializes Mercury SDK
└── web/
    ├── MirrorWebViewActivity.kt  # Hosts WebView + mirror TextureView + gesture controls
    ├── MirroringWebView.kt       # TextureView that captures & displays WebView frames
    └── WebViewInjector.kt        # Injects dark mode CSS + focus management JS
```

### Key Components

#### 1. MirrorWebViewActivity
- Hosts a hidden `WebView` (the source) and a visible `MirroringWebView` (the mirror display)
- Implements swipe gesture detection: **swipe up/down** to navigate focus, **swipe left** to click the focused element
- Shows a loading overlay while pages load
- Manages Mercury SDK mirroring session lifecycle

#### 2. MirroringWebView
- A custom `TextureView` that captures the source WebView's content at a configurable frame rate (default 30 FPS)
- Supports three capture methods: `DRAW` (recommended), `BITMAP` (fallback), and `AUTO` (optimized with Bitmap reuse)
- Provides start/stop/pause/resume controls and FPS statistics callback
- Uses `WeakReference` to avoid memory leaks

#### 3. WebViewInjector
- **Dark Mode**: Injects CSS and inline style overrides to force a dark theme on any webpage. Includes a `MutationObserver` to darken dynamically added DOM elements (e.g., popups).
- **Focus Management System**: Injects JavaScript that:
  - Collects all interactive/clickable elements on the page (links, buttons, inputs, ARIA roles, `cursor:pointer` elements, jQuery/Vue event-bound elements)
  - Detects modal popups (ARIA dialogs and CSS fixed/absolute overlays) and scopes focus within them
  - Provides `moveFocus(direction)` to navigate between elements with visual highlighting
  - Provides `clickFocused()` to simulate a full click event sequence (pointer → mouse → click)
  - Handles smart scrolling when the next focusable element is far from the viewport

### How Binocular Mirroring Works (vs. BaseMirrorActivity)

The Mercury SDK provides `BaseMirrorActivity` for binocular display, which typically requires **two separate Views** — one for the left eye and one for the right eye. For native UI this works well, but for WebView it creates fundamental problems:

- **Two WebViews** means two independent JS runtimes, two separate page states, and complex synchronization issues
- Every user interaction must be duplicated across both WebViews
- Cookie/session states may diverge, causing inconsistencies

**MirrorWebView takes a completely different approach**: only **one real WebView** is created (placed on the left side). The right side uses a custom `MirroringWebView` (based on `TextureView`) that captures the WebView's rendered content frame-by-frame via `WebView.draw(canvas)` and displays it as a pixel-level mirror.

**Key advantages:**
1. **Single JS runtime** — no state synchronization issues whatsoever
2. **Zero-modification H5 support** — any existing webpage can be displayed binocularly on AR glasses without changes
3. **All native calls execute only once** — JS Bridge, cookies, localStorage are naturally unified
4. **Simple architecture** — the mirror is purely a display surface with no logic of its own

The capture pipeline: `WebView.draw(canvas)` → reusable `Bitmap` → `TextureView.lockCanvas()` → draw scaled bitmap → `unlockCanvasAndPost()`, running at a configurable frame rate (default 30 FPS).

### How TP (Touchpad) Operations Map to Web Interactions via JS Injection

Rayneo X3 glasses have a temple touchpad (TP) instead of a touchscreen. The challenge is: **how to let users navigate and interact with arbitrary web pages using only swipe and tap gestures?**

The solution is a clever two-layer architecture:

**Layer 1 — Native gesture recognition** (`MirrorWebViewActivity`):
- Extends `BaseEventActivity` from Mercury SDK, which converts raw TP touch events into semantic `TempleAction`s (Click, SlideForward, SlideBackward, DoubleClick, etc.)
- TP touch events are **intercepted** (`isTouchDispatchEnabled = false`) so they don't cause the WebView to scroll directly — instead, they are consumed purely as gesture input

**Layer 2 — Injected JS focus management system** (`WebViewInjector`):
- On every `onPageFinished`, a complete focus management system (`window.__mercuryFocus`) is injected into the page
- The system **collects all clickable/interactive elements** on the page through a comprehensive 3-round scan:
  - **Round 1**: CSS selectors match definite interactive elements (links, buttons, inputs, ARIA roles like `role="button"`, `[onclick]`, `[tabindex]`, etc. — over 30 selectors)
  - **Round 2**: Traverse all DOM elements to detect `cursor:pointer` styles, jQuery-bound events (`jQuery._data(el, 'events')`), and framework-specific attributes (`@click` for Vue, `ng-click` for AngularJS, `data-toggle` for Bootstrap, etc.)
  - **Round 3**: Deduplicate — if a parent and child are both clickable, remove the parent to avoid duplicate focus targets
- **Modal popup detection**: Automatically detects ARIA dialogs and CSS `position:fixed/absolute` overlays with high `z-index`, and **scopes focus within the popup** so users don't accidentally navigate to elements behind the overlay
- **Smart scrolling**: When the next focusable element is more than 0.5× viewport height away, the system scrolls the page first instead of jumping focus, letting users read intermediate content

**Gesture-to-JS mapping:**

| TP Gesture         | TempleAction                | JS Call                                   | Effect                                   |
|--------------------|-----------------------------|-------------------------------------------|------------------------------------------|
| Swipe forward/down | SlideForward/SlideDownwards | `__mercuryFocus.moveFocus(1)`             | Focus next element (with cyan highlight) |
| Swipe backward/up  | SlideBackward/SlideUpwards  | `__mercuryFocus.moveFocus(-1)`            | Focus previous element                   |
| Single tap         | Click                       | `__mercuryFocus.clickFocused()`           | Simulate full click sequence             |
| Double tap         | DoubleClick                 | (native) `webView.goBack()` or `finish()` | Navigate back or exit                    |

**Click simulation** dispatches a complete event chain (`pointerdown → mousedown → focus → pointerup → mouseup → click`) to ensure compatibility with SPA frameworks (React, Vue) that may listen on different event phases. For `<a href>` links, a fallback `window.location.href` navigation is triggered after a 100ms delay if the framework hasn't already handled routing.

### How Dark Mode Prevents Current Overload

On Rayneo X3 glasses, displaying bright white web pages causes high current draw through the optical display, which can trigger overcurrent protection and **force the device to reboot**. The dark mode injection solves this by ensuring all displayed content is predominantly dark.

The injection happens in `onPageFinished` (before the mirroring view becomes visible) and works in multiple layers:

1. **Global CSS rules**: Inject a `<style>` tag that sets `html, body { background: #000 }`, forces dark backgrounds on inputs/tables, and recolors links to `#6CB4EE`
2. **Inline style override**: Explicitly set `background-color: #000` on `document.documentElement` and `document.body` to override any inline styles
3. **Per-element scan**: Traverse **every DOM element**, compute its brightness via `getBrightness()` (weighted RGB formula), and force dark background/light text on any element with brightness > 50. Intelligently preserves small decorative elements (progress bars, badges) that have actual color (saturation check: `max - min > 30`)
4. **Gradient removal**: Strips CSS gradient backgrounds on large elements (but preserves them on small decorative ones)
5. **Shadow/outline cleanup**: Removes bright `box-shadow` and `outline` to prevent glowing edges
6. **iframe handling**: Applies dark backgrounds to same-origin iframes
7. **MutationObserver**: Watches for dynamically added DOM nodes (popups, lazy-loaded content) and applies the same darkening treatment in real-time, with a 150ms delayed retry for async-rendered popup children

The WebView's native background is also set to `Color.BLACK` as a fallback, so even if JS injection fails (e.g., on error pages), the display remains dark.

### Known Limitations

1. **Video playback is laggy** — The frame capture mechanism (`WebView.draw(canvas)`) is designed for static/semi-static content. Video playback within the WebView will experience significant stuttering since each frame must be captured, drawn to a Bitmap, and then rendered on the TextureView
2. **No system IME support for text input** — Form fields (input, textarea) cannot invoke the system input method. Currently there is no way to perform text input on the glasses
3. **Imperfect webpage compatibility** — Web elements are highly diverse. The focus management and dark mode systems cannot perfectly adapt to every webpage. However, the JS injection logic in `WebViewInjector` can be customized and extended to handle specific websites

### Tech Stack

- **Language**: Kotlin
- **Min SDK**: Android (see `build.gradle.kts`)
- **Dependencies**: Mercury Android SDK (v0.2.6, bundled as `.aar`)
- **Build System**: Gradle with Kotlin DSL

### Getting Started

1. Clone the repository
2. Open in Android Studio
3. Sync Gradle and build
4. Run on a device or emulator
5. Customize the URL and tap to navigate to MirrorWebViewActivity

### License

This project is licensed under the [MIT License](LICENSE).

---

## 中文

### 概述

**MirrorWebView** 是一个 rayneo x3 眼镜应用，演示了 WebView 内容的实时合目，集成了暗黑模式注入避免电流过载设备重启和焦点管理系统。它使用 **Mercury Android SDK** 实现屏幕镜像能力，逐帧捕获 WebView 内容并显示在一个 `TextureView` 上。

### 架构

```
com.example.mirror.webview
├── MainActivity.kt              # 入口页面，包含 URL 输入
├── MyApplication.kt             # Application 类，初始化 Mercury SDK
└── web/
    ├── MirrorWebViewActivity.kt  # 承载 WebView + 镜像 TextureView + 手势控制
    ├── MirroringWebView.kt       # 捕获并显示 WebView 帧的 TextureView
    └── WebViewInjector.kt        # 注入暗黑模式 CSS + 焦点管理 JS
```

### 核心组件

#### 1. MirrorWebViewActivity
- 承载一个隐藏的 `WebView`（源）和一个可见的 `MirroringWebView`（镜像显示）
- 实现滑动手势检测：**上下滑动** 切换焦点，**左滑** 点击当前焦点元素
- 页面加载时显示加载遮罩
- 管理 Mercury SDK 镜像会话生命周期

#### 2. MirroringWebView
- 自定义 `TextureView`，以可配置的帧率（默认 30 FPS）捕获源 WebView 的内容
- 支持三种捕获方式：`DRAW`（推荐）、`BITMAP`（后备）、`AUTO`（优化复用 Bitmap）
- 提供开始/停止/暂停/恢复控制和帧率统计回调
- 使用 `WeakReference` 避免内存泄漏

#### 3. WebViewInjector
- **暗黑模式**：注入 CSS 和内联样式覆盖，强制任意网页使用暗色主题。包含 `MutationObserver` 对动态添加的 DOM 元素（如弹窗）进行暗色处理。
- **焦点管理系统**：注入 JavaScript 实现：
  - 收集页面上所有可交互/可点击元素（链接、按钮、输入框、ARIA 角色、`cursor:pointer` 元素、jQuery/Vue 事件绑定元素）
  - 检测模态弹窗（ARIA 对话框和 CSS fixed/absolute 浮层），并将焦点限定在弹窗内
  - 提供 `moveFocus(direction)` 在元素间导航，带视觉高亮效果
  - 提供 `clickFocused()` 模拟完整的点击事件序列（pointer → mouse → click）
  - 当下一个可聚焦元素距离视口较远时，智能滚动页面

### WebView 合目显示原理（与 BaseMirrorActivity 方案的本质区别）

Mercury SDK 提供了 `BaseMirrorActivity` 实现合目显示，通常需要**左右眼各创建一个 View**。对于原生 UI 这没有问题，但对 WebView 会产生根本性问题：

- **两个 WebView** 意味着两套独立的 JS 运行时、两份独立的页面状态，状态同步极其复杂
- 每次用户交互都必须在两个 WebView 中重复执行
- Cookie / Session 状态可能出现不一致

**MirrorWebView 采用了完全不同的方案**：只创建**一个真实的 WebView**（放在左侧），右侧使用自定义的 `MirroringWebView`（基于 `TextureView`），通过 `WebView.draw(canvas)` 逐帧捕获 WebView 的渲染内容，实现像素级镜像。

**核心优势：**
1. **单一 JS 运行时** — 完全没有状态同步问题
2. **H5 页面零修改即可合目显示** — 任意现有网页都可以直接在 AR 眼镜上双目显示
3. **所有原生调用只执行一次** — JS Bridge、Cookie、localStorage 天然统一
4. **架构简洁** — 镜像端纯粹是显示面，不承载任何逻辑

捕获流程：`WebView.draw(canvas)` → 复用 `Bitmap` → `TextureView.lockCanvas()` → 绘制缩放后的 bitmap → `unlockCanvasAndPost()`，以可配置帧率运行（默认 30 FPS）。

### TP 触控操作如何通过 JS 注入映射到网页

Rayneo X3 眼镜使用镜腿触控板（TP）而非触摸屏。核心挑战：**如何仅通过滑动和点击手势，让用户浏览和操作任意网页？**

解决方案是一个精巧的两层架构：

**第一层 — 原生手势识别**（`MirrorWebViewActivity`）：
- 继承 Mercury SDK 的 `BaseEventActivity`，将原始 TP 触摸事件转换为语义化的 `TempleAction`（Click、SlideForward、SlideBackward、DoubleClick 等）
- TP 触摸事件被**拦截**（`isTouchDispatchEnabled = false`），不会直接导致 WebView 滚动，而是纯粹作为手势输入消费

**第二层 — 注入的 JS 焦点管理系统**（`WebViewInjector`）：
- 每次 `onPageFinished` 时，向页面注入完整的焦点管理系统（`window.__mercuryFocus`）
- 系统通过 **三轮扫描** 收集页面上所有可点击/可交互元素：
  - **第一轮**：CSS 选择器直接匹配明确的交互元素（链接、按钮、输入框、ARIA 角色如 `role="button"`、`[onclick]`、`[tabindex]` 等 — 超过 30 个选择器）
  - **第二轮**：遍历所有 DOM 元素，检测 `cursor:pointer` 样式、jQuery 绑定事件（`jQuery._data(el, 'events')`）、框架特定属性（Vue 的 `@click`、AngularJS 的 `ng-click`、Bootstrap 的 `data-toggle` 等）
  - **第三轮**：去重 — 如果父子元素都可点击，移除父元素以避免重复焦点目标
- **模态弹窗检测**：自动检测 ARIA 对话框和 CSS `position:fixed/absolute` 的高 `z-index` 浮层，并**将焦点限定在弹窗内**，避免用户误操作到遮罩后的元素
- **智能滚动**：当下一个可聚焦元素距离超过 0.5 倍视口高度时，系统先滚动页面而非跳转焦点，让用户阅读中间内容

**手势到 JS 的映射关系：**

| TP 手势 | TempleAction                | JS 调用                               | 效果            |
|-------|-----------------------------|-------------------------------------|---------------|
| 前滑/下滑 | SlideForward/SlideDownwards | `__mercuryFocus.moveFocus(1)`       | 聚焦下一个元素（青色高亮） |
| 后滑/上滑 | SlideBackward/SlideUpwards  | `__mercuryFocus.moveFocus(-1)`      | 聚焦上一个元素       |
| 单击    | Click                       | `__mercuryFocus.clickFocused()`     | 模拟完整点击序列      |
| 双击    | DoubleClick                 | （原生）`webView.goBack()` 或 `finish()` | 返回上一页或退出      |

**点击模拟**会派发完整的事件链（`pointerdown → mousedown → focus → pointerup → mouseup → click`），确保与 SPA 框架（React、Vue）兼容，因为它们可能监听不同的事件阶段。对于 `<a href>` 链接，会在 100ms 延迟后触发 `window.location.href` 导航作为兜底，以防框架未处理路由跳转。

### 暗黑模式如何避免电流过载

在 Rayneo X3 眼镜上，显示明亮的白色网页会导致光学显示器高电流消耗，可能触发过流保护并**导致设备强制重启**。暗黑模式注入通过确保所有显示内容以深色为主来解决此问题。

注入发生在 `onPageFinished`（镜像视图变为可见之前），分多层工作：

1. **全局 CSS 规则**：注入 `<style>` 标签，设置 `html, body { background: #000 }`，强制输入框/表格使用暗色背景，链接颜色改为 `#6CB4EE`
2. **内联样式覆盖**：显式在 `document.documentElement` 和 `document.body` 上设置 `background-color: #000`，覆盖任何内联样式
3. **逐元素扫描**：遍历**每个 DOM 元素**，通过 `getBrightness()`（加权 RGB 公式）计算亮度，对亮度 > 50 的元素强制深色背景/浅色文字。智能保留有实际颜色的小型装饰性元素（进度条、徽章等，通过饱和度检查：`max - min > 30`）
4. **渐变背景移除**：移除大型元素的 CSS 渐变背景（但保留小型装饰元素的渐变）
5. **阴影/轮廓清理**：移除明亮的 `box-shadow` 和 `outline`，防止发光边缘
6. **iframe 处理**：对同源 iframe 应用暗色背景
7. **MutationObserver**：监听动态添加的 DOM 节点（弹窗、懒加载内容），实时应用相同的暗色处理，并对异步渲染的弹窗子元素进行 150ms 延迟重试

WebView 原生背景也被设置为 `Color.BLACK` 作为兜底，即使 JS 注入失败（如错误页面），显示仍然保持暗色。

### 已知局限性

1. **视频播放卡顿** — 帧捕获机制（`WebView.draw(canvas)`）是为静态/半静态内容设计的。WebView 中的视频播放会出现明显卡顿，因为每一帧都需要被捕获、绘制到 Bitmap、再渲染到 TextureView
2. **表单输入无系统输入法支持** — 表单字段（input、textarea）无法唤起系统输入法，目前无法在眼镜上进行文字输入
3. **网页兼容性不完美** — 网页元素多种多样，焦点管理和暗黑模式系统无法完美适配任意网页。但 `WebViewInjector` 中的 JS 注入逻辑可以自行定制和扩展，针对特定网站进行适配

### 技术栈

- **语言**：Kotlin
- **最低 SDK**：Android（详见 `build.gradle.kts`）
- **依赖**：Mercury Android SDK（v0.2.6，以 `.aar` 形式内置）
- **构建系统**：Gradle + Kotlin DSL

### 快速开始

1. 克隆仓库
2. 在 Android Studio 中打开
3. 同步 Gradle 并构建
4. 在设备或模拟器上运行
5. 自定义URL，点击跳转MirrorWebViewActivity

### 许可证

本项目基于 [MIT 许可证](LICENSE) 开源。
