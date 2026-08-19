package com.toolbox.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.toolbox.app.log.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "connections")

class ConnectionRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val key = stringPreferencesKey("list")

    val connections: Flow<List<ConnectionConfig>> = context.dataStore.data.map { prefs ->
        val raw = prefs[key] ?: return@map emptyList()
        runCatching {
            json.decodeFromString(ListSerializer(ConnectionConfig.serializer()), raw)
        }.getOrElse {
            Log.e("ConnRepo", "解析连接配置失败", it)
            emptyList()
        }
    }

    suspend fun add(config: ConnectionConfig) {
        val list = connections.first()
        context.dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(ListSerializer(ConnectionConfig.serializer()), list + config)
        }
    }

    suspend fun update(config: ConnectionConfig) {
        val list = connections.first()
        val updated = list.map { if (it.id == config.id) config else it }
        context.dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(ListSerializer(ConnectionConfig.serializer()), updated)
        }
    }

    suspend fun delete(id: String) {
        val list = connections.first()
        context.dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(ListSerializer(ConnectionConfig.serializer()), list.filter { it.id != id })
        }
    }

    suspend fun get(id: String): ConnectionConfig? = connections.first().firstOrNull { it.id == id }

    /** 导出全部连接为 JSON 字符串 */
    suspend fun exportJson(): String {
        val list = connections.first()
        return json.encodeToString(ListSerializer(ConnectionConfig.serializer()), list)
    }

    /** 导入 JSON（格式与存储一致），id 冲突时覆盖面，返回导入条数 */
    suspend fun importJson(raw: String): Int {
        val imported = runCatching {
            json.decodeFromString(ListSerializer(ConnectionConfig.serializer()), raw)
                .filter { it.id.isNotBlank() }
        }.getOrElse { throw IllegalArgumentException("JSON 格式错误: ${it.message}") }
        val byId = connections.first().associateBy { it.id }.toMutableMap()
        imported.forEach { byId[it.id] = it }
        context.dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(ListSerializer(ConnectionConfig.serializer()), byId.values.toList())
        }
        return imported.size
    }
}