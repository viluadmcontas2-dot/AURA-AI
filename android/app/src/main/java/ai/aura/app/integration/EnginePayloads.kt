package ai.aura.app.integration

import ai.aura.app.ui.engine.Gate
import ai.aura.app.ui.engine.ModelRoute
import ai.aura.app.ui.engine.ModelsDoc
import ai.aura.app.ui.engine.POLICY_KIND_KEYS
import ai.aura.app.ui.engine.POLICY_RISK_KEYS
import ai.aura.app.ui.engine.PolicyDoc
import ai.aura.app.ui.engine.PolicyMode
import ai.aura.app.ui.engine.PolicyScope
import ai.aura.app.ui.engine.PolicyVersion
import ai.aura.app.ui.engine.RootPolicy
import ai.aura.app.ui.engine.SpendCap
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Conversão JSON <-> modelos do app para /v1/policy e /v1/models (docs/design/propostas/
 * politica-modelos-e-design.md). Estrito: valor desconhecido lança JSONException em vez de
 * virar um padrão permissivo; o gateway traduz isso em "indisponível".
 */
internal object EnginePayloads {
    /** O motor pode devolver o documento direto ou embrulhado em "policy"/"models". */
    private fun unwrap(raw: JSONObject, key: String): JSONObject = raw.optJSONObject(key) ?: raw

    private fun gates(obj: JSONObject?, keys: List<String>): Map<String, Gate> {
        val result = LinkedHashMap<String, Gate>()
        if (obj == null) return result
        for (key in keys) {
            if (!obj.has(key)) continue
            result[key] = Gate.fromWire(obj.getString(key)) ?: throw JSONException("gate:" + key)
        }
        return result
    }

    private fun nullableDouble(obj: JSONObject, key: String): Double? =
        if (!obj.has(key) || obj.isNull(key)) null else obj.getDouble(key)

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else List(array.length()) { array.getString(it) }

    fun policyFromJson(raw: JSONObject): PolicyDoc {
        val o = unwrap(raw, "policy")
        val defaults = PolicyDoc(version = 0)
        val mode = PolicyMode.fromWire(o.getString("mode")) ?: throw JSONException("mode")
        val rootObj = o.optJSONObject("root")
        val scopesArr = o.optJSONArray("scopes")
        val capObj = o.optJSONObject("spend_cap")
        return PolicyDoc(
            version = o.getInt("version"),
            mode = mode,
            byRisk = gates(o.optJSONObject("by_risk"), POLICY_RISK_KEYS),
            byKind = gates(o.optJSONObject("by_kind"), POLICY_KIND_KEYS),
            root = if (rootObj == null) defaults.root else RootPolicy(
                enabled = rootObj.optBoolean("enabled", true),
                via = strings(rootObj.optJSONArray("via")),
            ),
            scopes = if (scopesArr == null) defaults.scopes else List(scopesArr.length()) { i ->
                val s = scopesArr.getJSONObject(i)
                PolicyScope(s.getString("path"), Gate.fromWire(s.getString("write")) ?: throw JSONException("scope"))
            },
            spendCap = if (capObj == null) defaults.spendCap else SpendCap(
                perJob = nullableDouble(capObj, "per_job"),
                perDay = nullableDouble(capObj, "per_day"),
                perMonth = nullableDouble(capObj, "per_month"),
                onReach = capObj.optString("on_reach", "avisar").ifBlank { "avisar" },
                alertPercent = if (capObj.has("alert_percent") && !capObj.isNull("alert_percent"))
                    capObj.getInt("alert_percent") else null,
            ),
            autoCountdownSeconds = o.optInt("auto_countdown_seconds", 0),
            notify = o.optString("notify", defaults.notify).ifBlank { defaults.notify },
        )
    }

