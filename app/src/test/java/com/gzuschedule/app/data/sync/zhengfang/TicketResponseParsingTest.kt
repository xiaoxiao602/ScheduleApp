package com.gzuschedule.app.data.sync.zhengfang

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 CAS 登录响应的【真实格式】。
 *
 * ⚠️ 背景：这些用例来自一次真实的失败 —— 真机实测返回
 * ```json
 * {"tgt":"TGT-032626-1586201f82d44872a406767a193bb47a",
 *  "ticket":"ST-032626-86b6a9cba4eb4a6bbf2f8d77b6e6b74a"}
 * ```
 * 而代码当初按 `{meta:{...}, data:"..."}` 解析，导致「登录未返回票据」。
 * 此文件即为防止该回归而存在。
 */
class TicketResponseParsingTest {

    private val gson = Gson()

    // 真机抓到的真实响应（原样）
    private val realResponse = """
        {"tgt":"TGT-032626-1586201f82d44872a406767a193bb47a",
         "ticket":"ST-032626-86b6a9cba4eb4a6bbf2f8d77b6e6b74a"}
    """.trimIndent()

    @Test
    fun `能解析顶层 tgt 字段`() {
        val r = gson.fromJson(
            realResponse,
            ZhengfangSessionClient.TicketResponse::class.java,
        )
        assertNotNull(r)
        assertEquals(
            "TGT-032626-1586201f82d44872a406767a193bb47a",
            r.tgt,
        )
    }

    @Test
    fun `能解析顶层 ticket 字段（实为 ST）`() {
        val r = gson.fromJson(
            realResponse,
            ZhengfangSessionClient.TicketResponse::class.java,
        )
        assertEquals(
            "ST-032626-86b6a9cba4eb4a6bbf2f8d77b6e6b74a",
            r.ticket,
        )
    }

    @Test
    fun `tgt 字段含 TGT- 前缀`() {
        val r = gson.fromJson(realResponse, ZhengfangSessionClient.TicketResponse::class.java)
        assertTrue(r.tgt!!.startsWith("TGT-"))
    }

    @Test
    fun `ticket 字段含 ST- 前缀`() {
        val r = gson.fromJson(realResponse, ZhengfangSessionClient.TicketResponse::class.java)
        assertTrue(r.ticket!!.startsWith("ST-"))
    }

    @Test
    fun `真实响应中不含 meta 与 data 字段`() {
        // 这条断言解释了当初为何失败：旧解析期待 meta/data，实际没有。
        val r = gson.fromJson(realResponse, ZhengfangSessionClient.TicketResponse::class.java)
        assertNull("旧假设的 meta 不应存在", r.meta)
        assertNull("旧假设的 data 不应存在", r.data)
    }

    @Test
    fun `错误响应 data 为对象时不再抛异常`() {
        // ⚠️ 回归防护：服务端失败响应的 data 是【对象】而非字符串。
        // 曾因模型声明为 String 导致 Gson 抛 JsonSyntaxException，
        // 使「密码错误」被吞成「无响应」—— 这个用例锁住该修复。
        val err = """
            {"meta":{"success":true,"statusCode":200,"message":"ok"},
             "data":{"code":"PASSERROR","data":"PASSERROR"}}
        """.trimIndent()

        val r = gson.fromJson(err, ZhengfangSessionClient.TicketResponse::class.java)

        assertNull("失败响应不应含 tgt", r.tgt)
        assertNotNull("data 应被解析为 JsonElement", r.data)
        assertTrue(
            "应能从对象形态的 data 中读出错误码",
            r.data!!.toString().contains("PASSERROR"),
        )
    }

    @Test
    fun `旧格式 data 为纯字符串时不抛异常`() {
        val old = """{"meta":{"success":true},"data":"TGT-031553-338f436cee0541adb5bda14d3fd8dec6"}"""
        val r = gson.fromJson(old, ZhengfangSessionClient.TicketResponse::class.java)
        assertNotNull(r.data)
        assertTrue(r.data!!.toString().contains("TGT-"))
    }

    @Test
    fun `data 为 null 时不抛异常`() {
        val r = gson.fromJson("""{"tgt":"TGT-1-a"}""", ZhengfangSessionClient.TicketResponse::class.java)
        assertNull(r.data)
    }
}
