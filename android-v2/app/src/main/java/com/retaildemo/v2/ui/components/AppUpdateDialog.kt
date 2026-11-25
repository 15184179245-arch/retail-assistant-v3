package com.retaildemo.v2.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retaildemo.v2.BuildConfig
import com.retaildemo.v2.data.remote.AppVersionDto
import com.retaildemo.v2.ui.theme.Primary
import com.retaildemo.v2.ui.theme.PrimaryLight
import com.retaildemo.v2.ui.theme.TextMain
import com.retaildemo.v2.ui.theme.TextSecondary
import com.retaildemo.v2.util.AppUpdateManager
import com.retaildemo.v2.util.UpdateState
import com.retaildemo.v2.ui.window.SanxAlertDialog
import java.util.Locale

private fun formatApkSize(bytes: Long): String {
    if (bytes <= 0) return "计算中"
    val mb = bytes.toDouble() / (1024 * 1024)
    return String.format(Locale.getDefault(), "%.1f MB", mb)
}

/**
 * 应用内版本更新内部推送弹窗。
 */
@Composable
fun AppUpdateDialog(updateManager: AppUpdateManager) {
    val context = LocalContext.current
    val state by updateManager.updateState.collectAsState()

    // ★ 版本信息必须自己留住：下载/安装期间 state 会切走（Downloading / ReadyToInstall），
    // 旧实现从 updateState 现读强转 UpdateAvailable，切到 Downloading 后必然拿到 null
    // → 标题掉成「v新」、安装包大小掉成「计算中」、正文掉成兜底文案。
    var lastVersion by remember { mutableStateOf<AppVersionDto?>(null) }

    LaunchedEffect(state) {
        when (val s = state) {
            is UpdateState.UpdateAvailable -> lastVersion = s.version
            is UpdateState.Idle -> lastVersion = null // ★ 状态重置时必须清空版本快照，杜绝弹窗死循环唤醒
            is UpdateState.UpToDate -> {
                lastVersion = null
                if (s.isManual) {
                    Toast.makeText(context, "当前已是最新版本 (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                }
                updateManager.dismissDialog()
            }
            is UpdateState.Error -> {
                lastVersion = null
                if (s.isManual) {
                    Toast.makeText(context, "检查更新失败: ${s.message}", Toast.LENGTH_SHORT).show()
                }
                updateManager.dismissDialog()
            }
            // 已唤起系统安装器：本窗功成身退（否则会盖在安装器上）
            is UpdateState.ReadyToInstall -> {
                lastVersion = null
                updateManager.dismissDialog()
            }
            else -> {}
        }
    }

    val version = (state as? UpdateState.UpdateAvailable)?.version
        ?: (state as? UpdateState.Downloading)?.let { lastVersion }
        ?: (state as? UpdateState.ReadyToInstall)?.let { lastVersion }

    if (version != null && state !is UpdateState.ReadyToInstall && state !is UpdateState.Idle) {
        val isDownloading = state is UpdateState.Downloading
        val downloadingState = state as? UpdateState.Downloading

        SanxAlertDialog(
            onDismissRequest = {
                if (!isDownloading) {
                    lastVersion = null
                    updateManager.dismissDialog()
                }
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = PrimaryLight,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "发现新版本 v${version?.versionName ?: "新"}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = TextMain,
                            )
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = PrimaryLight,
                            ) {
                                Text(
                                    "内部推送",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Primary,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "安装包: ${formatApkSize(version?.apkSize ?: 0L)} · ${version?.releaseDate ?: "最新"}",
                            fontSize = 11.sp,
                            color = TextSecondary,
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    // 方案 A 去套娃（画布 66:1126 方案注记：更新说明由带框 Card 改为分组小标题 + 行）：
                    // 去掉 0.5dp 描边，且**不许再涂比弹窗白底更暗的 PageBg**——透明落到宿主 Card 上即同色。
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // ★ 这里不再自挂滚动：宿主 SanxAlertDialog 的 text 槽已是唯一滚动容器
                        // （window/SanxWindow.kt:536-543 内置 weight(1f, fill=false).verticalScroll，
                        //  且无关闭开关），本层再挂一层就是同轴双层滚动（红线 5 / rule 14②）。
                        // 原写法 heightIn(max=200.dp)+verticalScroll 一并去掉：日志短（3–8 行）时
                        // 200dp 从不生效，渲染结果逐像素一致；日志超长时改由弹窗整体滚，
                        // 既不裁剪也不丢滚动。
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                version?.title ?: "店员助手新版本更新：",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp,
                                color = Primary,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                version?.updateLog.orEmpty().ifBlank { "包含性能优化与体验增强。" },
                                fontSize = 12.sp,
                                color = TextMain,
                                lineHeight = 18.sp,
                            )
                        }
                    }

                    if (isDownloading && downloadingState != null) {
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress = { downloadingState.progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = Primary,
                            trackColor = PrimaryLight,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "正在极速下载更新...",
                                fontSize = 11.5.sp,
                                color = Primary,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "${(downloadingState.progress * 100).toInt()}% (${formatApkSize(downloadingState.currentBytes)} / ${formatApkSize(downloadingState.totalBytes)})",
                                fontSize = 11.5.sp,
                                color = TextSecondary,
                            )
                        }
                    }
                }
            },
            confirmText = if (isDownloading) "下载中..." else "立即更新",
            confirmEnabled = !isDownloading,
            // ★ 点「立即更新」绝不关窗（旧实现走默认 confirmCloses=true：先播退场动画把窗口置成
            // 「已淡出」，而 onDismissRequest 又因「下载中不可关」拒绝移除 → 卡在
            // 「只剩遮罩、浮窗没了、进度条也没了」的死状态。U8 小窗迁移引入的回归）。
            confirmCloses = false,
            onConfirm = { updateManager.downloadAndInstall(context, version) },
            dismissText = if (!isDownloading) "稍后提醒" else null,
            onDismiss = {
                lastVersion = null
                updateManager.dismissDialog()
            },
        )
    }
}
