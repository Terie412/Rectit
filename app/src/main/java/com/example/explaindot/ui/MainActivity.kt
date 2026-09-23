package com.example.explaindot.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.explaindot.analysis.AnalysisController
import com.example.explaindot.analysis.AnalysisStage
import com.example.explaindot.image.PendingImage
import com.example.explaindot.ui.theme.ExplainDotTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页的宿主，同时也是取图结果的落地页。
 *
 * 之所以把两件事合成一个页面：结果本来就是用户打开这个 App 想看的唯一东西。
 * 原先它是从一个悬浮窗弹出来的、用完即弃的临时页面，而首页摆的是四张权限卡片
 * 和一份使用说明 —— 那些东西看一次就够了，却天天占着首屏。
 *
 * 于是结果上移到首页，权限和使用说明下沉到设置页（[SettingsActivity]）。
 * 副作用是点开 App 图标时会看到上次取的那张图，这是有意的：比一片空屏有用。
 *
 * **三条取图路径最后都汇到同一个动作上**（[ingest]）：把图收进
 * [PendingImage] 那个固定槽位，然后 [AnalysisController.load] 让它跑起来。
 * 圆点框选走的是 OverlayService 侧的同一条路（见 [captureIntent]），
 * 差别只在图是谁递过来的。
 */
class MainActivity : ComponentActivity() {

    // 只能是占位值：成员初始化跑在构造函数里，那时读 Context 会 NPE。
    // 真实状态在 onCreate 里读一次补上，见 AppStatus.NONE 的注释
    private var status by mutableStateOf(AppStatus.NONE)
    private var imagePath by mutableStateOf<String?>(null)
    private var imageVersion by mutableStateOf(0L)

    /**
     * 分析状态不归这个 Activity 管 —— 它在 [AnalysisController.shared] 里，
     * 活得比页面久。见那个类的注释。
     */
    private val analysis get() = AnalysisController.shared

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refresh()

        // 顺手收一次尾。老版本按时间戳存图、从不清理，用户升级上来时
        // 历史截图还堆在缓存里 —— 不能非要等他再取一次图才被清掉。
        // 代价很低：那个目录里通常就一个文件。
        PendingImage.prune(this)

        // 重建（转屏、被系统回收后重开）时 intent 里的 extra 还在，
        // 不能把它当成一次新的框选 —— 那会白花一次请求，还会把已经拿到的答案冲掉
        bind(intent, recreated = savedInstanceState != null)

        setContent {
            ExplainDotTheme {
                // 系统返回键 = 返回上一层，而不是退出 App。
                //
                // 这条不是可选项：用户钻了五六层之后按返回，期望的是回到上一个概念。
                // 让系统默认行为把它直接切出去（丢掉整条阅读线索），
                // 会造成"手滑一下就全没了"的感觉。
                //
                // 只在真的在某个概念里时才接管 —— 停在概念列表时返回就该退出 App，
                // 那是 Android 的默认约定
                BackHandler(enabled = analysis.stage is AnalysisStage.Viewing) {
                    analysis.goBack()
                }

                // 取图的两个启动器。
                //
                // 这里用 registerForActivityResult 而不是像 AppActions 里那样
                // 「发一次请求、不管结果」—— 那条注释说的是通知权限，结果可以忽略；
                // 而取图的结果正是我们要的东西，没有别的通路能拿到它。
                // 用 Composable 版的 rememberLauncherForActivityResult，
                // 时序问题由它自己在重组里解决，不用我们操心。
                //
                // 相册走系统的图片选择器：不需要任何读图库的权限，
                // 系统按次把用户选中的那一张授权给应用
                val galleryLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    if (uri != null) ingest(uri)
                }

                // 相机是「借系统相机 App 拍一张，写到我们指定的文件」。
                // 结果回调只给一个 Boolean（拍没拍成），图在 [cameraTargetUri] 指的地方
                val cameraLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.TakePicture()
                ) { captured ->
                    val target = cameraTargetUri()
                    if (captured && target != null) {
                        ingest(target)
                    } else {
                        // 取消了，或者相机没能写成。顺手把暂存文件清掉 ——
                        // 相机多半已经把它截成了一个 0 字节的文件，留着只会
                        // 在下次「清理」之前一直躺在缓存里。
                        // prune 只保留槽位那一个文件，所以这里不会动到当前那张图
                        PendingImage.prune(this)
                    }
                }

