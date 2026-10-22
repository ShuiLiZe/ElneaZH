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
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// ---- 拟态玻璃主题：深蓝紫夜空 + 金色点缀 + 半透明磨砂卡片 ----
private val BgTop = Color(0xFF141A3D)
private val BgBottom = Color(0xFF2A1F52)
private val Ink = Color(0xFFF4F1FA)          // 主文字（浅）
private val Muted = Color(0xFFB9B4CF)        // 次要文字
private val Hairline = Color(0x33FFFFFF)
private val Gold = Color(0xFFE9C46A)
private val GoldDeep = Color(0xFFC9973A)
private val WarnBg = Color(0x33E9C46A)
private val WarnInk = Color(0xFFFBE7B5)
private val ErrBg = Color(0x40E5554A)
private val ErrInk = Color(0xFFFFD2CC)
private val OkInk = Color(0xFF9BE3AE)
private val OkBg = Color(0x334CC777)
private const val QQ_LINK = "https://qun.qq.com/universal-share/share?ac=1&authKey=2oXkeJiLXvUlnYoZp%2FCWwlD%2Fkvb3f69RpT6%2BePF8jahMWgvsuOZr5BW53OFi%2FX9j&busi_data=eyJncm91cENvZGUiOiI3OTAxODQ4NTIiLCJ0b2tlbiI6IkcvckZJRVFKWnZGanZ6YlhZRkNXbHJRM3kwb21kc1hWZzlOd21yYlA1dlI1UnRxWGthdzVqRnBRUFZhRVRlTDgiLCJ1aW4iOiIyNzcwMjEzOTE2In0%3D&data=sWsjEEx2_zevFLauPt59ifUwPK3xkIuZdmdJBr4RNbJzma9BghIQ407l-FwVZUGo53DJDh98NYQO90mOdeJjoA&svctype=4&tempid=h5_group_info"

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Gold, onPrimary = Color(0xFF2A1F0A), background = BgTop, surface = BgTop,
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

/** 背景：深色渐变 + 几团柔光，让上面的玻璃卡片有东西可「透」。 */
@Composable
private fun GlassBackdrop() {
    Canvas(Modifier.fillMaxSize()) {
        if (size.width < 2f || size.height < 2f) return@Canvas   // 尺寸为 0 时 RadialGradient 会抛异常
        drawRect(Brush.verticalGradient(listOf(BgTop, Color(0xFF1E2458), BgBottom)))
        fun glow(c: Color, x: Float, y: Float, r: Float) =
            drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), center = Offset(x, y), radius = r), r, Offset(x, y))
        glow(Color(0x667B5CFF), size.width * 0.12f, size.height * 0.10f, size.width * 0.75f)
        glow(Color(0x55E9C46A), size.width * 0.95f, size.height * 0.32f, size.width * 0.65f)
        glow(Color(0x553DA5FF), size.width * 0.05f, size.height * 0.68f, size.width * 0.70f)
        glow(Color(0x44C86BFF), size.width * 0.85f, size.height * 0.95f, size.width * 0.70f)
    }
}

