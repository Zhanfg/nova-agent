package fuck.andes.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import fuck.andes.data.model.Settings
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal object SettingsDataStore {
    private const val STORE_NAME = "fuck_andes_settings"

    private val SELECTED_PROVIDER_ID = stringPreferencesKey("selected_provider_id")
    private val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
    private val VISION_PROVIDER_ID = stringPreferencesKey("vision_provider_id")
    private val VISION_MODEL_ID = stringPreferencesKey("vision_model_id")
    private val AUTO_VISION_ROUTING = booleanPreferencesKey("auto_vision_routing")
    private val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = STORE_NAME)

    @Volatile
    private lateinit var dataStore: DataStore<Preferences>

    fun init(context: Context) {
        if (!::dataStore.isInitialized) {
            dataStore = context.applicationContext.dataStore
        }
    }

    fun settingsFlow(): Flow<Settings> {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw cause
                }
            }
            .map { prefs ->
                Settings(
                    selectedProviderId = prefs[SELECTED_PROVIDER_ID],
                    selectedModelId = prefs[SELECTED_MODEL_ID],
                    visionProviderId = prefs[VISION_PROVIDER_ID],
                    visionModelId = prefs[VISION_MODEL_ID],
                    autoVisionRouting = prefs[AUTO_VISION_ROUTING] ?: true,
                    memoryEnabled = prefs[MEMORY_ENABLED] ?: true,
                )
            }
    }

    suspend fun settings(): Settings = settingsFlow().first()

    suspend fun updateSettings(transform: (Settings) -> Settings) {
        ensureInitialized()
        dataStore.edit { prefs ->
            val current = Settings(
                selectedProviderId = prefs[SELECTED_PROVIDER_ID],
                selectedModelId = prefs[SELECTED_MODEL_ID],
                visionProviderId = prefs[VISION_PROVIDER_ID],
                visionModelId = prefs[VISION_MODEL_ID],
                autoVisionRouting = prefs[AUTO_VISION_ROUTING] ?: true,
                memoryEnabled = prefs[MEMORY_ENABLED] ?: true,
            )
            val updated = transform(current)
            prefs.putOrRemove(SELECTED_PROVIDER_ID, updated.selectedProviderId)
            prefs.putOrRemove(SELECTED_MODEL_ID, updated.selectedModelId)
            prefs.putOrRemove(VISION_PROVIDER_ID, updated.visionProviderId)
            prefs.putOrRemove(VISION_MODEL_ID, updated.visionModelId)
            prefs[AUTO_VISION_ROUTING] = updated.autoVisionRouting
            prefs[MEMORY_ENABLED] = updated.memoryEnabled
        }
    }

    fun selectedProviderIdFlow(): Flow<String?> =
        settingsFlow().map { it.selectedProviderId }

    fun selectedModelIdFlow(): Flow<String?> =
        settingsFlow().map { it.selectedModelId }

    fun visionProviderIdFlow(): Flow<String?> =
        settingsFlow().map { it.visionProviderId }

    fun visionModelIdFlow(): Flow<String?> =
        settingsFlow().map { it.visionModelId }

    fun autoVisionRoutingFlow(): Flow<Boolean> =
        settingsFlow().map { it.autoVisionRouting }

    fun memoryEnabledFlow(): Flow<Boolean> =
        settingsFlow().map { it.memoryEnabled }

    suspend fun setSelectedProviderId(id: String?) {
        updateSettings { it.copy(selectedProviderId = id) }
    }

    suspend fun setSelectedModelId(id: String?) {
        updateSettings { it.copy(selectedModelId = id) }
    }

    suspend fun setSelection(providerId: String?, modelId: String?) {
        updateSettings {
            it.copy(
                selectedProviderId = providerId,
                selectedModelId = modelId,
            )
        }
    }

    suspend fun setVisionSelection(providerId: String?, modelId: String?) {
        updateSettings {
            it.copy(
                visionProviderId = providerId,
                visionModelId = modelId,
            )
        }
    }

    suspend fun setAutoVisionRouting(enabled: Boolean) {
        updateSettings { it.copy(autoVisionRouting = enabled) }
    }

    suspend fun setMemoryEnabled(enabled: Boolean) {
        updateSettings { it.copy(memoryEnabled = enabled) }
    }

    private fun ensureInitialized() {
        check(::dataStore.isInitialized) {
            "SettingsDataStore.init(context) must be called in Application.onCreate()"
        }
    }

    private fun MutablePreferences.putOrRemove(key: Preferences.Key<String>, value: String?) {
        if (value.isNullOrBlank()) {
            remove(key)
        } else {
            this[key] = value
        }
    }
}
