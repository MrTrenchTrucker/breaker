package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.syncApiModule
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AuthHandoffExchangeMoreTest : HandoffRouteTestBase() {

    @Test fun `two concurrent exchanges of one code give exactly one 200 and one 401`() {
        val aid = register("alice", 1); val token = sessionToken(aid); val c = codeFor(token, sealedPackage)
        val statuses = ArrayList<HttpStatusCode>()
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                coroutineScope {
                    val first = async {
                        client.post("/v1/auth/handoff/exchange") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"code":"$c","secret":"$secret"}""")
                        }.status
                    }
                    val second = async {
                        client.post("/v1/auth/handoff/exchange") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"code":"$c","secret":"$secret"}""")
                        }.status
                    }
                    statuses.add(first.await())
                    statuses.add(second.await())
                }
            }
        }
        assertEquals("one answer per request", 2, statuses.size)
        assertEquals("exactly one 200", 1, statuses.count { it == HttpStatusCode.OK })
        assertEquals("exactly one 401", 1, statuses.count { it == HttpStatusCode.Unauthorized })
    }

    @Test fun `exchange stores the device name it was given and none when it is null`() {
        val aid = register("alice", 1); val token = sessionToken(aid)
        assertEquals(HttpStatusCode.OK, exch("""{"code":"${codeFor(token, sealedPackage)}","secret":"$secret","device_name":"Pixel 8"}""").status)
        assertEquals("stored name", "Pixel 8", lastSessionDeviceName())
        assertEquals(HttpStatusCode.OK, exch("""{"code":"${codeFor(token, sealedPackage)}","secret":"$secret","device_name":null}""").status)
        assertEquals("null name stays empty", noName, lastSessionDeviceName())
    }

    @Test fun `exchange decodes a valid unicode escape in device_name`() {
        val aid = register("alice", 1); val token = sessionToken(aid); val c = codeFor(token, sealedPackage)
        assertEquals(HttpStatusCode.OK, exch("""{"code":"$c","secret":"$secret","device_name":"\u0041"}""").status)
        assertEquals("decoded name", "A", lastSessionDeviceName())
    }

    @Test fun `exchange rejects a truncated unicode escape in device_name with 400`() {
        val aid = register("alice", 1); val token = sessionToken(aid); val c = codeFor(token, sealedPackage)
        val r = exch("""{"code":"$c","secret":"$secret","device_name":"\u12"}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals(badRequestBody, r.body)
    }

    @Test fun `exchange rejects a signed unicode escape in device_name with 400`() {
        val aid = register("alice", 1); val token = sessionToken(aid); val c = codeFor(token, sealedPackage)
        val r = exch("""{"code":"$c","secret":"$secret","device_name":"\u+041"}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals(badRequestBody, r.body)
    }

    @Test fun `exchange rejects a non-hex unicode escape in device_name with 400`() {
        val aid = register("alice", 1); val token = sessionToken(aid); val c = codeFor(token, sealedPackage)
        val r = exch("""{"code":"$c","secret":"$secret","device_name":"\uZZZZ"}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals(badRequestBody, r.body)
    }

    @Test fun `exchange answers a non-canonical package as the canonical text of the stored bytes`() {
        val aid = register("alice", 1); val token = sessionToken(aid)
        val c = codeFor(token, nonCanonicalOf(sealedPackage))
        val r = exch("""{"code":"$c","secret":"$secret"}""")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue("canonical package text", r.body.contains("\"sealed_package\":\"$sealedPackage\""))
    }

    @Test fun `exchange answers the role user and the own username and id for a second account`() {
        register("alice", 1)
        val bobId = register("bob", 2); val token = sessionToken(bobId); val c = codeFor(token, sealedPackage)
        val r = exch("""{"code":"$c","secret":"$secret"}""")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue("role user", r.body.contains("\"role\":\"user\""))
        assertTrue("username bob", r.body.contains("\"username\":\"bob\""))
        assertTrue("account id of bob", r.body.contains("\"account_id\":$bobId"))
    }

    @Test fun `the session token issued by an exchange works on the transcriptions list`() {
        val aid = register("alice", 1); val token = sessionToken(aid); val c = codeFor(token, sealedPackage)
        val ex = exch("""{"code":"$c","secret":"$secret"}""")
        assertEquals(HttpStatusCode.OK, ex.status)
        val ts = ex.body.indexOf("\"token\":\"") + 9
        val issuedToken = ex.body.substring(ts, ts + 43)
        val list = getList(issuedToken)
        assertEquals(HttpStatusCode.OK, list.status)
    }

    @Test fun `a sign-in code used as a Bearer token on START gets exactly the 401`() {
        val aid = register("alice", 1); val token = sessionToken(aid); val c = codeFor(token, sealedPackage)
        val r = postTo("/v1/auth/handoff/start", c, """{"challenge":"$challenge","sealed_package":"$sealedPackage"}""")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertEquals(sessionRequiredBody, r.body)
    }

    @Test fun `a sign-in code used as a Bearer token on the transcriptions list gets exactly the 401`() {
        val aid = register("alice", 1); val token = sessionToken(aid); val c = codeFor(token, sealedPackage)
        val r = getList(c)
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertEquals("""{"error":"unauthorized","message":"Invalid or missing user session token"}""", r.body)
    }
}
