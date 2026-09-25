package ru.queuejw.lumetro.components.core.sidebar

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

class KeyboardKeyBindings(context: Context) {

    companion object {
        @Volatile
        private var instance: KeyboardKeyBindings? = null

        fun getInstance(context: Context): KeyboardKeyBindings {
            return instance ?: synchronized(this) {
                instance ?: KeyboardKeyBindings(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("keyboard_bindings", Context.MODE_PRIVATE)

    enum class BindingType {
        NONE,
        APP,
        SHORTCUT,
        CUSTOM
    }

    data class Binding(
        val type: BindingType,
        val value: String,       // 应用：包名 / 快捷方式：action|extra / 自定义：JSON
        val label: String        // 显示名称
    )

    fun getBinding(digit: String): Binding {
        val typeStr = prefs.getString("key_${digit}_type", "NONE") ?: "NONE"
        val value = prefs.getString("key_${digit}_value", "") ?: ""
        val label = prefs.getString("key_${digit}_label", "") ?: ""

        return Binding(
            type = try { BindingType.valueOf(typeStr) } catch (e: Exception) { BindingType.NONE },
            value = value,
            label = label
        )
    }

    fun setBinding(digit: String, binding: Binding) {
        prefs.edit()
            .putString("key_${digit}_type", binding.type.name)
            .putString("key_${digit}_value", binding.value)
            .putString("key_${digit}_label", binding.label)
            .apply()
    }

    fun clearBinding(digit: String) {
        prefs.edit()
            .remove("key_${digit}_type")
            .remove("key_${digit}_value")
            .remove("key_${digit}_label")
            .apply()
    }

    fun getAllBindings(): Map<String, Binding> {
        val result = mutableMapOf<String, Binding>()
        for (i in 0..9) {
            val binding = getBinding(i.toString())
            if (binding.type != BindingType.NONE) {
                result[i.toString()] = binding
            }
        }
        return result
    }

    // ============================================================
    // ========== 自定义 Intent 数据模型 ==========
    // ============================================================
    data class CustomIntent(
        val action: String = "",
        val data: String = "",
        val packageName: String = "",
        val category: String = ""
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("action", action)
            json.put("data", data)
            json.put("packageName", packageName)
            json.put("category", category)
            return json.toString()
        }

        companion object {
            fun fromJson(jsonStr: String): CustomIntent {
                return try {
                    val json = JSONObject(jsonStr)
                    CustomIntent(
                        action = json.optString("action", ""),
                        data = json.optString("data", ""),
                        packageName = json.optString("packageName", ""),
                        category = json.optString("category", "")
                    )
                } catch (e: Exception) {
                    CustomIntent()
                }
            }
        }
    }
}