    fun policyToJson(p: PolicyDoc): JSONObject {
        fun gateJson(map: Map<String, Gate>) = JSONObject().also { j -> map.forEach { (k, v) -> j.put(k, v.wire) } }
        fun cap(v: Double?): Any = v ?: JSONObject.NULL
        val spend = JSONObject()
            .put("per_job", cap(p.spendCap.perJob))
            .put("per_day", cap(p.spendCap.perDay))
            .put("per_month", cap(p.spendCap.perMonth))
            .put("on_reach", p.spendCap.onReach)
        p.spendCap.alertPercent?.let { spend.put("alert_percent", it) }
        return JSONObject()
            .put("version", p.version)
            .put("mode", p.mode.wire)
            .put("by_risk", gateJson(p.byRisk))
            .put("by_kind", gateJson(p.byKind))
            .put("root", JSONObject().put("enabled", p.root.enabled).put("via", JSONArray(p.root.via)))
            .put("scopes", JSONArray().also { a ->
                p.scopes.forEach { a.put(JSONObject().put("path", it.path).put("write", it.write.wire)) }
            })
            .put("spend_cap", spend)
            .put("auto_countdown_seconds", p.autoCountdownSeconds)
            .put("notify", p.notify)
    }

    /** Histórico opcional ("history"); item malformado é ignorado, nunca derruba a tela. */
    fun historyFromJson(raw: JSONObject): List<PolicyVersion> {
        val array = raw.optJSONArray("history") ?: return emptyList()
        val items = ArrayList<PolicyVersion>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val version = item.optInt("version", -1)
            if (version < 0) continue
            val seconds = item.optDouble("at", item.optDouble("created_at", 0.0))
            val millis = if (item.has("at_millis")) item.optLong("at_millis") else (seconds * 1000).toLong()
            items += PolicyVersion(
                version = version,
                phrase = item.optString("phrase").ifBlank { null },
                atMillis = millis,
                by = item.optString("by").ifBlank { null },
                snapshot = item.optJSONObject("snapshot")?.let { runCatching { policyFromJson(it) }.getOrNull() },
            )
        }
        return items
    }

    fun modelsFromJson(raw: JSONObject): ModelsDoc {
        val o = unwrap(raw, "models")
        val defaults = ModelsDoc(version = 0)
        val ceilingObj = o.optJSONObject("ceiling")
        val routesArr = o.optJSONArray("routes")
        val fallback = o.optString("api_fallback", defaults.apiFallback)
        if (fallback != "never" && fallback != "ask") throw JSONException("api_fallback")
        return ModelsDoc(
            version = o.getInt("version"),
            deny = if (o.has("deny")) strings(o.optJSONArray("deny")) else defaults.deny,
            ceiling = if (ceilingObj == null) defaults.ceiling else {
                val map = LinkedHashMap<String, String>()
                for (key in ceilingObj.keys()) map[key] = ceilingObj.getString(key)
                map
            },
            routes = if (routesArr == null) emptyList() else List(routesArr.length()) { i ->
                val r = routesArr.getJSONObject(i)
                ModelRoute(r.getString("task"), strings(r.optJSONArray("use")))
            },
            preferSubscription = o.optBoolean("prefer_subscription", defaults.preferSubscription),
            apiFallback = fallback,
        )
    }

    fun modelsToJson(m: ModelsDoc): JSONObject =
        JSONObject()
            .put("version", m.version)
            .put("deny", JSONArray(m.deny))
            .put("ceiling", JSONObject().also { j -> m.ceiling.forEach { (k, v) -> j.put(k, v) } })
            .put("routes", JSONArray().also { a ->
                m.routes.forEach { a.put(JSONObject().put("task", it.task).put("use", JSONArray(it.use))) }
            })
            .put("prefer_subscription", m.preferSubscription)
            .put("api_fallback", m.apiFallback)

    /** Linhas de GET /v1/spend como mapas simples (para [SpendParser], que é puro). */
    fun spendRows(raw: JSONObject): List<Map<String, Any?>> {
        val array = SpendParser.ROW_KEYS.firstNotNullOfOrNull { raw.optJSONArray(it) } ?: return emptyList()
        val rows = ArrayList<Map<String, Any?>>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val map = LinkedHashMap<String, Any?>()
            for (key in item.keys()) {
                val value = item.opt(key)
                map[key] = if (value == null || value == JSONObject.NULL) null else value
            }
            rows += map
        }
        return rows
    }
}