                // 界面这里只做一件事：把控制器的状态摆出来。
                // 「看的是哪个概念」不在 stage 里 —— 它在跳转栈的栈顶，由 controller 给出
                val stage = analysis.stage
                MainScreen(
                    status = status,
                    imagePath = imagePath,
                    imageVersion = imageVersion,
                    stage = stage,
                    concepts = analysis.concepts,
                    termContent = analysis.termContent,
                    currentTerm = analysis.currentFrame?.term,
                    trail = analysis.trail.map { it.term },
                    library = analysis.matcher,
                    suggested = analysis.suggestions,
                    refining = analysis.refining,
                    lastRefine = analysis.lastRefine,
                    onOpenSettings = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                    onOpenKnowledge = {
                        startActivity(Intent(this, KnowledgeActivity::class.java))
                    },
                    onOpenChat = {
                        startActivity(Intent(this, ChatActivity::class.java))
                    },
                    onOpenGallery = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onOpenCamera = {
                        val target = cameraTargetUri()
                        if (target == null) {
                            Toast.makeText(this, "拿不到相机的写入位置", Toast.LENGTH_LONG).show()
                        } else {
                            // 设备上没有相机应用时 launch 会抛，不能让它把 App 带走
                            runCatching { cameraLauncher.launch(target) }.onFailure {
                                Log.w(TAG, "相机起不来", it)
                                Toast.makeText(this, "这台设备上找不到相机应用", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onToggleDot = {
                        toggleDot(this, status.dotRunning)
                        // 服务是异步起来的，而 Activity 一直停在前台、onResume 不会再触发。
                        // 先乐观翻一下让按钮立刻响应，300ms 后回读真实状态纠正
                        status = status.copy(dotRunning = !status.dotRunning)
                        window.decorView.postDelayed({ refresh() }, 300L)
                    },
                    onScan = { analysis.scan() },
                    // 从概念列表点进来：没有"正在读的文字"，所以不带上下文
                    onPickConcept = { analysis.openConcept(it.term) },
                    // 从解释正文里点链接跳：把那条正文当上下文带过去，
                    // 「重新解释」要靠它判断库里的解释和用户读的东西对不对得上
                    onFollowLink = { term, context -> analysis.openConcept(term, context) },
                    onBack = { analysis.goBack() },
                    onBackToList = { analysis.backToList() },
                    onRefine = { analysis.refine() },
                    onDeleteEntry = { term, tag -> analysis.deleteEntry(term, tag) },
                    onRetryTerm = { analysis.retryExplanation() }
                )
            }
        }
    }

    /**
     * 悬浮圆点框选完之后重新拉起这个 Activity 走的就是这条路。
     *
     * 清单里声明了 singleTop：Activity 已经在栈顶时系统不再新建实例，
     * 而是把这次 intent 递到这里 —— 否则连框两次会在「最近任务」里堆出两个首页。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent, recreated = false)
    }

    override fun onResume() {
        super.onResume()
        // 从设置页回来，状态可能已经变了
        refresh()
        // 这里不再需要「发现用户改了模型、把当前解释作废」——
        // 解释存在本地库里、与模型无关，换了模型也照样有效。
        // 见 AnalysisController 的类注释。
    }

    /**
     * 决定这一屏要展示什么。
     *
     * 有一处必须靠 [recreated] 区分而不是靠 intent 内容：重建时 extra 和首次
     * 启动时一模一样（同一个路径、同一个尺寸），但那次框选已经问过模型了。
     */
    private fun bind(intent: Intent?, recreated: Boolean) {
        val path = intent?.getStringExtra(EXTRA_PATH)
        if (path != null && !recreated) {
            show(path, System.currentTimeMillis())
            analysis.load(path, autoScan = true)
            return
        }

        // 走到这里只有两种情况：页面被重建了（控制器里那轮对话还在，摆出来就行），
        // 或者用户是点图标进来的（那就摆上次那张，不自动问 —— 他可能只是来开个开关，
        // 不该顺手花他一次请求）
        val current = analysis.imageFile ?: PendingImage.file(this).takeIf { it.exists() }
        if (current == null) return
        show(current.absolutePath, current.lastModified())
        if (analysis.imageFile == null) analysis.load(current.absolutePath, autoScan = false)
    }

    /**
     * 相册、相机取回来的图都从这里进。
     *
     * 收图（解码、摆正方向、压缩、落盘）在 IO 线程上做 —— 一张 4000px 的照片
     * 解码加缩放是几十到上百毫秒，放主线程就是一次可感的卡顿。
     *
     * 收好之后跟框选走完全相同的两步：摆出缩略图、让控制器开始分析。
     * `autoScan = true` 和框选一致 —— 用户主动给一张图，意图就是要弄明白它。
     */
    private fun ingest(uri: Uri) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { PendingImage.ingest(this@MainActivity, uri) }
            if (file == null) {
                Toast.makeText(this@MainActivity, "这张图读不出来，换一张试试", Toast.LENGTH_LONG).show()
                return@launch
            }
            show(file.absolutePath, file.lastModified())
            analysis.load(file.absolutePath, autoScan = true)
        }
    }

    /**
     * 相机该往哪儿写。
     *
     * **写的是 [PendingImage.cameraShot]，不是槽位本身。** 系统相机打开目标文件时
     * 会把它截断，如果目标是槽位，用户按返回取消就等于把当前那张图抹掉了 ——
     * 而槽位里的图可能正是界面上正在看的那张。
     *
     * **每次都现算，不把 Uri 存在字段里。** 它恒等于同一个位置，而「记下来」
     * 会在相机占用前台的那段时间里出问题：转屏或被系统回收都会重建这个 Activity，
     * 存的字段跟着没了 —— 那时拍完的结果回调照样会来，却没人知道图写到了哪。
     * 现算的话，重建与否都不影响。
     *
     * 目录要先建出来：FileProvider 只负责授权一个路径，不会替我们创建它。
     */
    private fun cameraTargetUri(): Uri? = runCatching {
        val file = PendingImage.cameraShot(this)
        file.parentFile?.mkdirs()
        // authority 里的 ${applicationId} 在清单里由构建期替换，这里得手动对齐
        FileProvider.getUriForFile(this, "$packageName$FILE_PROVIDER_SUFFIX", file)
    }.onFailure {
        Log.e(TAG, "相机的写入位置给不出来", it)
    }.getOrNull()

    /**
     * 换一张待分析图。
     *
     * **这里不再量图的尺寸。** 早先它会读一次文件头拿分辨率和大小，
     * 只为在首页写一行「1163×1625 px · 174 KB」—— 那两个数字已经删了
     * （见 [CaptureThumbnail] 的注释），顺手把这次读盘也去掉。
     */
    private fun show(path: String, version: Long) {
        imagePath = path
        imageVersion = version
    }

    private fun refresh() {
        status = AppStatus.read(this)
    }

    companion object {
        private const val TAG = "MainActivity"

        private const val EXTRA_PATH = "image_path"

        /** 与 AndroidManifest 里 provider 的 authorities 尾部一致 */
        private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

        /**
         * 框选完成后的入口。
         *
         * 两个标志缺一不可：从 Service 启动 Activity 必须带 NEW_TASK（否则直接抛异常），
         * CLEAR_TOP 则保证复用已经存在的实例、并把压在它上面的设置页关掉 ——
         * 框选的结果比设置页重要，不该被一层设置页挡在后面。
         *
         * 清单里对应的 launchMode 是 singleTop，所以 CLEAR_TOP 走的是
         * onNewIntent 而不是重建实例。
         *
         * 只传路径不传尺寸：尺寸和体积都由接收方从文件量出来。文件是唯一的真相，
         * 多传一份只会多一个能对不上的地方。
         */
        fun captureIntent(context: Context, file: File): Intent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_PATH, file.absolutePath)
            }
    }
}
