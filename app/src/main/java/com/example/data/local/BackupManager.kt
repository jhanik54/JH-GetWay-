package com.example.data.local

import com.example.core.security.CryptoManager
import org.json.JSONArray
import org.json.JSONObject

class BackupManager(private val db: JhGetWayDatabase) {

    suspend fun exportEncryptedBackup(passphrase: String): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())

        val providersArr = JSONArray()
        db.providerDao().getAllOnce().forEach { p ->
            providersArr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("type", p.type.name)
                put("presetId", p.presetId)
                put("baseUrl", p.baseUrl)
                put("apiFormat", p.apiFormat.name)
                put("defaultModel", p.defaultModel)
                put("priority", p.priority)
                put("enabled", p.enabled)
                put("customHeadersJson", p.customHeadersJson)
                put("notes", p.notes)
            })
        }
        root.put("providers", providersArr)

        val keysArr = JSONArray()
        db.apiKeyDao().getAllOnce().forEach { k ->
            keysArr.put(JSONObject().apply {
                put("id", k.id)
                put("providerId", k.providerId)
                put("label", k.label)
                put("plainKey", CryptoManager.decrypt(k.encryptedKey))
                put("maskedPreview", k.maskedPreview)
                put("priority", k.priority)
                put("isDefault", k.isDefault)
                put("enabled", k.enabled)
                put("selectionPolicy", k.selectionPolicy.name)
            })
        }
        root.put("apiKeys", keysArr)

        val modelsArr = JSONArray()
        db.modelDao().getAllOnce().forEach { m ->
            modelsArr.put(JSONObject().apply {
                put("id", m.id)
                put("modelId", m.modelId)
                put("displayName", m.displayName)
                put("providerId", m.providerId)
                put("providerName", m.providerName)
                put("modelType", m.modelType.name)
                put("isLocal", m.isLocal)
                put("enabled", m.enabled)
                put("isDefault", m.isDefault)
                put("alias", m.alias)
            })
        }
        root.put("models", modelsArr)

        val rulesArr = JSONArray()
        db.routingRuleDao().getAllOnce().forEach { r ->
            rulesArr.put(JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("modelAlias", r.modelAlias)
                put("allowedProviderIdsCsv", r.allowedProviderIdsCsv)
                put("preferredProviderId", r.preferredProviderId)
                put("backupProviderIdsCsv", r.backupProviderIdsCsv)
                put("priority", r.priority)
                put("localCloudPreference", r.localCloudPreference)
                put("enabled", r.enabled)
            })
        }
        root.put("routingRules", rulesArr)

        val workflowsArr = JSONArray()
        db.workflowDao().getWorkflowsOnce().filter { !it.isTemplate }.forEach { w ->
            workflowsArr.put(JSONObject().apply {
                put("id", w.id)
                put("name", w.name)
                put("description", w.description)
                put("nodesJson", w.nodesJson)
                put("edgesJson", w.edgesJson)
            })
        }
        root.put("workflows", workflowsArr)

        return CryptoManager.encryptWithPassphrase(root.toString(), passphrase)
    }

    suspend fun restoreEncryptedBackup(encryptedPayload: String, passphrase: String): Int {
        val jsonStr = CryptoManager.decryptWithPassphrase(encryptedPayload, passphrase)
        val root = JSONObject(jsonStr)
        var importedCount = 0

        root.optJSONArray("providers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                db.providerDao().upsert(
                    ProviderEntity(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        type = runCatching { ProviderType.valueOf(o.optString("type")) }.getOrDefault(ProviderType.CUSTOM),
                        presetId = o.optString("presetId", "CUSTOM"),
                        baseUrl = o.getString("baseUrl"),
                        apiFormat = runCatching { ApiFormat.valueOf(o.optString("apiFormat")) }.getOrDefault(ApiFormat.OPENAI),
                        defaultModel = o.optString("defaultModel"),
                        priority = o.optInt("priority", 10),
                        enabled = o.optBoolean("enabled", true),
                        customHeadersJson = o.optString("customHeadersJson"),
                        notes = o.optString("notes")
                    )
                )
                importedCount++
            }
        }

        root.optJSONArray("apiKeys")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val plain = o.optString("plainKey")
                db.apiKeyDao().upsert(
                    ApiKeyEntity(
                        id = o.getString("id"),
                        providerId = o.getString("providerId"),
                        label = o.getString("label"),
                        encryptedKey = CryptoManager.encrypt(plain),
                        maskedPreview = o.optString("maskedPreview"),
                        priority = o.optInt("priority", 1),
                        isDefault = o.optBoolean("isDefault", false),
                        enabled = o.optBoolean("enabled", true),
                        selectionPolicy = runCatching { KeySelectionPolicy.valueOf(o.optString("selectionPolicy")) }.getOrDefault(KeySelectionPolicy.PRIORITY)
                    )
                )
                importedCount++
            }
        }

        root.optJSONArray("models")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                db.modelDao().upsert(
                    ModelEntity(
                        id = o.getString("id"),
                        modelId = o.getString("modelId"),
                        displayName = o.getString("displayName"),
                        providerId = o.getString("providerId"),
                        providerName = o.getString("providerName"),
                        modelType = runCatching { ModelType.valueOf(o.optString("modelType")) }.getOrDefault(ModelType.GENERAL_CHAT),
                        isLocal = o.optBoolean("isLocal", false),
                        enabled = o.optBoolean("enabled", true),
                        isDefault = o.optBoolean("isDefault", false),
                        alias = o.optString("alias")
                    )
                )
                importedCount++
            }
        }

        root.optJSONArray("routingRules")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                db.routingRuleDao().upsert(
                    RoutingRuleEntity(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        modelAlias = o.getString("modelAlias"),
                        allowedProviderIdsCsv = o.optString("allowedProviderIdsCsv"),
                        preferredProviderId = o.optString("preferredProviderId"),
                        backupProviderIdsCsv = o.optString("backupProviderIdsCsv"),
                        priority = o.optInt("priority", 1),
                        localCloudPreference = o.optString("localCloudPreference", "ANY"),
                        enabled = o.optBoolean("enabled", true)
                    )
                )
                importedCount++
            }
        }

        root.optJSONArray("workflows")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                db.workflowDao().upsertWorkflow(
                    WorkflowEntity(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        description = o.optString("description"),
                        nodesJson = o.getString("nodesJson"),
                        edgesJson = o.getString("edgesJson"),
                        isTemplate = false
                    )
                )
                importedCount++
            }
        }

        return importedCount
    }
}
