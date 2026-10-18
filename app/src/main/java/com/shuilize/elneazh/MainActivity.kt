package com.shuilize.elneazh

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Cream = Color(0xFFF6F3EE)
private val CardWhite = Color(0xFFFFFFFF)
private val Ink = Color(0xFF1C1B19)
private val Muted = Color(0xFF6E6860)
private val Hairline = Color(0xFFE6DFD4)
private val WarnBg = Color(0xFFFFF4E2)
private val WarnInk = Color(0xFF7A4E0E)
private val ErrBg = Color(0xFFFBEAE7)
private val ErrInk = Color(0xFF9A2B1F)
private val OkInk = Color(0xFF2F6B3A)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Ink, onPrimary = Color.White, background = Cream, surface = CardWhite,
                    onSurface = Ink, onBackground = Ink,
                )
            ) {
                App(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshInstalled()
    }
}

@Composable
private fun App(vm: MainViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val view = LocalView.current
    view.keepScreenOn = s.phase == Phase.WORKING

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onFilePicked(uri)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Cream)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Header
        Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
            Text("艾尔尼亚汉化器", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.height(8.dp))
            Text(
                "一键把 Elnea Kingdom 变成中文版。全程在手机上完成，不需要电脑。",
                fontSize = 15.sp, color = Muted, lineHeight = 22.sp,
            )
            s.patchInfo?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = Muted)
            }
        }

        // Step 1: find the game
        StepCard(number = 1, title = "找到游戏", done = s.game != null) {
            when {
                s.searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ink)
                    Spacer(Modifier.width(12.dp))
                    Text("正在查找已安装的 Elnea Kingdom…", color = Muted, fontSize = 15.sp)
                }
                s.pickingFile -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ink)
                    Spacer(Modifier.width(12.dp))
                    Text("正在读取所选 APK…", color = Muted, fontSize = 15.sp)
                }
                s.game != null -> {
                    val g = s.game!!
                    Text(g.label, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Ink)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            append(g.packageName)
                            g.versionName?.let { append(" · 版本 ").append(it) }
                            if (g.fromFile) append(" · 来自文件")
                            if (g.apks.size > 1) append(" · 分包 ${g.apks.size} 个")
                        },
                        fontSize = 13.sp, color = Muted,
                    )
                    if (g.versionName != null && g.versionName != "2.10.7") {
                        Spacer(Modifier.height(10.dp))
                        Notice("汉化补丁只适用于 2.10.7 版，当前版本可能无法汉化。", WarnBg, WarnInk)
                    }
                }
                else -> Text(
                    "没有找到已安装的 Elnea Kingdom。请先从商店安装 2.10.7 版，或手动选择游戏 APK 文件。",
                    color = Muted, fontSize = 15.sp, lineHeight = 22.sp,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("重新查找", enabled = !s.searching && s.phase != Phase.WORKING, modifier = Modifier.weight(1f)) { vm.search() }
                SecondaryButton("选择 APK 文件", enabled = s.phase != Phase.WORKING, modifier = Modifier.weight(1f)) {
                    picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
                }
            }
        }

        // Step 2: build
        StepCard(number = 2, title = "生成汉化版", done = s.phase == Phase.DONE) {
            Text(
                "校验原版资源 → 打上汉化补丁 → 重新打包并签名。大约需要一两分钟，请保持本页面打开。",
                color = Muted, fontSize = 15.sp, lineHeight = 22.sp,
            )
            Spacer(Modifier.height(14.dp))
            val labels = listOf("校验原版", "应用补丁", "重新打包", "签名")
            labels.forEachIndexed { i, label ->
                val state = when {
                    s.phase == Phase.DONE || (s.step > i && s.phase == Phase.WORKING) -> 2
                    s.step == i && s.phase == Phase.WORKING -> 1
                    s.step == i && s.phase == Phase.ERROR -> 3
                    else -> 0
                }
                SubStep(label, state)
            }
            AnimatedVisibility(s.phase == Phase.WORKING) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = Ink, trackColor = Hairline,
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${s.message}  ${(s.progress * 100).toInt()}%", fontSize = 13.sp, color = Muted)
                }
            }
            s.error?.let {
                Spacer(Modifier.height(12.dp))
                Notice(it, ErrBg, ErrInk)
            }
            Spacer(Modifier.height(18.dp))
            PrimaryButton(
                text = when (s.phase) {
                    Phase.WORKING -> "正在汉化…"
                    Phase.DONE -> "重新生成"
                    Phase.ERROR -> "重试"
                    else -> "开始汉化"
                },
                enabled = s.game != null && s.phase != Phase.WORKING && !s.searching && !s.pickingFile,
            ) { vm.start() }
        }

        // Step 3: install
        AnimatedVisibility(s.phase == Phase.DONE) {
            StepCard(number = 3, title = "安装汉化版", done = false) {
                Notice(
                    "重要：汉化版的签名和商店版不同，不能覆盖安装。\n" +
                        "① 先在游戏里备份存档：Support → Save Data（存档数据），记下引继码；\n" +
                        "② 再卸载原版；\n③ 安装汉化版后在游戏里恢复存档。",
                    WarnBg, WarnInk,
                )
                Spacer(Modifier.height(16.dp))
                val g = s.game
                if (g != null && s.gameInstalled) {
                    SecondaryButton("卸载原版", modifier = Modifier.fillMaxWidth()) {
                        Installer.uninstall(ctx, g.packageName)
                    }
                } else {
                    Text("✓ 原版已卸载", color = OkInk, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(10.dp))
                PrimaryButton("安装汉化版", enabled = s.outputs.isNotEmpty()) {
                    if (s.gameInstalled) {
                        Toast.makeText(ctx, "请先备份存档并卸载原版，否则会安装失败。", Toast.LENGTH_LONG).show()
                        return@PrimaryButton
                    }
                    if (!Installer.canInstall(ctx)) {
                        Toast.makeText(ctx, "请允许「艾尔尼亚汉化器」安装未知应用，然后返回再点一次。", Toast.LENGTH_LONG).show()
                        Installer.openInstallPermissionSettings(ctx)
                        return@PrimaryButton
                    }
                    try {
                        if (s.outputs.size == 1) Installer.installSingle(ctx, s.outputs[0])
                        else Installer.installSession(ctx, s.outputs)
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "无法打开安装程序：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        Text(
            "仅供个人使用。汉化版使用本机生成的签名，游戏更新后需要等待新的汉化补丁。",
            fontSize = 12.sp, color = Muted, lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun StepCard(number: Int, title: String, done: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(14.dp, shape, ambientColor = Color(0x14000000), spotColor = Color(0x1F000000))
            .background(CardWhite, shape)
            .padding(22.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).background(if (done) Ink else Cream, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (done) "✓" else number.toString(), color = if (done) Color.White else Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(12.dp))
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

/** state: 0 pending, 1 running, 2 done, 3 failed */
@Composable
private fun SubStep(label: String, state: Int) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (state) {
                1 -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Ink)
                2 -> Text("✓", color = OkInk, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                3 -> Text("✕", color = ErrInk, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                else -> Box(Modifier.size(8.dp).background(Hairline, CircleShape))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 15.sp, color = if (state == 0) Muted else Ink)
    }
}

@Composable
private fun Notice(text: String, bg: Color, ink: Color) {
    Text(
        text,
        color = ink, fontSize = 14.sp, lineHeight = 21.sp,
        modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(16.dp)).padding(14.dp),
    )
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Ink, contentColor = Color.White,
            disabledContainerColor = Color(0xFFD9D3C9), disabledContentColor = Color(0xFF8C867D),
        ),
        contentPadding = PaddingValues(vertical = 16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        border = BorderStroke(1.dp, Hairline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink, containerColor = Cream),
        contentPadding = PaddingValues(vertical = 13.dp, horizontal = 12.dp),
        modifier = modifier,
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
