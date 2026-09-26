package com.tudouni.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.tudouni.tv.ui.theme.TvShapes
import com.tudouni.tv.ui.theme.TvType
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码位图边长（px）。解码端（手机相机）会二次缩放，源图给足分辨率最稳。
 */
private const val QR_BITMAP_PX = 512

/**
 * 静默区宽度（模块数）。二维码规范要求四周留 ≥4 个模块的白边，
 * 少了会让扫码识别率明显下降。
 */
private const val QR_QUIET_ZONE_MODULES = 4

/**
 * 二维码显示组件（扫码登录 TV 端用）。
 *
 * 只用 zxing core 生成位图，**不需要摄像头、不申请相机权限**——电视盒子普遍无摄像头，
 * 扫的动作发生在手机端，电视这边只负责「显示」。
 *
 * 几个实现要点：
 * - 位图在 [remember] 里生成，[content] 不变就不重复编码（编码是纯 CPU 计算，别放进组合每帧跑）。
 * - 内部固定白底黑码，**不跟随主题色**：反色/低对比度会让手机识别率骤降。
 * - 用 [FilterQuality.None] 缩放：最近邻保持模块边缘锐利，双线性插值会把黑白边界糊掉。
 * - [content] 为空或编码失败（内容过长等）时位图返回 null，退化为占位文案，
 *   由调用方同时展示人读确认码兜底，不阻断登录流程。
 */
@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
    sizeDp: Int = 260,
    quietZoneModules: Int = QR_QUIET_ZONE_MODULES,
) {
    val bitmap = remember(content, quietZoneModules) {
        generateQrBitmap(content, quietZoneModules)
    }

    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(TvShapes.Card)
            .background(Color.White)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "扫码登录二维码",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
        } else {
            Text(
                text = "二维码生成失败\n请用下方确认码登录",
                style = TvType.Caption,
                color = Color(0xFF3A2A00),
            )
        }
    }
}

/**
 * 使用 zxing core 把 [content] 编码为二维码位图；内容为空或编码失败返回 null。
 *
 * 白底黑码硬编码（Color.WHITE / Color.BLACK 为 android.graphics 色彩）是刻意的：
 * 扫码识别对反色与低对比度都很敏感，这里不能用主题色。
 */
private fun generateQrBitmap(content: String, quietZoneModules: Int): Bitmap? {
    if (content.isBlank()) return null
    return try {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to quietZoneModules,
        )
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            QR_BITMAP_PX,
            QR_BITMAP_PX,
            hints,
        )
        // 尺寸从返回结果动态读取：zxing 会按请求尺寸补齐/取整，不要假设等于 QR_BITMAP_PX
        val width = matrix.width
        val height = matrix.height
        if (width <= 0 || height <= 0) return null

        val pixels = IntArray(width * height)
        val black = android.graphics.Color.BLACK
        val white = android.graphics.Color.WHITE
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                pixels[rowOffset + x] = if (matrix.get(x, y)) black else white
            }
        }
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    } catch (e: Exception) {
        // 内容过长、字符集不支持等：不让登录页因为画二维码崩掉
        null
    }
}
