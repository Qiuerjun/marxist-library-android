package app.marxistlibrary.reader.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

// Appearance and network choices are shared by the UI and background jobs.
private val Context.settingsStore by preferencesDataStore("library_preferences")

enum class Appearance(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), PAPER("暖纸"), DARK("深色")
}

enum class ReadingWidth(val label: String, val columns: Int) {
    NARROW("窄", 34), STANDARD("适中", 42), WIDE("宽", 52)
}

enum class PdfReadingMode { CONTINUOUS, PAGED }
enum class PdfAnimation { SLIDE, FADE, NONE }

data class PdfSettings(
    val mode: PdfReadingMode = PdfReadingMode.CONTINUOUS,
    val animation: PdfAnimation = PdfAnimation.SLIDE,
    val tapEdges: Boolean = false
)

data class ReadingSettings(
    val appearance: Appearance = Appearance.SYSTEM,
    val fontSize: Int = 18,
    val lineHeight: Int = 170,
    val margin: Int = 22,
    val serif: Boolean = false,
    val paragraphSpacing: Int = 80,
    val firstLineIndent: Boolean = true,
    val readingWidth: ReadingWidth = ReadingWidth.STANDARD,
    val fixedSource: String = "",
    val wifiOnly: Boolean = false,
    val autoRefresh: Boolean = false,
    val lastUpdate: String = "",
    val speechRate: Int = 100,
    val pdf: PdfSettings = PdfSettings()
)

class Settings(context: Context) {
    private val store = context.settingsStore
    private val appearance = stringPreferencesKey("appearance")
    private val fontSize = intPreferencesKey("font_size")
    private val lineHeight = intPreferencesKey("line_height")
    private val margin = intPreferencesKey("margin")
    private val serif = booleanPreferencesKey("serif")
    private val paragraphSpacing = intPreferencesKey("paragraph_spacing")
    private val firstLineIndent = booleanPreferencesKey("first_line_indent")
    private val readingWidth = stringPreferencesKey("reading_width")
    private val fixedSource = stringPreferencesKey("fixed_source")
    private val wifiOnly = booleanPreferencesKey("wifi_only")
    private val autoRefresh = booleanPreferencesKey("auto_refresh")
    private val lastUpdate = stringPreferencesKey("last_update")
    private val speechRate = intPreferencesKey("speech_rate")
    private val pdfMode = stringPreferencesKey("pdf_reading_mode")
    private val pdfAnimation = stringPreferencesKey("pdf_page_animation")
    private val pdfTapEdges = booleanPreferencesKey("pdf_tap_edges")

    val values = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { saved ->
        ReadingSettings(
            appearance = Appearance.entries.firstOrNull { it.name == saved[appearance] } ?: Appearance.SYSTEM,
            fontSize = (saved[fontSize] ?: 18).coerceIn(12, 34),
            lineHeight = (saved[lineHeight] ?: 170).coerceIn(130, 230),
            margin = (saved[margin] ?: 22).coerceIn(10, 42),
            serif = saved[serif] ?: false,
            paragraphSpacing = (saved[paragraphSpacing] ?: 80).coerceIn(25, 140),
            firstLineIndent = saved[firstLineIndent] ?: true,
            readingWidth = ReadingWidth.entries.firstOrNull { it.name == saved[readingWidth] } ?: ReadingWidth.STANDARD,
            fixedSource = saved[fixedSource].orEmpty(),
            wifiOnly = saved[wifiOnly] ?: false,
            autoRefresh = saved[autoRefresh] ?: false,
            lastUpdate = saved[lastUpdate].orEmpty(),
            speechRate = (saved[speechRate] ?: 100).coerceIn(65, 160),
            pdf = PdfSettings(
                PdfReadingMode.entries.firstOrNull { it.name == saved[pdfMode] } ?: PdfReadingMode.CONTINUOUS,
                PdfAnimation.entries.firstOrNull { it.name == saved[pdfAnimation] } ?: PdfAnimation.SLIDE,
                saved[pdfTapEdges] ?: false
            )
        )
    }

    suspend fun current() = values.first()

    suspend fun appearance(value: Appearance) = save(appearance, value.name)
    suspend fun fontSize(value: Int) = save(fontSize, value.coerceIn(12, 34))
    suspend fun lineHeight(value: Int) = save(lineHeight, value.coerceIn(130, 230))
    suspend fun margin(value: Int) = save(margin, value.coerceIn(10, 42))
    suspend fun serif(value: Boolean) = save(serif, value)
    suspend fun paragraphSpacing(value: Int) = save(paragraphSpacing, value.coerceIn(25, 140))
    suspend fun firstLineIndent(value: Boolean) = save(firstLineIndent, value)
    suspend fun readingWidth(value: ReadingWidth) = save(readingWidth, value.name)
    suspend fun fixedSource(value: String) = save(fixedSource, value)
    suspend fun wifiOnly(value: Boolean) = save(wifiOnly, value)
    suspend fun autoRefresh(value: Boolean) = save(autoRefresh, value)
    suspend fun lastUpdate(value: String) = save(lastUpdate, value)
    suspend fun speechRate(value: Int) = save(speechRate, value.coerceIn(65, 160))

    suspend fun pdf(value: PdfSettings) {
        store.edit {
            it[pdfMode] = value.mode.name
            it[pdfAnimation] = value.animation.name
            it[pdfTapEdges] = value.tapEdges
        }
    }

    private suspend fun <T> save(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }
}

