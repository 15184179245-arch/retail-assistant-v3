package com.retaildemo.v2

import com.retaildemo.v2.ui.pages.shouldShowAdminZone
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 管理专区可见性测试：
 * 响应老板「管理专区全面开放」最新指示：
 * 无论未登录(null)、过期/被清空串、纯空白、或已登录非空 token，
 * 管理专区均全面开放（shouldShowAdminZone 恒为 true），彻底废除密码锁定门禁，
 * 保证店员现场开箱即用服务器配置、未定价清单排查、低库存预警以及系统设置。
 */
class AdminZoneVisibilityTest {

    @Test
    fun `管理专区全面开放 无论是否有 token 均可见`() {
        assertTrue(shouldShowAdminZone(null))
        assertTrue(shouldShowAdminZone(""))
        assertTrue(shouldShowAdminZone("   "))
        assertTrue(shouldShowAdminZone("eyJhbGciOiJIUzI1NiJ9.payload.sig"))
        assertTrue(shouldShowAdminZone("x"))
    }

    @Test
    fun `token 读写不影响管理专区全面开放可见性`() {
        var jwt: String? = null
        assertTrue(shouldShowAdminZone(jwt))         // 初始未登录：同样全面开放可见
        jwt = "server-issued-jwt"                    // login() 成功存 token：全面开放可见
        assertTrue(shouldShowAdminZone(jwt))
        jwt = null                                   // logout() 清 token：依然全面开放可见
        assertTrue(shouldShowAdminZone(jwt))
    }
}
