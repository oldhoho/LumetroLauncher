package ru.queuejw.lumetro.components.freeze

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object FreezeManager {
    fun getList(context: Context): List<String> {
        val prefs = context.getSharedPreferences("freeze", Context.MODE_PRIVATE)
        val json = prefs.getString("freeze_list", "[]") ?: "[]"
        val arr = JSONArray(json)
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) list.add(arr.getString(i))
        return list
    }

    fun addToList(context: Context, pkg: String) {
        val list = getList(context).toMutableList()
        if (!list.contains(pkg)) { list.add(pkg); saveList(context, list) }
    }

    fun removeFromList(context: Context, pkg: String) {
        val list = getList(context).toMutableList()
        list.remove(pkg); saveList(context, list)
    }

    private fun saveList(context: Context, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        context.getSharedPreferences("freeze", Context.MODE_PRIVATE).edit().putString("freeze_list", arr.toString()).apply()
    }

    fun setFrozen(context: Context, pkg: String, frozen: Boolean) {
        val prefs = context.getSharedPreferences("freeze", Context.MODE_PRIVATE)
        val json = prefs.getString("frozen_states", "{}") ?: "{}"
        val obj = JSONObject(json)
        obj.put(pkg, frozen)
        prefs.edit().putString("frozen_states", obj.toString()).apply()
    }

    fun isFrozen(context: Context, pkg: String): Boolean {
        val prefs = context.getSharedPreferences("freeze", Context.MODE_PRIVATE)
        val json = prefs.getString("frozen_states", "{}") ?: "{}"
        val obj = JSONObject(json)
        return obj.optBoolean(pkg, false)
    }

    fun getHiddenList(context: Context): List<String> {
        val prefs = context.getSharedPreferences("freeze", Context.MODE_PRIVATE)
        val json = prefs.getString("hidden_apps", "[]") ?: "[]"
        val arr = JSONArray(json)
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) list.add(arr.getString(i))
        return list
    }

    fun isHidden(context: Context, pkg: String): Boolean {
        return getHiddenList(context).contains(pkg)
    }

    fun toggleHidden(context: Context, pkg: String) {
        val list = getHiddenList(context).toMutableList()
        if (list.contains(pkg)) list.remove(pkg) else list.add(pkg)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        context.getSharedPreferences("freeze", Context.MODE_PRIVATE).edit().putString("hidden_apps", arr.toString()).apply()
    }
}