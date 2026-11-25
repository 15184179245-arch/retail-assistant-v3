package com.retaildemo.v2.ui.pages

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import java.io.File

/**
 * 内置拍照契约：输入=目标输出文件，输出=是否拍成。
 *
 * 用法与旧 `ActivityResultContracts.TakePicture()` 对齐（launch(File) → Boolean），
 * 但不再跳系统相机，由 [CameraCaptureActivity]（CameraX）在 App 内完成拍摄；
 * 相机权限由 [CameraCaptureActivity] 自行申请，调用点无需再挂权限 launcher。
 */
class CameraCaptureContract : ActivityResultContract<File, Boolean>() {

    override fun createIntent(context: Context, input: File): Intent =
        Intent(context, CameraCaptureActivity::class.java)
            .putExtra(CameraCaptureActivity.EXTRA_OUTPUT_PATH, input.absolutePath)

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        resultCode == Activity.RESULT_OK
}

/**
 * I27 连拍契约：输入=基名文件（[com.retaildemo.v2.data.repo.ProductPhotoStore.newTargetFile]，
 * 决定归属商品与目录），输出=已拍成且非空的文件列表（点「完成」才回传；取消=空列表）。
 *
 * 与单拍 [CameraCaptureContract] 并存：盘点/悬浮球/标记等入口继续走单拍，行为零变化；
 * 商品详情页照片区用本契约（拍一张也当作「一张会话」）。
 */
class CameraBurstContract : ActivityResultContract<File, List<File>>() {

    override fun createIntent(context: Context, input: File): Intent =
        Intent(context, CameraCaptureActivity::class.java)
            .putExtra(CameraCaptureActivity.EXTRA_OUTPUT_PATH, input.absolutePath)
            .putExtra(CameraCaptureActivity.EXTRA_MULTI, true)
            .putExtra(CameraCaptureActivity.EXTRA_MAX_SHOTS, MAX_SHOTS)

    override fun parseResult(resultCode: Int, intent: Intent?): List<File> =
        if (resultCode != Activity.RESULT_OK) emptyList()
        else intent?.getStringArrayListExtra(CameraCaptureActivity.EXTRA_OUTPUT_PATHS).orEmpty()
            .map { File(it) }
            .filter { it.exists() && it.length() > 0 }

    companion object {
        /** 一次会话上限：与相册多选一致，防连拍误触刷出几十张待传。 */
        const val MAX_SHOTS = 20
    }
}