/** 玻璃卡片：半透明白 + 亮边 + 柔和投影。 */
@Composable
private fun Glass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(24.dp, shape, ambientColor = Color(0x40000000), spotColor = Color(0x55000000))
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0x2EFFFFFF), Color(0x10FFFFFF))))
            .border(1.dp, Brush.linearGradient(listOf(Color(0x80FFFFFF), Color(0x14FFFFFF), Color(0x40FFFFFF))), shape)
            .padding(22.dp),
        content = content,
    )
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

    Box(Modifier.fillMaxSize()) {
    GlassBackdrop()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Header
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(R.drawable.ic_launcher_art), contentDescription = null,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(18.dp))
                    .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(18.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text("艾尔尼亚汉化器", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("ELNEA KINGDOM · 中文版", fontSize = 12.sp, color = Gold, letterSpacing = 2.sp)
            }
        }
        Column(Modifier.padding(horizontal = 4.dp)) {
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
                        color = Gold, trackColor = Hairline,
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
                val zh = s.game?.alreadyZh == true
                if (zh) Notice(
                    "手机上已经是汉化版（签名相同），直接点「安装汉化版」覆盖更新即可，不用卸载，存档保留。",
                    OkBg, OkInk,
                ) else Notice(
                    "重要：汉化版的签名和商店版不同，第一次需要卸载原版（以后更新汉化器就能直接覆盖）。\n" +
                        "① 先在游戏里备份存档：Support → Save Data（存档数据），记下引继码；\n" +
                        "② 再卸载原版；\n③ 安装汉化版后在游戏里恢复存档。",
                    WarnBg, WarnInk,
                )
                Spacer(Modifier.height(16.dp))
                val g = s.game
                if (zh) {
                    // nothing to uninstall
                } else if (g != null && s.gameInstalled) {
                    SecondaryButton("卸载原版", modifier = Modifier.fillMaxWidth()) {
                        Installer.uninstall(ctx, g.packageName)
                    }
                } else {
                    Text("✓ 原版已卸载", color = OkInk, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(10.dp))
                PrimaryButton("安装汉化版", enabled = s.outputs.isNotEmpty()) {
                    if (s.gameInstalled && !zh) {
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

        SupportCard()

        Text(
            "仅供个人使用。汉化版使用本机生成的签名，游戏更新后需要等待新的汉化补丁。",
            fontSize = 12.sp, color = Muted, lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    }
}

@Composable
private fun SupportCard() {
    val ctx = LocalContext.current
    Glass {
        Text("交流与支持", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "本汉化完全免费，禁止倒卖。反馈没汉化的地方、获取新版，来汉化交流群。",
            fontSize = 14.sp, color = Muted, lineHeight = 21.sp,
        )
        Spacer(Modifier.height(14.dp))
        PrimaryButton("加入汉化交流 QQ 群") { openQQ(ctx) }
    }
    Glass {
        Text("赞赏作者", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text("觉得好玩，可以微信扫码请汉化作者喝杯奶茶。全凭自愿，不赞赏也一样能用。",
            fontSize = 14.sp, color = Muted, lineHeight = 21.sp)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .shadow(18.dp, RoundedCornerShape(24.dp), spotColor = Color(0x66E9C46A))
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(Gold, GoldDeep)))
                    .padding(3.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .background(Color.White)
                    .padding(14.dp),
            ) {
                Image(painterResource(R.drawable.donate_qr), contentDescription = "微信赞赏码", modifier = Modifier.size(196.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        SecondaryButton("保存赞赏码到相册", modifier = Modifier.fillMaxWidth()) { saveQr(ctx) }
    }
    Glass {
        Text("卡「维护中」/ 卡登录？", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "两种都是账号和设备码配不上。汉化版已默认开启「防卡维护」；已经卡住的，点下面在游戏里更换身份：" +
                "设备码、账号、存档一起换新（会先自动备份），资源包保留不用重下。",
            fontSize = 13.sp, color = Muted, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(12.dp))
        SecondaryButton("更换游戏身份", modifier = Modifier.fillMaxWidth()) { openIdentityReset(ctx) }
    }
}

private fun openQQ(ctx: android.content.Context) {
    try {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(QQ_LINK))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(ctx, "打不开链接，请安装 QQ 或浏览器", Toast.LENGTH_LONG).show()
    }
}

private fun saveQr(ctx: android.content.Context) {
    try {
        val bmp = android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.donate_qr)
        val name = "艾尔尼亚汉化_赞赏码.png"
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val v = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ElneaZH")
            }
            val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
                ?: throw IllegalStateException("insert")
            ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } else {
            @Suppress("DEPRECATION")
            val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "ElneaZH")
            dir.mkdirs()
            java.io.FileOutputStream(java.io.File(dir, name)).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        Toast.makeText(ctx, "已保存到相册，打开微信扫一扫 → 相册选择即可", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "保存失败，可以直接截图", Toast.LENGTH_LONG).show()
    }
}

/** Opens the injected SupportActivity inside the localized game with reset_id=true (Identity.confirm runs in the game process). */
private fun openIdentityReset(ctx: android.content.Context) {
    for (pkg in listOf("jp.co.althi.elnea.en", "jp.co.althi.elnea")) {
        try {
            val i = android.content.Intent().setClassName(pkg, "com.shuilize.elneazh.inj.SupportActivity")
                .putExtra("reset_id", true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            return
        } catch (e: Exception) {
        }
    }
    Toast.makeText(ctx, "没有找到汉化版游戏，请先安装汉化版", Toast.LENGTH_LONG).show()
}

@Composable
private fun StepCard(number: Int, title: String, done: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Glass {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).clip(CircleShape)
                    .background(if (done) Brush.linearGradient(listOf(Gold, GoldDeep)) else Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x14FFFFFF))))
                    .border(1.dp, Color(0x55FFFFFF), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (done) "✓" else number.toString(), color = if (done) Color(0xFF2A1F0A) else Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
    val shape = CircleShape
    Box(
        Modifier
            .fillMaxWidth()
            .shadow(if (enabled) 14.dp else 0.dp, shape, spotColor = Color(0x88E9C46A))
            .clip(shape)
            .background(
                if (enabled) Brush.linearGradient(listOf(Color(0xFFF6DC8E), Gold, GoldDeep))
                else Brush.linearGradient(listOf(Color(0x26FFFFFF), Color(0x1AFFFFFF)))
            )
            .border(1.dp, if (enabled) Color(0x99FFF3CF) else Hairline, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (enabled) Color(0xFF2A1F0A) else Muted)
    }
}

@Composable
private fun SecondaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = CircleShape
    Box(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0x24FFFFFF), Color(0x0FFFFFFF))))
            .border(1.dp, Color(0x40FFFFFF), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (enabled) Ink else Muted)
    }
}
