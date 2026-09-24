package com.tudouni.tv.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 「重新登录」动作的全局通道。
 *
 * 为什么用 CompositionLocal 而不是逐层传参：登录态失效（HTTP 401）可能发生在任意页面
 * （首页 / 分类 / 搜索 / 历史 / 详情），这些页面的失败态都需要一个「重新登录」按钮，
 * 但它们的调用链并不都经过同一个父组件；逐个加参数会污染一层层签名，
 * 而这个动作全站只有一个实现（App 顶层：清凭证 → 跳登录页）。
 *
 * 默认值为 no-op：未包裹时（如预览）按钮不崩溃，只是没有动作。
 */
val LocalRelogin = staticCompositionLocalOf<() -> Unit> { {} }
