package com.retaildemo.v2.data.repo

import com.retaildemo.v2.data.local.AppDatabase
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.sync.MirrorPayload
import javax.inject.Inject
import javax.inject.Singleton

/** S13 数据全景：孤岛表单向镜像推送。失败静默（返回 false），绝不影响 APP 主流程。 */
@Singleton
class MirrorRepo @Inject constructor(
    private val db: AppDatabase,
    private val api: retaildemoApi,
) {
    suspend fun push(device: String): Boolean = try {
        val dao = db.mirrorDao()
        val req = MirrorPayload.build(
            device = device,
            tempProducts = dao.tempProducts(),
            dataMarks = dao.dataMarks(),
            supplements = dao.supplements(),
            productViews = dao.productViews(),
            searchHistory = dao.searchHistory(),
            drafts = dao.drafts(),
            customers = dao.customers(),
        )
        api.mirrorPush(req).isSuccessful
    } catch (t: Throwable) {
        android.util.Log.w("MirrorRepo", "mirror push failed (silently tolerated)", t)
        false
    }
}
